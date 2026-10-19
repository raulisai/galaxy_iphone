import Foundation

// P2 own-channel FRAMING and ERROR model, revision 2 of contracts/proposals/p2-channel-draft.md.
//
// Scope, deliberately narrow: bytes <-> frames, reassembly and the error taxonomy. There is NO
// cryptography, no GATT access and no session here, and nothing in the app calls this yet. The
// secure channel (identity, Noise, AEAD) is blocked by the independent security review (K3);
// until it passes this code must not carry any user data. It exists so the failure modes are
// defined, tested and shared with Kotlin before the channel is switched on.

public enum P2FrameType: UInt8, Sendable {
    case data = 0x81, ack = 0x82, error = 0x83, cancel = 0x84, handshake = 0x85
}

/// Error codes of the draft (7.1). `closesSession` marks the ones that always end the application
/// session (never the radio link, and never ANCS).
public enum P2Error: UInt16, Error, CaseIterable, Sendable {
    case malformedFrame = 0x0001, incompatibleFrame = 0x0002, incompatibleMajor = 0x0003
    case unsupportedType = 0x0004, oversize = 0x0005, lengthMismatch = 0x0006, sequence = 0x0007
    case tooManyPartials = 0x0008, timeout = 0x0009, badTag = 0x000A, busy = 0x000B
    case capacityTooSmall = 0x000C
    case unknownCapability = 0x0100, notAuthorized = 0x0101, expired = 0x0102
    case outcomeUnknown = 0x0103, invalidPayload = 0x0104
    case identityChanged = 0x0200

    public var name: String {
        switch self {
        case .malformedFrame: "E_MALFORMED_FRAME"
        case .incompatibleFrame: "E_INCOMPATIBLE_FRAME"
        case .incompatibleMajor: "E_INCOMPATIBLE_MAJOR"
        case .unsupportedType: "E_UNSUPPORTED_TYPE"
        case .oversize: "E_OVERSIZE"
        case .lengthMismatch: "E_LENGTH_MISMATCH"
        case .sequence: "E_SEQUENCE"
        case .tooManyPartials: "E_TOO_MANY_PARTIALS"
        case .timeout: "E_TIMEOUT"
        case .badTag: "E_BAD_TAG"
        case .busy: "E_BUSY"
        case .capacityTooSmall: "E_CAPACITY_TOO_SMALL"
        case .unknownCapability: "E_UNKNOWN_CAPABILITY"
        case .notAuthorized: "E_NOT_AUTHORIZED"
        case .expired: "E_EXPIRED"
        case .outcomeUnknown: "E_OUTCOME_UNKNOWN"
        case .invalidPayload: "E_INVALID_PAYLOAD"
        case .identityChanged: "E_IDENTITY_CHANGED"
        }
    }

    public static func named(_ name: String) -> P2Error? { allCases.first { $0.name == name } }

    /// Errors that end the application session on their own (draft 7.1).
    public var closesSession: Bool {
        switch self {
        case .incompatibleFrame, .incompatibleMajor, .unsupportedType, .identityChanged: true
        default: false
        }
    }
    /// What the operator may be told, never including content or identifiers.
    public var userFacingSummary: String {
        switch self {
        case .incompatibleFrame, .incompatibleMajor: "La app del reloj y la del iPhone usan versiones incompatibles."
        case .identityChanged: "La identidad del reloj cambió. Hay que volver a registrarlo en ambos dispositivos."
        case .badTag, .notAuthorized: "No se pudo verificar un mensaje del reloj; se descartó."
        case .busy: "El reloj está ocupado. Se reintentará."
        case .capacityTooSmall: "La conexión Bluetooth es demasiado limitada para este canal."
        case .timeout: "Un mensaje del reloj no terminó de llegar y se descartó."
        default: "Se descartó un mensaje dañado del reloj."
        }
    }
}

public struct P2Frame: Equatable, Sendable {
    public let type: P2FrameType
    public let flags: UInt8
    public let messageId: UInt16
    public let fragIndex: UInt16
    public let fragCount: UInt16
    public let totalLength: UInt32?
    public let payload: [UInt8]
    public var isPriority: Bool { flags & 0x01 != 0 }
}

