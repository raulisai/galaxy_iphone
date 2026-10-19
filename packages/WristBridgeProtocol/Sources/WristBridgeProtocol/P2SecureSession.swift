import Foundation

/// Authenticated DATA transport after the handshake (draft 3.3 / 8.5), Swift side.
/// Body = counter(u64 BE) || ciphertext || tag. AD = frameVersion || type || flags || messageId(u16) || totalLength(u32).
/// One message at a time per direction. DATA message ids start at 16 (1...3 are the handshake).
/// A new session is built after every reconnection.
public final class P2SecureSession {
    public enum Inbound: Equatable {
        case none
        case message(messageId: UInt16, json: P2JSON)
        case peerAck(messageId: UInt16)
        case peerError(code: UInt16, related: UInt16)
    }
    public static let firstDataMessageId: UInt16 = 16

    private var keys: P2TransportKeys
    private let maxAttValueBytes: Int
    private var reassembler: P2Reassembler
    private var nextMessageId = P2SecureSession.firstDataMessageId

    public init(keys: P2TransportKeys, maxAttValueBytes: Int) {
        self.keys = keys; self.maxAttValueBytes = maxAttValueBytes
        reassembler = P2Reassembler()
    }

    public var securityCode: String { keys.securityCode() }
    public var sessionId: String { keys.sessionId }
    public var peerStaticKey: [UInt8] { keys.remoteStatic }

    private static func associatedData(type: UInt8, flags: UInt8, messageId: UInt16, totalLength: Int) -> [UInt8] {
        [P2Codec.frameVersion, type, flags, UInt8(messageId >> 8), UInt8(messageId & 0xFF),
         UInt8(totalLength >> 24 & 0xFF), UInt8(totalLength >> 16 & 0xFF), UInt8(totalLength >> 8 & 0xFF), UInt8(totalLength & 0xFF)]
    }

    /// Encrypts `json` and returns the frames to write, in order.
    public func send(_ json: P2JSON, flags: UInt8 = 0) throws -> (messageId: UInt16, frames: [[UInt8]]) {
        let plain = try P2StrictJSON.encode(json)
        let id = nextMessageId
        nextMessageId = nextMessageId >= 0xFFFE ? Self.firstDataMessageId : nextMessageId + 1
        let total = 8 + plain.count + 16
        let counter = keys.send.counter
        let ciphertext = try keys.send.seal(ad: Self.associatedData(type: P2FrameType.data.rawValue, flags: flags, messageId: id, totalLength: total),
                                            plaintext: plain)
        var body: [UInt8] = (0..<8).map { UInt8(truncatingIfNeeded: counter >> UInt64(56 - 8 * $0)) }
        body += ciphertext
        reassembler.outboundInFlight.insert(id)
        return (id, try P2Codec.encode(type: .data, flags: flags, messageId: id, body: body, maxAttValueBytes: maxAttValueBytes))
    }

    public func ackFrame(for messageId: UInt16) -> [UInt8] { P2Codec.encodeAck(messageId: messageId) }

    /// A forged, replayed or reordered DATA message throws `.badTag` and leaves the receive counter untouched; the caller closes the session.
    public func receive(_ bytes: [UInt8], atMs now: Int64) throws -> Inbound {
        var ackedId: UInt16?
        if case .frame(let frame) = P2Codec.parse(bytes), frame.type == .ack { ackedId = frame.messageId }
        switch reassembler.accept(bytes, atMs: now) {
        case .partial, .cancelled, .cancelNoop, .ignored: return .none
        case .failed(let error, _): throw error
        case .ack(let known): return known ? .peerAck(messageId: ackedId ?? 0) : .none
        case .peerError(let code, let related): return .peerError(code: code, related: related)
        case .complete(let type, let messageId, let flags, let body):
            guard type == .data else { throw P2Error.unsupportedType }
            guard body.count >= 24 else { throw P2Error.badTag }
            let counter = body[0..<8].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
            guard counter == keys.receive.counter else { throw P2Error.badTag }
            let plain = try keys.receive.open(
                ad: Self.associatedData(type: type.rawValue, flags: flags, messageId: messageId, totalLength: body.count),
                ciphertext: Array(body[8...]))
            return .message(messageId: messageId, json: try P2StrictJSON.parse(plain))
        }
    }

    public func reset() { reassembler.reset() }
}
