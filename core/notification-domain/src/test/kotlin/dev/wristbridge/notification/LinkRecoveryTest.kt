package dev.wristbridge.notification

import dev.wristbridge.protocol.gatt.MonotonicClock
import kotlin.test.*

class LinkRecoveryTest {
    private var now = 0L
    private val recovery = LinkRecovery(MonotonicClock { now })
    private fun seconds(n: Long) { now += n * 1_000_000_000 }

    @Test fun directAndBackgroundConnectCannotWaitForever() {
        recovery.connecting(false)
        seconds(29); assertNull(recovery.takeDue())
        seconds(1); assertEquals(LinkRecovery.Due.CONNECT_TIMEOUT, recovery.takeDue())
        assertNull(recovery.takeDue())
        recovery.connecting(true)
        seconds(89); assertNull(recovery.takeDue())
        seconds(1); assertEquals(LinkRecovery.Due.CONNECT_TIMEOUT, recovery.takeDue())
    }

    @Test fun retriesContinueAfterThreeFailuresWithCappedBackoff() {
        for (delay in listOf(2L, 5L, 15L, 30L, 60L, 60L, 60L)) {
            assertEquals(now + delay * 1_000_000_000, recovery.failed())
            seconds(delay)
            assertEquals(LinkRecovery.Due.RETRY, recovery.takeDue())
            recovery.connecting(true)
        }
    }

    @Test fun repeatedFailuresDoNotPostponeRetryAndReadyResetsBudget() {
        val first = recovery.failed()
        seconds(1); assertEquals(first, recovery.failed())
        recovery.ready()
        assertNull(recovery.nextDeadlineNanos())
        assertEquals(now + 2_000_000_000, recovery.failed())
    }

    @Test fun stopCancelsPendingConnectAndRetry() {
        recovery.connecting(true); recovery.cancel()
        seconds(100); assertNull(recovery.takeDue())
        recovery.failed(); recovery.cancel()
        seconds(100); assertNull(recovery.takeDue())
    }
}
