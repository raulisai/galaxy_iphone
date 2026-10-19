package dev.wristbridge.protocol.p2

import dev.wristbridge.protocol.p2.secure.NoiseXX
import dev.wristbridge.protocol.p2.secure.X25519Key
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deterministic Noise_XX handshake + first transport messages with fixed keys (PROPOSED vector for Apple's port).
 * Set -Dwristbridge.writeVectors=true to regenerate test/fixtures/shared/p2-noise-xx-vectors.json.
 */
private const val CRLF = "\r\n"
private const val LF = "\n"

class NoiseVectorsTest {
    private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun key(seed: Int) = X25519Key.fromPrivate(ByteArray(32) { (seed + it).toByte() })

    private fun build(): String {
        val si = key(1); val sr = key(101); val ei = key(51); val er = key(151)
        val prologue = "wristbridge-p2-vector".toByteArray()
        val i = NoiseXX(true, si, prologue, testEphemeral = ei)
        val r = NoiseXX(false, sr, prologue, testEphemeral = er)
        val m1 = i.writeMessage(); r.readMessage(m1)
        val m2 = r.writeMessage("p2".toByteArray()); i.readMessage(m2)
        val m3 = i.writeMessage("p3".toByteArray()); r.readMessage(m3)
        val ti = i.split(); val tr = r.split()
        val ad = byteArrayOf(1, 2)
        val c1 = ti.send.seal(ad, "initiator-to-responder".toByteArray())
        val c2 = tr.send.seal(ad, "responder-to-initiator".toByteArray())
        return """{
  "status": "PROPOSED",
  "protocol": "Noise_XX_25519_ChaChaPoly_SHA256",
  "prologueHex": "${hx(prologue)}",
  "initiatorStaticPrivateHex": "${hx(si.privateKey)}",
  "responderStaticPrivateHex": "${hx(sr.privateKey)}",
  "initiatorEphemeralPrivateHex": "${hx(ei.privateKey)}",
  "responderEphemeralPrivateHex": "${hx(er.privateKey)}",
  "message1Hex": "${hx(m1)}",
  "message2PayloadUtf8": "p2",
  "message2Hex": "${hx(m2)}",
  "message3PayloadUtf8": "p3",
  "message3Hex": "${hx(m3)}",
  "handshakeHashHex": "${hx(ti.handshakeHash)}",
  "securityCode": "${ti.securityCode()}",
  "transportAdHex": "${hx(ad)}",
  "initiatorToResponderHex": "${hx(c1)}",
  "responderToInitiatorHex": "${hx(c2)}"
}
"""
    }

    @Test
    fun vectorsAreStable() {
        val root = File(System.getProperty("wristbridge.repoRoot"))
        val f = File(root, "test/fixtures/shared/p2-noise-xx-vectors.json")
        val built = build()
        if (System.getProperty("wristbridge.writeVectors") == "true") f.writeText(built)
        assertEquals(f.readText().replace(CRLF, LF), built)
    }
}
