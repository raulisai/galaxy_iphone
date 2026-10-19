package dev.wristbridge.protocol.p2

// DRAFT (contracts/proposals/p2-channel-draft.md, revision 2, sections 3.2 and 3.5). NOT FROZEN.
// Framing only: pure functions, no Android, no crypto, no keys, no GATT access. Inputs are never mutated.
// The test vectors in test/fixtures/shared/p2-framing-vectors.json are derived by hand; this codec is the thing
// under test and must never be used to produce expected values.

/** Wire constants of the draft framing. All multi-byte integers are big-endian. */
object P2Wire {
    const val FRAME_VERSION = 0x01

    /** frameVersion(1) type(1) flags(1) messageId(2) fragIndex(2) fragCount(2). */
    const val HEADER_BYTES = 9

    /** First DATA/HANDSHAKE frame of a message carries a u32 totalLength after the header. */
    const val TOTAL_LENGTH_BYTES = 4
    const val FIRST_FRAME_OVERHEAD = HEADER_BYTES + TOTAL_LENGTH_BYTES // 13

    /** Below this ATT value capacity the channel is not opened (E_CAPACITY_TOO_SMALL): 13 overhead + 1 payload byte. */
    const val MIN_ATT_VALUE_BYTES = FIRST_FRAME_OVERHEAD + 1 // 14

    const val TYPE_DATA = 0x81
    const val TYPE_ACK = 0x82
    const val TYPE_ERROR = 0x83
    const val TYPE_CANCEL = 0x84
    const val TYPE_HANDSHAKE = 0x85
    const val TYPE_CRITICAL_BIT = 0x80

    const val FLAG_PRIORITY = 0x01
    const val RESERVED_FLAG_MASK = 0xFE

    /** ERROR frames are exactly header + u16 errorCode + u16 relatedMessageId. */
    const val ERROR_FRAME_BYTES = HEADER_BYTES + 4
    const val NO_RELATED_MESSAGE = 0xFFFF

    /** Section 3.5: a DATA message body is at least counter(8) + tag(16). Applied here as a framing sanity floor. */
    const val MIN_DATA_BODY_BYTES = 24L
    const val MIN_HANDSHAKE_BODY_BYTES = 1L

    fun minBodyBytes(type: Int): Long = if (type == TYPE_DATA) MIN_DATA_BODY_BYTES else MIN_HANDSHAKE_BODY_BYTES

    /** Payload bytes of the first frame of a message for an ATT value capacity. */
    fun firstPayloadCapacity(maxAttValueBytes: Int): Int = maxAttValueBytes - FIRST_FRAME_OVERHEAD

    /** Payload bytes of every following frame. */
    fun nextPayloadCapacity(maxAttValueBytes: Int): Int = maxAttValueBytes - HEADER_BYTES

    /**
     * Number of frames of a message whose first frame carries [firstPayload] bytes (rule K1/A): every following
     * non-final frame carries exactly [firstPayload] + 4 bytes (the first frame has 4 bytes more header).
     */
    fun fragmentCountFor(totalLength: Long, firstPayload: Int): Long {
        if (totalLength <= firstPayload) return 1
        val rest = firstPayload.toLong() + (TOTAL_LENGTH_BYTES)
        return 1 + (totalLength - firstPayload + rest - 1) / rest
    }
}

/** A parsed frame. Immutable: payload arrays are private copies. */
sealed interface Frame {
    val messageId: Int
}

/** DATA or HANDSHAKE frame: part of a message that may span several frames. */
sealed interface FragmentFrame : Frame {
    val type: Int
    val flags: Int
    val fragIndex: Int
    val fragCount: Int

    /** Present only on the first frame (fragIndex == 0). */
    val totalLength: Long?
    val payload: ByteArray
}

@Suppress("ArrayInDataClass")
data class DataFrame(
    override val messageId: Int,
    override val flags: Int,
    override val fragIndex: Int,
    override val fragCount: Int,
    override val totalLength: Long?,
    override val payload: ByteArray,
) : FragmentFrame {
    override val type: Int get() = P2Wire.TYPE_DATA
}

@Suppress("ArrayInDataClass")
data class HandshakeFrame(
    override val messageId: Int,
    override val flags: Int,
    override val fragIndex: Int,
    override val fragCount: Int,
    override val totalLength: Long?,
    override val payload: ByteArray,
) : FragmentFrame {
    override val type: Int get() = P2Wire.TYPE_HANDSHAKE
}

