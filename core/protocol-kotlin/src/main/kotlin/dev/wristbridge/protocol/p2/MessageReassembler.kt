package dev.wristbridge.protocol.p2

// DRAFT (contracts/proposals/p2-channel-draft.md, revision 2, sections 3.5 and 3.6). NOT FROZEN.
// Framing only: no crypto, no AEAD tag/counter checks (those belong to the session layer, which does not exist yet).
// Time is passed in as monotonic nanoseconds (U20); there is no clock access and no threading here: callers must
// serialise calls per direction.

/**
 * Size limits applied to the message `totalLength` (wire body, so a DATA body includes counter 8 + tag 16)
 * BEFORE any buffer is allocated (F07). The frame type is the only classification available before decryption:
 * HANDSHAKE has its own limit, DATA with the PRIORITY flag uses the control-lane limit, other DATA the batch limit.
 */
data class ReassemblyLimits(
    val maxDataBodyBytes: Long,
    val maxPriorityBodyBytes: Long,
    val maxHandshakeBodyBytes: Long,
    /** No progress for this long drops the partial message (Doc/01 section 4: 15 s). */
    val partialTimeoutNanos: Long = 15_000_000_000L,
) {
    fun limitFor(type: Int, flags: Int): Long = when {
        type == P2Wire.TYPE_HANDSHAKE -> maxHandshakeBodyBytes
        (flags and P2Wire.FLAG_PRIORITY) != 0 -> maxPriorityBodyBytes
        else -> maxDataBodyBytes
    }

    companion object {
        /** Control 8 KiB and batch 64 KiB of plaintext plus 24 bytes AEAD overhead; handshake 1 KiB (assumption). */
        val DEFAULT = ReassemblyLimits(
            maxDataBodyBytes = 64L * 1024 + 24,
            maxPriorityBodyBytes = 8L * 1024 + 24,
            maxHandshakeBodyBytes = 1024,
        )

        fun uniform(maxBodyBytes: Long) = ReassemblyLimits(maxBodyBytes, maxBodyBytes, maxBodyBytes)
    }
}

/**
 * Receive side of one direction. v1 rule (Q-A3 closed without interleaving): at most ONE open DATA/HANDSHAKE message;
 * the frames of a message are contiguous. ACK, ERROR and CANCEL are single frames that may appear between the frames
 * of an open message without touching it (except CANCEL of that same message).
 *
 * K1 rule (option A), verified per frame:
 * - first frame: payload 1..max-13; when the message needs several frames it is exactly the capacity (max-13);
 *   the capacity is inferred from it (max = payload+13) unless [knownMaxAttValueBytes] is given;
 * - every non-final frame carries exactly firstPayload+4 (= max-9) bytes, else E_MALFORMED_FRAME;
 * - the final frame carries 1..max-9 bytes, never empty, and the sum must equal totalLength (E_LENGTH_MISMATCH);
 * - fragCount must equal the count derived from totalLength and the first payload (E_LENGTH_MISMATCH).
 */
