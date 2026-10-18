package dev.wristbridge.notification

import dev.wristbridge.protocol.gatt.MonotonicClock

/** One deadline for connecting or retrying. Repeated failures back off without giving up permanently. */
class LinkRecovery(private val clock: MonotonicClock) {
    enum class Due { CONNECT_TIMEOUT, RETRY }
    private var due: Due? = null
    private var deadline: Long? = null
    private var failures = 0

    fun connecting(background: Boolean) {
        due = Due.CONNECT_TIMEOUT
        deadline = clock.nowNanos() + (if (background) 90L else 30L) * 1_000_000_000
    }

    /** Does not postpone an already scheduled retry (e.g. repeated Service Changed). */
    fun failed(): Long {
        if (due == Due.RETRY) return deadline!!
        val seconds = longArrayOf(2, 5, 15, 30, 60)[failures.coerceAtMost(4)]
        failures = (failures + 1).coerceAtMost(5)
        due = Due.RETRY
        return (clock.nowNanos() + seconds * 1_000_000_000).also { deadline = it }
    }

    fun ready() { cancel(); failures = 0 }
    fun cancel() { due = null; deadline = null }
    fun nextDeadlineNanos(): Long? = deadline
    fun takeDue(): Due? {
        val at = deadline ?: return null
        if (clock.nowNanos() - at < 0) return null
        return due.also { cancel() }
    }
}