/** Acknowledges a reassembled and validated message (second confirmation of Doc/01 section 5). No body. */
data class AckFrame(override val messageId: Int) : Frame

/** Transport-level error. The header messageId is always 0. */
data class ErrorFrame(val errorCode: Int, val relatedMessageId: Int) : Frame {
    override val messageId: Int get() = 0
}

/** Cancels [messageId]; idempotent. No body. */
data class CancelFrame(override val messageId: Int) : Frame

sealed interface ParseResult {
    data class Parsed(val frame: Frame) : ParseResult

    /** The frame violates the draft; [error] is the code to report. */
    data class Rejected(val error: P2Error, val detail: String) : ParseResult

    /** Unknown non-critical type (bit 7 clear): discard and count, never an error. */
    data class IgnoredNonCritical(val type: Int) : ParseResult
}

object FrameCodec {
    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    private fun reject(error: P2Error, detail: String): ParseResult = ParseResult.Rejected(error, detail)

    /**
     * Parses exactly one ATT value as one frame. Validation order (documented in the draft, section 3.5):
     * empty -> version -> truncated header -> type -> reserved flags -> fragCount/fragIndex -> per-type shape.
     * Payload emptiness, totalLength limits and the K1 length rules need message state and live in
     * [MessageReassembler].
     */
    fun parse(bytes: ByteArray): ParseResult {
        if (bytes.isEmpty()) return reject(P2Error.MALFORMED_FRAME, "empty value")
        if (u8(bytes, 0) != P2Wire.FRAME_VERSION) {
            return reject(P2Error.INCOMPATIBLE_FRAME, "frameVersion ${u8(bytes, 0)}")
        }
        if (bytes.size < P2Wire.HEADER_BYTES) return reject(P2Error.MALFORMED_FRAME, "header truncated: ${bytes.size} bytes")
        val type = u8(bytes, 1)
        if ((type and P2Wire.TYPE_CRITICAL_BIT) == 0) return ParseResult.IgnoredNonCritical(type)
        if (type < P2Wire.TYPE_DATA || type > P2Wire.TYPE_HANDSHAKE) {
            return reject(P2Error.UNSUPPORTED_TYPE, "critical type 0x%02X".format(type))
        }
        val flags = u8(bytes, 2)
        if ((flags and P2Wire.RESERVED_FLAG_MASK) != 0) return reject(P2Error.MALFORMED_FRAME, "reserved flag bits set")
        val messageId = u16(bytes, 3)
        val fragIndex = u16(bytes, 5)
        val fragCount = u16(bytes, 7)
        if (fragCount == 0) return reject(P2Error.MALFORMED_FRAME, "fragCount is 0")
        if (fragIndex >= fragCount) return reject(P2Error.MALFORMED_FRAME, "fragIndex $fragIndex >= fragCount $fragCount")

        return when (type) {
            P2Wire.TYPE_ACK, P2Wire.TYPE_CANCEL -> {
                if (fragIndex != 0 || fragCount != 1) return reject(P2Error.MALFORMED_FRAME, "control frame must be single")
                if (bytes.size != P2Wire.HEADER_BYTES) return reject(P2Error.MALFORMED_FRAME, "control frame must have no body")
                ParseResult.Parsed(if (type == P2Wire.TYPE_ACK) AckFrame(messageId) else CancelFrame(messageId))
            }
            P2Wire.TYPE_ERROR -> {
                if (fragIndex != 0 || fragCount != 1) return reject(P2Error.MALFORMED_FRAME, "ERROR must be single")
                if (messageId != 0) return reject(P2Error.MALFORMED_FRAME, "ERROR header messageId must be 0")
                if (bytes.size != P2Wire.ERROR_FRAME_BYTES) return reject(P2Error.MALFORMED_FRAME, "ERROR body must be 4 bytes")
                ParseResult.Parsed(ErrorFrame(errorCode = u16(bytes, 9), relatedMessageId = u16(bytes, 11)))
            }
            else -> { // DATA or HANDSHAKE
                val first = fragIndex == 0
                val headerBytes = if (first) P2Wire.FIRST_FRAME_OVERHEAD else P2Wire.HEADER_BYTES
                if (bytes.size < headerBytes) return reject(P2Error.MALFORMED_FRAME, "header truncated: ${bytes.size} bytes")
                val total = if (first) u32(bytes, 9) else null
                val payload = bytes.copyOfRange(headerBytes, bytes.size)
                val frame: Frame = if (type == P2Wire.TYPE_DATA) {
                    DataFrame(messageId, flags, fragIndex, fragCount, total, payload)
                } else {
                    HandshakeFrame(messageId, flags, fragIndex, fragCount, total, payload)
                }
                ParseResult.Parsed(frame)
            }
        }
    }

