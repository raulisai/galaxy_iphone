package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.AncsUid
import dev.wristbridge.protocol.ancs.CategoryId
import dev.wristbridge.protocol.gatt.GattOutcome
import dev.wristbridge.protocol.gatt.MonotonicClock
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class Clock(var now: Long = 5_000_000_000L) : MonotonicClock {
    override fun nowNanos() = now
    fun ms(v: Long) { now += v * 1_000_000 }
}

/** Test-side byte builders written from the ANCS layout, independent of production encoders. */
private fun ns(event: Int, uid: Long, flags: Int = 0, category: Int = 4) = byteArrayOf(
    event.toByte(), flags.toByte(), category.toByte(), 1,
    (uid and 0xFF).toByte(), ((uid shr 8) and 0xFF).toByte(), ((uid shr 16) and 0xFF).toByte(), ((uid shr 24) and 0xFF).toByte(),
)

private fun notifResponse(uid: Long, values: Map<Int, String>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0)
    for (s in 0..3) out.write(((uid shr (8 * s)) and 0xFF).toInt())
    // The production request asks for attributes 0..7 in order.
    for (id in 0..7) {
        val v = values[id].orEmpty().toByteArray(Charsets.UTF_8)
        out.write(id); out.write(v.size and 0xFF); out.write(v.size shr 8); out.write(v)
    }
    return out.toByteArray()
}

private fun appResponse(app: String, name: String): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(1); out.write(app.toByteArray()); out.write(0)
    val v = name.toByteArray()
    out.write(0); out.write(v.size); out.write(0); out.write(v)
    return out.toByteArray()
}

class AncsSessionEngineTest {
    private val clock = Clock()
    private val engine = AncsSessionEngine(clock)
    private var gen = engine.startSession("iphone-A").let { engine.generation }

    private fun writes(effects: List<SessionEffect>) = effects.filterIsInstance<SessionEffect.WriteControlPoint>()

    private fun added(uid: Long, flags: Int = 0) = engine.onNotificationSource(gen, ns(0, uid, flags))

    /** Acknowledges the single outstanding write and returns its transaction id. */
    private fun ack(effects: List<SessionEffect>): Long {
        val w = writes(effects).single()
        engine.onControlPointWritten(gen, w.transactionId, GattOutcome.Success)
        return w.transactionId
    }

    private fun attrs(title: String, msg: String = "", app: String = "com.example") =
        mapOf(0 to app, 1 to title, 3 to msg, 4 to msg.length.toString())

    @Test
    fun n01_shortNotificationProducesCardAfterCompleteResponse() {
        val e = added(1)
        ack(e)
        val done = engine.onDataSource(gen, notifResponse(1, attrs("mvp1042-a · F001", "Aviso sintético breve.")))
        val post = done.filterIsInstance<SessionEffect.PostCard>().single()
        assertEquals("mvp1042-a · F001", post.card.title)
        assertEquals("Aviso sintético breve.", post.card.message)
        assertEquals(FixtureTag("mvp1042-a", "F001"), post.card.fixture)
        assertTrue(post.alert)
        // Next queued work is the app display-name lookup.
        assertEquals(1, writes(done).size)
    }

    @Test
    fun n02_longBodyFragmentedIsAssembledAndTruncationReported() {
        ack(added(2))
        val body = "Texto largo de laboratorio; sin datos personales. ".repeat(20)
        val bytes = notifResponse(2, mapOf(0 to "com.example", 1 to "T", 3 to body, 4 to "2000"))
        var post: SessionEffect.PostCard? = null
        bytes.toList().chunked(17).forEach { c ->
            engine.onDataSource(gen, c.toByteArray()).filterIsInstance<SessionEffect.PostCard>().firstOrNull()?.let { post = it }
        }
        val card = post!!.card
        assertEquals(body, card.message)
        assertTrue(card.truncated)
    }

    @Test
    fun n03_consecutiveNotificationsNeverMixAttributes() {
        val first = added(10)
        engine.onNotificationSource(gen, ns(0, 11))
        ack(first)
        val r1 = engine.onDataSource(gen, notifResponse(10, attrs("A", "cuerpo A", "com.a")))
        assertEquals("cuerpo A", r1.filterIsInstance<SessionEffect.PostCard>().single().card.message)
        // Next transaction is uid 11 (FIFO), before the app lookups.
        val next = writes(r1).single()
        assertEquals(0, next.bytes[0].toInt())
        assertEquals(11, next.bytes[1].toInt())
        engine.onControlPointWritten(gen, next.transactionId, GattOutcome.Success)
        val r2 = engine.onDataSource(gen, notifResponse(11, attrs("B", "cuerpo B", "com.b")))
        val card = r2.filterIsInstance<SessionEffect.PostCard>().single().card
        assertEquals("B", card.title)
        assertEquals("cuerpo B", card.message)
        assertEquals(11, card.key.uid.value)
    }

