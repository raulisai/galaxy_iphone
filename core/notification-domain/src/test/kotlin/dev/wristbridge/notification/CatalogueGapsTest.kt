package dev.wristbridge.notification

import dev.wristbridge.protocol.contract.AncsLimits
import dev.wristbridge.protocol.gatt.GattOutcome
import dev.wristbridge.protocol.gatt.MonotonicClock
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GapClock(var now: Long = 5_000_000_000L) : MonotonicClock {
    override fun nowNanos() = now
    fun ms(v: Long) { now += v * 1_000_000 }
}

/** Test-side byte builders written from the ANCS layout (not shared with other test files). */
private fun gapNs(event: Int, uid: Long, flags: Int = 0, category: Int = 4) = byteArrayOf(
    event.toByte(), flags.toByte(), category.toByte(), 1,
    (uid and 0xFF).toByte(), ((uid shr 8) and 0xFF).toByte(), ((uid shr 16) and 0xFF).toByte(), ((uid shr 24) and 0xFF).toByte(),
)

private fun gapNotifResponse(uid: Long, values: Map<Int, String>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0)
    for (s in 0..3) out.write(((uid shr (8 * s)) and 0xFF).toInt())
    for (id in 0..7) {
        val v = values[id].orEmpty().toByteArray(Charsets.UTF_8)
        out.write(id); out.write(v.size and 0xFF); out.write(v.size shr 8); out.write(v)
    }
    return out.toByteArray()
}

private fun gapAppResponse(app: String, name: String): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(1); out.write(app.toByteArray()); out.write(0)
    val v = name.toByteArray()
    out.write(0); out.write(v.size); out.write(0); out.write(v)
    return out.toByteArray()
}

private fun gapAttrs(title: String) = mapOf(0 to "com.example", 1 to title, 3 to "m", 4 to "1")

private fun gapWrites(effects: List<SessionEffect>) = effects.filterIsInstance<SessionEffect.WriteControlPoint>()

/** Acks and answers every outstanding control-point write until the engine is idle; collects all effects. */
private fun gapDrive(
    engine: AncsSessionEngine,
    generation: Long,
    initial: List<SessionEffect>,
    sink: MutableList<SessionEffect>,
) {
    sink += initial
    var pending = gapWrites(initial)
    while (pending.isNotEmpty()) {
        val w = pending.single()
        engine.onControlPointWritten(generation, w.transactionId, GattOutcome.Success)
        val r = if (w.bytes[0].toInt() == 0) {
            var uid = 0L
            for (s in 0..3) uid = uid or ((w.bytes[1 + s].toLong() and 0xFF) shl (8 * s))
            engine.onDataSource(generation, gapNotifResponse(uid, gapAttrs("t$uid")))
        } else {
            engine.onDataSource(generation, gapAppResponse("com.example", "Ejemplo"))
        }
        sink += r
        pending = gapWrites(r)
    }
}

class CatalogueGapsTest {
    private val clock = GapClock()

    private fun newEngine(limits: AncsLimits = AncsLimits()): Pair<AncsSessionEngine, Long> {
        val e = AncsSessionEngine(clock, limits)
        e.startSession("iphone-A")
        return e to e.generation
    }

    // ---- U09: truncated / oversized streams without final byte ----

    @Test
    fun u09_streamWithoutFinalByteTimesOutWithFallbackAndReleasesQueue() {
        val (engine, g) = newEngine()
        val first = gapWrites(engine.onNotificationSource(g, gapNs(0, 1))).single()
        engine.onNotificationSource(g, gapNs(0, 2))
        engine.onControlPointWritten(g, first.transactionId, GattOutcome.Success)
        // Header + attribute 0 announcing 10 bytes but only 3 delivered: never completes.
        val partial = byteArrayOf(0, 1, 0, 0, 0, 0, 10, 0, 97, 98, 99)
        assertTrue(engine.onDataSource(g, partial).isEmpty())
        assertTrue(engine.transactionActive)
        clock.ms(9_999)
        assertTrue(engine.onTick().isEmpty())
        clock.ms(1)
        val t = engine.onTick()
        assertTrue(t.filterIsInstance<SessionEffect.PostCard>().single().card.detailsUnavailable)
        assertEquals(2, gapWrites(t).single().bytes[1].toInt())
        assertEquals(1, engine.counters.fetchTimedOut)
    }