class MessageReassembler(
    private val limits: ReassemblyLimits = ReassemblyLimits.DEFAULT,
    private val knownMaxAttValueBytes: Int? = null,
) {
    sealed interface Result {
        /** Fragment accepted; the message is not complete yet. */
        data object Partial : Result

        /** Message complete. Delivered only here; partial data is never exposed. */
        class Complete(val type: Int, val flags: Int, val messageId: Int, data: ByteArray) : Result {
            private val bytes = data
            val body: ByteArray get() = bytes.copyOf()
            val bodyLength: Int get() = bytes.size
        }

        data class Rejected(val error: P2Error, val closeSession: Boolean, val detail: String) : Result

        /** CANCEL processed. [discardedPartial] is false when it matched nothing open (idempotent no-op). */
        data class Cancelled(val messageId: Int, val discardedPartial: Boolean) : Result

        /** ACK frames are not reassembly state; the caller routes them to an [OutboundTracker]. */
        data class PeerAck(val messageId: Int) : Result

        /** The peer reported a transport error. */
        data class PeerError(val errorCode: Int, val relatedMessageId: Int) : Result
    }

    private class OpenMessage(
        val type: Int,
        val flags: Int,
        val messageId: Int,
        val fragCount: Int,
        val totalLength: Int,
        val firstPayload: Int,
        val buffer: ByteArray,
        var received: Int,
        var nextIndex: Int,
        var lastProgressNanos: Long,
    )

    private var partial: OpenMessage? = null

    /** Partials dropped by the 15 s rule since creation (diagnostic counter, no content). */
    var timedOutCount: Int = 0
        private set

    val hasOpenMessage: Boolean get() = partial != null

    init {
        require(knownMaxAttValueBytes == null || knownMaxAttValueBytes >= P2Wire.MIN_ATT_VALUE_BYTES)
    }

    /** Drops a stale partial. Returns true if one was dropped. Call from a timer or let [accept] do it. */
    fun expire(nowNanos: Long): Boolean {
        val p = partial ?: return false
        if (nowNanos - p.lastProgressNanos >= limits.partialTimeoutNanos) {
            partial = null
            timedOutCount++
            return true
        }
        return false
    }

    /** Drops everything (session closed, link lost). */
    fun reset() {
        partial = null
    }

    fun accept(frame: Frame, nowNanos: Long): Result {
        expire(nowNanos)
        return when (frame) {
            is AckFrame -> Result.PeerAck(frame.messageId)
            is ErrorFrame -> Result.PeerError(frame.errorCode, frame.relatedMessageId)
            is CancelFrame -> cancel(frame.messageId)
            is FragmentFrame -> fragment(frame, nowNanos)
        }
    }

    private fun cancel(messageId: Int): Result {
        val p = partial
        if (p != null && p.messageId == messageId) {
            partial = null
            return Result.Cancelled(messageId, true)
        }
        return Result.Cancelled(messageId, false)
    }

    private fun reject(error: P2Error, detail: String, closeSession: Boolean = error.closesSession, discard: Boolean = false): Result {
        if (discard) partial = null
        return Result.Rejected(error, closeSession, detail)
    }

    private fun fragment(f: FragmentFrame, now: Long): Result {
        val open = partial
        if (f.fragIndex == 0) {
            if (open != null) {
                if (open.messageId == f.messageId) {
                    val conflict = f.fragCount != open.fragCount || f.totalLength != open.totalLength.toLong() ||
                        f.type != open.type || f.flags != open.flags
                    // Same id restarted: either a plain duplicate (discard) or an F05 conflict (discard and close).
                    return reject(P2Error.SEQUENCE, if (conflict) "conflicting restart of message" else "duplicate first frame", closeSession = conflict, discard = true)
                }
                // v1 has no interleaving: the open partial is kept, the new message is refused (no silent eviction, F06).
                return reject(P2Error.TOO_MANY_PARTIALS, "another message is open")
            }
            return start(f, now)
        }
        if (open == null || open.messageId != f.messageId) {
            // Never touch an unrelated open partial.
            return reject(P2Error.SEQUENCE, "continuation without an open message")
        }
        if (f.fragCount != open.fragCount || f.type != open.type || f.flags != open.flags) {
            return reject(P2Error.SEQUENCE, "fragCount/type/flags conflict", closeSession = true, discard = true)
        }
        if (f.fragIndex != open.nextIndex) {
            return reject(P2Error.SEQUENCE, "expected fragIndex ${open.nextIndex}, got ${f.fragIndex}", discard = true)
        }
        val n = f.payload.size
        val isFinal = f.fragIndex == open.fragCount - 1
        val fullRest = open.firstPayload + P2Wire.TOTAL_LENGTH_BYTES
        if (n == 0) return reject(P2Error.MALFORMED_FRAME, "empty fragment", discard = true)
        if (!isFinal) {
            if (n != fullRest) {
                return reject(P2Error.MALFORMED_FRAME, "non-final frame has $n bytes, must be exactly $fullRest", discard = true)
            }
            if (open.received.toLong() + n >= open.totalLength) {
                return reject(P2Error.LENGTH_MISMATCH, "payload reaches totalLength before the final frame", discard = true)
            }
        } else {
            if (n > fullRest) {
                return reject(P2Error.MALFORMED_FRAME, "final frame has $n bytes, capacity is $fullRest", discard = true)
            }
            if (open.received.toLong() + n != open.totalLength.toLong()) {
                return reject(P2Error.LENGTH_MISMATCH, "payload sum differs from totalLength", discard = true)
            }
        }
        System.arraycopy(f.payload, 0, open.buffer, open.received, n)
        open.received += n
        open.nextIndex++
        open.lastProgressNanos = now
        if (!isFinal) return Result.Partial
        partial = null
        return Result.Complete(open.type, open.flags, open.messageId, open.buffer)
    }

    private fun start(f: FragmentFrame, now: Long): Result {
        val total = f.totalLength ?: return reject(P2Error.MALFORMED_FRAME, "first frame without totalLength")
        // 1) size limit first, before anything is allocated (F07).
        if (total > limits.limitFor(f.type, f.flags) || total > Int.MAX_VALUE) {
            return reject(P2Error.OVERSIZE, "totalLength $total exceeds the limit for the type")
        }
        if (total < P2Wire.minBodyBytes(f.type)) {
            return reject(P2Error.MALFORMED_FRAME, "totalLength $total below the minimum for the type")
        }
        // 2) K1 rules on the first frame.
        val p0 = f.payload.size
        if (p0 == 0) return reject(P2Error.MALFORMED_FRAME, "empty fragment")
        val known = knownMaxAttValueBytes
        if (known != null) {
            val cap = P2Wire.firstPayloadCapacity(known)
            if (p0 > cap) return reject(P2Error.MALFORMED_FRAME, "first payload $p0 exceeds capacity $cap")
            if (f.fragCount > 1 && p0 != cap) {
                return reject(P2Error.MALFORMED_FRAME, "first frame of a multi-frame message must carry exactly $cap bytes")
            }
        }
        if (p0 > total) return reject(P2Error.LENGTH_MISMATCH, "first payload larger than totalLength")
        if (P2Wire.fragmentCountFor(total, p0) != f.fragCount.toLong()) {
            return reject(P2Error.LENGTH_MISMATCH, "fragCount ${f.fragCount} inconsistent with totalLength $total")
        }
        if (f.fragCount == 1) {
            return Result.Complete(f.type, f.flags, f.messageId, f.payload)
        }
        // 3) allocate only now: bounded by the limit checked above.
        val buffer = ByteArray(total.toInt())
        System.arraycopy(f.payload, 0, buffer, 0, p0)
        partial = OpenMessage(f.type, f.flags, f.messageId, f.fragCount, total.toInt(), p0, buffer, p0, 1, now)
        return Result.Partial
    }
}

/** Result of matching a peer ACK against messages this side sent. */
enum class AckOutcome { KNOWN, UNKNOWN }

/**
 * Sender-side bookkeeping of messages awaiting ACK (window = maxInflightMessages, proposal 8). An ACK for a message
 * that is not in flight (duplicate, late, never sent) is ignored and counted: no ERROR, no session close.
 */
class OutboundTracker(private val maxInFlight: Int = 8) {
    private val pending = LinkedHashSet<Int>()

    var unknownAckCount: Int = 0
        private set

    val inFlight: Set<Int> get() = pending.toSet()

    /** Returns false (and registers nothing) if the window is full or the id is already in flight. */
    fun register(messageId: Int): Boolean {
        if (pending.size >= maxInFlight || messageId in pending) return false
        pending += messageId
        return true
    }

    fun onAck(messageId: Int): AckOutcome {
        if (pending.remove(messageId)) return AckOutcome.KNOWN
        unknownAckCount++
        return AckOutcome.UNKNOWN
    }

    /** Local CANCEL or expiry: the message no longer waits for an ACK. */
    fun forget(messageId: Int) {
        pending.remove(messageId)
    }
}
