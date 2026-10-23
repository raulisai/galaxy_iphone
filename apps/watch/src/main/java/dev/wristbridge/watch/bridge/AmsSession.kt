package dev.wristbridge.watch.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.util.Log
import dev.wristbridge.protocol.ams.AmsCodec
import dev.wristbridge.protocol.ams.AmsEntity
import dev.wristbridge.protocol.ams.AmsError
import dev.wristbridge.protocol.ams.AmsUuids
import dev.wristbridge.protocol.ams.EntityUpdate
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ams.TrackAttribute
import dev.wristbridge.protocol.contract.AncsUuids
import dev.wristbridge.protocol.gatt.GattOutcome
import dev.wristbridge.protocol.gatt.MonotonicClock
import java.util.UUID

/**
 * Apple Media Service consumer (now playing plus remote commands) on the same bonded link as ANCS.
 *
 * Invariants: [setup] runs only after ANCS is READY, so music never delays notifications; every GATT
 * operation goes through the link's serial queue via [enqueue]; READY is reported only after the
 * player registration write is confirmed; an AMS failure only moves [MediaStatus] and never touches ANCS
 * state. Callbacks of an earlier setup are dropped by [session]. Track titles and artists are content:
 * they reach [MediaStatus] and [media] but never logs or events. Runs only on the BLE handler thread.
 */
