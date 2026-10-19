package dev.wristbridge.protocol.p2.secure

import dev.wristbridge.protocol.p2.P2Error
import dev.wristbridge.protocol.p2.P2Exception
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Noise_XX_25519_ChaChaPoly_SHA256 written against the Noise specification (rev 34): own X25519, JCA ChaCha20-Poly1305/SHA-256/HMAC.
 * NOT independently reviewed (draft section 8, K3): own implementation, checked by RFC 7748 vectors, size vectors
 * and round-trips only. Replace with a reviewed library before enabling anything beyond low-risk data.
 */
class X25519Key(val privateKey: ByteArray, val publicKey: ByteArray) {
    companion object {
        private val BASE = ByteArray(32).also { it[0] = 9 }

        fun generate(rnd: SecureRandom? = null): X25519Key {
            val priv = ByteArray(32)
            (rnd ?: SecureRandom()).nextBytes(priv)
            return fromPrivate(priv)
        }

        fun fromPrivate(priv: ByteArray): X25519Key {
            require(priv.size == 32)
            return X25519Key(priv.copyOf(), X25519.scalarMult(priv, BASE))
        }

        fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
            val out = X25519.scalarMult(priv, pub)
            if (out.all { it == 0.toByte() }) throw P2Exception(P2Error.INVALID_PAYLOAD, "low-order public key")
            return out
        }
    }
}

/**
 * X25519 (RFC 7748) with a Montgomery ladder over BigInteger. Pure Kotlin so that JVM tests and the watch run the same
 * code (the Android provider rejects the parameter specs the JDK accepts). NOT constant time: BigInteger arithmetic and
 * the branchy swap can leak timing. Part of the K3 review; replace with a reviewed library when one is chosen.
 */
internal object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)

    private fun decodeLe(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun encodeLe(v: BigInteger): ByteArray {
        val be = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val out = ByteArray(32)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }

    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kInt = decodeLe(k)
        val uc = u.copyOf()
        uc[31] = (uc[31].toInt() and 0x7F).toByte()
        val x1 = decodeLe(uc).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = kInt.testBit(t)
            if (swap != bit) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val s1 = da.add(cb).mod(P)
            val s2 = da.subtract(cb).mod(P)
            x3 = s1.multiply(s1).mod(P)
            z3 = x1.multiply(s2.multiply(s2).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return encodeLe(x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P))
    }
}

internal object NoiseCrypto {
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    /** HKDF per Noise section 4.3 with two outputs. */
    fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(ck, ikm)
        val o1 = hmac(temp, byteArrayOf(1))
        val o2 = hmac(temp, o1 + byteArrayOf(2))
        return o1 to o2
    }

    /** 4 zero bytes then the 64-bit little-endian counter. */
    fun nonce(n: Long): ByteArray {
        val b = ByteArray(12)
        for (i in 0 until 8) b[4 + i] = (n ushr (8 * i)).toByte()
        return b
    }

    private fun cipher(mode: Int, key: ByteArray, n: Long, ad: ByteArray): Cipher =
        Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce(n)))
            updateAAD(ad)
        }

    fun encrypt(key: ByteArray, n: Long, ad: ByteArray, pt: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, n, ad).doFinal(pt)

    fun decrypt(key: ByteArray, n: Long, ad: ByteArray, ct: ByteArray): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, key, n, ad).doFinal(ct)
    } catch (e: java.security.GeneralSecurityException) {
        throw P2Exception(P2Error.BAD_TAG, "authentication failed")
    }
}

/** Transport cipher for one direction. The counter is implicit (frames arrive in order, draft section 8.5). */
class CipherState internal constructor(private val key: ByteArray) {
    var counter: Long = 0
        private set

    fun seal(ad: ByteArray, plaintext: ByteArray): ByteArray {
        if (counter == Long.MAX_VALUE) throw P2Exception(P2Error.EXPIRED, "nonce space exhausted")
        return NoiseCrypto.encrypt(key, counter, ad, plaintext).also { counter++ }
    }

    /** The counter only advances on success, so a forged or replayed message leaves the state intact. */
    fun open(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < 16) throw P2Exception(P2Error.BAD_TAG, "short ciphertext")
        return NoiseCrypto.decrypt(key, counter, ad, ciphertext).also { counter++ }
    }

    fun wipe() = key.fill(0)
}

