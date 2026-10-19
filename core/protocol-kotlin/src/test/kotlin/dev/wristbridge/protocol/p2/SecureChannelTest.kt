package dev.wristbridge.protocol.p2

import dev.wristbridge.protocol.p2.health.HealthSample
import dev.wristbridge.protocol.p2.health.HealthSource
import dev.wristbridge.protocol.p2.health.HealthSync
import dev.wristbridge.protocol.p2.secure.NoiseXX
import dev.wristbridge.protocol.p2.secure.SecureSession
import dev.wristbridge.protocol.p2.secure.StrictJson
import dev.wristbridge.protocol.p2.secure.X25519Key
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

class NoiseTest {
    @Test
    fun rfc7748X25519Vector() {
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val aPub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertContentEquals(aPub, X25519Key.fromPrivate(a).publicKey)
        assertContentEquals(bPub, X25519Key.fromPrivate(b).publicKey)
        assertContentEquals(shared, X25519Key.dh(a, bPub))
        assertContentEquals(shared, X25519Key.dh(b, aPub))
    }

    private fun handshake(pi: ByteArray = byteArrayOf(1), pr: ByteArray = byteArrayOf(1)): Triple<NoiseXX, NoiseXX, List<ByteArray>> {
        val i = NoiseXX(true, X25519Key.generate(), pi)
        val r = NoiseXX(false, X25519Key.generate(), pr)
        val m1 = i.writeMessage()
        r.readMessage(m1)
        val m2 = r.writeMessage()
        i.readMessage(m2)
        val m3 = i.writeMessage()
        r.readMessage(m3)
        return Triple(i, r, listOf(m1, m2, m3))
    }

    @Test
    fun messageSizesMatchTheDraftWithEmptyPayload() {
        assertEquals(listOf(32, 96, 64), handshake().third.map { it.size })
    }

    @Test
    fun bothSidesAgreeOnKeysCodeAndIdentity() {
        val si = X25519Key.generate()
        val sr = X25519Key.generate()
        val i = NoiseXX(true, si, byteArrayOf())
        val r = NoiseXX(false, sr, byteArrayOf())
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())
        val ti = i.split()
        val tr = r.split()
        assertEquals(ti.securityCode(), tr.securityCode())
        assertContentEquals(sr.publicKey, ti.remoteStatic)
        assertContentEquals(si.publicKey, tr.remoteStatic)
        val ct = ti.send.seal(byteArrayOf(7), "hola".toByteArray())
        assertContentEquals("hola".toByteArray(), tr.receive.open(byteArrayOf(7), ct))
        val ct2 = tr.send.seal(byteArrayOf(), "eco".toByteArray())
        assertContentEquals("eco".toByteArray(), ti.receive.open(byteArrayOf(), ct2))
    }

    @Test
    fun prologueMismatchBreaksTheHandshake() {
        val i = NoiseXX(true, X25519Key.generate(), byteArrayOf(1))
        val r = NoiseXX(false, X25519Key.generate(), byteArrayOf(2))
        r.readMessage(i.writeMessage())
        val m2 = r.writeMessage()
        assertFailsWith<P2Exception> { i.readMessage(m2) }
    }

    @Test
    fun tamperingReplayAndWrongAdAreRejectedWithoutAdvancing() {
        val (i, r, _) = handshake()
        val ti = i.split()
        val tr = r.split()
        val c1 = ti.send.seal(byteArrayOf(), "uno".toByteArray())
        val bad = c1.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(P2Error.BAD_TAG, assertFailsWith<P2Exception> { tr.receive.open(byteArrayOf(), bad) }.error)
        assertContentEquals("uno".toByteArray(), tr.receive.open(byteArrayOf(), c1))
        assertEquals(P2Error.BAD_TAG, assertFailsWith<P2Exception> { tr.receive.open(byteArrayOf(), c1) }.error)
        val c2 = ti.send.seal(byteArrayOf(1), "dos".toByteArray())
        assertEquals(P2Error.BAD_TAG, assertFailsWith<P2Exception> { tr.receive.open(byteArrayOf(2), c2) }.error)
    }

    @Test
    fun differentSessionsGiveDifferentCodes() {
        assertNotEquals(handshake().first.split().securityCode(), handshake().first.split().securityCode())
    }

    @Test
    fun outOfTurnUseIsASequenceError() {
        val r = NoiseXX(false, X25519Key.generate(), byteArrayOf())
        assertEquals(P2Error.SEQUENCE, assertFailsWith<P2Exception> { r.writeMessage() }.error)
    }
}

