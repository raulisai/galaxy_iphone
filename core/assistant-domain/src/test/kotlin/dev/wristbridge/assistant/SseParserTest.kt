package dev.wristbridge.assistant

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Catalogue ids: I06 (split bytes, CRLF, multi-line, unknown events), I08 (EOF without terminal). */
class SseParserTest {
    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)

    private data class Outcome(val events: List<SseEvent>, val finish: SseError?)

    private fun parse(bytes: ByteArray, chunkSizes: List<Int>, maxEventBytes: Int = SseParser.DEFAULT_MAX_EVENT_BYTES): Outcome {
        val p = SseParser(maxEventBytes)
        val out = ArrayList<SseEvent>()
        var pos = 0
        var k = 0
        while (pos < bytes.size) {
            val n = minOf(chunkSizes[k % chunkSizes.size], bytes.size - pos)
            out += p.feed(bytes, pos, n)
            pos += n
            k++
        }
        return Outcome(out, p.finish())
    }

    private fun whole(s: String) = parse(b(s), listOf(Int.MAX_VALUE))

    @Test
    fun i06_basicEventsAndTerminal() {
        val o = whole("data: hola\n\nevent: delta\nid: 7\ndata: mundo\n\ndata: [DONE]\n\n")
        assertEquals(listOf("hola", "mundo", "[DONE]"), o.events.map { it.data })
        assertEquals("delta", o.events[1].event)
        assertEquals("7", o.events[1].id)
        assertNull(o.events[0].id)
        assertNull(o.finish)
    }

    @Test
    fun i06_lineEndingsLfCrCrlfAndMixed() {
        for (nl in listOf("\n", "\r", "\r\n")) {
            val o = whole("data: a${nl}data: b$nl${nl}data: [DONE]$nl$nl")
            assertEquals(listOf("a\nb", "[DONE]"), o.events.map { it.data }, "newline=${nl.length}")
            assertNull(o.finish)
        }
        val mixed = whole("data: x\r\ndata: y\rdata: z\n\r\ndata: [DONE]\n\n")
        assertEquals(listOf("x\ny\nz", "[DONE]"), mixed.events.map { it.data })
    }

    @Test
    fun i06_crlfSplitBetweenChunks() {
        val p = SseParser()
        val ev = ArrayList<SseEvent>()
        ev += p.feed(b("data: a\r"))
        ev += p.feed(b("\n\r"))
        ev += p.feed(b("\ndata: [DONE]\r\n\r\n"))
        assertEquals(listOf("a", "[DONE]"), ev.map { it.data })
    }

    @Test
    fun i06_commentsUnknownFieldsRetryAndSpaceRule() {
        val o = whole(
            ": keep-alive\n" +
                "foo: bar\n" +
                "retry: 1500\n" +
                "data:no-space\n" +
                "data:  two-spaces\n" +
                "bogus line without colon\n" +
                "\n" +
                "retry: abc\n" +
                "data\n" +
                "\n" +
                "data: [DONE]\n\n",
        )
        assertEquals("no-space\n two-spaces", o.events[0].data)
        assertEquals(1500L, o.events[0].retryMillis)
        assertEquals("", o.events[1].data) // field name without colon -> empty value
        assertNull(o.events[1].retryMillis)
        assertNull(o.finish)
    }

    @Test
    fun i06_eventWithoutDataIsDroppedAndTypeDoesNotLeak() {
        val o = whole("event: ping\n\ndata: x\n\ndata: [DONE]\n\n")
        assertEquals(2, o.events.size)
        assertNull(o.events[0].event)
    }

    @Test
    fun i06_idWithNulIgnoredAndIdPersists() {
        val o = whole("id: 1\ndata: a\n\nid: b\u0000d\ndata: b\n\ndata: [DONE]\n\n")
        assertEquals(listOf("1", "1", "1"), o.events.map { it.id })
    }

    @Test
    fun i06_bomAtStartOnly() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + b("data: a\n\ndata: [DONE]\n\n")
        assertEquals(listOf("a", "[DONE]"), parse(bytes, listOf(Int.MAX_VALUE)).events.map { it.data })
        assertEquals(listOf("a", "[DONE]"), parse(bytes, listOf(1)).events.map { it.data }) // BOM split
        // A BOM that is not at stream start is not stripped: it makes an unknown field name, so "b" is dropped.
        val late = b("data: a\n\n") + bom + b("data: b\n\ndata: [DONE]\n\n")
        assertEquals(listOf("a", "[DONE]"), parse(late, listOf(Int.MAX_VALUE)).events.map { it.data })
    }

    @Test
    fun i06_multibyteCharactersSplitAtEveryByte() {
        val text = "ñandú € 😀 日本語"
        val bytes = b("data: $text\n\ndata: [DONE]\n\n")
        val o = parse(bytes, listOf(1))
        assertEquals(text, o.events[0].data)
        for (size in 1..7) assertEquals(text, parse(bytes, listOf(size)).events[0].data, "size=$size")
        assertNull(o.finish)
    }

    @Test
    fun i06_invalidUtf8IsTypedError() {
        val bytes = b("data: ") + byteArrayOf(0xC3.toByte(), 0x28) + b("\n\ndata: [DONE]\n\n")
        val p = SseParser()
        val ev = p.feed(bytes)
        assertTrue(ev.isEmpty())
        assertEquals(SseError.INVALID_UTF8, p.error)
        assertEquals(SseError.INVALID_UTF8, p.finish())
    }

    @Test
    fun i06_eventSizeLimitIsTypedErrorAndSticky() {
        val p = SseParser(maxEventBytes = 32)
        val ev = ArrayList<SseEvent>()
        ev += p.feed(b("data: ok\n\n"))
        ev += p.feed(b("data: " + "x".repeat(10) + "\n" + "data: " + "y".repeat(30) + "\n\n"))
        ev += p.feed(b("data: [DONE]\n\n"))
        assertEquals(listOf("ok"), ev.map { it.data })
        assertEquals(SseError.EVENT_TOO_LARGE, p.error)
        assertEquals(SseError.EVENT_TOO_LARGE, p.finish())
    }

    @Test
    fun i06_singleHugeLineWithoutNewlineIsBounded() {
        val p = SseParser(maxEventBytes = 64)
        p.feed(ByteArray(10_000) { 'a'.code.toByte() })
        assertEquals(SseError.EVENT_TOO_LARGE, p.error)
    }

    @Test
    fun i08_eofWithoutTerminalIsErrorNotSuccess() {
        val o = whole("data: hola\n\ndata: mundo\n\n")
        assertEquals(2, o.events.size)
        assertEquals(SseError.ENDED_WITHOUT_TERMINAL, o.finish)
        assertEquals(SseError.ENDED_WITHOUT_TERMINAL, SseParser().finish()) // empty stream
    }

    @Test
    fun i08_partialTrailingEventAtEofIsDiscardedAndTerminalMissing() {
        val o = whole("data: a\n\ndata: [DONE]") // terminal event never dispatched (no blank line)
        assertEquals(listOf("a"), o.events.map { it.data })
        assertEquals(SseError.ENDED_WITHOUT_TERMINAL, o.finish)
    }

    @Test
    fun i08_namedTerminalEventsAndBytesAfterTerminalIgnored() {
        val o = whole("event: message_stop\ndata: {}\n\ndata: late\n\n")
        assertEquals(1, o.events.size)
        assertNull(o.finish)
        val custom = SseParser(isTerminal = { it.event == "end" })
        val ev = custom.feed(b("event: end\ndata: x\n\n"))
        assertEquals(1, ev.size)
        assertNull(custom.finish())
    }

    @Test
    fun i06_randomisedChunkingNeverChangesOutput() {
        val rnd = Random(20261018)
        val nls = listOf("\n", "\r", "\r\n")
        val words = listOf("hola", "ñu", "€5", "😀", "日本", "a b", "", " x", "{\"k\":1}")
        repeat(200) { round ->
            val sb = StringBuilder()
            if (rnd.nextInt(4) == 0) sb.append('﻿') // BOM as char -> EF BB BF in UTF-8
            repeat(rnd.nextInt(1, 8)) {
                if (rnd.nextInt(5) == 0) sb.append(": c").append(nls.random(rnd))
                if (rnd.nextInt(6) == 0) sb.append("event: e").append(rnd.nextInt(3)).append(nls.random(rnd))
                if (rnd.nextInt(6) == 0) sb.append("id: ").append(rnd.nextInt(100)).append(nls.random(rnd))
                if (rnd.nextInt(8) == 0) sb.append("retry: ").append(rnd.nextInt(5000)).append(nls.random(rnd))
                repeat(rnd.nextInt(1, 4)) { sb.append("data: ").append(words.random(rnd)).append(nls.random(rnd)) }
                sb.append(nls.random(rnd))
            }
            if (rnd.nextBoolean()) sb.append("data: [DONE]").append(nls.random(rnd)).append(nls.random(rnd))
            if (rnd.nextInt(5) == 0) sb.append("data: partial") // truncated tail
            val bytes = sb.toString().toByteArray(Charsets.UTF_8)
            val limit = if (round % 10 == 0) 40 else SseParser.DEFAULT_MAX_EVENT_BYTES // exercise error path too
            val reference = parse(bytes, listOf(Int.MAX_VALUE), limit)
            repeat(8) {
                val sizes = List(rnd.nextInt(1, 6)) { rnd.nextInt(1, 9) } // includes 1-byte chunks
                assertEquals(reference, parse(bytes, sizes, limit), "round=$round sizes=$sizes")
            }
            assertEquals(reference, parse(bytes, listOf(1), limit), "round=$round one-byte")
        }
    }
}
