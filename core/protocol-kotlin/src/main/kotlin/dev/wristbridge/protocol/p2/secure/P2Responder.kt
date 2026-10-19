package dev.wristbridge.protocol.p2.secure

import dev.wristbridge.protocol.p2.Frame
import dev.wristbridge.protocol.p2.FrameCodec
import dev.wristbridge.protocol.p2.MessageReassembler
import dev.wristbridge.protocol.p2.P2Error
import dev.wristbridge.protocol.p2.P2Exception
import dev.wristbridge.protocol.p2.P2Wire
import dev.wristbridge.protocol.p2.ParseResult
import dev.wristbridge.protocol.p2.health.HealthSource
import dev.wristbridge.protocol.p2.health.HealthSync

/** Constants shared by both ends. NOT FROZEN (draft revision 3). */
object P2Profile {
    const val SERVICE = "6f06b8b1-21d9-4c65-9d7e-118880e36a01"
    const val PROFILE_REVISION = "p2-cand-1"

    /** Capabilities record (draft section 2.1): v1, protocol 1.0, frame 1, 8 KiB control, 64 KiB batch, 1 partial, 8 in flight, AEAD bit. */
    val CAPABILITIES: ByteArray = byteArrayOf(
        1, 1, 0, 1, 0x20, 0x00, 0x00, 0x40, 1, 8, 0x00, 0x00, 0x00, 0x01, 0, 0,
    )

    /** Handshake prologue (draft section 8.3): label, profile revision, service UUID and the Capabilities record read. */
    fun prologue(capabilities: ByteArray = CAPABILITIES): ByteArray =
        "WB-P2|1.0|$PROFILE_REVISION|$SERVICE|".toByteArray(Charsets.UTF_8) + capabilities
}

/** What the responder needs from the app: identity, registration window and the health source. */
interface P2Policy {
    /** User opened "register iPhone" on the watch and the window has not expired. */
    fun registrationOpen(): Boolean
    fun pinnedPeer(): ByteArray?
    fun pin(peerStaticKey: ByteArray)
    val health: HealthSource

    /** Stable pseudonymous id of this watch for the iPhone ledger (ASCII, 1-64). */
    val deviceId: String
}

interface P2Listener {
    /** Registration: show this code on the watch; the user answers through [P2Responder.confirmCode]. */
    fun onSecurityCode(code: String)
    fun onReady(sessionId: String, registered: Boolean)
    fun onClosed(reason: String)
    fun onDiagnostic(text: String)

    /** The iPhone asked the watch to ring/vibrate ("find.watch"). */
    fun onFindWatch() = Unit

    /** The iPhone confirmed receipt of a request this watch sent (message-level ACK, not a domain result). */
    fun onPeerAck(messageId: Int) = Unit

    /** The iPhone answered a request this watch sent. [error] is the wire error name or null. */
    fun onResponse(capability: String, requestId: Long, error: String?) = Unit
}

/**
 * Watch-side (responder) state machine of the own channel. Pure logic: the caller feeds the bytes written to the
 * Requests characteristic and sends the returned frames, in order, through Events notifications.
 * Diagnostics never include keys, codes' secrets, health values or counters.
 */