class StrictJsonTest {
    @Test
    fun roundTripAndRejections() {
        val v = mapOf("a" to listOf(1L, 2.5, "x\n\"y"), "b" to null, "c" to true)
        assertEquals(v, StrictJson.parse(StrictJson.encode(v)))
        for (bad in listOf("{\"a\":1,\"a\":2}", "[1,]", "9007199254740992", "NaN", "{\"a\":1} x", "[[[[[[[[[[1]]]]]]]]]]", "\"\u0001\"")) {
            assertEquals(P2Error.INVALID_PAYLOAD, assertFailsWith<P2Exception>(bad) { StrictJson.parse(bad.toByteArray()) }.error)
        }
        assertFailsWith<P2Exception> { StrictJson.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 49)) }
        assertFailsWith<P2Exception> { StrictJson.parse(byteArrayOf(0xC3.toByte())) }
    }
}

class SecureHealthSyncTest {
    private class Watch(n: Int) : HealthSource {
        val all = (1..n).map { HealthSample(it.toLong(), if (it % 2 == 0) "heart_rate_bpm" else "steps_daily_snapshot", it * 1000L, it * 1000L + 500, 60.0 + it, "id-$it") }
        var acked = 0L
        override fun samplesAfter(cursor: Long, limit: Int) = all.filter { it.cursor > cursor }.take(limit)
        override fun hasMoreAfter(cursor: Long) = all.any { it.cursor > cursor }
        override fun acknowledge(through: Long) { acked = through }
    }

    private fun sessions(mtu: Int): Pair<SecureSession, SecureSession> {
        val i = NoiseXX(true, X25519Key.generate(), byteArrayOf(9))
        val r = NoiseXX(false, X25519Key.generate(), byteArrayOf(9))
        r.readMessage(i.writeMessage()); i.readMessage(r.writeMessage()); r.readMessage(i.writeMessage())
        return SecureSession(i.split(), mtu) to SecureSession(r.split(), mtu)
    }

    private fun deliver(from: Pair<Int, List<ByteArray>>, to: SecureSession): Any? {
        var out: SecureSession.Inbound = SecureSession.Inbound.None
        for (f in from.second) out = to.receive(f, 0L)
        return (out as SecureSession.Inbound.Message).json
    }

    @Test
    fun phonePullsInBatchesAcksAfterSavingAndStaysIdempotent() {
        val (phone, watchSession) = sessions(23 - 3 + 40)
        val watch = Watch(450)
        val ledger = LinkedHashMap<String, HealthSample>()
        var cursor = 0L
        var req = 1L
        do {
            val reqJson = deliver(phone.send(HealthSync.requestCursor(req++, cursor, 200)), watchSession)
            val resp = HealthSync.respond(reqJson, watch)
            val back = deliver(watchSession.send(resp), phone)
            val samples = HealthSync.parseBatch(back)
            samples.forEach { ledger[it.sampleId] = it }         // idempotent by sampleId
            cursor = samples.lastOrNull()?.cursor ?: cursor
            val ack = deliver(phone.send(HealthSync.requestAck(req++, cursor)), watchSession)   // only after saving
            deliver(watchSession.send(HealthSync.respond(ack, watch)), phone)
        } while (HealthSync.batchHasMore(back))
        assertEquals(450, ledger.size)
        assertEquals(450L, watch.acked)
    }

    @Test
    fun replayedAndTamperedDataFramesAreRejected() {
        val (phone, watchSession) = sessions(100)
        val m = phone.send(HealthSync.requestCursor(1, 0, 10))
        assertTrue(deliver(m, watchSession) != null)
        // Replaying the same frames: counter no longer matches.
        // A replayed message must never be delivered again: it is either rejected or ignored without effect.
        val replayed = runCatching { m.second.map { watchSession.receive(it, 0L) } }
        assertTrue(replayed.isFailure || replayed.getOrThrow().none { it is SecureSession.Inbound.Message })
        val m2 = phone.send(HealthSync.requestCursor(2, 0, 10))
        val frames = m2.second.toMutableList()
        frames[frames.size - 1] = frames.last().copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val tamperError = assertFailsWith<P2Exception> { frames.forEach { watchSession.receive(it, 0L) } }
        assertEquals(P2Error.BAD_TAG, tamperError.error, tamperError.message)
    }

    @Test
    fun unknownCapabilityAndBadRequestsHaveNoEffect() {
        val w = Watch(3)
        val badCap = mapOf("kind" to "req", "requestId" to 1L, "capability" to "contacts.read", "payload" to mapOf("op" to "cursor"))
        assertEquals(P2Error.UNKNOWN_CAPABILITY, assertFailsWith<P2Exception> { HealthSync.respond(badCap, w) }.error)
        assertFailsWith<P2Exception> { HealthSync.respond(HealthSync.requestCursor(1, 0, 100000), w) }
        assertEquals(0L, w.acked)
    }
}
