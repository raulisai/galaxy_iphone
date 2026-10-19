package dev.wristbridge.protocol.p2.secure

import dev.wristbridge.protocol.p2.Frame
import dev.wristbridge.protocol.p2.FrameCodec
import dev.wristbridge.protocol.p2.MessageReassembler
import dev.wristbridge.protocol.p2.P2Error
import dev.wristbridge.protocol.p2.P2Exception
import dev.wristbridge.protocol.p2.P2Wire
import dev.wristbridge.protocol.p2.ParseResult

/**
 * Authenticated DATA transport after the handshake (draft sections 3.3 and 8.5).
 * Body = counter(u64 BE) || ciphertext || tag. AD = frameVersion || type || flags || messageId(u16) || totalLength(u32).
 * One message at a time in each direction (no interleaving). Each reconnection builds a new session.
 */
class SecureSession(
    private val keys: TransportKeys,
    private val maxAttValueBytes: Int,
    /** Inbound frame capacity when known; null infers it from the first frame (K1 rule), as the iPhone does. */
    knownInboundMaxAttValueBytes: Int? = maxAttValueBytes,
) {
    private val reassembler = MessageReassembler(knownMaxAttValueBytes = knownInboundMaxAttValueBytes)
    private var nextMessageId = FIRST_DATA_MESSAGE_ID

    /** Six-digit code to show on both screens at registration. */
    fun securityCode(): String = keys.securityCode()

    val peerStaticKey: ByteArray get() = keys.remoteStatic.copyOf()

    sealed interface Inbound {
        /** More frames needed, or a control frame with nothing for the app. */
        data object None : Inbound

        class Message(val messageId: Int, val json: Any?) : Inbound

        data class PeerAck(val messageId: Int) : Inbound

        data class PeerError(val code: Int, val relatedMessageId: Int) : Inbound
    }

    private fun ad(type: Int, flags: Int, messageId: Int, totalLength: Int): ByteArray = byteArrayOf(
        P2Wire.FRAME_VERSION.toByte(), type.toByte(), flags.toByte(),
        (messageId ushr 8).toByte(), messageId.toByte(),
        (totalLength ushr 24).toByte(), (totalLength ushr 16).toByte(), (totalLength ushr 8).toByte(), totalLength.toByte(),
    )

    /** Encrypts [json] (a Map/List/primitive tree) and returns the wire frames, in order. */
    fun send(json: Any?, flags: Int = 0): Pair<Int, List<ByteArray>> {
        val plain = StrictJson.encode(json)
        val id = nextMessageId
        nextMessageId = if (nextMessageId >= 0xFFFE) FIRST_DATA_MESSAGE_ID else nextMessageId + 1
        val total = 8 + plain.size + 16
        val counter = keys.send.counter
        val ct = keys.send.seal(ad(P2Wire.TYPE_DATA, flags, id, total), plain)
        val body = ByteArray(8 + ct.size)
        for (i in 0 until 8) body[i] = (counter ushr (56 - 8 * i)).toByte()
        System.arraycopy(ct, 0, body, 8, ct.size)
        return id to FrameCodec.encodeMessage(P2Wire.TYPE_DATA, flags, id, body, maxAttValueBytes)
    }

    fun ackFrame(messageId: Int): ByteArray = FrameCodec.encodeAck(messageId)

    /**
     * Feeds one received frame. A forged, replayed or reordered DATA message throws [P2Exception] (BAD_TAG) and leaves
     * the receive counter untouched; the caller closes the session.
     */
    fun receive(frameBytes: ByteArray, nowNanos: Long): Inbound {
        val frame: Frame = when (val p = FrameCodec.parse(frameBytes)) {
            is ParseResult.Parsed -> p.frame
            is ParseResult.Rejected -> throw P2Exception(p.error, p.detail)
            is ParseResult.IgnoredNonCritical -> return Inbound.None
        }
        return when (val r = reassembler.accept(frame, nowNanos)) {
            MessageReassembler.Result.Partial -> Inbound.None
            is MessageReassembler.Result.Rejected -> throw P2Exception(r.error, r.detail)
            is MessageReassembler.Result.Cancelled -> Inbound.None
            is MessageReassembler.Result.PeerAck -> Inbound.PeerAck(r.messageId)
            is MessageReassembler.Result.PeerError -> Inbound.PeerError(r.errorCode, r.relatedMessageId)
            is MessageReassembler.Result.Complete -> {
                if (r.type != P2Wire.TYPE_DATA) throw P2Exception(P2Error.UNSUPPORTED_TYPE, "only DATA after the handshake")
                val body = r.body
                if (body.size < P2Wire.MIN_DATA_BODY_BYTES) throw P2Exception(P2Error.BAD_TAG, "short body")
                var counter = 0L
                for (i in 0 until 8) counter = (counter shl 8) or (body[i].toLong() and 0xFF)
                if (counter != keys.receive.counter) throw P2Exception(P2Error.BAD_TAG, "unexpected counter")
                val plain = keys.receive.open(ad(r.type, r.flags, r.messageId, body.size), body.copyOfRange(8, body.size))
                Inbound.Message(r.messageId, StrictJson.parse(plain))
            }
        }
    }

    companion object {
        /** 1..3 are reserved for the handshake messages. */
        const val FIRST_DATA_MESSAGE_ID = 16
    }
}
