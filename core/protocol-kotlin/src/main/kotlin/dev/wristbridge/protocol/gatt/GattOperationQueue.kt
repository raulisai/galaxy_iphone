package dev.wristbridge.protocol.gatt

/** Monotonic nanoseconds (Android: SystemClock.elapsedRealtimeNanos). Never wall-clock time (U20). */
fun interface MonotonicClock {
    fun nowNanos(): Long
}

sealed interface GattOutcome {
    data object Success : GattOutcome
    data class Failed(val status: Int) : GattOutcome

    /** The stack refused the call synchronously (returned false or threw) (U13). */
    data class Rejected(val reason: String) : GattOutcome

    /** No callback before the deadline (U14). */
    data object TimedOut : GattOutcome
    data object Cancelled : GattOutcome
}

/**
 * A GATT operation. [key] identifies the callback that completes it (for example
 * "write:<characteristic>" or "desc:<characteristic>:<descriptor>").
 */
class GattOperation(
    val key: String,
    val timeoutNanos: Long,
    val execute: () -> Boolean,
    val onDone: (GattOutcome) -> Unit,
)

/**
 * Serial owner of GATT operations for one connection generation: Android allows a single outstanding
 * operation. Not thread-safe; call from one thread. A synchronous rejection or a missing callback
 * never leaves the queue blocked.
 */
class GattOperationQueue(private val clock: MonotonicClock) {
    private val pending = ArrayDeque<GattOperation>()
    private var current: GattOperation? = null
    private var deadline: Long = 0
    private var closed = false

    var ignoredCallbacks: Int = 0
        private set

    val size: Int get() = pending.size + if (current != null) 1 else 0

    val currentKey: String? get() = current?.key

    fun enqueue(op: GattOperation) {
        if (closed) {
            op.onDone(GattOutcome.Cancelled)
            return
        }
        pending.addLast(op)
        pump()
    }

    /** Completes the current operation if [key] matches; otherwise the callback is counted and ignored. */
    fun onCallback(key: String, status: Int) {
        val op = current
        if (op == null || op.key != key) {
            ignoredCallbacks++
            return
        }
        finish(if (status == 0) GattOutcome.Success else GattOutcome.Failed(status))
    }

    /** Checks the current deadline. Call whenever [nextDeadlineNanos] elapses. */
    fun onTick() {
        if (current != null && clock.nowNanos() - deadline >= 0) finish(GattOutcome.TimedOut)
    }

    fun nextDeadlineNanos(): Long? = if (current != null) deadline else null

    /** Cancels everything; later enqueues are cancelled immediately. */
    fun close() {
        closed = true
        val all = listOfNotNull(current) + pending
        current = null
        pending.clear()
        all.forEach { it.onDone(GattOutcome.Cancelled) }
    }

    private fun finish(outcome: GattOutcome) {
        val op = current ?: return
        current = null
        op.onDone(outcome)
        pump()
    }

    private fun pump() {
        while (current == null && !closed) {
            val op = pending.removeFirstOrNull() ?: return
            current = op
            deadline = clock.nowNanos() + op.timeoutNanos
            val accepted = try {
                op.execute()
            } catch (e: RuntimeException) {
                current = null
                op.onDone(GattOutcome.Rejected(e.javaClass.simpleName))
                continue
            }
            if (!accepted) {
                current = null
                op.onDone(GattOutcome.Rejected("returned-false"))
            }
        }
    }
}
