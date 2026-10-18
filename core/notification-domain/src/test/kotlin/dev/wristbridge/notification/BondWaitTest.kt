package dev.wristbridge.notification

import dev.wristbridge.protocol.gatt.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class BondWaitTest {
    private var now = 1_000L
    private val wait = BondWait(MonotonicClock { now }, timeoutNanos = 100)

    @Test fun repeatedFailuresDoNotExtendPairingDeadline() {
        wait.begin()
        now += 99
        wait.begin()
        assertEquals(1_100L, wait.nextDeadlineNanos())
        assertFalse(wait.expire())
        now++
        assertTrue(wait.expire())
        assertFalse(wait.waiting)
        assertFalse(wait.expire())
    }

    @Test fun completedOrCancelledPairingCannotExpireLater() {
        wait.begin()
        assertTrue(wait.finish())
        now += 1_000
        assertFalse(wait.expire())
        assertFalse(wait.finish())
        assertEquals(null, wait.nextDeadlineNanos())
    }

    @Test fun newAttemptGetsItsOwnDeadline() {
        wait.begin()
        wait.finish()
        now += 1_000
        wait.begin()
        assertEquals(2_100L, wait.nextDeadlineNanos())
        assertTrue(wait.waiting)
    }

    @Test fun invalidBudgetIsRejected() {
        assertFailsWith<IllegalArgumentException> { BondWait(MonotonicClock { now }, 0) }
        assertFailsWith<IllegalArgumentException> { BondWait(MonotonicClock { now }, -1) }
    }
}
