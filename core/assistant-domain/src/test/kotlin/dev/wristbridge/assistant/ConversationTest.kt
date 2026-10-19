package dev.wristbridge.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Catalogue ids: I01 flow, I02 double submit, I03 offline, I04 cancel, I05 reset, I07/I08 partial/EOF, I09 retries. */
class ConversationTest {
    private val clock = ManualAssistantClock()
    private val ok = listOf(
        FakeStep.Headers(), FakeStep.Delta("Ho"), FakeStep.Delta("la", 5), FakeStep.Complete(10),
    )

    private fun conv(
        scripts: List<List<FakeStep>>,
        ignoreCancel: Boolean = false,
        maxHistory: Int = 20,
    ): Pair<Conversation, FakeProvider> {
        val p = FakeProvider(clock, scripts, ignoreCancel)
        return Conversation(p, clock, maxHistory) to p
    }

    private fun fail(status: Int) = FakeStep.Fail(ErrorMapping.mapHttp(status))

    @Test
    fun i01_fullFlowWithFakeProvider() {
        val (c, p) = conv(listOf(ok))
        assertEquals(ConversationState.IDLE, c.state)
        assertTrue(c.submit("  ¿Qué hora es?  ") is SubmitResult.Accepted)
        assertEquals(ConversationState.SENDING, c.state)
        p.pump()
        assertEquals(ConversationState.STREAMING, c.state)
        assertEquals("Ho", c.partialText)
        clock.advance(5); p.pump()
        assertEquals("Hola", c.partialText)
        clock.advance(5); p.pump()
        assertEquals(ConversationState.COMPLETED, c.state)
        assertEquals(listOf(ChatMessage(Role.USER, "¿Qué hora es?"), ChatMessage(Role.ASSISTANT, "Hola")), c.messages)
        assertNull(c.lastError)
        assertEquals(c.messages, p.requests.single().messages.take(1) + c.messages.drop(1))
    }

    @Test
    fun i01_emptyAndTooLongInputRejectedWithoutProviderCall() {
        val (c, p) = conv(listOf(ok))
        assertEquals(SubmitResult.RejectedEmpty, c.submit("   "))
        assertEquals(SubmitResult.RejectedTooLong, c.submit("x".repeat(5_000)))
        assertEquals(0, p.startCount)
        assertEquals(ConversationState.IDLE, c.state)
    }

    @Test
    fun i01_completionWithoutAnyTextIsAFailureNotSuccess() {
        val (c, p) = conv(listOf(listOf(FakeStep.Headers(), FakeStep.Complete())))
        c.submit("hola"); p.pump()
        assertEquals(ConversationState.FAILED, c.state)
        assertEquals(AssistantErrorKind.MALFORMED_STREAM, c.lastError?.kind)
    }

    @Test
    fun i02_doubleSubmitWhileBusyIsRejectedNotCoalesced() {
        val (c, p) = conv(listOf(ok))
        val first = c.submit("uno")
        assertTrue(first is SubmitResult.Accepted)
        assertEquals(SubmitResult.RejectedBusy, c.submit("uno"))
        p.pump()
        assertEquals(SubmitResult.RejectedBusy, c.submit("dos")) // also while STREAMING
        assertEquals(1, p.startCount)
        clock.advance(10); p.pump()
        assertEquals(1, c.messages.count { it.role == Role.USER })
        assertTrue(c.submit("dos") is SubmitResult.Accepted) // allowed again after completion
        assertEquals(2, p.startCount)
    }

    @Test
    fun i03_offlineKeepsDraftAndDoesNotPolluteHistory() {
        val (c, p) = conv(listOf(listOf(FakeStep.Fail(ErrorMapping.map(TransportFailure.Offline)))))
        c.submit("borrador importante"); p.pump()
        assertEquals(ConversationState.FAILED, c.state)
        assertEquals(AssistantErrorKind.OFFLINE, c.lastError?.kind)
        assertEquals("borrador importante", c.draft)
        assertTrue(c.messages.isEmpty())
        assertNull(c.nextRetryAtMillis) // offline is not auto-retried
        assertEquals(1, p.startCount)
    }

