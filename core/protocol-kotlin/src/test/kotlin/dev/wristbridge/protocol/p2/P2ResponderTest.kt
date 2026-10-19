package dev.wristbridge.protocol.p2

import dev.wristbridge.protocol.p2.health.HealthSample
import dev.wristbridge.protocol.p2.health.HealthSource
import dev.wristbridge.protocol.p2.health.HealthSync
import dev.wristbridge.protocol.p2.secure.NoiseXX
import dev.wristbridge.protocol.p2.secure.P2Listener
import dev.wristbridge.protocol.p2.secure.P2Policy
import dev.wristbridge.protocol.p2.secure.P2Profile
import dev.wristbridge.protocol.p2.secure.P2Responder
import dev.wristbridge.protocol.p2.secure.SecureSession
import dev.wristbridge.protocol.p2.secure.X25519Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P2ResponderTest {
    private val mtu = 23 - 3 + 60

    private class Source : HealthSource {
        val all = (1..30).map { HealthSample(it.toLong(), "heart_rate_bpm", it * 1000L, it * 1000L, 70.0 + it, "s-$it") }
        var acked = 0L
        override fun samplesAfter(cursor: Long, limit: Int) = all.filter { it.cursor > cursor }.take(limit)
        override fun hasMoreAfter(cursor: Long) = all.any { it.cursor > cursor }
        override fun acknowledge(through: Long) { acked = through }
    }

    private class Policy(var open: Boolean) : P2Policy {
        var pinned: ByteArray? = null
        val src = Source()
        override fun registrationOpen() = open
        override fun pinnedPeer() = pinned
        override fun pin(peerStaticKey: ByteArray) { pinned = peerStaticKey.copyOf() }
        override val health: HealthSource get() = src
        override val deviceId = "watch-test"
    }

    private class Listener : P2Listener {
        var code: String? = null
        var ready: Pair<String, Boolean>? = null
        var closed: String? = null
        var findWatch = 0
        var response: Triple<String, Long, String?>? = null
        override fun onFindWatch() { findWatch++ }
        override fun onResponse(capability: String, requestId: Long, error: String?) { response = Triple(capability, requestId, error) }
        override fun onSecurityCode(code: String) { this.code = code }
        override fun onReady(sessionId: String, registered: Boolean) { ready = sessionId to registered }
        override fun onClosed(reason: String) { closed = reason }
        override fun onDiagnostic(text: String) = Unit
    }

    /** Phone-side reference client (the Swift port must behave the same). */
    private class Phone(val key: X25519Key, val mtu: Int) {
        val noise = NoiseXX(true, key, P2Profile.prologue())
        var session: SecureSession? = null
        val reasm = MessageReassembler(knownMaxAttValueBytes = mtu)

        fun hello(responder: P2Responder): List<ByteArray> {
            val m1 = FrameCodec.encodeMessage(P2Wire.TYPE_HANDSHAKE, 0, 1, noise.writeMessage(), mtu)
            val out = m1.flatMap { responder.onWrite(it, 0) }
            var m2: ByteArray? = null
            for (f in out) {
                val p = FrameCodec.parse(f) as ParseResult.Parsed
                val r = reasm.accept(p.frame, 0)
                if (r is MessageReassembler.Result.Complete) m2 = r.body
            }
            noise.readMessage(m2!!)
            val m3 = FrameCodec.encodeMessage(P2Wire.TYPE_HANDSHAKE, 0, 3, noise.writeMessage(), mtu)
            val resp = m3.flatMap { responder.onWrite(it, 0) }
            session = SecureSession(noise.split(), mtu)
            return resp
        }


        fun call(responder: P2Responder, json: Map<String, Any?>): Any? {
            val frames = session!!.send(json).second
            val out = frames.flatMap { responder.onWrite(it, 0) }
            var result: Any? = null
            for (f in out) {
                val r = session!!.receive(f, 0)
                if (r is SecureSession.Inbound.Message) result = r.json
            }
            return result
        }
    }

    private fun newResponder(policy: Policy, l: Listener) = P2Responder(X25519Key.generate(), policy, mtu, l)

    @Test
    fun registrationShowsCodeThenPinsAndServesHealth() {
        val policy = Policy(open = true)
        val l = Listener()
        val r = newResponder(policy, l)
        val phone = Phone(X25519Key.generate(), mtu)
        assertTrue(phone.hello(r).isEmpty())
        assertNotNull(l.code)
        assertNull(policy.pinned)
        assertTrue(r.confirmCode(true).isEmpty())
        assertTrue(r.isReady)
        assertEquals(true, l.ready!!.second)
        assertNotNull(policy.pinned)

        val hello = phone.call(r, mapOf("kind" to "req", "requestId" to 1L, "capability" to "hello", "payload" to emptyMap<String, Any?>())) as Map<*, *>
        assertEquals(l.ready!!.first, (hello["payload"] as Map<*, *>)["sessionId"])
        val batch = phone.call(r, HealthSync.requestCursor(2, 0, 100))
        assertEquals(30, HealthSync.parseBatch(batch).size)
        phone.call(r, HealthSync.requestAck(3, 30))
        assertEquals(30L, policy.src.acked)
    }

    @Test
    fun codeMismatchStoresNothing() {
        val policy = Policy(open = true)
        val l = Listener()
        val r = newResponder(policy, l)
        Phone(X25519Key.generate(), mtu).hello(r)
        val out = r.confirmCode(false)
        assertEquals(1, out.size)
        assertNull(policy.pinned)
        assertTrue(r.isClosed)
    }

    @Test
    fun registrationClosedRejectsUnknownPhone() {
        val policy = Policy(open = false)
        val l = Listener()
        val r = newResponder(policy, l)
        Phone(X25519Key.generate(), mtu).hello(r)
        assertTrue(r.isClosed)
        assertEquals("E_NOT_AUTHORIZED", l.closed)
        assertNull(policy.pinned)
    }

    @Test
    fun pinnedPhoneReconnectsWithoutCodeAndAnotherPhoneIsRejected() {
        val policy = Policy(open = true)
        val phoneKey = X25519Key.generate()
        val first = newResponder(policy, Listener())
        Phone(phoneKey, mtu).hello(first)
        first.confirmCode(true)
        policy.open = false

        val l2 = Listener()
        val second = newResponder(policy, l2)
        val p = Phone(phoneKey, mtu)
        p.hello(second)
        assertTrue(second.isReady)
        assertEquals(false, l2.ready!!.second)
        assertNull(l2.code)

        val l3 = Listener()
        val third = newResponder(policy, l3)
        Phone(X25519Key.generate(), mtu).hello(third)
        assertEquals("E_IDENTITY_CHANGED", l3.closed)
        assertFalse(third.isReady)
    }

    @Test
    fun unknownCapabilityAnswersWithErrorAndKeepsTheSession() {
        val policy = Policy(open = true)
        val r = newResponder(policy, Listener())
        val phone = Phone(X25519Key.generate(), mtu)
        phone.hello(r)
        r.confirmCode(true)
        val res = phone.call(r, mapOf("kind" to "req", "requestId" to 9L, "capability" to "contacts.read", "payload" to emptyMap<String, Any?>())) as Map<*, *>
        assertEquals("E_UNKNOWN_CAPABILITY", res["error"])
        assertTrue(r.isReady)
    }

    @Test
    fun dataBeforeConfirmationIsRejected() {
        val policy = Policy(open = true)
        val l = Listener()
        val r = newResponder(policy, l)
        val phone = Phone(X25519Key.generate(), mtu)
        phone.hello(r)
        val frames = phone.session!!.send(HealthSync.requestCursor(1, 0, 10)).second
        frames.forEach { r.onWrite(it, 0) }
        assertTrue(r.isClosed)
        assertNull(policy.pinned)
    }
    @Test
    fun ledgerFormatSkipsSamplesTheIphoneWouldRejectButAdvancesTheCursor() {
        val bad = listOf(
            HealthSample(1, "heart_rate_bpm", 1000, 1000, 300.0, "a1"),
            HealthSample(2, "steps_daily_snapshot", 5000, 5000, 10.0, "a2"),
            HealthSample(3, "heart_rate_bpm", 9000, 9000, 72.0, "ok:3"),
            HealthSample(4, "steps_daily_snapshot", 0, 86_400_000, 4321.0, "ok-4"),
        )
        val src = object : HealthSource {
            override fun samplesAfter(cursor: Long, limit: Int) = bad.filter { it.cursor > cursor }
            override fun hasMoreAfter(cursor: Long) = false
            override fun acknowledge(through: Long) = Unit
        }
        val res = HealthSync.respond(HealthSync.requestCursor(1, 0, 100), src, "watch-x")
        val parsed = HealthSync.parseBatch(res)
        assertEquals(listOf(3L, 4L), parsed.map { it.cursor })
        assertEquals(listOf("heartRate", "stepsDaily"), parsed.map { it.metric })
        assertEquals(4L, HealthSync.batchLast(res))
        val hr = ((res["payload"] as Map<*, *>)["samples"] as List<*>)[0] as Map<*, *>
        assertEquals(72.0, hr["min"])
        assertTrue((hr["e"] as Long) > (hr["s"] as Long))
    }

    @Test
    fun findWatchRingsAndFindPhoneIsRequestedByTheWatch() {
        val policy = Policy(open = true)
        val l = Listener()
        val r = newResponder(policy, l)
        val phone = Phone(X25519Key.generate(), mtu)
        phone.hello(r)
        r.confirmCode(true)
        val res = phone.call(r, mapOf("kind" to "req", "requestId" to 5L, "capability" to "find.watch", "payload" to emptyMap<String, Any?>())) as Map<*, *>
        assertEquals(1, l.findWatch)
        assertEquals("requested", (res["payload"] as Map<*, *>)["status"])

        val (id, frames) = r.request("find.phone")
        var got: Any? = null
        for (f in frames) { val x = phone.session!!.receive(f, 0); if (x is SecureSession.Inbound.Message) got = x.json }
        assertEquals("find.phone", (got as Map<*, *>)["capability"])
        val reply = phone.session!!.send(mapOf("kind" to "res", "requestId" to id, "capability" to "find.phone", "payload" to mapOf("status" to "started"))).second
        reply.forEach { r.onWrite(it, 0) }
        assertEquals("find.phone", l.response!!.first)
        assertNull(l.response!!.third)
    }
}
