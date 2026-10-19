package dev.wristbridge.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Catalogue ids: I03 (offline), I09 (401/403/429/5xx, timeout, bounded backoff). */
class ErrorMappingTest {
    @Test
    fun i09_authStatuses() {
        for (s in listOf(401, 403)) assertEquals(AssistantErrorKind.NEEDS_AUTH, ErrorMapping.mapHttp(s).kind)
    }

    @Test
    fun i09_rateLimitedParsesRetryAfterSafely() {
        assertEquals(30, ErrorMapping.mapHttp(429, "30").retryAfterSeconds)
        assertEquals(30, ErrorMapping.mapHttp(429, " 30 ").retryAfterSeconds)
        assertEquals(0, ErrorMapping.mapHttp(429, "0").retryAfterSeconds)
        assertEquals(ErrorMapping.MAX_RETRY_AFTER_SECONDS, ErrorMapping.mapHttp(429, "999999999").retryAfterSeconds)
        assertEquals(ErrorMapping.MAX_RETRY_AFTER_SECONDS, ErrorMapping.mapHttp(429, "99999999999999999").retryAfterSeconds)
        for (bad in listOf(null, "", "  ", "-5", "+5", "1.5", "abc", "Wed, 21 Oct 2026 07:28:00 GMT", "9".repeat(40), "5; drop table")) {
            val e = ErrorMapping.mapHttp(429, bad)
            assertEquals(AssistantErrorKind.RATE_LIMITED, e.kind, "header=$bad")
            assertNull(e.retryAfterSeconds, "header=$bad")
            assertFalse(e.userMessage.contains("drop"), "header=$bad")
        }
    }

    @Test
    fun i09_serverErrorsAreRetryableOthersAreNot() {
        for (s in listOf(500, 502, 503, 504, 599)) assertEquals(AssistantErrorKind.RETRYABLE, ErrorMapping.mapHttp(s).kind)
        for (s in listOf(0, 400, 404, 418, 600, -1)) assertEquals(AssistantErrorKind.UNEXPECTED, ErrorMapping.mapHttp(s).kind)
        assertEquals(AssistantErrorKind.TIMEOUT, ErrorMapping.mapHttp(408).kind)
    }

    @Test
    fun i03_transportConditions() {
        assertEquals(AssistantErrorKind.OFFLINE, ErrorMapping.map(TransportFailure.Offline).kind)
        assertEquals(AssistantErrorKind.TIMEOUT, ErrorMapping.map(TransportFailure.Timeout).kind)
        assertEquals(AssistantErrorKind.CANCELLED, ErrorMapping.map(TransportFailure.Cancelled).kind)
        for (r in listOf(null) + SseError.entries) {
            assertEquals(AssistantErrorKind.MALFORMED_STREAM, ErrorMapping.map(TransportFailure.MalformedStream(r)).kind)
        }
    }

    @Test
    fun i09_backoffIsOneTwoFourEightAndBounded() {
        val p = RetryPolicy()
        assertEquals(listOf<Long?>(1000, 2000, 4000, null), (1..4).map { p.delayMillis(it) })
        assertNull(p.delayMillis(0))
        assertNull(p.delayMillis(-1))
        val wide = RetryPolicy(maxRetries = 6)
        assertEquals(listOf<Long?>(1000, 2000, 4000, 8000, 8000, 8000, null), (1..7).map { wide.delayMillis(it) })
        assertEquals(10, RetryPolicy(maxRetries = 1_000).maxRetries)
        assertNull(RetryPolicy(maxRetries = 0).delayMillis(1))
    }

    @Test
    fun i09_userMessagesAreSpanishAndNeverContainSecretsOrProviderText() {
        val secrets = listOf("sk-live-ABC123", "Bearer eyJhbGciOi", "api_key=zzz", "https://api.example.invalid/v1")
        val all = buildList {
            for (s in listOf(0, 400, 401, 403, 408, 429, 500, 503)) {
                for (h in listOf(null) + secrets) add(ErrorMapping.mapHttp(s, h))
            }
            add(ErrorMapping.map(TransportFailure.Offline))
            add(ErrorMapping.map(TransportFailure.Timeout))
            add(ErrorMapping.map(TransportFailure.Cancelled))
            add(ErrorMapping.map(TransportFailure.MalformedStream()))
        }
        for (e in all) {
            assertTrue(e.userMessage.isNotBlank())
            for (s in secrets) assertFalse(e.userMessage.contains(s), e.userMessage)
            assertFalse(e.userMessage.contains("{") || e.userMessage.contains("http"), e.userMessage)
        }
    }
}
