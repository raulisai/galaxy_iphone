package dev.wristbridge.notification

import dev.wristbridge.protocol.gatt.MonotonicClock

/** Bounded pairing wait owned by the BLE thread, independent of notification fetch deadlines. */
class BondWait(
    private val clock: MonotonicClock,
    private val timeoutNanos: Long = 60_000_000_000L,
) {
    init { require(timeoutNanos > 0) }
    private var deadline: Long? = null
    val waiting: Boolean get() = deadline != null

    /** Repeated subscription failures must not extend an existing pairing prompt forever. */
    fun begin() {
        if (deadline == null) deadline = clock.nowNanos() + timeoutNanos
    }

    fun finish(): Boolean {
        val wasWaiting = waiting
        deadline = null
        return wasWaiting
    }

    fun expire(): Boolean {
        val due = deadline ?: return false
        if (clock.nowNanos() - due < 0) return false
        deadline = null
        return true
    }

    fun nextDeadlineNanos(): Long? = deadline
}