    @Test
    fun u09_hugeAnnouncedLengthIsBoundedByResponseLimitNotByAnnouncedLength() {
        val (engine, g) = newEngine(AncsLimits(maxResponseBytes = 64))
        val w = gapWrites(engine.onNotificationSource(g, gapNs(0, 1))).single()
        engine.onControlPointWritten(g, w.transactionId, GattOutcome.Success)
        // Attribute 0 announces 0xFFFF bytes; deliver them one byte at a time (each chunk is "progress").
        val header = byteArrayOf(0, 1, 0, 0, 0, 0, -1, -1)
        var fed = 0
        var fallback: SessionEffect.PostCard? = null
        val stream = header + ByteArray(200) { 97 }
        for (b in stream) {
            fed++
            val r = engine.onDataSource(g, byteArrayOf(b))
            fallback = r.filterIsInstance<SessionEffect.PostCard>().firstOrNull()
            if (fallback != null) break
        }
        assertEquals(65, fed, "must stop at the byte that exceeds maxResponseBytes")
        assertTrue(fallback!!.card.detailsUnavailable)
        assertFalse(engine.transactionActive)
        assertEquals(1, engine.counters.fetchFailed)
    }

    // ---- N10: notification 1 s after reconnect ----

    @Test
    fun n10_notificationOneSecondAfterReconnectIsProcessedWithoutFixedGrace() {
        val (engine, g1) = newEngine()
        engine.endSession(g1)
        clock.ms(1_000)
        engine.startSession("iphone-A")
        val g2 = engine.generation
        clock.ms(1_000)
        val sink = mutableListOf<SessionEffect>()
        gapDrive(engine, g2, engine.onNotificationSource(g2, gapNs(0, 7)), sink)
        val post = sink.filterIsInstance<SessionEffect.PostCard>().single()
        assertEquals("t7", post.card.title)
        assertTrue(post.alert, "a new (non pre-existing) notification must alert")
        assertEquals(g2, post.card.key.sessionGeneration)
    }

    // ---- N13: 300 events with oracle ----

    @Test
    fun n13_threeHundredCoherentEventsFinalStateMatchesOracle() {
        val (engine, g) = newEngine()
        val sink = mutableListOf<SessionEffect>()
        val oracle = linkedSetOf<Long>()
        for (i in 0 until 300) {
            val bytes = when {
                i < 150 -> { oracle += (i + 1).toLong(); gapNs(0, (i + 1).toLong()) }
                i < 225 -> gapNs(1, (i - 150 + 1).toLong())
                else -> { val uid = (i - 225) * 2L + 1; oracle -= uid; gapNs(2, uid) }
            }
            gapDrive(engine, g, engine.onNotificationSource(g, bytes), sink)
        }
        val shown = linkedSetOf<Long>()
        for (e in sink) when (e) {
            is SessionEffect.PostCard -> shown += e.card.key.uid.value
            is SessionEffect.RemoveCard -> shown -= e.key.uid.value
            else -> Unit
        }
        assertEquals(oracle, shown)
        assertEquals(75, engine.visibleCards)
        val c = engine.counters
        assertEquals(300L, c.nsEvents)
        assertEquals(150L, c.added)
        assertEquals(75L, c.modified)
        assertEquals(75L, c.removed)
        assertEquals(150L, c.cardsPosted)
        assertEquals(75L, c.cardsRemoved)
        assertEquals(c.cardsPosted, sink.count { it is SessionEffect.PostCard }.toLong())
        assertEquals(c.cardsRemoved, sink.count { it is SessionEffect.RemoveCard }.toLong())
        assertEquals(c.cardsUpdated, sink.count { it is SessionEffect.UpdateCard }.toLong())
        assertEquals(0, engine.pendingFetches)
        assertFalse(engine.transactionActive)
        assertEquals(0L, c.queueOverflow)
        assertEquals(0L, c.fetchFailed)
    }

    // ---- N14 (partial) / Q10: bounded queues under flood ----

