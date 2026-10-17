package dev.wristbridge.protocol.gatt

import kotlin.test.Test
import kotlin.test.assertEquals

class FakeClock(var now: Long = 1_000_000_000L) : MonotonicClock {
    override fun nowNanos(): Long = now
    fun advanceMs(ms: Long) { now += ms * 1_000_000 }
}

class GattOperationQueueTest {
    private val clock = FakeClock()
    private val q = GattOperationQueue(clock)
    private val log = mutableListOf<String>()

    private fun op(key: String, accept: Boolean = true, timeoutMs: Long = 1000, throws: Boolean = false) =
        GattOperation(
            key = key,
            timeoutNanos = timeoutMs * 1_000_000,
            execute = { log += "exec:$key"; if (throws) throw SecurityException("x"); accept },
            onDone = { log += "done:$key:$it" },
        )

    @Test
    fun operationsRunOneAtATime() {
        q.enqueue(op("a"))
        q.enqueue(op("b"))
        assertEquals(listOf("exec:a"), log)
        q.onCallback("a", 0)
        assertEquals(listOf("exec:a", "done:a:Success", "exec:b"), log)
    }

    @Test
    fun u13_synchronousRejectionReleasesQueue() {
        q.enqueue(op("a", accept = false))
        q.enqueue(op("b", throws = true))
        q.enqueue(op("c"))
        assertEquals(
            listOf("exec:a", "done:a:Rejected(reason=returned-false)", "exec:b", "done:b:Rejected(reason=SecurityException)", "exec:c"),
            log,
        )
        assertEquals("c", q.currentKey)
    }

    @Test
    fun u14_failedCallbackAndMissingCallbackBothRelease() {
        q.enqueue(op("a"))
        q.enqueue(op("b", timeoutMs = 500))
        q.enqueue(op("c"))
        q.onCallback("a", 133)
        clock.advanceMs(499)
        q.onTick()
        assertEquals("b", q.currentKey)
        clock.advanceMs(1)
        q.onTick()
        assertEquals(
            listOf("exec:a", "done:a:Failed(status=133)", "exec:b", "done:b:TimedOut", "exec:c"),
            log,
        )
    }

    @Test
    fun mismatchedOrLateCallbacksAreIgnored() {
        q.enqueue(op("a", timeoutMs = 10))
        clock.advanceMs(10)
        q.onTick()
        q.enqueue(op("b"))
        q.onCallback("a", 0) // late callback of the timed-out op
        assertEquals("b", q.currentKey)
        assertEquals(1, q.ignoredCallbacks)
    }

    @Test
    fun closeCancelsAllAndLaterEnqueues() {
        q.enqueue(op("a"))
        q.enqueue(op("b"))
        q.close()
        q.enqueue(op("c"))
        assertEquals(listOf("exec:a", "done:a:Cancelled", "done:b:Cancelled", "done:c:Cancelled"), log)
    }

    @Test
    fun u20_deadlineUsesOnlyMonotonicClock() {
        // Wall-clock jumps cannot reach the queue: it only reads MonotonicClock.
        q.enqueue(op("a", timeoutMs = 5000))
        clock.advanceMs(4999)
        q.onTick()
        assertEquals("a", q.currentKey)
        assertEquals(clock.now + 1_000_000, q.nextDeadlineNanos())
    }
}