    @Test
    fun i04_cancelBeforeHeadersThenLateEventsIgnored() {
        val (c, p) = conv(listOf(ok), ignoreCancel = true)
        c.submit("a")
        assertEquals(ConversationState.SENDING, c.state)
        assertTrue(c.cancel())
        assertEquals(ConversationState.CANCELLED, c.state)
        assertEquals(1, p.cancelCalls)
        clock.advance(100); p.pump() // provider ignores cancel and keeps delivering
        assertEquals(ConversationState.CANCELLED, c.state)
        assertEquals("", c.partialText)
        assertTrue(c.messages.isEmpty())
        assertEquals("a", c.draft)
    }

    @Test
    fun i04_cancelMatrixAtEveryPointOfTheStream() {
        // Cancel after k scripted steps were delivered, for every k, with a provider that ignores cancel.
        for (k in 0..ok.size) {
            val (c, p) = conv(listOf(ok), ignoreCancel = true)
            c.submit("q")
            for (step in 0 until k) { clock.advance(if (step >= 2) 5 else 0); p.pump(); }
            val before = c.state
            val cancelled = c.cancel()
            val partialAtCancel = c.partialText
            clock.advance(1_000); p.pump()
            if (before == ConversationState.COMPLETED) {
                assertFalse(cancelled)
                assertEquals(ConversationState.COMPLETED, c.state)
            } else {
                assertTrue(cancelled, "k=$k")
                assertEquals(ConversationState.CANCELLED, c.state, "k=$k")
                assertEquals(partialAtCancel, c.partialText, "late delta leaked, k=$k")
                assertTrue(c.messages.none { it.role == Role.ASSISTANT }, "k=$k")
            }
        }
    }

    @Test
    fun i04_cancelIsIdempotentAndNoOpWhenIdle() {
        val (c, _) = conv(listOf(ok))
        assertFalse(c.cancel())
        c.submit("a")
        assertTrue(c.cancel())
        assertFalse(c.cancel())
        assertEquals(ConversationState.CANCELLED, c.state)
    }

    @Test
    fun i04_cancelDuringStreamingKeepsPartialOutOfHistory() {
        val (c, p) = conv(listOf(ok))
        c.submit("a"); p.pump()
        assertEquals("Ho", c.partialText)
        c.cancel()
        assertEquals("Ho", c.partialText) // identified partial, never history
        assertTrue(c.messages.isEmpty())
        clock.advance(100); p.pump() // cooperative provider stops
        assertEquals("Ho", c.partialText)
    }

    @Test
    fun i04_cancelInsideProviderStartIsHonouredAfterStartReturns() {
        lateinit var conv: Conversation
        var handleCancelled = false
        val provider = object : AssistantProvider {
            override fun start(request: AssistantRequest, sink: AssistantSink): AssistantHandle {
                conv.cancel() // re-entrant cancel before the handle exists
                sink.onDelta("late")
                return object : AssistantHandle { override fun cancel() { handleCancelled = true } }
            }
        }
        conv = Conversation(provider, clock)
        conv.submit("a")
        assertEquals(ConversationState.CANCELLED, conv.state)
        assertTrue(handleCancelled)
        assertEquals("", conv.partialText)
    }

    @Test
    fun i04_synchronousCompletionInsideStartIsHandled() {
        val provider = object : AssistantProvider {
            override fun start(request: AssistantRequest, sink: AssistantSink): AssistantHandle {
                sink.onHeaders(); sink.onDelta("ok"); sink.onComplete()
                return object : AssistantHandle { override fun cancel() = Unit }
            }
        }
        val c = Conversation(provider, clock)
        c.submit("a")
        assertEquals(ConversationState.COMPLETED, c.state)
        assertEquals("ok", c.messages.last().text)
    }

