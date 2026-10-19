package dev.wristbridge.assistant

enum class AssistantErrorKind {
    NEEDS_AUTH,
    RATE_LIMITED,
    RETRYABLE,
    OFFLINE,
    TIMEOUT,
    MALFORMED_STREAM,
    CANCELLED,
    UNEXPECTED,
}

/**
 * Typed error shown to the user. [userMessage] is built only from fixed Spanish templates (plus a
 * sanitised integer for Retry-After); it never contains provider text, request content or secrets.
 */
data class AssistantError(
    val kind: AssistantErrorKind,
    val userMessage: String,
    val retryAfterSeconds: Int? = null,
)

/** Transport-level conditions a provider adapter reports. Carries no body text on purpose. */
sealed interface TransportFailure {
    data class Http(val status: Int, val retryAfterHeader: String? = null) : TransportFailure
    data object Offline : TransportFailure
    data object Timeout : TransportFailure
    data class MalformedStream(val reason: SseError? = null) : TransportFailure
    data object Cancelled : TransportFailure
}

/** Bounded exponential backoff: retry n (1-based) waits 1, 2, 4, 8 s ... capped; at most [maxRetries]. */
class RetryPolicy(
    maxRetries: Int = 3,
    private val baseDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 8_000,
) {
    val maxRetries: Int = maxRetries.coerceIn(0, 10)

    /** Delay before retry number [retryNumber] (1-based), or null when retries are exhausted. */
    fun delayMillis(retryNumber: Int): Long? {
        if (retryNumber < 1 || retryNumber > maxRetries) return null
        val shift = (retryNumber - 1).coerceAtMost(20)
        return (baseDelayMillis shl shift).coerceAtMost(maxDelayMillis)
    }
}

object ErrorMapping {
    /** Upper bound for a honoured Retry-After. */
    const val MAX_RETRY_AFTER_SECONDS: Int = 3_600

    fun map(failure: TransportFailure): AssistantError = when (failure) {
        is TransportFailure.Http -> mapHttp(failure.status, failure.retryAfterHeader)
        TransportFailure.Offline -> AssistantError(AssistantErrorKind.OFFLINE, "Sin conexión a Internet.")
        TransportFailure.Timeout ->
            AssistantError(AssistantErrorKind.TIMEOUT, "El asistente tardó demasiado en responder.")
        is TransportFailure.MalformedStream ->
            AssistantError(AssistantErrorKind.MALFORMED_STREAM, "La respuesta del asistente no llegó completa.")
        TransportFailure.Cancelled -> AssistantError(AssistantErrorKind.CANCELLED, "Cancelado.")
    }

    fun mapHttp(status: Int, retryAfterHeader: String? = null): AssistantError = when {
        status == 401 || status == 403 -> AssistantError(
            AssistantErrorKind.NEEDS_AUTH,
            "Hace falta iniciar sesión o revisar el acceso del asistente.",
        )
        status == 429 -> {
            val secs = parseRetryAfterSeconds(retryAfterHeader)
            val msg = if (secs != null) "Demasiadas solicitudes. Inténtalo de nuevo en $secs s."
            else "Demasiadas solicitudes. Inténtalo de nuevo en unos segundos."
            AssistantError(AssistantErrorKind.RATE_LIMITED, msg, secs)
        }
        status == 408 -> map(TransportFailure.Timeout)
        status in 500..599 -> AssistantError(
            AssistantErrorKind.RETRYABLE,
            "El servicio del asistente no está disponible por ahora.",
        )
        else -> AssistantError(AssistantErrorKind.UNEXPECTED, "No se pudo completar la solicitud.")
    }

    /**
     * Parses the delta-seconds form of Retry-After. Returns null for null/blank/negative/non-numeric
     * values (HTTP-date form is deliberately unsupported); huge values are clamped.
     */
    fun parseRetryAfterSeconds(header: String?): Int? {
        val t = header?.trim() ?: return null
        if (t.isEmpty() || t.length > 18 || !t.all { it in '0'..'9' }) return null
        val v = t.toLongOrNull() ?: return null
        return v.coerceAtMost(MAX_RETRY_AFTER_SECONDS.toLong()).toInt()
    }
}
