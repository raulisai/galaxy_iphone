package dev.wristbridge.watch.p2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.wristbridge.protocol.contract.WristBridgeProfile
import dev.wristbridge.protocol.p2.secure.NoiseXX
import dev.wristbridge.protocol.p2.secure.P2Listener
import dev.wristbridge.protocol.p2.secure.P2Policy
import dev.wristbridge.protocol.p2.secure.P2Profile
import dev.wristbridge.protocol.p2.secure.P2Responder
import dev.wristbridge.protocol.p2.secure.X25519Key
import dev.wristbridge.protocol.p2.health.HealthSource
import dev.wristbridge.watch.bridge.BridgeStatus
import dev.wristbridge.watch.bridge.Pseudonym
import dev.wristbridge.watch.health.HealthStore
import dev.wristbridge.watch.health.HealthStoreSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Static X25519 identity of the watch, wrapped by a non-exportable AES-256-GCM Android Keystore key (draft 8.6; the key
 * does not require an unlocked device because the bridge runs while the watch is locked). The paired iPhone's PUBLIC
 * key is stored next to it. Nothing here is written to logs.
 */
class P2Identity(context: Context) {
    private val dir: File = context.noBackupFilesDir
    private val keyFile = File(dir, "p2-identity.bin")
    private val peerFile = File(dir, "p2-peer.bin")

    @Synchronized
    fun localKey(): X25519Key {
        if (keyFile.exists()) {
            runCatching { return X25519Key.fromPrivate(unwrap(keyFile.readBytes())) }
            keyFile.delete() // unreadable (e.g. keystore reset): a new identity forces a new registration
            peerFile.delete()
        }
        val k = X25519Key.generate()
        keyFile.writeBytes(wrap(k.privateKey))
        return k
    }

    @Synchronized fun pinned(): ByteArray? = peerFile.takeIf { it.exists() }?.readBytes()?.takeIf { it.size == 32 }

    @Synchronized fun pin(key: ByteArray) {
        require(key.size == 32)
        peerFile.writeBytes(key)
    }

    @Synchronized fun forget() {
        peerFile.delete()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        return c.iv + c.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private companion object {
        const val ALIAS = "wristbridge_p2_identity_wrap"
    }
}

enum class P2Phase { IDLE, REGISTERING, CODE, READY, CLOSED }

data class P2Ui(
    val phase: P2Phase = P2Phase.IDLE,
    val paired: Boolean = false,
    val code: String? = null,
    /** Elapsed-realtime deadline of the registration window, 0 when closed. */
    val registrationUntil: Long = 0,
    val lastClosed: String = "",
    val cryptoSelfTest: String = "sin ejecutar",
)

/** Process-wide state and entry points used by the UI (all work is re-posted to the BLE thread). */
object P2Bridge {
    private val mutable = MutableStateFlow(P2Ui())
    val state: StateFlow<P2Ui> = mutable
    internal fun update(f: (P2Ui) -> P2Ui) = mutable.update(f)
    @Volatile internal var channel: P2Channel? = null
    private const val REGISTRATION_WINDOW_MS = 120_000L

    fun refreshPaired(context: Context) {
        update { it.copy(paired = P2Identity(context).pinned() != null) }
    }

    fun openRegistration() {
        update { it.copy(phase = P2Phase.REGISTERING, registrationUntil = SystemClock.elapsedRealtime() + REGISTRATION_WINDOW_MS, code = null) }
    }

    fun cancelRegistration() {
        update { it.copy(phase = if (it.phase == P2Phase.READY) it.phase else P2Phase.IDLE, registrationUntil = 0, code = null) }
        channel?.confirmCode(false)
    }

    fun confirmCode(match: Boolean) {
        channel?.confirmCode(match)
        if (!match) update { it.copy(phase = P2Phase.IDLE, registrationUntil = 0, code = null) }
    }