public enum P2ParseResult: Equatable, Sendable {
    case frame(P2Frame)
    /// Non-critical unknown type: discard and count; never an error.
    case ignored
    case failure(P2Error)
}

public enum P2Codec {
    public static let frameVersion: UInt8 = 0x01
    public static let headerBytes = 9
    public static let firstHeaderBytes = 13
    public static let minimumAttValueBytes = 14

    // MARK: Parse (stateless checks, in the draft's fixed order, 3.5)

    public static func parse(_ bytes: [UInt8]) -> P2ParseResult {
        guard let version = bytes.first else { return .failure(.malformedFrame) }
        guard version == frameVersion else { return .failure(.incompatibleFrame) }
        guard bytes.count >= headerBytes else { return .failure(.malformedFrame) }
        let rawType = bytes[1], flags = bytes[2]
        let messageId = u16(bytes, 3), fragIndex = u16(bytes, 5), fragCount = u16(bytes, 7)
        guard let type = P2FrameType(rawValue: rawType) else {
            return rawType & 0x80 == 0 ? .ignored : .failure(.unsupportedType)
        }
        guard flags & 0xFE == 0 else { return .failure(.malformedFrame) }
        guard fragCount != 0, fragIndex < fragCount else { return .failure(.malformedFrame) }
        switch type {
        case .ack, .cancel:
            guard bytes.count == headerBytes, fragCount == 1, fragIndex == 0 else { return .failure(.malformedFrame) }
            return .frame(P2Frame(type: type, flags: flags, messageId: messageId, fragIndex: 0, fragCount: 1, totalLength: nil, payload: []))
        case .error:
            guard bytes.count == 13, fragCount == 1, fragIndex == 0, messageId == 0 else { return .failure(.malformedFrame) }
            return .frame(P2Frame(type: .error, flags: flags, messageId: 0, fragIndex: 0, fragCount: 1, totalLength: nil,
                                  payload: Array(bytes[9..<13])))
        case .data, .handshake:
            if fragIndex == 0 {
                guard bytes.count >= firstHeaderBytes else { return .failure(.malformedFrame) }
                let total = UInt32(bytes[9]) << 24 | UInt32(bytes[10]) << 16 | UInt32(bytes[11]) << 8 | UInt32(bytes[12])
                return .frame(P2Frame(type: type, flags: flags, messageId: messageId, fragIndex: 0, fragCount: fragCount,
                                      totalLength: total, payload: Array(bytes[13...])))
            }
            return .frame(P2Frame(type: type, flags: flags, messageId: messageId, fragIndex: fragIndex, fragCount: fragCount,
                                  totalLength: nil, payload: Array(bytes[9...])))
        }
    }

    // MARK: Encode

    /// Splits `body` into frames filling each position to capacity (rule A, 3.5).
    public static func encode(type: P2FrameType, flags: UInt8 = 0, messageId: UInt16, body: [UInt8],
                              maxAttValueBytes: Int) throws -> [[UInt8]] {
        guard type == .data || type == .handshake else { throw P2Error.malformedFrame }
        guard maxAttValueBytes >= minimumAttValueBytes else { throw P2Error.capacityTooSmall }
        guard flags & 0xFE == 0, !body.isEmpty, body.count <= Int(UInt32.max) else { throw P2Error.malformedFrame }
        let first = min(body.count, maxAttValueBytes - firstHeaderBytes)
        let rest = maxAttValueBytes - headerBytes
        let count = 1 + (body.count > first ? (body.count - first + rest - 1) / rest : 0)
        guard count <= Int(UInt16.max) else { throw P2Error.oversize }
        var frames: [[UInt8]] = []
        var offset = 0
        for index in 0..<count {
            let size = index == 0 ? first : min(rest, body.count - offset)
            var frame: [UInt8] = [frameVersion, type.rawValue, flags]
            frame += be16(messageId) + be16(UInt16(index)) + be16(UInt16(count))
            if index == 0 { frame += be32(UInt32(body.count)) }
            frame += body[offset..<(offset + size)]
            frames.append(frame)
            offset += size
        }
        return frames
    }

    public static func encodeAck(messageId: UInt16) -> [UInt8] { control(.ack, messageId) }
    public static func encodeCancel(messageId: UInt16) -> [UInt8] { control(.cancel, messageId) }
    public static func encodeError(_ code: P2Error, related: UInt16?) -> [UInt8] {
        control(.error, 0) + be16(code.rawValue) + be16(related ?? 0xFFFF)
    }