@SuppressLint("MissingPermission")
class AmsSession(
    private val clock: MonotonicClock,
    private val enqueue: (key: String, timeoutMs: Long, execute: () -> Boolean, done: (GattOutcome) -> Unit) -> Unit,
    private val media: MediaPresenter,
) {
    private var session = 0L
    private var gatt: BluetoothGatt? = null
    private var remoteCommand: BluetoothGattCharacteristic? = null
    private var entityAttribute: BluetoothGattCharacteristic? = null

    /** Sequence of the last Entity Update per (entity, attribute); a full-value fetch is stale once it moves. */
    private val latest = HashMap<Pair<Int, Int>, Long>()
    private var updateSeq = 0L

    /** (entity, attribute) pairs with a full-value fetch queued or running: at most one each. */
    private val inFlight = HashSet<Pair<Int, Int>>()

    /** Value of the last Entity Attribute read, stored before the queue completes the read operation. */
    private var readValue: ByteArray? = null

    /** Entity Update packets shorter than the header; diagnostics only. */
    var invalidUpdates = 0L
        private set

    /** Starts AMS on [g] once ANCS is ready. A missing service is a normal NOT_FOUND, not a failure. */
    fun setup(g: BluetoothGatt) {
        forget()
        val service = g.getService(AmsUuids.SERVICE)
        if (service == null) {
            publish { MediaUiState(ams = AmsState.NOT_FOUND, detail = "El iPhone no expone música (AMS)") }
            BridgeStatus.event("AMS no disponible en este iPhone")
            return
        }
        val rc = service.getCharacteristic(AmsUuids.REMOTE_COMMAND)
        val eu = service.getCharacteristic(AmsUuids.ENTITY_UPDATE)
        if (rc == null || eu == null) {
            fail("Servicio AMS incompleto")
            return
        }
        gatt = g
        remoteCommand = rc
        // Optional for us: without it truncated titles simply stay truncated.
        entityAttribute = service.getCharacteristic(AmsUuids.ENTITY_ATTRIBUTE)
        val s = session
        publish { MediaUiState(ams = AmsState.SUBSCRIBING, detail = "Remote Command") }
        subscribe(g, rc, s) {
            publish { it.copy(detail = "Entity Update") }
            subscribe(g, eu, s) { register(g, eu, s) }
        }
    }

    /** Forgets the session (link closed, Service Changed or stop) and retires the media notification. */
    fun reset() {
        forget()
        MediaStatus.update { MediaUiState() }
        safely { media.clear() }
    }

    /** Returns true when [uuid] is an AMS characteristic (handled or deliberately ignored here). */
    fun onCharacteristicChanged(uuid: UUID, value: ByteArray): Boolean {
        if (uuid != AmsUuids.REMOTE_COMMAND && uuid != AmsUuids.ENTITY_UPDATE) return false
        // Not set up (reset or failed): late notifications of an earlier subscription are dropped.
        if (gatt == null) return true
        if (uuid == AmsUuids.REMOTE_COMMAND) {
            val supported = AmsCodec.parseSupportedCommands(value)
            // Command names only (no content): explains which controls the watch can offer.
            if (supported != MediaStatus.state.value.media.supportedCommands) {
                BridgeStatus.event("AMS comandos: ${supported.joinToString(",") { it.name }.ifEmpty { "ninguno" }}")
            }
            publish { it.copy(media = it.media.withSupportedCommands(supported)) }
        } else {
            onEntityUpdate(value)
        }
        return true
    }

    /** Stores an Entity Attribute value; the caller completes the "read:" queue operation right after. */
    fun onCharacteristicRead(uuid: UUID, value: ByteArray, status: Int) {
        if (uuid != AmsUuids.ENTITY_ATTRIBUTE) return
        readValue = if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null
    }

    /** Sends [cmd] to the iPhone's now-playing app. Only when READY and, if advertised, supported. */
    fun command(cmd: RemoteCommand) {
        val g = gatt
        val rc = remoteCommand
        val state = MediaStatus.state.value
        if (state.ams != AmsState.READY || g == null || rc == null) {
            BridgeStatus.event("Música no disponible: comando ${cmd.name} ignorado")
            return
        }
        val supported = state.media.supportedCommands
        if (supported.isNotEmpty() && cmd !in supported) {
            BridgeStatus.event("Comando no disponible en el iPhone: ${cmd.name}")
            return
        }
        BridgeStatus.event("Comando de música ${cmd.name}")
        write(g, rc, AmsCodec.remoteCommand(cmd), session) { outcome ->
            if (outcome != GattOutcome.Success) BridgeStatus.event("Comando de música ${cmd.name} falló: ${describe(outcome)}")
        }
    }

    private fun forget() {
        session++
        gatt = null
        remoteCommand = null
        entityAttribute = null
        latest.clear()
        inFlight.clear()
        readValue = null
    }

    /** Local enable plus remote CCCD write, like ANCS; success only after the descriptor write is confirmed. */
    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic, s: Long, onConfirmed: () -> Unit) {
        val cccd = ch.getDescriptor(AncsUuids.CCCD) ?: run {
            fail("Falta CCCD de AMS")
            return
        }
        // Like ANCS: on a link that stayed up the CCCD may still be enabled and the iPhone would not resend
        // the current player and track. Disable first (outcome ignored) so registration starts fresh.
        enqueue("desc:${ch.uuid}", WRITE_TIMEOUT_MS, {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        }) {}
        enqueue("desc:${ch.uuid}", SUBSCRIBE_TIMEOUT_MS, {
            g.setCharacteristicNotification(ch, true) &&
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        }) { outcome ->
            if (s == session) {
                if (outcome == GattOutcome.Success) onConfirmed() else fail("Suscripción AMS falló: ${describe(outcome)}")
            }
        }
    }

    /** One entity per write (AMS spec). Only the player is required; queue and track are best effort. */
    private fun register(g: BluetoothGatt, eu: BluetoothGattCharacteristic, s: Long) {
        publish { it.copy(detail = "Registro") }
        write(g, eu, AmsCodec.registerPlayer, s) { player ->
            if (player != GattOutcome.Success) {
                fail("Registro del reproductor falló: ${describe(player)}")
                return@write
            }
            publish { it.copy(ams = AmsState.READY, detail = "") }
            BridgeStatus.event("AMS listo")
            write(g, eu, AmsCodec.registerQueue, s) { queue ->
                if (queue != GattOutcome.Success) BridgeStatus.event("AMS: registro de cola falló: ${describe(queue)}")
                write(g, eu, AmsCodec.registerTrack, s) { track ->
                    if (track != GattOutcome.Success) BridgeStatus.event("AMS: registro de pista falló: ${describe(track)}")
                }
            }
        }
    }

    private fun onEntityUpdate(value: ByteArray) {
        val update = AmsCodec.parseEntityUpdate(value) ?: run {
            if (invalidUpdates++ == 0L) BridgeStatus.event("AMS: Entity Update inválido ignorado")
            return
        }
        latest[update.entity to update.attribute] = ++updateSeq
        publish { it.copy(media = it.media.apply(update, clock.nowNanos())) }
        if (update.truncated && update.entity == AmsEntity.TRACK.raw && update.attribute in FULL_TEXT_ATTRIBUTES) {
            fetchFull(AmsEntity.TRACK, update.attribute)
        }
    }

    /**
     * Fetches the full value of a truncated attribute: write the request to Entity Attribute, then read
     * it. The result is applied only if no newer Entity Update arrived for that attribute meanwhile.
     */
    private fun fetchFull(entity: AmsEntity, attribute: Int) {
        val g = gatt ?: return
        val ea = entityAttribute ?: return
        val key = entity.raw to attribute
        if (!inFlight.add(key)) return
        val s = session
        val requested = latest[key]
        write(g, ea, AmsCodec.entityAttributeRequest(entity, attribute), s) { written ->
            if (written != GattOutcome.Success) {
                inFlight.remove(key)
                BridgeStatus.event("AMS: valor completo no disponible: ${describe(written)}")
                return@write
            }
            enqueue("read:${ea.uuid}", READ_TIMEOUT_MS, {
                readValue = null
                g.readCharacteristic(ea)
            }) done@{ read ->
                if (s != session) return@done
                inFlight.remove(key)
                val bytes = readValue
                readValue = null
                if (read != GattOutcome.Success || bytes == null) {
                    BridgeStatus.event("AMS: lectura de valor completo falló: ${describe(read)}")
                    return@done
                }
                val stillTruncated = key in MediaStatus.state.value.media.truncated
                if (latest[key] != requested) {
                    // Superseded: fetch again only if the newer value is truncated as well.
                    if (stillTruncated) fetchFull(entity, attribute)
                    return@done
                }
                // A player reset clears the truncated set: the old track's value must not come back.
                if (!stillTruncated) return@done
                val full = EntityUpdate(entity.raw, attribute, truncated = false, value = bytes.toString(Charsets.UTF_8))
                publish { it.copy(media = it.media.apply(full, clock.nowNanos())) }
            }
        }
    }

    private fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, s: Long, done: (GattOutcome) -> Unit) {
        enqueue("write:${ch.uuid}", WRITE_TIMEOUT_MS, {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        }) { outcome -> if (s == session) done(outcome) }
    }

    /** Ends this setup as FAILED; ANCS and the link stay as they are. */
    private fun fail(detail: String) {
        forget()
        publish { MediaUiState(ams = AmsState.FAILED, detail = detail) }
        BridgeStatus.event("AMS falló: $detail")
    }

    private fun publish(block: (MediaUiState) -> MediaUiState) {
        MediaStatus.update(block)
        safely { media.render(MediaStatus.state.value) }
    }

    /** The notification layer must never take the BLE thread down with it. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            BridgeStatus.event("Permiso de notificaciones denegado: música no mostrada")
            DiagnosticLog.w("media notify denied", e)
        }
    }

    private fun describe(outcome: GattOutcome): String = when (outcome) {
        GattOutcome.Success -> "ok"
        is GattOutcome.Failed -> AmsError.fromAttStatus(outcome.status)?.name ?: "estado=${outcome.status}"
        is GattOutcome.Rejected -> "rechazado (${outcome.reason})"
        GattOutcome.TimedOut -> "sin respuesta"
        GattOutcome.Cancelled -> "cancelado"
    }

    companion object {
        private const val SUBSCRIBE_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 5_000L

        /** Text attributes whose full value is worth one extra round trip when truncated. */
        private val FULL_TEXT_ATTRIBUTES = setOf(TrackAttribute.ARTIST, TrackAttribute.ALBUM, TrackAttribute.TITLE)
    }
}