    @Test
    fun n04_modifiedUpdatesExistingCardWithoutAlert() {
        ack(added(3))
        val first = engine.onDataSource(gen, notifResponse(3, attrs("v1")))
        ack(first) // app lookup write
        engine.onDataSource(gen, appResponse("com.example", "Ejemplo"))
        val mod = engine.onNotificationSource(gen, ns(1, 3))
        ack(mod)
        val upd = engine.onDataSource(gen, notifResponse(3, attrs("v2")))
        val card = upd.filterIsInstance<SessionEffect.UpdateCard>().single().card
        assertEquals("v2", card.title)
        assertEquals("Ejemplo", card.appName)
        assertTrue(upd.none { it is SessionEffect.PostCard })
    }

    @Test
    fun n05_removedRetiresCard() {
        ack(added(4))
        engine.onDataSource(gen, notifResponse(4, attrs("x")))
        val r = engine.onNotificationSource(gen, ns(2, 4))
        val removed = r.filterIsInstance<SessionEffect.RemoveCard>().single()
        assertEquals(4, removed.key.uid.value)
        assertEquals(0, engine.visibleCards)
    }

    @Test
    fun n06_u18_removedDuringFetchDoesNotResurrect() {
        ack(added(5))
        engine.onNotificationSource(gen, ns(2, 5))
        val late = engine.onDataSource(gen, notifResponse(5, attrs("tardía")))
        assertTrue(late.none { it is SessionEffect.PostCard || it is SessionEffect.UpdateCard })
        assertEquals(1, engine.counters.discardedResponses)
        assertEquals(0, engine.visibleCards)
    }

    @Test
    fun n07_u19_reusedUidInNewSessionIsSeparateAndOldCallbacksIgnored() {
        ack(added(6))
        val p1 = engine.onDataSource(gen, notifResponse(6, attrs("old"))).filterIsInstance<SessionEffect.PostCard>().single()
        val oldGen = gen
        engine.endSession(oldGen)
        val start = engine.startSession("iphone-A")
        gen = engine.generation
        assertEquals(SessionEffect.RemoveCardsBefore(gen), start.single())
        // Late callbacks from the previous generation are ignored.
        assertTrue(engine.onNotificationSource(oldGen, ns(0, 99)).isEmpty())
        assertTrue(engine.onDataSource(oldGen, notifResponse(6, attrs("ghost"))).isEmpty())
        assertTrue(engine.onControlPointWritten(oldGen, 1, GattOutcome.Success).isEmpty())
        assertEquals(3, engine.counters.staleInputsIgnored)
        // Same UID in the new session yields a new key.
        val e = engine.onNotificationSource(gen, ns(0, 6, flags = 0x04))
        ack(e)
        val p2 = engine.onDataSource(gen, notifResponse(6, attrs("new"))).filterIsInstance<SessionEffect.PostCard>().single()
        assertTrue(p1.card.key != p2.card.key)
        assertFalse(p2.alert, "pre-existing backlog is rebuilt silently (N09)")
    }

    @Test
    fun n08_unknownAppAndEmptyNameHaveReadableFallback() {
        ack(added(7))
        val r = engine.onDataSource(gen, notifResponse(7, mapOf(0 to "", 1 to "", 3 to "")))
        val card = r.filterIsInstance<SessionEffect.PostCard>().single().card
        assertEquals("Social", card.sourceLabel)
        val withId = card.copy(appIdentifier = "com.vendor.chat", appName = "")
        assertEquals("chat", withId.sourceLabel)
    }

    @Test
    fun u15_writeAckDoesNotStartNextTransactionBeforeResponse() {
        val e = added(20)
        engine.onNotificationSource(gen, ns(0, 21))
        ack(e)
        clock.ms(2000)
        assertTrue(engine.onTick().isEmpty())
        assertTrue(engine.transactionActive)
        val r = engine.onDataSource(gen, notifResponse(20, attrs("a")))
        assertEquals(21, writes(r).single().bytes[1].toInt())
    }

    @Test
    fun u14_timeoutPostsFallbackAndReleasesQueue() {
        val e = added(30)
        engine.onNotificationSource(gen, ns(0, 31))
        ack(e)
        clock.ms(10_000)
        val t = engine.onTick()
        val fb = t.filterIsInstance<SessionEffect.PostCard>().single().card
        assertTrue(fb.detailsUnavailable)
        assertEquals(31, writes(t).single().bytes[1].toInt())
        assertEquals(1, engine.counters.fetchTimedOut)
    }

    @Test
    fun u11_ancsErrorOnWriteYieldsTypedCountAndNoFakeCompleteCard() {
        val w = writes(added(40)).single()
        val r = engine.onControlPointWritten(gen, w.transactionId, GattOutcome.Failed(0xA2))
        val card = r.filterIsInstance<SessionEffect.PostCard>().single().card
        assertTrue(card.detailsUnavailable)
        assertEquals(1, engine.counters.ancsErrors)
    }

