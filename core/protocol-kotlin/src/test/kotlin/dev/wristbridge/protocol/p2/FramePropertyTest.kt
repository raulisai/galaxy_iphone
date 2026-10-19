package dev.wristbridge.protocol.p2

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Property-style round trips for the DRAFT P2 framing codec (deterministic seed, capacities 14..512).
 * These do not replace the hand-derived vectors (FrameVectorsTest): they only prove the codec is self-consistent,
 * never exceeds the ATT capacity and reassembles exactly. The codec is DRAFT; nothing here approves G1/G2.
 */
class FramePropertyTest {
    private val limits = ReassemblyLimits.uniform(1_000_000)

    /** Independent frame count: peel the first capacity (max-13), then max-9 per frame. */
    private fun expectedFrameCount(bodySize: Int, max: Int): Int {
        var remaining = bodySize
        var cap = max - 13
        var n = 0
        while (remaining > 0) {
            remaining -= cap
            cap = max - 9
            n++
        }
        return n
    }

    private fun sizesFor(max: Int, type: Int, random: Random): List<Int> {
        val first = max - 13
        val rest = max - 9
        val base = listOf(1, first - 1, first, first + 1, first + rest - 1, first + rest, first + rest + 1, first + 2 * rest, first + 2 * rest + 1)
        val randoms = List(3) { random.nextInt(1, 3001) }
        return (base + randoms).filter { it >= P2Wire.minBodyBytes(type) && it <= 4000 }.distinct()
    }

    private fun roundTrip(type: Int, flags: Int, messageId: Int, body: ByteArray, max: Int, known: Boolean) {
        val frames = FrameCodec.encodeMessage(type, flags, messageId, body, max)
        assertEquals(expectedFrameCount(body.size, max), frames.size, "frame count max=$max size=${body.size}")
        val reassembler = MessageReassembler(limits, if (known) max else null)
        var delivered: ByteArray? = null
        frames.forEachIndexed { i, bytes ->
            assertTrue(bytes.size <= max, "frame $i is ${bytes.size} > capacity $max")
            if (i < frames.size - 1) assertEquals(max, bytes.size, "non-final frame $i must be exactly full (max=$max)")
            val parsed = FrameCodec.parse(bytes)
            val frame = (parsed as? ParseResult.Parsed)?.frame as? FragmentFrame ?: error("frame $i did not parse: $parsed")
            assertEquals(i, frame.fragIndex)
            assertEquals(frames.size, frame.fragCount)
            assertEquals(messageId, frame.messageId)
            assertEquals(if (i == 0) body.size.toLong() else null, frame.totalLength)
            val r = reassembler.accept(frame, 0L)
            if (i < frames.size - 1) {
                assertTrue(r is MessageReassembler.Result.Partial, "frame $i of ${frames.size}: $r")
            } else {
                assertTrue(r is MessageReassembler.Result.Complete, "final frame: $r")
                delivered = (r as MessageReassembler.Result.Complete).body
            }
        }
        assertContentEquals(body, delivered, "reassembled body max=$max size=${body.size}")
        assertTrue(!reassembler.hasOpenMessage)
    }

    @Test
    fun P2F02_roundTripAllCapacitiesAndBoundarySizes() {
        val random = Random(0x5EED)
        var cases = 0
        for (max in 14..512) {
            for (type in listOf(P2Wire.TYPE_DATA, P2Wire.TYPE_HANDSHAKE)) {
                for (size in sizesFor(max, type, random)) {
                    val body = random.nextBytes(size)
                    val flags = if (random.nextBoolean()) P2Wire.FLAG_PRIORITY else 0
                    roundTrip(type, flags, random.nextInt(0, 0x10000), body, max, known = random.nextBoolean())
                    cases++
                }
            }
        }
        assertTrue(cases > 3000, "only $cases cases ran")
    }

    @Test
    fun P2F02_capacityBelow14IsRejectedByTheEncoder() {
        for (max in listOf(Int.MIN_VALUE, -1, 0, 1, 13)) {
            val e = assertFailsWith<P2Exception> { FrameCodec.encodeMessage(P2Wire.TYPE_DATA, 0, 1, ByteArray(24), max) }
            assertEquals(P2Error.CAPACITY_TOO_SMALL, e.error)
        }
        // 14 is the smallest legal capacity: 1 payload byte in the first frame, 5 in the others.
        assertEquals(6, FrameCodec.encodeMessage(P2Wire.TYPE_DATA, 0, 1, ByteArray(24), 14).size)
    }

    @Test
    fun P2F04_droppingOrDuplicatingAFrameNeverDeliversAMessage() {
        val random = Random(0xFA11)
        for (max in listOf(14, 20, 64, 185, 512)) {
            val body = random.nextBytes(maxOf(24, 3 * max))
            val frames = FrameCodec.encodeMessage(P2Wire.TYPE_DATA, 0, 7, body, max)
            assertTrue(frames.size >= 3)
            for (drop in frames.indices) {
                val got = feed(frames.filterIndexed { i, _ -> i != drop })
                assertTrue(got.none { it is MessageReassembler.Result.Complete }, "dropping $drop delivered a message (max=$max)")
            }
            for (dup in 0 until frames.size - 1) {
                val seq = frames.flatMapIndexed { i, f -> if (i == dup) listOf(f, f) else listOf(f) }
                val got = feed(seq)
                assertTrue(got.none { it is MessageReassembler.Result.Complete }, "duplicating $dup delivered a message (max=$max)")
            }
            // Duplicating the FINAL frame: the message was already delivered once, the copy is an orphan.
            val lastDup = feed(frames + frames.last())
            assertEquals(1, lastDup.count { it is MessageReassembler.Result.Complete })
            // Swapping two neighbours always breaks the order.
            for (i in 0 until frames.size - 1) {
                val swapped = frames.toMutableList().also { val t = it[i]; it[i] = it[i + 1]; it[i + 1] = t }
                assertTrue(feed(swapped).none { it is MessageReassembler.Result.Complete }, "swap $i delivered a message")
            }
        }
    }

