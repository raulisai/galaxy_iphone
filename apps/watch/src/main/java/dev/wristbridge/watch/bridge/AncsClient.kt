package dev.wristbridge.watch.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import androidx.core.content.edit
import dev.wristbridge.watch.WristBridgeApp
import android.os.Handler
import android.os.SystemClock
import dev.wristbridge.notification.LinkRecovery
import dev.wristbridge.notification.BondWait
import dev.wristbridge.notification.AncsSessionEngine
import dev.wristbridge.notification.NotificationKey
import dev.wristbridge.notification.SessionEffect
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.protocol.ancs.AncsUid
import dev.wristbridge.protocol.contract.AncsUuids
import dev.wristbridge.protocol.gatt.GattOperation
import dev.wristbridge.protocol.gatt.GattOperationQueue
import dev.wristbridge.protocol.gatt.GattOutcome
import dev.wristbridge.protocol.gatt.MonotonicClock

/**
 * ANCS consumer on the watch (P1-04/06/07/10). The watch is a GATT client of the iPhone's ANCS service
 * on the link the iPhone opened (path A) or, once bonded, through a background reconnection.
 *
 * Invariants (Contract §3): Data Source is confirmed before Notification Source; GATT operations are
 * serialized by [GattOperationQueue]; "ready" is only reported after both remote CCCD writes succeed;
 * callbacks of an older link are dropped by [linkGen]; Service Changed closes the session and rediscovers.
 * AMS (music) shares the link and the queue but starts only after ANCS is READY and never changes ANCS
 * state. Runs only on the BLE [handler] thread. Permission checks happen in [BridgeService] before start.
 */