    private static func control(_ type: P2FrameType, _ id: UInt16) -> [UInt8] {
        [frameVersion, type.rawValue, 0] + be16(id) + be16(0) + be16(1)
    }
    private static func u16(_ b: [UInt8], _ i: Int) -> UInt16 { UInt16(b[i]) << 8 | UInt16(b[i + 1]) }
    private static func be16(_ v: UInt16) -> [UInt8] { [UInt8(v >> 8), UInt8(v & 0xFF)] }
    private static func be32(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)] }
}

/// Size limits checked against `totalLength` BEFORE any memory is reserved (F07).
public struct P2Limits: Equatable, Sendable {
    public var handshake: Int, control: Int, batch: Int
    public init(handshake: Int = 1024, control: Int = 8 * 1024 + 24, batch: Int = 64 * 1024 + 24) {
        self.handshake = handshake; self.control = control; self.batch = batch
    }
    public static func uniform(_ n: Int) -> P2Limits { P2Limits(handshake: n, control: n, batch: n) }
    func limit(for type: P2FrameType, priority: Bool) -> Int {
        type == .handshake ? handshake : (priority ? control : batch)
    }
}

public enum P2Step: Equatable, Sendable {
    case partial
    case complete(type: P2FrameType, messageId: UInt16, body: [UInt8])
    case failed(P2Error, closeSession: Bool)
    case cancelled
    case cancelNoop
    case ack(known: Bool)
    case ignored
    case peerError(code: UInt16, related: UInt16)
}

/// One open message per direction (v1: no interleaving). ACK/ERROR/CANCEL may arrive between its frames.
public struct P2Reassembler: Sendable {
    public static let progressTimeoutMs: Int64 = 15_000

    private struct Open: Sendable {
        let type: P2FrameType, flags: UInt8, messageId: UInt16
        let fragCount: UInt16, totalLength: Int, firstPayload: Int
        var next: UInt16, body: [UInt8], lastProgress: Int64
    }

    public let limits: P2Limits
    public let knownMaxAttValueBytes: Int?
    public var outboundInFlight: Set<UInt16>
    public private(set) var timedOutCount = 0
    public private(set) var ignoredCount = 0
    public private(set) var unknownAckCount = 0
    private var open: Open?
    private var consecutiveMalformed = 0
    private var recentMalformed: [Int64] = []

    public init(limits: P2Limits = P2Limits(), knownMaxAttValueBytes: Int? = nil, outboundInFlight: Set<UInt16> = []) {
        self.limits = limits; self.knownMaxAttValueBytes = knownMaxAttValueBytes; self.outboundInFlight = outboundInFlight
    }

    public var hasOpenMessage: Bool { open != nil }

    /// Drops any partial message (link lost, Service Changed, session closed). Nothing partial is ever delivered.
    public mutating func reset() { open = nil; consecutiveMalformed = 0; recentMalformed = [] }

    public mutating func accept(_ bytes: [UInt8], atMs now: Int64 = 0) -> P2Step {
        if let current = open, now - current.lastProgress >= Self.progressTimeoutMs {
            open = nil; timedOutCount += 1
        }
        switch P2Codec.parse(bytes) {
        case .ignored:
            ignoredCount += 1
            return .ignored
        case .failure(let error):
            return fail(error, now: now)
        case .frame(let frame):
            return handle(frame, now: now)
        }
    }

    private mutating func fail(_ error: P2Error, now: Int64, forceClose: Bool = false) -> P2Step {
        var close = forceClose || error.closesSession
        if error == .malformedFrame {
            consecutiveMalformed += 1
            recentMalformed = recentMalformed.filter { now - $0 < 60_000 } + [now]
            if consecutiveMalformed >= 3 || recentMalformed.count >= 8 { close = true }
        }
        return .failed(error, closeSession: close)
    }

