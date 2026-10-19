package dev.wristbridge.assistant.voice

/** Injected monotonic clock (milliseconds) so metrics are deterministic in tests. */
fun interface VoiceClock {
    fun nowMs(): Long
}

/** One PCM chunk tagged with the session that produced it (used to drop stale callbacks, V07). */
class PcmChunk(val sessionId: Long, val bytes: ByteArray, val durationMs: Int) {
    init {
        require(bytes.isNotEmpty()) { "empty chunk" }
        require(durationMs > 0) { "durationMs must be positive" }
    }

    val size: Int get() = bytes.size
}

/** What the queue does when a new chunk does not fit. Always explicit, never implicit. */
enum class OverflowPolicy {
    /** Evict the oldest chunks until the new one fits (keeps latency bounded; playback default). */
    DROP_OLDEST,

    /** Refuse the new chunk and keep the queued ones (caller applies backpressure; capture default). */
    REJECT,
}

sealed interface OfferResult {
    data object Accepted : OfferResult
    data class AcceptedAfterDropping(val droppedChunks: Int, val droppedBytes: Long) : OfferResult
    data object Rejected : OfferResult

    /** The single chunk exceeds the whole queue capacity; it can never be stored. */
    data object TooLarge : OfferResult

    /** Queue closed, session terminated, wrong state, paused, or stale session id. */
    data object Closed : OfferResult
}

data class QueueCounters(
    val droppedChunks: Long,
    val droppedBytes: Long,
    /** Number of offers that found the queue full (whatever the policy did next). */
    val overruns: Long,
    val rejectedChunks: Long,
)

/**
 * Fixed-capacity FIFO of PCM chunks bounded by bytes AND duration. Never grows past the limits
 * ("no growing voice queue", P5-04). Thread-safe through a single monitor.
 */
class BoundedAudioQueue(
    val maxBytes: Int,
    val maxDurationMs: Int,
    val policy: OverflowPolicy,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxDurationMs > 0) { "maxDurationMs must be positive" }
    }

    private val items = ArrayDeque<PcmChunk>()
    private var bytes = 0L
    private var durationMs = 0L
    private var closed = false
    private var droppedChunks = 0L
    private var droppedBytes = 0L
    private var overruns = 0L
    private var rejectedChunks = 0L

    val isClosed: Boolean @Synchronized get() = closed
    val size: Int @Synchronized get() = items.size
    val queuedBytes: Long @Synchronized get() = bytes
    val queuedDurationMs: Long @Synchronized get() = durationMs

    @Synchronized
    fun offer(chunk: PcmChunk): OfferResult {
        if (closed) return OfferResult.Closed
        if (chunk.size > maxBytes || chunk.durationMs > maxDurationMs) {
            overruns++
            rejectedChunks++
            return OfferResult.TooLarge
        }
        if (fits(chunk)) {
            push(chunk)
            return OfferResult.Accepted
        }
        overruns++
        if (policy == OverflowPolicy.REJECT) {
            rejectedChunks++
            return OfferResult.Rejected
        }
        var dc = 0
        var db = 0L
        while (!fits(chunk) && items.isNotEmpty()) {
            val old = items.removeFirst()
            bytes -= old.size
            durationMs -= old.durationMs
            dc++
            db += old.size
        }
        droppedChunks += dc
        droppedBytes += db
        push(chunk)
        return OfferResult.AcceptedAfterDropping(dc, db)
    }

    @Synchronized
    fun poll(): PcmChunk? {
        val c = items.removeFirstOrNull() ?: return null
        bytes -= c.size
        durationMs -= c.durationMs
        return c
    }

    /** Empties the queue (e.g. barge-in). Discarded audio is counted as dropped. */
    @Synchronized
    fun clear() {
        droppedChunks += items.size
        droppedBytes += bytes
        items.clear()
        bytes = 0
        durationMs = 0
    }

    /** Clears and refuses all further offers; irreversible. */
    @Synchronized
    fun close() {
        clear()
        closed = true
    }

    @Synchronized
    fun counters(): QueueCounters = QueueCounters(droppedChunks, droppedBytes, overruns, rejectedChunks)

    private fun fits(c: PcmChunk) = bytes + c.size <= maxBytes && durationMs + c.durationMs <= maxDurationMs

    private fun push(c: PcmChunk) {
        items.addLast(c)
        bytes += c.size
        durationMs += c.durationMs
    }
}
