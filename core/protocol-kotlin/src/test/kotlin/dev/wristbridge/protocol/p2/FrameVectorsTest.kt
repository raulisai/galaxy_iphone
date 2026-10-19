package dev.wristbridge.protocol.p2

import dev.wristbridge.protocol.MiniJson
import dev.wristbridge.protocol.hex
import dev.wristbridge.protocol.list
import dev.wristbridge.protocol.obj
import dev.wristbridge.protocol.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives every vector of test/fixtures/shared/p2-framing-vectors.json through the DRAFT P2 framing codec.
 *
 * The vectors are derived by hand ("a mano") from section 3.2/3.5 of contracts/proposals/p2-channel-draft.md and are
 * all PROPUESTO. The codec is DRAFT and NOT FROZEN. A PASS here only means "the draft codec agrees with the
 * hand-derived vectors"; it is not interoperability evidence with Swift and approves nothing (G1/G2 stay open).
 * Expected values come from the JSON only, never from the encoder under test.
 */
class FrameVectorsTest {
    private val manifest = MiniJson.parse(repoFile("test/fixtures/shared/p2-framing-vectors.json").readText()).obj()
    private val vectors: List<Map<String, Any?>> = manifest["vectors"].list().map { it.obj() }

    private fun Any?.int(): Int = (this as Long).toInt()

    private fun idOf(v: Map<String, Any?>): String = v["id"] as String

