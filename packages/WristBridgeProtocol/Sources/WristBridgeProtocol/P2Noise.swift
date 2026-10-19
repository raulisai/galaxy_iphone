import CryptoKit
import Foundation

// Noise_XX_25519_ChaChaPoly_SHA256 for the own channel (draft P2 revision 3, section 8.3).
// Port of core/protocol-kotlin/.../p2/secure/Noise.kt; checked against
// test/fixtures/shared/p2-noise-xx-vectors.json. NOT independently reviewed (K3): own composition of
// CryptoKit primitives. Not compiled or run on Windows (no Swift toolchain): status NOT_RUN until a Mac runs it.

public struct P2StaticKey: Sendable {
    public let privateKey: [UInt8]
    public let publicKey: [UInt8]

    public static func generate() -> P2StaticKey {
        let key = Curve25519.KeyAgreement.PrivateKey()
        return P2StaticKey(privateKey: [UInt8](key.rawRepresentation), publicKey: [UInt8](key.publicKey.rawRepresentation))
    }

    public init(privateKey: [UInt8]) throws {
        guard privateKey.count == 32,
              let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(privateKey)) else { throw P2Error.invalidPayload }
        self.privateKey = privateKey
        self.publicKey = [UInt8](key.publicKey.rawRepresentation)
    }

    private init(privateKey: [UInt8], publicKey: [UInt8]) { self.privateKey = privateKey; self.publicKey = publicKey }
}

enum P2Crypto {
    static func dh(_ privateKey: [UInt8], _ publicKey: [UInt8]) throws -> [UInt8] {
        guard let priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(privateKey)),
              let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: Data(publicKey)),
              let secret = try? priv.sharedSecretFromKeyAgreement(with: pub) else { throw P2Error.invalidPayload }
        return secret.withUnsafeBytes { [UInt8]($0) }
    }

    static func sha256(_ parts: [UInt8]...) -> [UInt8] {
        var hasher = SHA256()
        for part in parts { hasher.update(data: Data(part)) }
        return [UInt8](hasher.finalize())
    }

    static func hmac(_ key: [UInt8], _ data: [UInt8]) -> [UInt8] {
        [UInt8](HMAC<SHA256>.authenticationCode(for: Data(data), using: SymmetricKey(data: Data(key))))
    }

    /// HKDF per the Noise specification (two outputs).
    static func hkdf2(_ chainingKey: [UInt8], _ input: [UInt8]) -> ([UInt8], [UInt8]) {
        let temp = hmac(chainingKey, input)
        let one = hmac(temp, [1])
        let two = hmac(temp, one + [2])
        return (one, two)
    }

    /// Four zero bytes then the 64-bit little-endian counter.
    static func nonce(_ n: UInt64) throws -> ChaChaPoly.Nonce {
        var bytes = [UInt8](repeating: 0, count: 12)
        for i in 0..<8 { bytes[4 + i] = UInt8((n >> (8 * UInt64(i))) & 0xFF) }
        return try ChaChaPoly.Nonce(data: Data(bytes))
    }

    static func encrypt(_ key: [UInt8], _ n: UInt64, ad: [UInt8], _ plaintext: [UInt8]) throws -> [UInt8] {
        let box = try ChaChaPoly.seal(Data(plaintext), using: SymmetricKey(data: Data(key)), nonce: nonce(n), authenticating: Data(ad))
        return [UInt8](box.ciphertext) + [UInt8](box.tag)
    }

    static func decrypt(_ key: [UInt8], _ n: UInt64, ad: [UInt8], _ sealed: [UInt8]) throws -> [UInt8] {
        guard sealed.count >= 16 else { throw P2Error.badTag }
        do {
            let box = try ChaChaPoly.SealedBox(nonce: nonce(n), ciphertext: Data(sealed.dropLast(16)), tag: Data(sealed.suffix(16)))
            return [UInt8](try ChaChaPoly.open(box, using: SymmetricKey(data: Data(key)), authenticating: Data(ad)))
        } catch { throw P2Error.badTag }
    }
}

/// One direction of the transport. The counter only advances on success.
public struct P2CipherState: Sendable {
    fileprivate let key: [UInt8]
    public private(set) var counter: UInt64 = 0
    fileprivate init(key: [UInt8]) { self.key = key }

    public mutating func seal(ad: [UInt8], _ plaintext: [UInt8]) throws -> [UInt8] {
        guard counter < UInt64.max else { throw P2Error.expired }
        let out = try P2Crypto.encrypt(key, counter, ad: ad, plaintext)
        counter += 1
        return out
    }

    public mutating func open(ad: [UInt8], _ sealed: [UInt8]) throws -> [UInt8] {
        let out = try P2Crypto.decrypt(key, counter, ad: ad, sealed)
        counter += 1
        return out
    }
}

public struct P2TransportKeys: Sendable {
    public var send: P2CipherState
    public var receive: P2CipherState
    public let handshakeHash: [UInt8]
    public let remoteStatic: [UInt8]

    /// Six digits both screens must show (draft section 8.4).
    public var securityCode: String {
        let d = P2Crypto.sha256(Array("WB-SAS-v1".utf8), handshakeHash)
        let v = (UInt64(d[0]) << 24 | UInt64(d[1]) << 16 | UInt64(d[2]) << 8 | UInt64(d[3])) % 1_000_000
        return String(format: "%06llu", v)
    }

