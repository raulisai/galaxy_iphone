package dev.wristbridge.assistant.voice

enum class VoiceState { Idle, Listening, Thinking, Speaking, Cancelled, Failed }

enum class FocusEvent {
    /** Permanent loss: stop and release everything. */
    Loss,

    /** Transient loss (call, alarm): pause deterministically, resume on Gain. */
    LossTransient,

    /** Duck request: no state change (volume handled by the platform layer). */
    LossTransientCanDuck,

    Gain,
}

data class VoiceLimits(
    val captureMaxBytes: Int = 64_000,
    val captureMaxDurationMs: Int = 2_000,
    val capturePolicy: OverflowPolicy = OverflowPolicy.REJECT,
    val playbackMaxBytes: Int = 96_000,
    val playbackMaxDurationMs: Int = 3_000,
    val playbackPolicy: OverflowPolicy = OverflowPolicy.DROP_OLDEST,
)

data class VoiceMetrics(
    val state: VoiceState,
    /** Time from end of user speech to first model audio byte; null until it happens. */
    val timeToFirstByteMs: Long?,
    val interruptions: Int,
    val droppedChunks: Long,
    val droppedBytes: Long,
    val overruns: Long,
    val staleCallbacks: Int,
    val focusLosses: Int,
    val micOpen: Boolean,
    val paused: Boolean,
    val failureReason: String?,
)

/**
 * Single-use voice session. Terminal states (Cancelled, Failed, or Idle after [complete]) are final:
 * the microphone/session resources are released exactly once and nothing re-opens them, not even
 * when the network comes back ([onNetworkAvailable] is deliberately a no-op). A new capture needs a
 * new session created by an explicit user action.
 *
 * [release] frees mic + transport; it is invoked exactly once. Drive the session from a single
 * dispatcher (not thread-safe).
 */