    @Test
    fun i04_staleResponseNeverMutatesNewerRequest() {
        val first = listOf(FakeStep.Headers(), FakeStep.Delta("OLD", 5), FakeStep.Complete(6))
        val second = listOf(FakeStep.Headers(), FakeStep.Delta("NEW", 10), FakeStep.Complete(11))
        val (c, p) = conv(listOf(first, second), ignoreCancel = true)
        c.submit("a")
        c.cancel()
        c.submit("b")
        clock.advance(5); p.pump() // old request's OLD + Complete arrive while the new one is active
        assertEquals("", c.partialText)
        assertEquals(ConversationState.STREAMING, c.state)
        clock.advance(6); p.pump()
        assertEquals(ConversationState.COMPLETED, c.state)
        assertEquals(listOf("b", "NEW"), c.messages.map { it.text })
        assertTrue(p.requests[0].requestId != p.requests[1].requestId)
    }

    @Test
    fun i05_resetDuringRequestDropsHistoryAndIgnoresLateEvents() {
        val (c, p) = conv(listOf(ok, ok), ignoreCancel = true)
        c.submit("cuenta A"); p.pump(); clock.advance(10); p.pump()
        assertEquals(2, c.messages.size)
        c.submit("otra pregunta")
        c.reset() // account/provider switch mid-request
        assertEquals(ConversationState.IDLE, c.state)
        assertTrue(c.messages.isEmpty())
        clock.advance(100); p.pump()
        assertEquals(ConversationState.IDLE, c.state)
        assertTrue(c.messages.isEmpty())
        assertEquals("", c.partialText)
        c.submit("cuenta B")
        assertEquals(listOf("cuenta B"), p.requests.last().messages.map { it.text }) // no history mixed
    }

    @Test
    fun i07_deltasThenFailureKeepsIdentifiedPartialNotComplete() {
        val (c, p) = conv(listOf(listOf(FakeStep.Headers(), FakeStep.Delta("par"), FakeStep.Delta("cial", 1), fail(500).copy(atMillis = 2))))
        c.submit("q"); clock.advance(2); p.pump()
        assertEquals(ConversationState.FAILED, c.state) // 5xx after deltas is never retried
        assertEquals("parcial", c.partialText)
        assertTrue(c.messages.isEmpty())
        assertEquals(1, p.startCount)
        assertEquals("q", c.draft)
    }

    @Test
    fun i08_streamNeverTerminatingIsNotSuccess() {
        val (c, p) = conv(listOf(listOf(FakeStep.Headers(), FakeStep.Delta("x"))))
        c.submit("q"); clock.advance(10_000); p.pump()
        assertEquals(ConversationState.STREAMING, c.state)
        // The adapter reports EOF without a terminal event as a malformed stream:
        val parser = SseParser()
        parser.feed("data: x\n\n".toByteArray())
        assertEquals(SseError.ENDED_WITHOUT_TERMINAL, parser.finish())
        val (c2, p2) = conv(listOf(listOf(FakeStep.Headers(), FakeStep.Delta("x"),
            FakeStep.Fail(ErrorMapping.map(TransportFailure.MalformedStream(parser.finish()))))))
        c2.submit("q"); p2.pump()
        assertEquals(ConversationState.FAILED, c2.state)
        assertEquals(AssistantErrorKind.MALFORMED_STREAM, c2.lastError?.kind)
    }

    @Test
    fun i09_retriesAreBoundedWithBackoffOneTwoFour() {
        val (c, p) = conv(listOf(listOf(fail(503))))
        c.submit("q"); p.pump()
        assertEquals(ConversationState.SENDING, c.state)
        assertEquals(1_000L, c.nextRetryAtMillis)
        c.tick(); assertEquals(1, p.startCount) // not due yet
        clock.advance(1_000); c.tick(); p.pump()
        assertEquals(2, p.startCount)
        assertEquals(3_000L, c.nextRetryAtMillis)
        clock.advance(2_000); c.tick(); p.pump()
        assertEquals(3, p.startCount)
        assertEquals(7_000L, c.nextRetryAtMillis)
        clock.advance(4_000); c.tick(); p.pump()
        assertEquals(4, p.startCount) // 1 original + 3 retries
        assertEquals(ConversationState.FAILED, c.state)
        assertEquals(AssistantErrorKind.RETRYABLE, c.lastError?.kind)
        clock.advance(100_000); c.tick(); p.pump()
        assertEquals(4, p.startCount)
        assertEquals("q", c.draft)
    }

