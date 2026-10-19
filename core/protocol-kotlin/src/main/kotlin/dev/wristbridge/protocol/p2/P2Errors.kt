package dev.wristbridge.protocol.p2

// DRAFT (contracts/proposals/p2-channel-draft.md, revision 2). NOT FROZEN. Framing only: no crypto, no keys,
// no GATT exposure. Error codes mirror section 7.1 of the draft and may change before the contract is frozen.

/** Raw u16 error codes of section 7.1 (draft). */
object P2ErrorCode {
    const val E_MALFORMED_FRAME = 0x0001
    const val E_INCOMPATIBLE_FRAME = 0x0002
    const val E_INCOMPATIBLE_MAJOR = 0x0003
    const val E_UNSUPPORTED_TYPE = 0x0004
    const val E_OVERSIZE = 0x0005
    const val E_LENGTH_MISMATCH = 0x0006
    const val E_SEQUENCE = 0x0007
    const val E_TOO_MANY_PARTIALS = 0x0008
    const val E_TIMEOUT = 0x0009
    const val E_BAD_TAG = 0x000A
    const val E_BUSY = 0x000B
    const val E_CAPACITY_TOO_SMALL = 0x000C
    const val E_UNKNOWN_CAPABILITY = 0x0100
    const val E_NOT_AUTHORIZED = 0x0101
    const val E_EXPIRED = 0x0102
    const val E_OUTCOME_UNKNOWN = 0x0103
    const val E_INVALID_PAYLOAD = 0x0104
    const val E_IDENTITY_CHANGED = 0x0200
}

/**
 * Typed error of the draft. [closesSession] is the default effect of the error per section 7.1; the reassembler can
 * raise it for [SEQUENCE] when the conflict is an F05 identifier conflict.
 */
enum class P2Error(val code: Int, val wireName: String, val closesSession: Boolean) {
    MALFORMED_FRAME(P2ErrorCode.E_MALFORMED_FRAME, "E_MALFORMED_FRAME", false),
    INCOMPATIBLE_FRAME(P2ErrorCode.E_INCOMPATIBLE_FRAME, "E_INCOMPATIBLE_FRAME", true),
    INCOMPATIBLE_MAJOR(P2ErrorCode.E_INCOMPATIBLE_MAJOR, "E_INCOMPATIBLE_MAJOR", true),
    UNSUPPORTED_TYPE(P2ErrorCode.E_UNSUPPORTED_TYPE, "E_UNSUPPORTED_TYPE", true),
    OVERSIZE(P2ErrorCode.E_OVERSIZE, "E_OVERSIZE", false),
    LENGTH_MISMATCH(P2ErrorCode.E_LENGTH_MISMATCH, "E_LENGTH_MISMATCH", false),
    SEQUENCE(P2ErrorCode.E_SEQUENCE, "E_SEQUENCE", false),
    TOO_MANY_PARTIALS(P2ErrorCode.E_TOO_MANY_PARTIALS, "E_TOO_MANY_PARTIALS", false),
    TIMEOUT(P2ErrorCode.E_TIMEOUT, "E_TIMEOUT", false),
    BAD_TAG(P2ErrorCode.E_BAD_TAG, "E_BAD_TAG", false),
    BUSY(P2ErrorCode.E_BUSY, "E_BUSY", false),
    CAPACITY_TOO_SMALL(P2ErrorCode.E_CAPACITY_TOO_SMALL, "E_CAPACITY_TOO_SMALL", false),
    UNKNOWN_CAPABILITY(P2ErrorCode.E_UNKNOWN_CAPABILITY, "E_UNKNOWN_CAPABILITY", false),
    NOT_AUTHORIZED(P2ErrorCode.E_NOT_AUTHORIZED, "E_NOT_AUTHORIZED", false),
    EXPIRED(P2ErrorCode.E_EXPIRED, "E_EXPIRED", false),
    OUTCOME_UNKNOWN(P2ErrorCode.E_OUTCOME_UNKNOWN, "E_OUTCOME_UNKNOWN", false),
    INVALID_PAYLOAD(P2ErrorCode.E_INVALID_PAYLOAD, "E_INVALID_PAYLOAD", false),
    IDENTITY_CHANGED(P2ErrorCode.E_IDENTITY_CHANGED, "E_IDENTITY_CHANGED", false);

    companion object {
        fun fromCode(code: Int): P2Error? = entries.firstOrNull { it.code == code }
        fun fromWireName(name: String): P2Error? = entries.firstOrNull { it.wireName == name }
    }
}

/** Thrown only by the encoder side for caller mistakes that map to a protocol error (never by parsing). */
class P2Exception(val error: P2Error, message: String) : RuntimeException("${error.wireName}: $message")