    private fun feed(frames: List<ByteArray>): List<MessageReassembler.Result> {
        val reassembler = MessageReassembler(limits)
        val out = ArrayList<MessageReassembler.Result>()
        for (bytes in frames) {
            val parsed = FrameCodec.parse(bytes)
            val frame = (parsed as ParseResult.Parsed).frame
            out += reassembler.accept(frame, 0L)
        }
        return out
    }

    @Test
    fun P2F01_controlFramesRoundTrip() {
        val ack = (FrameCodec.parse(FrameCodec.encodeAck(0x1234)) as ParseResult.Parsed).frame
        assertEquals(AckFrame(0x1234), ack)
        val cancel = (FrameCodec.parse(FrameCodec.encodeCancel(0xFFFF)) as ParseResult.Parsed).frame
        assertEquals(CancelFrame(0xFFFF), cancel)
        val err = (FrameCodec.parse(FrameCodec.encodeError(P2ErrorCode.E_BAD_TAG, P2Wire.NO_RELATED_MESSAGE)) as ParseResult.Parsed).frame
        assertEquals(ErrorFrame(P2ErrorCode.E_BAD_TAG, 0xFFFF), err)
        assertEquals(9, FrameCodec.encodeAck(1).size)
        assertEquals(13, FrameCodec.encodeError(1, 1).size)
    }

    @Test
    fun P2F01_errorCodeTableMatchesSection71OfTheDraft() {
        // Typed by hand from section 7.1; not derived from the enum.
        val table = mapOf(
            0x0001 to "E_MALFORMED_FRAME", 0x0002 to "E_INCOMPATIBLE_FRAME", 0x0003 to "E_INCOMPATIBLE_MAJOR",
            0x0004 to "E_UNSUPPORTED_TYPE", 0x0005 to "E_OVERSIZE", 0x0006 to "E_LENGTH_MISMATCH",
            0x0007 to "E_SEQUENCE", 0x0008 to "E_TOO_MANY_PARTIALS", 0x0009 to "E_TIMEOUT",
            0x000A to "E_BAD_TAG", 0x000B to "E_BUSY", 0x000C to "E_CAPACITY_TOO_SMALL",
            0x0100 to "E_UNKNOWN_CAPABILITY", 0x0101 to "E_NOT_AUTHORIZED", 0x0102 to "E_EXPIRED",
            0x0103 to "E_OUTCOME_UNKNOWN", 0x0104 to "E_INVALID_PAYLOAD", 0x0200 to "E_IDENTITY_CHANGED",
        )
        assertEquals(table.size, P2Error.entries.size)
        for ((code, name) in table) {
            assertEquals(name, P2Error.fromCode(code)?.wireName)
            assertEquals(code, P2Error.fromWireName(name)?.code)
        }
        assertNull(P2Error.fromCode(0x7777))
        val closing = P2Error.entries.filter { it.closesSession }.map { it.wireName }.toSet()
        assertEquals(setOf("E_INCOMPATIBLE_FRAME", "E_INCOMPATIBLE_MAJOR", "E_UNSUPPORTED_TYPE"), closing)
    }

    @Test
    fun P2F07_noBufferIsAllocatedBeforeTheLimitCheck() {
        // A hostile first frame declaring 4 GiB-1 and carrying nothing must be refused with no state left behind.
        val reassembler = MessageReassembler(ReassemblyLimits.DEFAULT)
        val hostile = (FrameCodec.parse(byteArrayOf(1, 0x81.toByte(), 0, 0, 1, 0, 0, 0, 1, -1, -1, -1, -1)) as ParseResult.Parsed).frame
        val r = reassembler.accept(hostile, 0L)
        assertTrue(r is MessageReassembler.Result.Rejected && r.error == P2Error.OVERSIZE)
        assertTrue(!reassembler.hasOpenMessage)
    }

    @Test
    fun P2F01_outboundTrackerIgnoresUnknownAcksAndBoundsTheWindow() {
        val t = OutboundTracker(maxInFlight = 2)
        assertTrue(t.register(1))
        assertTrue(t.register(2))
        assertTrue(!t.register(3), "window full")
        assertTrue(!t.register(1), "duplicate id")
        assertEquals(AckOutcome.UNKNOWN, t.onAck(9))
        assertEquals(1, t.unknownAckCount)
        assertEquals(AckOutcome.KNOWN, t.onAck(1))
        assertEquals(AckOutcome.UNKNOWN, t.onAck(1))
        assertTrue(t.register(3))
        t.forget(2)
        assertEquals(setOf(3), t.inFlight)
    }
}
