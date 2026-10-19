package dev.wristbridge.assistant

enum class ConversationState { IDLE, SENDING, STREAMING, COMPLETED, FAILED, CANCELLED }

sealed interface SubmitResult {
    data class Accepted(val requestId: Long) : SubmitResult

    /** A request is already in flight: the second submit is REJECTED (not coalesced, not queued). */
    data object RejectedBusy : SubmitResult
    data object RejectedEmpty : SubmitResult
    data object RejectedTooLong : SubmitResult
}

/**
 * Single-conversation state machine: IDLE -> SENDING -> STREAMING -> COMPLETED | FAILED | CANCELLED.
 * COMPLETED/FAILED/CANCELLED accept a new submit. Single-threaded: the caller serialises all calls
 * (including provider callbacks and [tick]).
 *
 * Rules:
 *  - Double submit while SENDING/STREAMING is rejected ([SubmitResult.RejectedBusy]); one logical request.
 *  - Every provider attempt gets a fresh token; callbacks from an old token (cancelled, retried,
 *    cleared, or from an older request) are ignored, so stale responses never mutate newer state.
 *  - cancel() is valid in SENDING (before headers, also while waiting for a retry) and STREAMING.
 *  - On any end other than COMPLETED the pending user message leaves the history and becomes [draft];
 *    streamed text is kept apart as [partialText] and never enters the history.
 *  - Only RETRYABLE errors received before any delta are retried, with [retryPolicy] backoff driven by
 *    [tick]; nothing is retried after cancel/reset.
 */
class Conversation(
    private val provider: AssistantProvider,
    private val clock: AssistantClock,
    maxHistoryMessages: Int = 20,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val maxInputChars: Int = 4_000,
) {
    private val maxHistory = maxHistoryMessages.coerceAtLeast(2)
    private val history = ArrayList<ChatMessage>()

    var state: ConversationState = ConversationState.IDLE
        private set
    var lastError: AssistantError? = null
        private set

    /** Text of the answer being streamed (or the partial answer kept after a failure/cancel). */
    var partialText: String = ""
        private set

    /** User text of the last request that did not complete, so the UI can restore it. */
    var draft: String? = null
        private set

    /** Time at which the pending retry fires, or null. */
    var nextRetryAtMillis: Long? = null
        private set

    val messages: List<ChatMessage> get() = history.toList()
    val isBusy: Boolean get() = state == ConversationState.SENDING || state == ConversationState.STREAMING

    private var requestSeq = 0L
    private var activeRequestId = 0L
    private var activeToken = 0L
    private var handle: AssistantHandle? = null
    private var pendingUser: ChatMessage? = null
    private var retriesUsed = 0
    private var gotDelta = false

    fun submit(text: String): SubmitResult {
        if (isBusy) return SubmitResult.RejectedBusy
        val t = text.trim()
        if (t.isEmpty()) return SubmitResult.RejectedEmpty
        if (t.length > maxInputChars) return SubmitResult.RejectedTooLong
        val user = ChatMessage(Role.USER, t)
        history.add(user)
        while (history.size > maxHistory) history.removeAt(0)
        pendingUser = user
        draft = null
        lastError = null
        partialText = ""
        gotDelta = false
        retriesUsed = 0
        nextRetryAtMillis = null
        state = ConversationState.SENDING
        activeRequestId = ++requestSeq
        startAttempt()
        return SubmitResult.Accepted(activeRequestId)
    }

    /** Cancels the in-flight request. Returns false when there is nothing to cancel. */
    fun cancel(): Boolean {
        if (!isBusy) return false
        val h = handle
        end(ConversationState.CANCELLED, AssistantError(AssistantErrorKind.CANCELLED, "Cancelado."))
        h?.let { runCatching { it.cancel() } }
        return true
    }

    /** Drops history and any in-flight request (e.g. account/provider change). Late events are ignored. */
    fun reset() {
        val h = handle
        activeToken++
        handle = null
        history.clear()
        pendingUser = null
        draft = null
        lastError = null
        partialText = ""
        nextRetryAtMillis = null
        state = ConversationState.IDLE
        h?.let { runCatching { it.cancel() } }
    }

    /** Starts a scheduled retry when due. Cheap; call from the UI/timer loop. */
    fun tick() {
        val due = nextRetryAtMillis ?: return
        if (state != ConversationState.SENDING || clock.nowMillis() < due) return
        nextRetryAtMillis = null
        startAttempt()
    }

    private fun startAttempt() {
        val token = ++activeToken
        val request = AssistantRequest(activeRequestId, history.toList())
        val h = try {
            provider.start(request, Sink(token))
        } catch (_: Exception) {
            if (token == activeToken) {
                end(ConversationState.FAILED, ErrorMapping.map(TransportFailure.Http(0)))
            }
            return
        }
        // If the request was cancelled or finished during start(), the token moved on.
        if (token == activeToken) handle = h else runCatching { h.cancel() }
    }

    private fun end(newState: ConversationState, error: AssistantError?) {
        activeToken++
        handle = null
        nextRetryAtMillis = null
        state = newState
        lastError = error
        val u = pendingUser
        if (newState != ConversationState.COMPLETED && u != null) {
            val i = history.indexOfLast { it === u }
            if (i >= 0) history.removeAt(i)
            draft = u.text
        }
        pendingUser = null
    }

    private inner class Sink(private val token: Long) : AssistantSink {
        private fun live() = token == activeToken && isBusy

        override fun onHeaders() {
            if (live() && state == ConversationState.SENDING) state = ConversationState.STREAMING
        }

        override fun onDelta(text: String) {
            if (!live()) return
            state = ConversationState.STREAMING
            gotDelta = true
            partialText += text
        }

        override fun onComplete() {
            if (!live()) return
            if (partialText.isEmpty()) {
                end(ConversationState.FAILED, ErrorMapping.map(TransportFailure.MalformedStream()))
                return
            }
            history.add(ChatMessage(Role.ASSISTANT, partialText))
            while (history.size > maxHistory) history.removeAt(0)
            end(ConversationState.COMPLETED, null)
        }

        override fun onError(error: AssistantError) {
            if (!live()) return
            if (error.kind == AssistantErrorKind.RETRYABLE && !gotDelta) {
                val delay = retryPolicy.delayMillis(retriesUsed + 1)
                if (delay != null) {
                    retriesUsed++
                    activeToken++ // invalidate this attempt
                    handle = null
                    state = ConversationState.SENDING
                    nextRetryAtMillis = clock.nowMillis() + delay
                    return
                }
            }
            end(ConversationState.FAILED, error)
        }
    }
}