class P2Responder(
    private val localKey: X25519Key,
    private val policy: P2Policy,
    private val maxAttValueBytes: Int,
    private val listener: P2Listener,
) {
    private enum class State { AWAIT_M1, AWAIT_M3, AWAIT_CONFIRM, READY, CLOSED }

    private var state = State.AWAIT_M1
    // Inbound capacity is inferred from the first frame: the iPhone writes with its own maximumWriteValueLength.
    private val handshakeAssembler = MessageReassembler(knownMaxAttValueBytes = null)
    private val earlyData = ArrayDeque<ByteArray>()
    private val noise = NoiseXX(false, localKey, P2Profile.prologue())
    private var keys: TransportKeys? = null
    private var session: SecureSession? = null

    val isReady: Boolean get() = state == State.READY
    val isClosed: Boolean get() = state == State.CLOSED

    fun onWrite(bytes: ByteArray, nowNanos: Long): List<ByteArray> {
        if (state == State.CLOSED) return emptyList()
        return try {
            handle(bytes, nowNanos)
        } catch (e: P2Exception) {
            fail(e.error)
        }
    }

    private fun fail(error: P2Error, related: Int = P2Wire.NO_RELATED_MESSAGE): List<ByteArray> {
        val wasOpen = state != State.CLOSED
        state = State.CLOSED
        if (wasOpen) listener.onClosed(error.wireName)
        return listOf(FrameCodec.encodeError(error.code, related))
    }

    private fun handle(bytes: ByteArray, now: Long): List<ByteArray> {
        if (state == State.READY) return handleData(bytes, now)
        if (state == State.AWAIT_CONFIRM) {
            if (earlyData.size >= MAX_EARLY_FRAMES) throw P2Exception(P2Error.BUSY, "too many frames before confirmation")
            earlyData += bytes.copyOf()
            return emptyList()
        }
        val frame: Frame = when (val p = FrameCodec.parse(bytes)) {
            is ParseResult.Parsed -> p.frame
            is ParseResult.Rejected -> throw P2Exception(p.error, p.detail)
            is ParseResult.IgnoredNonCritical -> return emptyList()
        }
        return when (val r = handshakeAssembler.accept(frame, now)) {
            MessageReassembler.Result.Partial -> emptyList()
            is MessageReassembler.Result.Rejected -> throw P2Exception(r.error, r.detail)
            is MessageReassembler.Result.Complete -> handshakeMessage(r)
            else -> emptyList()
        }
    }

    private fun handshakeMessage(m: MessageReassembler.Result.Complete): List<ByteArray> {
        if (m.type != P2Wire.TYPE_HANDSHAKE) throw P2Exception(P2Error.SEQUENCE, "handshake expected")
        return when (state) {
            State.AWAIT_M1 -> {
                if (m.messageId != 1) throw P2Exception(P2Error.SEQUENCE, "message 1 expected")
                noise.readMessage(m.body)
                val reply = noise.writeMessage()
                state = State.AWAIT_M3
                FrameCodec.encodeMessage(P2Wire.TYPE_HANDSHAKE, 0, 2, reply, maxAttValueBytes)
            }
            State.AWAIT_M3 -> {
                if (m.messageId != 3) throw P2Exception(P2Error.SEQUENCE, "message 3 expected")
                noise.readMessage(m.body)
                val t = noise.split()
                keys = t
                val pinned = policy.pinnedPeer()
                when {
                    pinned != null && pinned.contentEquals(t.remoteStatic) -> becomeReady(t, registered = false)
                    pinned != null -> throw P2Exception(P2Error.IDENTITY_CHANGED, "peer key differs from the pinned one")
                    policy.registrationOpen() -> {
                        state = State.AWAIT_CONFIRM
                        listener.onSecurityCode(t.securityCode())
                        emptyList()
                    }
                    else -> throw P2Exception(P2Error.NOT_AUTHORIZED, "registration is closed")
                }
            }
            else -> throw P2Exception(P2Error.SEQUENCE, "unexpected handshake message")
        }
    }

    private fun becomeReady(t: TransportKeys, registered: Boolean): List<ByteArray> {
        session = SecureSession(t, maxAttValueBytes, knownInboundMaxAttValueBytes = null)
        state = State.READY
        listener.onReady(sessionId(t), registered)
        // DATA the iPhone sent while the user was still comparing the code is replayed in order, not lost.
        val out = ArrayList<ByteArray>()
        while (earlyData.isNotEmpty()) out += handleData(earlyData.removeFirst(), System.nanoTime())
        return out
    }

    private fun sessionId(t: TransportKeys): String = t.handshakeHash.take(8).joinToString("") { "%02x".format(it) }

    /** The user compared the code on both screens. Only a match stores the iPhone identity. */
    fun confirmCode(match: Boolean): List<ByteArray> {
        if (state != State.AWAIT_CONFIRM) return emptyList()
        val t = keys!!
        if (!match || !policy.registrationOpen()) {
            state = State.CLOSED
            listener.onClosed("registro cancelado")
            return listOf(FrameCodec.encodeError(P2Error.NOT_AUTHORIZED.code, P2Wire.NO_RELATED_MESSAGE))
        }
        policy.pin(t.remoteStatic)
        return becomeReady(t, registered = true)
    }

    private var nextRequestId = 1L

    private companion object {
        const val MAX_EARLY_FRAMES = 64
    }

    /** Watch -> iPhone request (for example "find.phone"). Returns the request id and the frames to notify; empty if not ready. */
    fun request(capability: String, payload: Map<String, Any?> = emptyMap()): Pair<Long, List<ByteArray>> {
        val s = session
        if (state != State.READY || s == null) return 0L to emptyList()
        val id = nextRequestId++
        return id to s.send(mapOf("kind" to "req", "requestId" to id, "capability" to capability, "payload" to payload)).second
    }

    fun close(reason: String) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        listener.onClosed(reason)
    }

    private fun handleData(bytes: ByteArray, now: Long): List<ByteArray> {
        val s = session!!
        val out = ArrayList<ByteArray>()
        when (val inbound = s.receive(bytes, now)) {
            SecureSession.Inbound.None -> Unit
            is SecureSession.Inbound.PeerAck -> listener.onPeerAck(inbound.messageId)
            is SecureSession.Inbound.PeerError -> listener.onDiagnostic("el iPhone informó un error")
            is SecureSession.Inbound.Message -> {
                out += s.ackFrame(inbound.messageId)
                val env = inbound.json as? Map<*, *>
                if (env?.get("kind") == "res") {
                    listener.onResponse(env["capability"] as? String ?: "", env["requestId"] as? Long ?: 0L, env["error"] as? String)
                } else {
                    out += s.send(respond(inbound.json)).second
                }
            }
        }
        return out
    }

    private fun respond(json: Any?): Map<String, Any?> {
        val env = json as? Map<*, *>
        val requestId = env?.get("requestId") as? Long ?: 0L
        val capability = env?.get("capability") as? String ?: ""
        return try {
            when (capability) {
                "hello" -> mapOf(
                    "kind" to "res", "requestId" to requestId, "capability" to "hello",
                    "payload" to mapOf(
                        "protocolMajor" to 1L, "protocolMinor" to 0L,
                        "sessionId" to sessionId(keys!!),
                    ),
                )
                HealthSync.CAPABILITY -> HealthSync.respond(json, policy.health, policy.deviceId)
                "find.watch" -> {
                    listener.onFindWatch()
                    mapOf("kind" to "res", "requestId" to requestId, "capability" to capability, "payload" to mapOf("status" to "requested"))
                }
                else -> throw P2Exception(P2Error.UNKNOWN_CAPABILITY, "capability")
            }
        } catch (e: P2Exception) {
            if (e.error == P2Error.BAD_TAG) throw e
            listener.onDiagnostic("petición rechazada ${e.error.wireName}")
            mapOf("kind" to "res", "requestId" to requestId, "capability" to capability, "error" to e.error.wireName)
        }
    }
}