    @Test
    fun n14_q10_floodOfAddsWithoutResponsesKeepsPendingQueueBounded() {
        val (engine, g) = newEngine()
        val effects = mutableListOf<SessionEffect>()
        for (uid in 1L..1000L) effects += engine.onNotificationSource(g, gapNs(0, uid))
        val max = AncsLimits().maxPendingUids
        assertTrue(engine.pendingFetches <= max)
        assertEquals(1000L - 1 - max, engine.counters.queueOverflow)
        // Only one control-point write is ever outstanding.
        assertEquals(1, gapWrites(effects).size)
    }

    @Test
    fun q10_floodOfInvalidFramesAndUnsolicitedDataIsCountedWithoutGrowth() {
        val (engine, g) = newEngine()
        repeat(5_000) {
            assertTrue(engine.onNotificationSource(g, ByteArray(3)).isEmpty())
            assertTrue(engine.onDataSource(g, gapNotifResponse(1, gapAttrs("x"))).isEmpty())
        }
        assertEquals(5_000L, engine.counters.nsInvalid)
        assertEquals(5_000L, engine.counters.unsolicitedData)
        assertEquals(0, engine.pendingFetches)
        assertFalse(engine.transactionActive)
        // The control path stays useful after the flood.
        val sink = mutableListOf<SessionEffect>()
        gapDrive(engine, g, engine.onNotificationSource(g, gapNs(0, 9)), sink)
        assertEquals("t9", sink.filterIsInstance<SessionEffect.PostCard>().single().card.title)
    }

    @Test
    fun q10_floodOfStaleGenerationInputsIsIgnored() {
        val (engine, g1) = newEngine()
        engine.endSession(g1)
        engine.startSession("iphone-A")
        repeat(2_000) {
            assertTrue(engine.onNotificationSource(g1, gapNs(0, 1)).isEmpty())
        }
        assertEquals(2_000L, engine.counters.staleInputsIgnored)
        assertEquals(0, engine.pendingFetches)
        assertFalse(engine.transactionActive)
    }

    // ---- R11 (engine side): 100 cycles with injected GATT errors ----

    @Test
    fun r11_hundredSessionCyclesWithInjectedGattErrorsLeaveNoResidue() {
        val engine = AncsSessionEngine(clock)
        for (cycle in 0 until 100) {
            engine.startSession("iphone-A")
            val g = engine.generation
            val w = gapWrites(engine.onNotificationSource(g, gapNs(0, cycle + 1L))).single()
            when (cycle % 5) {
                0 -> engine.onControlPointWritten(g, w.transactionId, GattOutcome.Failed(0xA2))
                1 -> engine.onControlPointWritten(g, w.transactionId, GattOutcome.Rejected("returned-false"))
                2 -> engine.onControlPointWritten(g, w.transactionId, GattOutcome.TimedOut)
                3 -> engine.onControlPointWritten(g, w.transactionId, GattOutcome.Cancelled)
                else -> {
                    engine.onControlPointWritten(g, w.transactionId, GattOutcome.Success)
                    clock.ms(10_000)
                    engine.onTick()
                }
            }
            assertFalse(engine.transactionActive, "cycle $cycle")
            assertEquals(0, engine.pendingFetches, "cycle $cycle")
            assertNull(engine.nextDeadlineNanos(), "cycle $cycle")
            engine.endSession(g)
        }
        assertEquals(100L, engine.generation)
        assertEquals(100L, engine.counters.fetchStarted)
        assertEquals(0, engine.visibleCards)
    }

    @Test
    fun r10_afterEndSessionNoFurtherWritesOrTransactionsAreProduced() {
        val (engine, g) = newEngine()
        val w = gapWrites(engine.onNotificationSource(g, gapNs(0, 1))).single()
        engine.endSession(g)
        assertFalse(engine.active)
        assertTrue(engine.onControlPointWritten(g, w.transactionId, GattOutcome.Success).isEmpty())
        assertTrue(engine.onNotificationSource(g, gapNs(0, 2)).isEmpty())
        clock.ms(60_000)
        assertTrue(engine.onTick().isEmpty())
        assertEquals(0L, engine.counters.fetchTimedOut)
    }
}