    private fun family(prefix: String) {
        val selected = vectors.filter { idOf(it).startsWith(prefix) }
        assertTrue(selected.isNotEmpty(), "no vectors for $prefix")
        val failures = selected.mapNotNull { v ->
            try {
                when (val schema = v["schema"]) {
                    "p2.frame" -> frameVector(v)
                    "p2.stream" -> streamVector(v)
                    "p2.encode" -> encodeVector(v)
                    else -> "${idOf(v)}: unknown schema $schema"
                }
            } catch (e: Throwable) {
                "${idOf(v)}: exception $e"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun frameVector(v: Map<String, Any?>): String? {
        val id = idOf(v)
        val ex = v["expected"].obj()
        val r = FrameCodec.parse(hex(v["bytesHex"] as String))
        return when (ex["outcome"]) {
            "error" ->
                if (r is ParseResult.Rejected && r.error.wireName == ex["error"]) null
                else "$id: expected ${ex["error"]}, got $r"
            "ignored" -> if (r is ParseResult.IgnoredNonCritical) null else "$id: expected ignored, got $r"
            "frame" -> {
                val frame = (r as? ParseResult.Parsed)?.frame ?: return "$id: expected a frame, got $r"
                when (ex["frameType"]) {
                    "ACK" -> if (frame is AckFrame && frame.messageId == ex["messageId"].int()) null else "$id: bad ACK $frame"
                    "CANCEL" -> if (frame is CancelFrame && frame.messageId == ex["messageId"].int()) null else "$id: bad CANCEL $frame"
                    "ERROR" ->
                        if (frame is ErrorFrame && frame.messageId == ex["messageId"].int() &&
                            frame.errorCode == ex["errorCode"].int() && frame.relatedMessageId == ex["relatedMessageId"].int()
                        ) null else "$id: bad ERROR $frame"
                    else -> "$id: unsupported frameType in manifest"
                }
            }
            else -> "$id: unknown expected outcome"
        }
    }

    private fun typeCode(name: String): Int = if (name == "DATA") P2Wire.TYPE_DATA else P2Wire.TYPE_HANDSHAKE

    private fun streamVector(v: Map<String, Any?>): String? {
        val id = idOf(v)
        val ex = v["expected"].obj()
        val ctx: Map<String, Any?> = if (v.containsKey("context")) v["context"].obj() else emptyMap()
        val limits = (ctx["limitsUniform"] as? Long)?.let { ReassemblyLimits.uniform(it) } ?: ReassemblyLimits.DEFAULT
        val known = (ctx["knownMaxAttValueBytes"] as? Long)?.toInt()
        val reassembler = MessageReassembler(limits, known)
        val tracker = OutboundTracker()
        ctx["outboundInFlight"]?.list()?.forEach { tracker.register(it.int()) }
        val times: List<Long>? = ctx["timesMs"]?.list()?.map { (it as Long) * 1_000_000L }

        val frames = v["framesHex"].list().map { hex(it as String) }
        val steps = ArrayList<String>()
        val closes = ArrayList<Int>()
        var body: ByteArray? = null
        frames.forEachIndexed { i, bytes ->
            val now = times?.get(i) ?: 0L
            when (val p = FrameCodec.parse(bytes)) {
                is ParseResult.Rejected -> steps += "error:${p.error.wireName}"
                is ParseResult.IgnoredNonCritical -> steps += "ignored"
                is ParseResult.Parsed -> {
                    val frame = p.frame
                    if (frame is AckFrame) {
                        steps += if (tracker.onAck(frame.messageId) == AckOutcome.KNOWN) "ack:known" else "ack:unknown"
                    } else {
                        steps += when (val r = reassembler.accept(frame, now)) {
                            is MessageReassembler.Result.Partial -> "partial"
                            is MessageReassembler.Result.Complete -> { body = r.body; "complete" }
                            is MessageReassembler.Result.Rejected -> {
                                if (r.closeSession) closes += i
                                "error:${r.error.wireName}"
                            }
                            is MessageReassembler.Result.Cancelled -> if (r.discardedPartial) "cancelled" else "cancel-noop"
                            is MessageReassembler.Result.PeerAck -> "ack:routed-to-tracker"
                            is MessageReassembler.Result.PeerError -> "peer-error:${r.errorCode}:${r.relatedMessageId}"
                        }
                    }
                }
            }
        }

        val wantSteps = ex["steps"].list().map { it as String }
        if (steps != wantSteps) return "$id: steps $steps != $wantSteps"
        (ex["bodyHex"] as? String)?.let { want ->
            val got = body ?: return "$id: no complete message"
            if (!got.contentEquals(hex(want))) return "$id: reassembled body differs"
            if (got.size != ex["bodyLength"].int()) return "$id: body length ${got.size} != ${ex["bodyLength"]}"
        }
        ex["closeSessionSteps"]?.let { want ->
            if (closes != want.list().map { it.int() }) return "$id: closeSession steps $closes != $want"
        }
        ex["timedOutCount"]?.let { if (reassembler.timedOutCount != it.int()) return "$id: timedOutCount ${reassembler.timedOutCount}" }
        ex["frameSizes"]?.let { want ->
            if (frames.map { it.size } != want.list().map { it.int() }) return "$id: frame sizes ${frames.map { it.size }}"
        }
        if (ctx.containsKey("encode")) {
            val e = ctx["encode"].obj()
            val encoded = FrameCodec.encodeMessage(
                typeCode(e["type"] as String), e["flags"].int(), e["messageId"].int(), hex(e["bodyHex"] as String), e["maxAttValueBytes"].int(),
            )
            if (encoded.size != frames.size) return "$id: encoder produced ${encoded.size} frames, vector has ${frames.size}"
            encoded.forEachIndexed { i, f -> if (!f.contentEquals(frames[i])) return "$id: encoder frame $i differs from the hand-derived vector" }
        }
        return null
    }

    private fun encodeVector(v: Map<String, Any?>): String? {
        val id = idOf(v)
        val ex = v["expected"].obj()
        val e = v["context"].obj()["encode"].obj()
        return try {
            FrameCodec.encodeMessage(
                typeCode(e["type"] as String), e["flags"].int(), e["messageId"].int(), hex(e["bodyHex"] as String), e["maxAttValueBytes"].int(),
            )
            "$id: expected ${ex["error"]} but the encoder accepted the input"
        } catch (x: P2Exception) {
            if (x.error.wireName == ex["error"]) null else "$id: expected ${ex["error"]}, got ${x.error.wireName}"
        }
    }

    @Test
    fun P2F00_manifestIsWellFormedAndEveryVectorHasAFamilyTest() {
        assertEquals("wristbridge.p2-framing-vectors", manifest["schema"])
        assertEquals("draft-2", manifest["version"])
        val prefixes = listOf("F01-", "F02-", "F03-", "F04-", "F05-", "F06-", "F07-", "F10-", "F11-", "F12-")
        val ids = vectors.map { idOf(it) }
        assertEquals(ids.size, ids.toSet().size, "duplicate vector ids")
        for (v in vectors) {
            assertTrue(prefixes.any { idOf(v).startsWith(it) }, "${idOf(v)} has no family test")
            assertEquals("PROPUESTO", v["status"], idOf(v))
            assertTrue(v["origin"] == "a mano (Apple)" || v["origin"] == "a mano (Windows, revisado)", idOf(v))
            assertTrue(v.containsKey("bytesHex") || v.containsKey("framesHex") || v["schema"] == "p2.encode", idOf(v))
        }
        // The ten hand vectors received from Apple must all be present and attributed to Apple.
        val apple = listOf("F01-a", "F01-b", "F01-c", "F01-d", "F10-a", "F10-b", "F10-c", "F07-a", "F02-a", "F02-b")
        for (a in apple) assertEquals("a mano (Apple)", vectors.single { idOf(it) == a }["origin"], a)
    }

    @Test
    fun P2F01_crossExchangeAndControlFrames() = family("F01-")

    @Test
    fun P2F02_capacityPerDirection() = family("F02-")

    @Test
    fun P2F03_fragmentationBoundariesAndLengthRule() = family("F03-")

    @Test
    fun P2F04_frameOrder() = family("F04-")

    @Test
    fun P2F05_conflictingIdentifiers() = family("F05-")

    @Test
    fun P2F06_partialMessageLimit() = family("F06-")

    @Test
    fun P2F07_oversizeBeforeAllocation() = family("F07-")

    @Test
    fun P2F10_versioningAndMalformedHeaders() = family("F10-")

    @Test
    fun P2F11_stalePartial() = family("F11-")

    @Test
    fun P2F12_cancelAndInterleavedControlFrames() = family("F12-")
}
