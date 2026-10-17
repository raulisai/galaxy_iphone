package dev.wristbridge.watch.bridge

import android.bluetooth.BluetoothDevice
import java.security.MessageDigest

/** Short stable pseudonym of a peer for logs and UI; the Bluetooth address itself is never shown. */
object Pseudonym {
    fun of(device: BluetoothDevice): String = of(device.address ?: "unknown")

    fun of(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("wristbridge:" + address).toByteArray())
        return "peer-" + digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