    @Test
    fun u10_lateBytesFromExpiredTransactionAreDiscarded() {
        ack(added(50))
        engine.onNotificationSource(gen, ns(0, 51))
        clock.ms(10_000)
        val t = engine.onTick()
        engine.onControlPointWritten(gen, writes(t).single().transactionId, GattOutcome.Success)
        // Response for 50 arrives late while 51 is active.
        assertTrue(engine.onDataSource(gen, notifResponse(50, attrs("late"))).none { it is SessionEffect.PostCard })
        val ok = engine.onDataSource(gen, notifResponse(51, attrs("ok")))
        assertEquals("ok", ok.filterIsInstance<SessionEffect.PostCard>().single().card.title)
    }

    @Test
    fun u16_burstOf100UidsCorrelatesAndRespectsLimit() {
        val small = AncsSessionEngine(clock, dev.wristbridge.protocol.contract.AncsLimits(maxPendingUids = 64))
        small.startSession("iphone-A")
        val g = small.generation
        val effects = mutableListOf<SessionEffect>()
        for (uid in 1L..100L) effects += small.onNotificationSource(g, ns(0, uid))
        // One in flight + 64 queued; the rest are shown as fallback instead of being lost silently.
        assertEquals(35, small.counters.queueOverflow)
        assertEquals(35, effects.filterIsInstance<SessionEffect.PostCard>().count { it.card.detailsUnavailable })
        var pending = writes(effects)
        val titles = mutableSetOf<Long>()
        while (pending.isNotEmpty()) {
            val w = pending.single()
            small.onControlPointWritten(g, w.transactionId, GattOutcome.Success)
            val uid = (w.bytes[1].toLong() and 0xFF) or ((w.bytes[2].toLong() and 0xFF) shl 8)
            val r = if (w.bytes[0].toInt() == 0) {
                small.onDataSource(g, notifResponse(uid, attrs("t$uid", app = "com.example")))
            } else {
                small.onDataSource(g, appResponse("com.example", "Ejemplo"))
            }
            r.filterIsInstance<SessionEffect.PostCard>().forEach {
                assertEquals("t${it.card.key.uid.value}", it.card.title)
                titles += it.card.key.uid.value
            }
            pending = writes(r)
        }
        assertEquals(65, titles.size)
    }

    @Test
    fun u17_modifiedTwiceDuringFetchCoalescesToOneRefetch() {
        ack(added(60))
        engine.onNotificationSource(gen, ns(1, 60))
        engine.onNotificationSource(gen, ns(1, 60))
        val r1 = engine.onDataSource(gen, notifResponse(60, attrs("v1", app = "")))
        assertEquals("v1", r1.filterIsInstance<SessionEffect.PostCard>().single().card.title)
        val refetch = writes(r1).single()
        engine.onControlPointWritten(gen, refetch.transactionId, GattOutcome.Success)
        val r2 = engine.onDataSource(gen, notifResponse(60, attrs("v3", app = "")))
        assertEquals("v3", r2.filterIsInstance<SessionEffect.UpdateCard>().single().card.title)
        assertTrue(writes(r2).isEmpty())
        assertEquals(2, engine.counters.fetchStarted)
    }

    @Test
    fun u20_deadlinesFollowMonotonicClockOnly() {
        ack(added(70))
        val deadline = engine.nextDeadlineNanos()!!
        clock.now = deadline - 1
        assertTrue(engine.onTick().isEmpty())
        clock.now = deadline
        assertTrue(engine.onTick().isNotEmpty())
    }

    @Test
    fun lateWriteAckOfFinishedTransactionDoesNotAdvanceNextOne() {
        val first = writes(added(80)).single()
        engine.onNotificationSource(gen, ns(0, 81))
        // Data completes before the write callback is delivered.
        val r = engine.onDataSource(gen, notifResponse(80, attrs("a", app = "")))
        val second = writes(r).single()
        engine.onControlPointWritten(gen, first.transactionId, GattOutcome.Success)
        clock.ms(5_000)
        // The stale ack was ignored: the second fetch is still WRITING, so its 5 s write deadline
        // expires (an accepted ack would have moved it to the 10 s data deadline).
        val t = engine.onTick()
        assertTrue(assertIs<SessionEffect.PostCard>(t.single { it is SessionEffect.PostCard }).card.detailsUnavailable)
        assertEquals(81, second.bytes[1].toInt())
    }

    @Test
    fun invalidNotificationSourceIsCountedNotFatal() {
        assertTrue(engine.onNotificationSource(gen, ByteArray(3)).isEmpty())
        assertEquals(1, engine.counters.nsInvalid)
    }

    @Test
    fun cardKeyUsesUnsignedUidAndCategoryFallback() {
        ack(engine.onNotificationSource(gen, ns(0, 0xFFFF_FFFFL, category = CategoryId.EMAIL.raw)))
        val r = engine.onDataSource(gen, notifResponse(0xFFFF_FFFFL, mapOf(1 to "x")))
        val card = r.filterIsInstance<SessionEffect.PostCard>().single().card
        assertEquals(AncsUid(0xFFFF_FFFFL), card.key.uid)
        assertEquals("Correo", card.sourceLabel)
    }
}