    /**
     * Splits a message body into frames for a direction whose ATT value capacity is [maxAttValueBytes] (rule K1/A):
     * the first frame carries min(body, max-13) bytes, every following frame min(rest, max-9), so each non-final
     * frame is exactly full for its position and the final frame carries the remainder (never empty).
     *
     * @throws P2Exception [P2Error.CAPACITY_TOO_SMALL] if capacity < 14, [P2Error.OVERSIZE] if more than 65535 frames
     * would be needed. Other argument mistakes throw [IllegalArgumentException].
     */
    fun encodeMessage(type: Int, flags: Int, messageId: Int, body: ByteArray, maxAttValueBytes: Int): List<ByteArray> {
        require(type == P2Wire.TYPE_DATA || type == P2Wire.TYPE_HANDSHAKE) { "not a fragmentable type" }
        require((flags and P2Wire.RESERVED_FLAG_MASK) == 0 && flags >= 0) { "reserved flag bits" }
        require(messageId in 0..0xFFFF) { "messageId out of u16" }
        if (maxAttValueBytes < P2Wire.MIN_ATT_VALUE_BYTES) {
            throw P2Exception(P2Error.CAPACITY_TOO_SMALL, "maxAttValueBytes=$maxAttValueBytes < ${P2Wire.MIN_ATT_VALUE_BYTES}")
        }
        require(body.size.toLong() >= P2Wire.minBodyBytes(type)) { "body shorter than the minimum for the type" }

        val firstCap = P2Wire.firstPayloadCapacity(maxAttValueBytes)
        val nextCap = P2Wire.nextPayloadCapacity(maxAttValueBytes)
        val count = P2Wire.fragmentCountFor(body.size.toLong(), firstCap)
        if (count > 0xFFFF) throw P2Exception(P2Error.OVERSIZE, "needs $count frames")

        val frames = ArrayList<ByteArray>(count.toInt())
        var offset = 0
        for (i in 0 until count.toInt()) {
            val cap = if (i == 0) firstCap else nextCap
            val n = minOf(cap, body.size - offset)
            val headerBytes = if (i == 0) P2Wire.FIRST_FRAME_OVERHEAD else P2Wire.HEADER_BYTES
            val f = ByteArray(headerBytes + n)
            f[0] = P2Wire.FRAME_VERSION.toByte()
            f[1] = type.toByte()
            f[2] = flags.toByte()
            putU16(f, 3, messageId)
            putU16(f, 5, i)
            putU16(f, 7, count.toInt())
            if (i == 0) putU32(f, 9, body.size.toLong())
            System.arraycopy(body, offset, f, headerBytes, n)
            offset += n
            frames += f
        }
        check(offset == body.size)
        return frames
    }

    fun encodeAck(messageId: Int): ByteArray = encodeControl(P2Wire.TYPE_ACK, messageId)

    fun encodeCancel(messageId: Int): ByteArray = encodeControl(P2Wire.TYPE_CANCEL, messageId)

    /** ERROR frame: header messageId = 0, then u16 errorCode and u16 relatedMessageId (0xFFFF if none). */
    fun encodeError(errorCode: Int, relatedMessageId: Int): ByteArray {
        require(errorCode in 0..0xFFFF && relatedMessageId in 0..0xFFFF)
        val f = ByteArray(P2Wire.ERROR_FRAME_BYTES)
        writeControlHeader(f, P2Wire.TYPE_ERROR, 0)
        putU16(f, 9, errorCode)
        putU16(f, 11, relatedMessageId)
        return f
    }

    private fun encodeControl(type: Int, messageId: Int): ByteArray {
        require(messageId in 0..0xFFFF)
        val f = ByteArray(P2Wire.HEADER_BYTES)
        writeControlHeader(f, type, messageId)
        return f
    }

    private fun writeControlHeader(f: ByteArray, type: Int, messageId: Int) {
        f[0] = P2Wire.FRAME_VERSION.toByte()
        f[1] = type.toByte()
        f[2] = 0
        putU16(f, 3, messageId)
        putU16(f, 5, 0)
        putU16(f, 7, 1)
    }
}