class VoiceSession(
    val id: Long,
    private val clock: VoiceClock,
    private val limits: VoiceLimits = VoiceLimits(),
    private val release: () -> Unit,
) {
    private var capture = newCapture()
    private var retiredDropped = 0L
    private var retiredDroppedBytes = 0L
    private var retiredOverruns = 0L
    private val playback = BoundedAudioQueue(limits.playbackMaxBytes, limits.playbackMaxDurationMs, limits.playbackPolicy)

    var state: VoiceState = VoiceState.Idle
        private set
    var micOpen: Boolean = false
        private set
    var paused: Boolean = false
        private set
    var closed: Boolean = false
        private set

    private var thinkingStartedMs: Long? = null
    private var ttfbMs: Long? = null
    private var interruptions = 0
    private var stale = 0
    private var focusLosses = 0
    private var failure: String? = null

    /** Idle -> Listening. Returns false on a used/closed session. */
    fun startListening(): Boolean {
        if (closed || state != VoiceState.Idle) return false
        state = VoiceState.Listening
        micOpen = true
        return true
    }

    /** Microphone callback. Ignored unless actively listening and not paused. */
    fun onCaptured(chunk: PcmChunk): OfferResult {
        if (chunk.sessionId != id) {
            stale++
            return OfferResult.Closed
        }
        if (state != VoiceState.Listening || paused) return OfferResult.Closed
        return capture.offer(chunk)
    }

    fun pollCaptured(): PcmChunk? = if (state == VoiceState.Listening && !paused) capture.poll() else null

    /** Listening -> Thinking; the mic is released here. */
    fun onEndOfUserSpeech(): Boolean {
        if (state != VoiceState.Listening) return false
        retireCapture()
        micOpen = false
        thinkingStartedMs = clock.nowMs()
        state = VoiceState.Thinking
        return true
    }

    /** Model audio callback. Chunks of another session or arriving after termination are dropped (V07). */
    fun onModelAudio(chunk: PcmChunk): OfferResult {
        if (closed || chunk.sessionId != id) {
            stale++
            return OfferResult.Closed
        }
        if (state != VoiceState.Thinking && state != VoiceState.Speaking) {
            stale++
            return OfferResult.Closed
        }
        val now = clock.nowMs()
        if (ttfbMs == null) ttfbMs = now - (thinkingStartedMs ?: now)
        state = VoiceState.Speaking
        return playback.offer(chunk)
    }

    /** Playback side pulls the next chunk; null while paused or empty. */
    fun pollPlayback(): PcmChunk? = if (state == VoiceState.Speaking && !paused) playback.poll() else null

    /** The model finished and playback drained: terminal Idle, resources released. */
    fun complete(): Boolean {
        if (closed || state != VoiceState.Speaking) return false
        state = VoiceState.Idle
        terminate()
        return true
    }

    /**
     * User barge-in while the model speaks (V05): old audio is flushed immediately and never
     * reappears; back to Listening (explicit user action, so the mic is re-armed).
     */
    fun interrupt(): Boolean {
        if (closed || state != VoiceState.Speaking) return false
        playback.clear()
        capture = newCapture() // the previous capture queue was closed at end of speech
        interruptions++
        paused = false
        micOpen = true
        thinkingStartedMs = null
        state = VoiceState.Listening
        return true
    }

    fun onFocus(event: FocusEvent) {
        if (closed) return
        when (event) {
            FocusEvent.Loss -> {
                focusLosses++
                cancel("focus_loss")
            }
            FocusEvent.LossTransient -> {
                focusLosses++
                if (state != VoiceState.Idle) paused = true
            }
            FocusEvent.LossTransientCanDuck -> Unit
            FocusEvent.Gain -> paused = false
        }
    }

    /** Network connectivity is intentionally irrelevant: never re-opens a closed session. */
    fun onNetworkAvailable() = Unit

    fun cancel(reason: String = "cancelled") {
        if (closed) return
        state = VoiceState.Cancelled
        failure = reason
        terminate()
    }

    fun fail(reason: String) {
        if (closed) return
        state = VoiceState.Failed
        failure = reason
        terminate()
    }

    fun metrics(): VoiceMetrics {
        val a = capture.counters()
        val b = playback.counters()
        return VoiceMetrics(
            state = state,
            timeToFirstByteMs = ttfbMs,
            interruptions = interruptions,
            droppedChunks = a.droppedChunks + retiredDropped + b.droppedChunks,
            droppedBytes = a.droppedBytes + retiredDroppedBytes + b.droppedBytes,
            overruns = a.overruns + retiredOverruns + b.overruns,
            staleCallbacks = stale,
            focusLosses = focusLosses,
            micOpen = micOpen,
            paused = paused,
            failureReason = failure,
        )
    }

    private fun newCapture() =
        BoundedAudioQueue(limits.captureMaxBytes, limits.captureMaxDurationMs, limits.capturePolicy)

    /** Closes the capture queue, folding its counters into the retired totals for metrics. */
    private fun retireCapture() {
        capture.close()
        val c = capture.counters()
        retiredDropped += c.droppedChunks
        retiredDroppedBytes += c.droppedBytes
        retiredOverruns += c.overruns
        capture = newCapture().also { it.close() }
    }

    private fun terminate() {
        closed = true
        micOpen = false
        paused = false
        retireCapture()
        playback.close()
        release()
    }
}

/** Owns the current session; starting a new one cancels the previous immediately (V07). */
class VoiceSessionManager(
    private val clock: VoiceClock,
    private val limits: VoiceLimits = VoiceLimits(),
    private val releaseFor: (sessionId: Long) -> Unit = {},
) {
    private var nextId = 1L
    var current: VoiceSession? = null
        private set
    var staleRouted: Int = 0
        private set

    /** Must be called from an explicit user action. */
    fun begin(): VoiceSession {
        current?.cancel("superseded")
        val id = nextId++
        val s = VoiceSession(id, clock, limits) { releaseFor(id) }
        current = s
        return s
    }

    /** Routes a model audio callback to the live session; stale session ids are ignored. */
    fun deliverModelAudio(chunk: PcmChunk): OfferResult {
        val s = current
        if (s == null || s.id != chunk.sessionId) {
            staleRouted++
            return OfferResult.Closed
        }
        return s.onModelAudio(chunk)
    }
}