    public var sessionId: String { handshakeHash.prefix(8).map { String(format: "%02x", $0) }.joined() }
}

public enum P2Profile {
    public static let service = "6f06b8b1-21d9-4c65-9d7e-118880e36a01"
    public static let profileRevision = "p2-cand-1"

    /// Handshake prologue (draft section 8.3): label, profile revision, service UUID and the Capabilities record read.
    public static func prologue(capabilities: [UInt8]) -> [UInt8] {
        Array("WB-P2|1.0|\(profileRevision)|\(service)|".utf8) + capabilities
    }
}

public struct P2NoiseXX {
    private let initiator: Bool
    private let staticKey: P2StaticKey
    private let fixedEphemeral: P2StaticKey?
    private var h: [UInt8]
    private var ck: [UInt8]
    private var k: [UInt8]?
    private var n: UInt64 = 0
    private var ephemeral: P2StaticKey?
    private var remoteEphemeral: [UInt8]?
    public private(set) var remoteStatic: [UInt8]?
    private var step = 0

    /// `testEphemeral` is for vectors only. Never set it in production code.
    public init(initiator: Bool, staticKey: P2StaticKey, prologue: [UInt8], testEphemeral: P2StaticKey? = nil) {
        self.initiator = initiator
        self.staticKey = staticKey
        self.fixedEphemeral = testEphemeral
        let name = Array("Noise_XX_25519_ChaChaPoly_SHA256".utf8) // exactly 32 bytes: used as is
        self.h = name.count <= 32 ? name + [UInt8](repeating: 0, count: 32 - name.count) : P2Crypto.sha256(name)
        self.ck = h
        self.h = P2Crypto.sha256(h, prologue)
    }

    public var isComplete: Bool { step == 3 }

    private mutating func mixHash(_ data: [UInt8]) { h = P2Crypto.sha256(h, data) }

    private mutating func mixKey(_ input: [UInt8]) {
        let (a, b) = P2Crypto.hkdf2(ck, input)
        ck = a; k = b; n = 0
    }

    private mutating func encryptAndHash(_ plaintext: [UInt8]) throws -> [UInt8] {
        guard let key = k else { mixHash(plaintext); return plaintext }
        let out = try P2Crypto.encrypt(key, n, ad: h, plaintext)
        n += 1
        mixHash(out)
        return out
    }

    private mutating func decryptAndHash(_ sealed: [UInt8]) throws -> [UInt8] {
        guard let key = k else { mixHash(sealed); return sealed }
        let out = try P2Crypto.decrypt(key, n, ad: h, sealed)
        n += 1
        mixHash(sealed)
        return out
    }

    public mutating func writeMessage(payload: [UInt8] = []) throws -> [UInt8] {
        let myTurn = initiator ? (step == 0 || step == 2) : step == 1
        guard myTurn else { throw P2Error.sequence }
        var out: [UInt8] = []
        switch step {
        case 0:
            let e = fixedEphemeral ?? P2StaticKey.generate()
            ephemeral = e
            out += e.publicKey
            mixHash(e.publicKey)
        case 1:
            let e = fixedEphemeral ?? P2StaticKey.generate()
            ephemeral = e
            out += e.publicKey
            mixHash(e.publicKey)
            mixKey(try P2Crypto.dh(e.privateKey, remoteEphemeral!))
            out += try encryptAndHash(staticKey.publicKey)
            mixKey(try P2Crypto.dh(staticKey.privateKey, remoteEphemeral!))
        default:
            out += try encryptAndHash(staticKey.publicKey)
            mixKey(try P2Crypto.dh(staticKey.privateKey, remoteEphemeral!))
        }
        out += try encryptAndHash(payload)
        step += 1
        return out
    }

    public mutating func readMessage(_ message: [UInt8]) throws -> [UInt8] {
        let myTurn = initiator ? step == 1 : (step == 0 || step == 2)
        guard myTurn else { throw P2Error.sequence }
        var offset = 0
        func take(_ count: Int) throws -> [UInt8] {
            guard message.count - offset >= count else { throw P2Error.malformedFrame }
            defer { offset += count }
            return Array(message[offset..<(offset + count)])
        }
        switch step {
        case 0:
            remoteEphemeral = try take(32)
            mixHash(remoteEphemeral!)
        case 1:
            remoteEphemeral = try take(32)
            mixHash(remoteEphemeral!)
            mixKey(try P2Crypto.dh(ephemeral!.privateKey, remoteEphemeral!))
            remoteStatic = try decryptAndHash(try take(48))
            mixKey(try P2Crypto.dh(ephemeral!.privateKey, remoteStatic!))
        default:
            remoteStatic = try decryptAndHash(try take(48))
            mixKey(try P2Crypto.dh(ephemeral!.privateKey, remoteStatic!))
        }
        let payload = try decryptAndHash(Array(message[offset...]))
        step += 1
        return payload
    }

    public func split() throws -> P2TransportKeys {
        guard isComplete, let rs = remoteStatic else { throw P2Error.sequence }
        let (k1, k2) = P2Crypto.hkdf2(ck, [])
        let send = initiator ? k1 : k2
        let receive = initiator ? k2 : k1
        return P2TransportKeys(send: P2CipherState(key: send), receive: P2CipherState(key: receive), handshakeHash: h, remoteStatic: rs)
    }
}
