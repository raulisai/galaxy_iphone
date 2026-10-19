package dev.wristbridge.assistant

/** Injectable clock so tests never sleep. */
fun interface AssistantClock {
    fun nowMillis(): Long
}

/** Manually advanced clock for tests. */
class ManualAssistantClock(private var now: Long = 0L) : AssistantClock {
    override fun nowMillis(): Long = now
    fun advance(millis: Long) {
        require(millis >= 0)
        now += millis
    }
}

enum class Role { USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String) {
    /** Redacted so accidental logging never leaks message content. */
    override fun toString(): String = "ChatMessage(role=$role, length=${text.length})"
}

/** [requestId] identifies the logical request; the provider must not reuse it for another one. */
data class AssistantRequest(val requestId: Long, val messages: List<ChatMessage>) {
    override fun toString(): String = "AssistantRequest(requestId=$requestId, messages=${messages.size})"
}

/** Callbacks from a provider. Callers must serialise them with the other Conversation calls. */
interface AssistantSink {
    /** Response headers received (stream is about to deliver data). */
    fun onHeaders()
    fun onDelta(text: String)

    /** Terminal success event received. */
    fun onComplete()
    fun onError(error: AssistantError)
}

interface AssistantHandle {
    /** Idempotent. After it returns the provider should stop delivering, but late calls must be tolerated. */
    fun cancel()
}

interface AssistantProvider {
    fun start(request: AssistantRequest, sink: AssistantSink): AssistantHandle
}

/** Scripted event for [FakeProvider], delivered once [atMillis] ticks passed since start. */
sealed interface FakeStep {
    val atMillis: Long

    data class Headers(override val atMillis: Long = 0) : FakeStep
    data class Delta(val text: String, override val atMillis: Long = 0) : FakeStep
    data class Complete(override val atMillis: Long = 0) : FakeStep
    data class Fail(val error: AssistantError, override val atMillis: Long = 0) : FakeStep
}

/**
 * Deterministic provider for tests only. Nothing is delivered until [pump] is called; time comes
 * from the injected [clock]. `scripts[n]` serves the n-th start (the last script repeats), which
 * allows failure-then-success retry scenarios. With [ignoreCancel] the provider keeps delivering
 * after cancel(), to prove consumers discard late events.
 */
class FakeProvider(
    private val clock: AssistantClock,
    private val scripts: List<List<FakeStep>>,
    private val ignoreCancel: Boolean = false,
) : AssistantProvider {
    private inner class Run(val steps: List<FakeStep>, val sink: AssistantSink, val startedAt: Long) : AssistantHandle {
        var index = 0
        var cancelled = false
        override fun cancel() {
            cancelled = true
            cancelCalls++
        }
    }

    private val runs = ArrayList<Run>()
    var startCount: Int = 0
        private set
    var cancelCalls: Int = 0
        private set
    val requests = ArrayList<AssistantRequest>()

    override fun start(request: AssistantRequest, sink: AssistantSink): AssistantHandle {
        val script = scripts[minOf(startCount, scripts.size - 1)]
        startCount++
        requests.add(request)
        return Run(script.sortedBy { it.atMillis }, sink, clock.nowMillis()).also { runs.add(it) }
    }

    /** Delivers every step that is due according to the clock. */
    fun pump() {
        for (run in runs.toList()) {
            while (run.index < run.steps.size) {
                if (run.cancelled && !ignoreCancel) break
                val step = run.steps[run.index]
                if (clock.nowMillis() - run.startedAt < step.atMillis) break
                run.index++
                when (step) {
                    is FakeStep.Headers -> run.sink.onHeaders()
                    is FakeStep.Delta -> run.sink.onDelta(step.text)
                    is FakeStep.Complete -> run.sink.onComplete()
                    is FakeStep.Fail -> run.sink.onError(step.error)
                }
            }
        }
    }
}