class TransportKeys(
    val send: CipherState,
    val receive: CipherState,
    val handshakeHash: ByteArray,
    val remoteStatic: ByteArray,
) {
    /** Six-digit security code both screens must show (draft section 8.4), derived from the transcript hash. */
    fun securityCode(): String {
        val d = NoiseCrypto.sha256("WB-SAS-v1".toByteArray(), handshakeHash)
        val v = (((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)) % 1_000_000L
        return v.toString().padStart(6, '0')
    }
}

/** XX handshake. Messages: 1 "-> e", 2 "<- e, ee, s, es", 3 "-> s, se". */
class NoiseXX(
    private val initiator: Boolean,
    private val s: X25519Key,
    prologue: ByteArray,
    private val random: SecureRandom? = null,
    /** Test vectors only: fixed ephemeral key. Never set in production code. */
    private val testEphemeral: X25519Key? = null,
) {
    private var h: ByteArray
    private var ck: ByteArray
    private var k: ByteArray? = null
    private var n = 0L
    private var e: X25519Key? = null
    private var re: ByteArray? = null
    private var rs: ByteArray? = null
    private var step = 0

    init {
        val name = "Noise_XX_25519_ChaChaPoly_SHA256".toByteArray()
        h = if (name.size <= 32) name.copyOf(32) else NoiseCrypto.sha256(name)
        ck = h
        mixHash(prologue)
    }

    val isComplete: Boolean get() = step == 3

    /** Remote static key, known after message 2 (initiator) or message 3 (responder). */
    val remoteStatic: ByteArray? get() = rs

    private fun mixHash(d: ByteArray) {
        h = NoiseCrypto.sha256(h, d)
    }

    private fun mixKey(ikm: ByteArray) {
        val (a, b) = NoiseCrypto.hkdf2(ck, ikm)
        ck = a
        k = b
        n = 0
    }

    private fun encryptAndHash(pt: ByteArray): ByteArray {
        val key = k ?: return pt.also { mixHash(it) }
        return NoiseCrypto.encrypt(key, n++, h, pt).also { mixHash(it) }
    }

    private fun decryptAndHash(ct: ByteArray): ByteArray {
        val key = k ?: return ct.also { mixHash(it) }
        return NoiseCrypto.decrypt(key, n, h, ct).also { n++; mixHash(ct) }
    }

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        val myTurn = if (initiator) step == 0 || step == 2 else step == 1
        if (!myTurn) throw P2Exception(P2Error.SEQUENCE, "not our turn")
        val out = java.io.ByteArrayOutputStream()
        when (step) {
            0 -> {
                e = testEphemeral ?: X25519Key.generate(random)
                out.write(e!!.publicKey)
                mixHash(e!!.publicKey)
            }
            1 -> {
                e = testEphemeral ?: X25519Key.generate(random)
                out.write(e!!.publicKey)
                mixHash(e!!.publicKey)
                mixKey(X25519Key.dh(e!!.privateKey, re!!))
                out.write(encryptAndHash(s.publicKey))
                mixKey(X25519Key.dh(s.privateKey, re!!))
            }
            2 -> {
                out.write(encryptAndHash(s.publicKey))
                mixKey(X25519Key.dh(s.privateKey, re!!))
            }
        }
        out.write(encryptAndHash(payload))
        step++
        return out.toByteArray()
    }

    fun readMessage(msg: ByteArray): ByteArray {
        val myTurn = if (initiator) step == 1 else step == 0 || step == 2
        if (!myTurn) throw P2Exception(P2Error.SEQUENCE, "not their turn")
        var o = 0
        fun take(len: Int): ByteArray {
            if (msg.size - o < len) throw P2Exception(P2Error.MALFORMED_FRAME, "short handshake message")
            return msg.copyOfRange(o, o + len).also { o += len }
        }
        when (step) {
            0 -> {
                re = take(32)
                mixHash(re!!)
            }
            1 -> {
                re = take(32)
                mixHash(re!!)
                mixKey(X25519Key.dh(e!!.privateKey, re!!))
                rs = decryptAndHash(take(48))
                mixKey(X25519Key.dh(e!!.privateKey, rs!!))
            }
            2 -> {
                rs = decryptAndHash(take(48))
                mixKey(X25519Key.dh(e!!.privateKey, rs!!))
            }
        }
        val payload = decryptAndHash(msg.copyOfRange(o, msg.size))
        step++
        return payload
    }

    fun split(): TransportKeys {
        if (!isComplete) throw P2Exception(P2Error.SEQUENCE, "handshake incomplete")
        val (k1, k2) = NoiseCrypto.hkdf2(ck, ByteArray(0))
        val (snd, rcv) = if (initiator) k1 to k2 else k2 to k1
        return TransportKeys(CipherState(snd), CipherState(rcv), h.copyOf(), rs!!.copyOf())
    }
}
