package dev.wristbridge.protocol.gatt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GapGattClock(var now: Long = 1_000_000_000L) : MonotonicClock {
    override fun nowNanos(): Long = now
    fun ms(v: Long) { now += v * 1_000_000 }
}

class GattCatalogueGapsTest {
    private val clock = GapGattClock()
    private val q = GattOperationQueue(clock)
    private var done = 0
    private var executed = 0
    private val outcomes = mutableListOf<GattOutcome>()

    private fun op(key: String, accept: Boolean = true, throws: Boolean = false, timeoutMs: Long = 1000) =
        GattOperation(
            key = key,
            timeoutNanos = timeoutMs * 1_000_000,
            execute = { executed++; if (throws) throw IllegalStateException("x"); accept },
            onDone = { done++; outcomes += it },
        )

    @Test
    fun r11_hundredCyclesWithInjectedGattErrorsReleaseQueueEveryTime() {
        repeat(100) { cycle ->
            q.enqueue(op("a$cycle", accept = false))
            q.enqueue(op("b$cycle", throws = true))
            q.enqueue(op("c$cycle"))
            q.enqueue(op("d$cycle", timeoutMs = 500))
            q.enqueue(op("e$cycle"))
            q.onCallback("c$cycle", 133)
            clock.ms(500)
            q.onTick()
            q.onCallback("e$cycle", 0)
            // A duplicate callback after completion must be ignored, never complete another operation.
            q.onCallback("e$cycle", 0)
            assertEquals(0, q.size, "cycle $cycle")
            assertNull(q.currentKey)
            assertNull(q.nextDeadlineNanos())
        }
        assertEquals(500, done)
        assertEquals(500, executed)
        assertEquals(100, q.ignoredCallbacks)
        assertEquals(100, outcomes.count { it is GattOutcome.TimedOut })
        assertEquals(200, outcomes.count { it is GattOutcome.Rejected })
        assertEquals(100, outcomes.count { it is GattOutcome.Failed })
        assertEquals(100, outcomes.count { it is GattOutcome.Success })
    }

    @Test
    fun r10_closeCancelsEverythingOnceAndNeverExecutesAgain() {
        q.enqueue(op("a"))
        q.enqueue(op("b"))
        q.enqueue(op("c"))
        assertEquals(1, executed)
        q.close()
        assertEquals(3, done)
        assertTrue(outcomes.all { it is GattOutcome.Cancelled })
        q.enqueue(op("d"))
        clock.ms(60_000)
        q.onTick()
        q.onCallback("a", 0)
        assertEquals(1, executed, "no operation may run after close")
        assertEquals(4, done)
        assertEquals(0, q.size)
        assertNull(q.nextDeadlineNanos())
    }
}
