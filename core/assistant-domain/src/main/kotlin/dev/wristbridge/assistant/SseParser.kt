package dev.wristbridge.assistant

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One dispatched Server-Sent Event. [id] is the last event id seen on the stream (or null). */
data class SseEvent(
    val event: String?,
    val data: String,
    val id: String?,
    val retryMillis: Long?,
)

/** Typed parser failures. None of them carries stream content. */
enum class SseError {
    /** A line or an event exceeded the configured byte limit. */
    EVENT_TOO_LARGE,

    /** A complete line was not valid UTF-8. */
    INVALID_UTF8,

    /** The stream ended without a terminal event: this is never a success. */
    ENDED_WITHOUT_TERMINAL,
}

/**
 * Incremental SSE parser working on raw bytes, so multibyte UTF-8 characters, CR/LF/CRLF pairs and
 * the BOM may be split across arbitrary chunks. Lines are decoded only once complete.
 *
 * Tolerance (documented): unknown fields are ignored; a `retry` that is not all ASCII digits is
 * ignored; an `id` containing NUL is ignored; an event without any `data:` line is dropped;
 * a trailing partial line/event without a blank line at EOF is discarded; bytes after the terminal
 * event are ignored. After an error the parser is sticky: later input is ignored.
 *
 * Not thread safe.
 */
class SseParser(
    private val maxEventBytes: Int = DEFAULT_MAX_EVENT_BYTES,
    private val isTerminal: (SseEvent) -> Boolean = DEFAULT_TERMINAL,
) {
    init {
        require(maxEventBytes > 0) { "maxEventBytes must be positive" }
    }

    private val line = ByteArrayOutputStream()
    private var lineBytes = 0
    private var eventBytes = 0
    private var afterCr = false
    private var firstLine = true
    private var data: StringBuilder? = null
    private var eventType: String? = null
    private var retry: Long? = null
    private var lastEventId: String? = null

    /** First error found, or null. */
    var error: SseError? = null
        private set

    /** True once an event accepted by the terminal predicate has been dispatched. */
    var terminalSeen: Boolean = false
        private set

    /** Feeds a chunk and returns the events completed by it (possibly none). */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<SseEvent> {
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) { "bad range" }
        val out = ArrayList<SseEvent>()
        var i = offset
        val end = offset + length
        while (i < end) {
            if (error != null || terminalSeen) break
            val b = chunk[i].toInt() and 0xFF
            i++
            if (afterCr) {
                afterCr = false
                if (b == LF) continue
            }
            when (b) {
                CR -> {
                    afterCr = true
                    endLine(out)
                }
                LF -> endLine(out)
                else -> {
                    if (lineBytes + 1 > maxEventBytes) {
                        error = SseError.EVENT_TOO_LARGE
                    } else {
                        line.write(b)
                        lineBytes++
                    }
                }
            }
        }
        return out
    }

    /**
     * Signals end of input. Returns null only when the stream ended cleanly after a terminal event;
     * otherwise the typed error (a missing terminal event is [SseError.ENDED_WITHOUT_TERMINAL]).
     */
    fun finish(): SseError? = error ?: if (terminalSeen) null else SseError.ENDED_WITHOUT_TERMINAL

    private fun endLine(out: MutableList<SseEvent>) {
        var bytes = line.toByteArray()
        line.reset()
        lineBytes = 0
        if (firstLine) {
            firstLine = false
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                bytes = bytes.copyOfRange(3, bytes.size)
            }
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            error = SseError.INVALID_UTF8
            return
        }
        if (text.isEmpty()) {
            dispatch(out)
            return
        }
        if (text[0] == ':') return // comment / keep-alive
        eventBytes += bytes.size + 1
        if (eventBytes > maxEventBytes) {
            error = SseError.EVENT_TOO_LARGE
            return
        }
        val idx = text.indexOf(':')
        val field = if (idx < 0) text else text.substring(0, idx)
        var value = if (idx < 0) "" else text.substring(idx + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                val sb = data ?: StringBuilder().also { data = it; dataLines = 0 }
                if (dataLines > 0) sb.append('\n')
                sb.append(value)
                dataLines++
            }
            "event" -> eventType = value
            "id" -> if (!value.contains('\u0000')) lastEventId = value
            "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) retry = value.toLongOrNull() ?: retry
            else -> Unit // unknown field
        }
    }

    private var dataLines = 0

    private fun dispatch(out: MutableList<SseEvent>) {
        val sb = data
        val type = eventType
        val r = retry
        data = null
        dataLines = 0
        eventType = null
        retry = null
        eventBytes = 0
        if (sb == null) return
        val ev = SseEvent(type, sb.toString(), lastEventId, r)
        out.add(ev)
        if (isTerminal(ev)) terminalSeen = true
    }

    companion object {
        const val DEFAULT_MAX_EVENT_BYTES: Int = 64 * 1024
        private const val CR = 0x0D
        private const val LF = 0x0A

        /** Default terminal predicate: `[DONE]` data or a conventional completion event name. */
        val DEFAULT_TERMINAL: (SseEvent) -> Boolean = { e ->
            e.data.trim() == "[DONE]" || e.event == "done" || e.event == "message_stop" || e.event == "response.completed"
        }
    }
}