    private mutating func handle(_ frame: P2Frame, now: Int64) -> P2Step {
        switch frame.type {
        case .ack:
            consecutiveMalformed = 0
            if outboundInFlight.remove(frame.messageId) != nil { return .ack(known: true) }
            unknownAckCount += 1
            return .ack(known: false)
        case .error:
            consecutiveMalformed = 0
            let code = UInt16(frame.payload[0]) << 8 | UInt16(frame.payload[1])
            let related = UInt16(frame.payload[2]) << 8 | UInt16(frame.payload[3])
            return .peerError(code: code, related: related)
        case .cancel:
            consecutiveMalformed = 0
            if open?.messageId == frame.messageId { open = nil; return .cancelled }
            return .cancelNoop
        case .data, .handshake:
            return frame.fragIndex == 0 ? first(frame, now: now) : continuation(frame, now: now)
        }
    }

    private mutating func first(_ frame: P2Frame, now: Int64) -> P2Step {
        let total = Int(frame.totalLength ?? 0)
        if let current = open {
            if current.messageId == frame.messageId {
                let conflicting = current.fragCount != frame.fragCount || current.totalLength != total
                    || current.type != frame.type || current.flags != frame.flags
                open = nil
                return fail(.sequence, now: now, forceClose: conflicting)  // F05 closes; an identical duplicate only drops
            }
            return fail(.tooManyPartials, now: now)                        // the open message is kept (F06)
        }
        guard total <= frame.type.limit(limits, frame.isPriority) else { return fail(.oversize, now: now) }
        let minimum = frame.type == .data ? 24 : 1
        guard total >= minimum, !frame.payload.isEmpty else { return fail(.malformedFrame, now: now) }
        let carried = frame.payload.count
        guard carried <= total else { return fail(.lengthMismatch, now: now) }

        if frame.fragCount == 1 {
            if let known = knownMaxAttValueBytes, carried > known - P2Codec.firstHeaderBytes { return fail(.malformedFrame, now: now) }
            guard carried == total else { return fail(.lengthMismatch, now: now) }
            consecutiveMalformed = 0
            return .complete(type: frame.type, messageId: frame.messageId, body: frame.payload)
        }
        if let known = knownMaxAttValueBytes, carried != known - P2Codec.firstHeaderBytes { return fail(.malformedFrame, now: now) }
        guard carried < total else { return fail(.lengthMismatch, now: now) }
        let capacity = carried + 4  // max - 9, deduced from the first frame (max = carried + 13)
        let expectedCount = 1 + (total - carried + capacity - 1) / capacity
        guard expectedCount == Int(frame.fragCount) else { return fail(.lengthMismatch, now: now) }
        open = Open(type: frame.type, flags: frame.flags, messageId: frame.messageId, fragCount: frame.fragCount,
                    totalLength: total, firstPayload: carried, next: 1, body: frame.payload, lastProgress: now)
        consecutiveMalformed = 0
        return .partial
    }

    private mutating func continuation(_ frame: P2Frame, now: Int64) -> P2Step {
        guard var current = open, current.messageId == frame.messageId else { return fail(.sequence, now: now) }
        guard current.fragCount == frame.fragCount, current.type == frame.type, current.flags == frame.flags else {
            open = nil
            return fail(.sequence, now: now, forceClose: true)             // F05 conflict
        }
        guard frame.fragIndex == current.next else { open = nil; return fail(.sequence, now: now) }
        guard !frame.payload.isEmpty else { open = nil; return fail(.malformedFrame, now: now) }
        let capacity = current.firstPayload + 4
        let isFinal = frame.fragIndex == frame.fragCount - 1
        if isFinal {
            guard frame.payload.count <= capacity else { open = nil; return fail(.malformedFrame, now: now) }
        } else {
            guard frame.payload.count == capacity else { open = nil; return fail(.malformedFrame, now: now) }
        }
        current.body += frame.payload
        guard current.body.count <= current.totalLength else { open = nil; return fail(.lengthMismatch, now: now) }
        if isFinal {
            open = nil
            guard current.body.count == current.totalLength else { return fail(.lengthMismatch, now: now) }
            consecutiveMalformed = 0
            return .complete(type: current.type, messageId: current.messageId, body: current.body)
        }
        current.next += 1
        current.lastProgress = now
        open = current
        consecutiveMalformed = 0
        return .partial
    }
}

private extension P2FrameType {
    func limit(_ limits: P2Limits, _ priority: Bool) -> Int { limits.limit(for: self, priority: priority) }
}