@SuppressLint("MissingPermission")
class AncsClient(
    private val context: Context,
    private val handler: Handler,
    private val presenter: CardPresenter,
    media: MediaPresenter,
) {
    private val clock = MonotonicClock { SystemClock.elapsedRealtimeNanos() }
    private val prefs = context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE)

    // Seeded from the last persisted generation so a restarted service never reuses a visible card's tag.
    private val engine = AncsSessionEngine(clock, initialGeneration = prefs.getLong(WristBridgeApp.PREF_LAST_GENERATION, 0))

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var linkGen = 0L
    private var linkConnected = false
    private var queue: GattOperationQueue? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private val bondWait = BondWait(clock)
    private var stopped = true
    private val campaign = CampaignRecorder.get(context)

    /** Paced recovery across connection failures; reset only after ANCS is READY. */
    private val recovery = LinkRecovery(clock)
    private val ams = AmsSession(clock, ::enqueueOnLink, media)

    private val tick = Runnable {
        when (recovery.takeDue()) {
            LinkRecovery.Due.CONNECT_TIMEOUT -> retryLink("Tiempo de conexión agotado")
            LinkRecovery.Due.RETRY -> device?.let { peer ->
                if (!stopped && peer.bondState == BluetoothDevice.BOND_BONDED) connect(peer, autoConnect = true)
            }
            null -> Unit
        }
        if (bondWait.expire()) {
            failPairing("El emparejamiento no se completó. Vuelve a intentarlo desde el iPhone.")
        } else {
            queue?.onTick()
        }
        apply(engine.onTick())
    }

    fun start() {
        stopped = false
        if (gatt != null || device != null) return
        // Restore only the exact previously authorized bond. Never select an arbitrary bonded device.
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (!adapter.isEnabled) return
            val address = prefs.getString(PREF_PEER, null) ?: return
            val peer = adapter.bondedDevices.firstOrNull { it.address == address } ?: run {
                prefs.edit { remove(PREF_PEER) }
                return
            }
            connect(peer, autoConnect = true)
        } catch (_: SecurityException) {
            BridgeStatus.event("No se pudo restaurar el enlace: permiso Bluetooth")
        }
    }

    /** The iPhone connected to our GATT server (path A): consume ANCS over that link. */
    fun onPeerConnected(peer: BluetoothDevice) {
        if (stopped) return
        if (device?.address == peer.address && gatt != null && linkConnected) return
        // A fresh central connection makes path A available now. Promote an outstanding background
        // wait to a direct client connection instead of waiting for peripheral advertisements.
        connect(peer, autoConnect = false)
    }

    fun onPeerDisconnected(peer: BluetoothDevice) {
        // The ANCS client link reports its own disconnection; nothing to force here.
        if (device?.address == peer.address) BridgeStatus.event("Canal propio cerrado; ANCS sigue su propio estado")
    }

    fun onBondStateChanged(peer: BluetoothDevice, state: Int) {
        if (device?.address != peer.address) return
        val bond = bondOf(state)
        BridgeStatus.update { it.copy(bond = bond) }
        BridgeStatus.event("Vínculo ${bond.name.lowercase()} peer=${Pseudonym.of(peer)}")
        if (bond == BondState.BONDED && bondWait.finish()) {
            // A timed-out descriptor can still call back. A fresh GATT generation prevents that
            // callback from confirming a new subscription on the same characteristic.
            prefs.edit { putString(PREF_PEER, peer.address) }
            connect(peer, autoConnect = false)
        } else if (bond == BondState.NONE) {
            prefs.edit { remove(PREF_PEER) }
            failPairing("Vínculo rechazado o retirado. Autoriza de nuevo el reloj en el iPhone.")
        }
    }

    /** Operator-triggered recovery: closes the link and reconnects to the same iPhone. */
    fun recover() {
        val d = device ?: run {
            BridgeStatus.event("Recuperar: no hay iPhone conocido; abre WristBridge en el iPhone")
            return
        }
        BridgeStatus.event("Recuperación manual solicitada")
        recovery.ready()
        connect(d, autoConnect = false)
    }

    /** closeLink also resets AMS, which retires the media notification. */
    fun stop() {
        stopped = true
        closeLink("detenido")
        device = null
        BridgeStatus.update { it.copy(ancs = AncsState.IDLE, ancsDetail = "", peer = null) }
    }

    /**
     * P2-07: requests [action] on the card (generation, uid) from a button, the app or a dismissal. The
     * engine answers UNAVAILABLE for a stale generation, an inactive session or an action not offered.
     */
    fun performAction(generation: Long, uid: Long, action: ActionId, dismissed: Boolean = false) {
        BridgeStatus.event("Acción ${action.name} solicitada gen=$generation uid=$uid")
        if (uid !in 0L..AncsUid.MAX) {
            BridgeStatus.event("Acción descartada: uid fuera de rango")
            return
        }
        if (dismissed) campaign.cardDismissed(generation, uid)
        val key = NotificationKey(engine.sourceDeviceId.orEmpty(), generation, AncsUid(uid))
        apply(engine.performAction(key, action))
    }

    /** Sends an AMS remote command; ignored with an event unless music is ready. */
    fun mediaCommand(command: RemoteCommand) {
        ams.command(command)
    }

    private fun connect(peer: BluetoothDevice, autoConnect: Boolean) {
        closeLink("nuevo enlace")
        if (stopped) return
        recovery.connecting(autoConnect)
        val myGen = ++linkGen
        device = peer
        BridgeStatus.update {
            it.copy(ancs = AncsState.CONNECTING, ancsDetail = if (autoConnect) "Esperando al iPhone" else "",
                peer = Pseudonym.of(peer), bond = bondOf(peer.bondState))
        }
        gatt = try {
            peer.connectGatt(context, autoConnect, Callback(myGen), BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, handler)
        } catch (e: SecurityException) {
            BridgeStatus.update { it.copy(ancs = AncsState.FAILED, ancsDetail = "Sin permiso Bluetooth") }
            null
        }
        if (gatt == null) retryLink("El sistema no inició la conexión")
        scheduleTick()
    }

    /** Invalidate callbacks immediately; the next attempt is paced, cancellable and bounded. */
    private fun retryLink(why: String) {
        val peer = device ?: return
        closeLink(why)
        if (stopped || peer.bondState != BluetoothDevice.BOND_BONDED) return
        recovery.failed()
        BridgeStatus.update { it.copy(ancs = AncsState.CONNECTING, ancsDetail = "Reintentando conexión") }
        BridgeStatus.event("$why; recuperación automática programada")
        scheduleTick()
    }

    private fun closeLink(reason: String) {
        handler.removeCallbacks(tick)
        recovery.cancel()
        campaign.sessionEnd(engine.generation, reason)
        engine.endSession(engine.generation)
        ams.reset()
        detachQueue()
        controlPoint = null
        bondWait.finish()
        linkConnected = false
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: SecurityException) {
            }
            BridgeStatus.event("Enlace ANCS cerrado: $reason")
        }
        gatt = null
        linkGen++
    }

    /** Detach first so the cancellations raised by close() are recognised as belonging to an old queue. */
    private fun detachQueue() {
        val old = queue
        queue = null
        old?.close()
    }

    private fun enqueue(q: GattOperationQueue, key: String, timeoutMs: Long, execute: () -> Boolean, done: (GattOutcome) -> Unit) {
        q.enqueue(GattOperation(key, timeoutMs * 1_000_000, execute) { outcome ->
            // Outcomes from a replaced queue (Service Changed, reconnection) never act on the new session.
            if (queue === q) {
                if (outcome == GattOutcome.TimedOut) {
                    // Android callbacks have no operation ID. Reusing the queue after a timeout could
                    // mistake a late callback for the next write to the same characteristic.
                    if (key.startsWith("desc:") && device?.bondState != BluetoothDevice.BOND_BONDED) {
                        detachQueue()
                        device?.let { requestBond(it, "Esperando que aceptes el emparejamiento") }
                    } else {
                        retryLink("Operación Bluetooth sin respuesta")
                    }
                } else done(outcome)
            }
        })
        scheduleTick()
    }

    /** AMS entry to the current link's queue; without a link the operation is cancelled at once. */
    private fun enqueueOnLink(key: String, timeoutMs: Long, execute: () -> Boolean, done: (GattOutcome) -> Unit) {
        val q = queue ?: return done(GattOutcome.Cancelled)
        enqueue(q, key, timeoutMs, execute, done)
    }

    private fun discover(g: BluetoothGatt) {
        val q = queue ?: return
        BridgeStatus.update { it.copy(ancs = AncsState.DISCOVERING) }
        enqueue(q, "discover", 15_000, { g.discoverServices() }) { outcome ->
            if (outcome == GattOutcome.Success) setupAncs(g)
            else retryLink("Descubrimiento falló: $outcome")
        }
    }

    private fun setupAncs(g: BluetoothGatt) {
        val service = g.getService(AncsUuids.SERVICE)
        val peer = device ?: return
        if (service == null) {
            if (peer.bondState != BluetoothDevice.BOND_BONDED) requestBond(peer, "ANCS oculto hasta vincular")
            else {
                retryLink("El iPhone no expone ANCS")
                BridgeStatus.update { it.copy(ancsDetail = "Revisa Compartir notificaciones; se reintentará") }
            }
            return
        }
        val ns = service.getCharacteristic(AncsUuids.NOTIFICATION_SOURCE)
        val ds = service.getCharacteristic(AncsUuids.DATA_SOURCE)
        val cp = service.getCharacteristic(AncsUuids.CONTROL_POINT)
        if (ns == null || ds == null || cp == null) {
            retryLink("Servicio ANCS incompleto")
            return
        }
        controlPoint = cp
        BridgeStatus.update { it.copy(ancs = AncsState.SUBSCRIBING, ancsDetail = "Data Source") }
        subscribe(g, ds) {
            BridgeStatus.update { it.copy(ancs = AncsState.DATA_SOURCE_CONFIRMED, ancsDetail = "Notification Source") }
            BridgeStatus.event("Data Source confirmado")
            subscribe(g, ns) {
                val effects = engine.startSession(Pseudonym.of(peer))
                prefs.edit { putLong(WristBridgeApp.PREF_LAST_GENERATION, engine.generation) }
                recovery.ready()
                prefs.edit { putString(PREF_PEER, peer.address) }
                campaign.sessionStart(engine.generation)
                BridgeStatus.update { it.copy(ancs = AncsState.READY, ancsDetail = "", generation = engine.generation) }
                BridgeStatus.event("ANCS listo gen=${engine.generation}")
                apply(effects)
                // Battery: a relaxed connection interval is enough for notifications and media commands.
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }
                // Music only after notifications are live; its outcome never feeds back into ANCS.
                ams.setup(g)
            }
        }
    }

    /** Local enable plus remote CCCD write; success only after the descriptor write is confirmed. */
    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic, onConfirmed: () -> Unit) {
        val q = queue ?: return
        val cccd = ch.getDescriptor(AncsUuids.CCCD) ?: run {
            retryLink("Falta CCCD")
            return
        }
        // iOS replays existing notifications (PreExisting) only on a fresh Notification Source subscription.
        // A recovery or Service Changed on a link that stayed up finds the CCCD still enabled, so a bonded peer
        // gets disable-then-enable; its outcome is irrelevant (the enable below decides). Unbonded peers skip
        // it so the pairing path is unchanged.
        if (ch.uuid == AncsUuids.NOTIFICATION_SOURCE && device?.bondState == BluetoothDevice.BOND_BONDED) {
            enqueue(q, "desc:${ch.uuid}", 5_000, {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            }) {}
        }
        enqueue(q, "desc:${ch.uuid}", 10_000, {
            g.setCharacteristicNotification(ch, true) &&
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        }) { outcome ->
            when {
                outcome == GattOutcome.Success -> onConfirmed()
                outcome is GattOutcome.Failed && outcome.status in AUTH_STATUSES &&
                    device?.bondState != BluetoothDevice.BOND_BONDED ->
                    device?.let { requestBond(it, "Suscripción requiere vínculo") }
                outcome == GattOutcome.TimedOut && device?.bondState != BluetoothDevice.BOND_BONDED ->
                    device?.let { requestBond(it, "Esperando que aceptes el emparejamiento") }
                else -> {
                    BridgeStatus.update {
                        it.copy(ancs = AncsState.FAILED, ancsDetail = "Suscripción falló: $outcome",
                            lastGattStatus = (outcome as? GattOutcome.Failed)?.status)
                    }
                    BridgeStatus.event("Suscripción ANCS falló $outcome")
                    retryLink("No se confirmó la suscripción ANCS")
                }
            }
        }
    }

    private fun requestBond(peer: BluetoothDevice, why: String) {
        bondWait.begin()
        BridgeStatus.update { it.copy(ancs = AncsState.SUBSCRIBING, ancsDetail = "$why; acepta el emparejamiento") }
        BridgeStatus.event("Esperando vínculo: $why")
        if (peer.bondState == BluetoothDevice.BOND_NONE && !peer.createBond()) {
            failPairing("El sistema no pudo iniciar el emparejamiento. Vuelve a intentarlo.")
            return
        }
        scheduleTick()
    }

    private fun failPairing(detail: String) {
        closeLink("vínculo no disponible")
        BridgeStatus.update { it.copy(ancs = AncsState.FAILED, ancsDetail = detail) }
        BridgeStatus.event("Emparejamiento no completado o vínculo retirado")
    }

    private fun apply(effects: List<SessionEffect>) {
        campaign.effects(effects)
        for (e in effects) {
            when (e) {
                is SessionEffect.WriteControlPoint -> writeControlPoint(e)
                else -> presenter.apply(e)
            }
        }
        BridgeStatus.update { it.copy(counters = engine.counters) }
        scheduleTick()
    }

    private fun writeControlPoint(e: SessionEffect.WriteControlPoint) {
        val g = gatt
        val q = queue
        val cp = controlPoint
        if (g == null || q == null || cp == null) {
            apply(engine.onControlPointWritten(e.generation, e.transactionId, GattOutcome.Cancelled))
            return
        }
        enqueue(q, "write:${cp.uuid}", 5_000, {
            g.writeCharacteristic(cp, e.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        }) { outcome ->
            if (outcome is GattOutcome.Failed) BridgeStatus.update { it.copy(lastGattStatus = outcome.status) }
            apply(engine.onControlPointWritten(e.generation, e.transactionId, outcome))
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val next = listOfNotNull(engine.nextDeadlineNanos(), queue?.nextDeadlineNanos(), bondWait.nextDeadlineNanos(), recovery.nextDeadlineNanos()).minOrNull() ?: return
        val delayMs = ((next - clock.nowNanos()) / 1_000_000).coerceAtLeast(0) + 1
        handler.postDelayed(tick, delayMs)
    }

    private inner class Callback(private val gen: Long) : BluetoothGattCallback() {
        private fun current(g: BluetoothGatt) = gen == linkGen && g === gatt

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!current(g)) {
                try { g.close() } catch (_: SecurityException) {}
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    retryLink("Conexión rechazada estado=$status")
                    return
                }
                if (linkConnected) return
                recovery.cancel()
                linkConnected = true
                campaign.linkUp()
                val q = GattOperationQueue(clock)
                queue = q
                BridgeStatus.event("Enlace cliente ANCS conectado estado=$status")
                enqueue(q, "mtu", 5_000, { g.requestMtu(517) }) { discover(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val peer = device
                BridgeStatus.event("Enlace ANCS desconectado estado=$status")
                campaign.linkDown("gatt=$status")
                BridgeStatus.update { it.copy(ancs = AncsState.IDLE, ancsDetail = "Desconectado ($status)", lastGattStatus = status) }
                if (peer != null) retryLink("Desconexión estado=$status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!current(g)) return
            BridgeStatus.event("MTU negociado=$mtu estado=$status")
            BridgeStatus.update { it.copy(mtu = mtu) }
            queue?.onCallback("mtu", status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (current(g)) queue?.onCallback("discover", status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (current(g)) queue?.onCallback("desc:${descriptor.characteristic.uuid}", status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (current(g)) queue?.onCallback("write:${characteristic.uuid}", status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (!current(g)) return
            val gen = engine.generation
            when (characteristic.uuid) {
                AncsUuids.NOTIFICATION_SOURCE -> {
                    BridgeStatus.update { it.copy(lastReceptionElapsedMs = SystemClock.elapsedRealtime()) }
                    apply(engine.onNotificationSource(gen, value))
                }
                AncsUuids.DATA_SOURCE -> apply(engine.onDataSource(gen, value))
                else -> ams.onCharacteristicChanged(characteristic.uuid, value)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (!current(g)) return
            // The value is handed over before completing the operation, whose continuation consumes it.
            ams.onCharacteristicRead(characteristic.uuid, value, status)
            queue?.onCallback("read:${characteristic.uuid}", status)
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            if (!current(g)) return
            // R03: a new GATT client link gives a new generation, a clean discovery and fresh subscriptions
            // (and the PreExisting replay). Rediscovering on the same client is not enough: an operation
            // already in flight there (MTU, write) can swallow the discovery callback, observed on Watch7.
            retryLink("Service Changed")
        }
    }

    companion object {
        private const val PREF_PEER = "ancs_authorized_peer"

        /** Insufficient authentication / encryption / auth failure. */
        private val AUTH_STATUSES = setOf(5, 15, 137)

        fun bondOf(state: Int) = when (state) {
            BluetoothDevice.BOND_BONDED -> BondState.BONDED
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            BluetoothDevice.BOND_NONE -> BondState.NONE
            else -> BondState.UNKNOWN
        }
    }
}