    fun forget(context: Context) {
        P2Identity(context).forget()
        channel?.closeAll("iPhone olvidado")
        update { it.copy(paired = false, phase = P2Phase.IDLE, code = null, registrationUntil = 0) }
    }
}

/**
 * Glue between the GATT server and [P2Responder]: per-device responder, MTU, and a one-in-flight notification queue
 * (draft section 5). Only touched on the BLE thread.
 */
@SuppressLint("MissingPermission")
class P2Channel(
    private val context: Context,
    private val handler: Handler,
    private val server: () -> BluetoothGattServer?,
    private val eventsCharacteristic: () -> BluetoothGattCharacteristic?,
) {
    private val identity = P2Identity(context)
    private val store get() = HealthStore.get(context)

    private class Peer(val device: BluetoothDevice, val responder: P2Responder) {
        val queue = ArrayDeque<ByteArray>()
        var inFlight = false
    }

    private val mtus = HashMap<String, Int>()
    private val peers = HashMap<String, Peer>()

    private val policy = object : P2Policy {
        override fun registrationOpen() = SystemClock.elapsedRealtime() < P2Bridge.state.value.registrationUntil
        override fun pinnedPeer() = identity.pinned()
        override fun pin(peerStaticKey: ByteArray) {
            identity.pin(peerStaticKey)
            P2Bridge.update { it.copy(paired = true) }
        }
        override val health: HealthSource get() = HealthStoreSource(store)
    }

    fun attach() {
        P2Bridge.channel = this
        P2Bridge.refreshPaired(context)
    }

    fun detach() {
        closeAll("detenido")
        if (P2Bridge.channel === this) P2Bridge.channel = null
    }

    fun onMtu(device: BluetoothDevice, mtu: Int) {
        mtus[device.address] = mtu
    }

    /** CCCD of Events enabled: a new application session begins. The CCCD is not a readiness signal (draft section 1). */
    fun onSubscribed(device: BluetoothDevice) {
        peers.remove(device.address)?.responder?.close("nueva suscripción")
        val id = Pseudonym.of(device)
        val maxAtt = ((mtus[device.address] ?: 23) - 3).coerceAtLeast(20)
        val listener = object : P2Listener {
            override fun onSecurityCode(code: String) {
                P2Bridge.update { it.copy(phase = P2Phase.CODE, code = code) }
                BridgeStatus.event("P2: código de seguridad mostrado peer=$id")
            }
            override fun onReady(sessionId: String, registered: Boolean) {
                P2Bridge.update { it.copy(phase = P2Phase.READY, code = null, registrationUntil = 0, paired = true) }
                BridgeStatus.event("P2: sesión lista peer=$id registrado=$registered sesion=$sessionId")
            }
            override fun onClosed(reason: String) {
                P2Bridge.update { it.copy(phase = if (it.phase == P2Phase.READY || it.phase == P2Phase.CODE) P2Phase.CLOSED else it.phase, code = null, lastClosed = reason) }
                BridgeStatus.event("P2: sesión cerrada peer=$id motivo=$reason")
            }
            override fun onDiagnostic(text: String) = BridgeStatus.event("P2: $text")
        }
        peers[device.address] = Peer(device, P2Responder(identity.localKey(), policy, maxAtt, listener))
        BridgeStatus.event("P2: suscrito peer=$id maxAtt=$maxAtt")
    }

    fun onUnsubscribed(device: BluetoothDevice) {
        peers.remove(device.address)?.responder?.close("sin suscripción")
    }

    fun onDisconnected(device: BluetoothDevice) {
        mtus.remove(device.address)
        onUnsubscribed(device)
    }

    fun onWrite(device: BluetoothDevice, value: ByteArray) {
        val peer = peers[device.address] ?: return
        val out = peer.responder.onWrite(value, System.nanoTime())
        enqueue(peer, out)
    }

    fun confirmCode(match: Boolean) {
        handler.post {
            for (peer in peers.values.toList()) enqueue(peer, peer.responder.confirmCode(match))
        }
    }

    fun closeAll(reason: String) {
        handler.post {
            peers.values.forEach { it.responder.close(reason) }
            peers.clear()
        }
    }

    private fun enqueue(peer: Peer, frames: List<ByteArray>) {
        peer.queue.addAll(frames)
        pump(peer)
    }

    fun onNotificationSent(device: BluetoothDevice, status: Int) {
        val peer = peers[device.address] ?: return
        peer.inFlight = false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            BridgeStatus.event("P2: notificación falló estado=$status")
            peer.queue.clear()
            peer.responder.close("fallo de notificación")
            return
        }
        pump(peer)
    }

    private fun pump(peer: Peer) {
        if (peer.inFlight) return
        val next = peer.queue.firstOrNull() ?: return
        val s = server() ?: return
        val c = eventsCharacteristic() ?: return
        val code = try {
            s.notifyCharacteristicChanged(peer.device, c, false, next)
        } catch (e: SecurityException) {
            BluetoothGatt.GATT_FAILURE
        }
        if (code == BluetoothGatt.GATT_SUCCESS) {
            peer.queue.removeFirst()
            peer.inFlight = true
        } else {
            BridgeStatus.event("P2: notify rechazado código=$code; se reintenta")
            handler.postDelayed({ pump(peer) }, 50)
        }
    }
}

/** Runs a full in-process handshake with the platform providers to prove X25519 and ChaCha20-Poly1305 exist on this watch. */
object P2SelfTest {
    fun run(): String = try {
        val i = NoiseXX(true, X25519Key.generate(), P2Profile.prologue())
        val r = NoiseXX(false, X25519Key.generate(), P2Profile.prologue())
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())
        val ti = i.split()
        val tr = r.split()
        val ct = ti.send.seal(byteArrayOf(1), "ok".toByteArray())
        val ok = String(tr.receive.open(byteArrayOf(1), ct)) == "ok" && ti.securityCode() == tr.securityCode()
        if (ok) "correcta" else "datos distintos"
    } catch (e: Exception) {
        "falló: ${e.javaClass.simpleName}"
    }
}