    @Test
    fun i09_retrySucceedsAndSameLogicalRequestId() {
        val (c, p) = conv(listOf(listOf(fail(500)), ok))
        c.submit("q"); p.pump()
        clock.advance(1_000); c.tick()
        clock.advance(10); p.pump()
        assertEquals(ConversationState.COMPLETED, c.state)
        assertEquals(p.requests[0].requestId, p.requests[1].requestId)
        assertEquals(listOf("q", "Hola"), c.messages.map { it.text })
    }

    @Test
    fun i09_noRetryAfterCancelWhileWaiting() {
        val (c, p) = conv(listOf(listOf(fail(500))))
        c.submit("q"); p.pump()
        assertEquals(1_000L, c.nextRetryAtMillis)
        c.cancel()
        assertNull(c.nextRetryAtMillis)
        clock.advance(60_000); c.tick(); p.pump()
        assertEquals(1, p.startCount)
        assertEquals(ConversationState.CANCELLED, c.state)
    }

    @Test
    fun i09_noRetryAfterResetAndNoRetryForOtherKinds() {
        val (c, p) = conv(listOf(listOf(fail(500))))
        c.submit("q"); p.pump(); c.reset()
        clock.advance(60_000); c.tick()
        assertEquals(1, p.startCount)
        for (status in listOf(401, 403, 429, 404)) {
            val (c2, p2) = conv(listOf(listOf(fail(status))))
            c2.submit("q"); p2.pump(); clock.advance(60_000); c2.tick()
            assertEquals(ConversationState.FAILED, c2.state, "status=$status")
            assertEquals(1, p2.startCount, "status=$status")
        }
    }

    @Test
    fun i09_providerThrowingIsContainedAsFailure() {
        val c = Conversation(object : AssistantProvider {
            override fun start(request: AssistantRequest, sink: AssistantSink): AssistantHandle =
                throw IllegalStateException("boom sk-SECRET")
        }, clock)
        c.submit("q")
        assertEquals(ConversationState.FAILED, c.state)
        assertFalse(c.lastError!!.userMessage.contains("SECRET"))
    }

    @Test
    fun i13_historyIsBounded() {
        val (c, p) = conv(listOf(ok), maxHistory = 4)
        repeat(6) { n ->
            c.submit("pregunta $n"); p.pump(); clock.advance(10); p.pump();
            assertTrue(c.messages.size <= 4, "size=${c.messages.size}")
        }
        assertEquals(4, c.messages.size)
        assertEquals("pregunta 5", c.messages[2].text)
        assertTrue(p.requests.all { it.messages.size <= 4 })
        // maxHistoryMessages is coerced to a minimum of 2 (one exchange).
        val (tiny, tp) = conv(listOf(ok), maxHistory = 0)
        tiny.submit("a"); tp.pump(); clock.advance(10); tp.pump()
        assertEquals(2, tiny.messages.size)
    }

    @Test
    fun noSecretLikeRequestTextEverAppearsInErrorsOrDiagnostics() {
        val secret = "sk-live-0123456789abcdef"
        val kinds = listOf(
            ErrorMapping.mapHttp(401), ErrorMapping.mapHttp(429, "7"), ErrorMapping.mapHttp(503),
            ErrorMapping.map(TransportFailure.Offline), ErrorMapping.map(TransportFailure.Timeout),
            ErrorMapping.map(TransportFailure.MalformedStream()),
        )
        for (e in kinds) {
            val (c, p) = conv(listOf(listOf(FakeStep.Fail(e))))
            c.submit("mi clave es $secret"); p.pump()
            clock.advance(100_000); c.tick(); p.pump()
            val err = c.lastError
            if (err != null) {
                assertFalse(err.userMessage.contains(secret))
                assertFalse(err.toString().contains(secret))
            }
            assertFalse(p.requests.first().toString().contains(secret))
            assertFalse(p.requests.first().messages.first().toString().contains(secret))
        }
    }
}
