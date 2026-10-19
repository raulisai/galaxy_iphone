import CryptoKit
import Foundation

// Noise_XX_25519_ChaChaPoly_SHA256 (Noise spec rev 34), ported from the Kotlin reference
// (core/protocol-kotlin/.../p2/secure/Noise.kt) on top of CryptoKit primitives (Curve25519,
// ChaChaPoly, SHA256, HMAC). The *composition* is our own and has NOT been independently
// reviewed (K3, draft section 8). CryptoKit is Apple's reviewed implementation of the
// primitives; the handshake state machine below is checked only against shared vectors and
// round-trips. Use only the user's own data until K3 is closed.

public struct P2StaticKey {
    let privateKey: Curve25519.KeyAgreement.PrivateKey
    public let publicKey: [UInt8]

    public init(privateKey: Curve25519.KeyAgreement.PrivateKey) {
        self.privateKey = privateKey
        publicKey = Array(privateKey.publicKey.rawRepresentation)
    }
    public static func generate() -> P2StaticKey { P2StaticKey(privateKey: Curve25519.KeyAgreement.PrivateKey()) }
    public init(rawPrivate: [UInt8]) throws {
        guard rawPrivate.count == 32 else { throw P2Error.malformedFrame }
        self.init(privateKey: try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(rawPrivate)))
    }
    public var rawPrivate: [UInt8] { Array(privateKey.rawRepresentation) }
}

enum P2NoiseCrypto {
    static func sha256(_ parts: [UInt8]...) -> [UInt8] {
        var hash = SHA256()
        for part in parts { hash.update(data: part) }
        return Array(hash.finalize())
    }
    static func hmac(key: [UInt8], data: [UInt8]) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }
    /// HKDF per Noise 4.3 with two outputs.
    static func hkdf2(chainingKey: [UInt8], input: [UInt8]) -> ([UInt8], [UInt8]) {
        let temp = hmac(key: chainingKey, data: input)
        let one = hmac(key: temp, data: [1])
        let two = hmac(key: temp, data: one + [2])
        return (one, two)
    }
    /// 4 zero bytes then the 64-bit little-endian counter.
    static func nonce(_ n: UInt64) throws -> ChaChaPoly.Nonce {
        var bytes = [UInt8](repeating: 0, count: 12)
        for i in 0..<8 { bytes[4 + i] = UInt8(truncatingIfNeeded: n >> (8 * UInt64(i))) }
        return try ChaChaPoly.Nonce(data: Data(bytes))
    }
    static func encrypt(key: [UInt8], n: UInt64, ad: [UInt8], plaintext: [UInt8]) throws -> [UInt8] {
        let box = try ChaChaPoly.seal(Data(plaintext), using: SymmetricKey(data: key), nonce: nonce(n), authenticating: Data(ad))
        return Array(box.ciphertext) + Array(box.tag)
    }
    static func decrypt(key: [UInt8], n: UInt64, ad: [UInt8], ciphertext: [UInt8]) throws -> [UInt8] {
        guard ciphertext.count >= 16 else { throw P2Error.badTag }
        do {
            let box = try ChaChaPoly.SealedBox(nonce: nonce(n), ciphertext: Data(ciphertext.dropLast(16)), tag: Data(ciphertext.suffix(16)))
            return Array(try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: Data(ad)))
        } catch { throw P2Error.badTag }
    }
    /// X25519; an all-zero result (low-order public key) is rejected.
    static func dh(_ privateKey: Curve25519.KeyAgreement.PrivateKey, _ publicRaw: [UInt8]) throws -> [UInt8] {
        guard publicRaw.count == 32 else { throw P2Error.malformedFrame }
        do {
            let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: Data(publicRaw))
            let secret = try privateKey.sharedSecretFromKeyAgreement(with: peer)
            let bytes = secret.withUnsafeBytes { Array($0) }
            if bytes.allSatisfy({ $0 == 0 }) { throw P2Error.invalidPayload }
            return bytes
        } catch { throw P2Error.invalidPayload }
    }
}

/// Transport cipher for one direction. The counter only advances on success, so a forged or
/// replayed message leaves the state intact.
public struct P2CipherState {
    private let key: [UInt8]
    public private(set) var counter: UInt64 = 0
    init(key: [UInt8]) { self.key = key }

    public mutating func seal(ad: [UInt8], plaintext: [UInt8]) throws -> [UInt8] {
        guard counter < UInt64.max else { throw P2Error.expired }
        let out = try P2NoiseCrypto.encrypt(key: key, n: counter, ad: ad, plaintext: plaintext)
        counter += 1
        return out
    }
    public mutating func open(ad: [UInt8], ciphertext: [UInt8]) throws -> [UInt8] {
        let out = try P2NoiseCrypto.decrypt(key: key, n: counter, ad: ad, ciphertext: ciphertext)
        counter += 1
        return out
    }
}

public struct P2TransportKeys {
    public var send: P2CipherState
    public var receive: P2CipherState
    public let handshakeHash: [UInt8]
    public let remoteStatic: [UInt8]

    /// Six-digit code both screens show at registration: SHA256("WB-SAS-v1" || h), first 4 bytes BE mod 1,000,000.
    public func securityCode() -> String {
        let digest = P2NoiseCrypto.sha256(Array("WB-SAS-v1".utf8), handshakeHash)
        let value = (UInt64(digest[0]) << 24 | UInt64(digest[1]) << 16 | UInt64(digest[2]) << 8 | UInt64(digest[3])) % 1_000_000
        return String(format: "%06llu", value)
    }
    /// First 8 bytes of the handshake hash, hex: the session id both sides agree on.
    public var sessionId: String { handshakeHash.prefix(8).map { String(format: "%02x", $0) }.joined() }
}

/// XX handshake. Messages: 1 "-> e", 2 "<- e, ee, s, es", 3 "-> s, se".
public final class P2NoiseXX {
    private let initiator: Bool
    private let s: P2StaticKey
    private let testEphemeral: Curve25519.KeyAgreement.PrivateKey?
    private var h: [UInt8]
    private var ck: [UInt8]
    private var k: [UInt8]?
    private var n: UInt64 = 0
    private var e: Curve25519.KeyAgreement.PrivateKey?
    private var re: [UInt8]?
    private var rs: [UInt8]?
    private var step = 0

    /// `testEphemeral` is for vectors only; never pass it in production code.
    public init(initiator: Bool, staticKey: P2StaticKey, prologue: [UInt8],
                testEphemeral: Curve25519.KeyAgreement.PrivateKey? = nil) {
        self.initiator = initiator; s = staticKey; self.testEphemeral = testEphemeral
        let name = Array("Noise_XX_25519_ChaChaPoly_SHA256".utf8)
        h = name.count <= 32 ? name + [UInt8](repeating: 0, count: 32 - name.count) : P2NoiseCrypto.sha256(name)
        ck = h
        mixHash(prologue)
    }

    public var isComplete: Bool { step == 3 }
    public var remoteStatic: [UInt8]? { rs }

    private func mixHash(_ data: [UInt8]) { h = P2NoiseCrypto.sha256(h, data) }
    private func mixKey(_ input: [UInt8]) {
        let (a, b) = P2NoiseCrypto.hkdf2(chainingKey: ck, input: input)
        ck = a; k = b; n = 0
    }
    private func encryptAndHash(_ plaintext: [UInt8]) throws -> [UInt8] {
        guard let key = k else { mixHash(plaintext); return plaintext }
        let out = try P2NoiseCrypto.encrypt(key: key, n: n, ad: h, plaintext: plaintext)
        n += 1; mixHash(out)
        return out
    }
    private func decryptAndHash(_ ciphertext: [UInt8]) throws -> [UInt8] {
        guard let key = k else { mixHash(ciphertext); return ciphertext }
        let out = try P2NoiseCrypto.decrypt(key: key, n: n, ad: h, ciphertext: ciphertext)
        n += 1; mixHash(ciphertext)
        return out
    }

    public func writeMessage(payload: [UInt8] = []) throws -> [UInt8] {
        let myTurn = initiator ? (step == 0 || step == 2) : step == 1
        guard myTurn else { throw P2Error.sequence }
        var out: [UInt8] = []
        switch step {
        case 0:
            let eph = testEphemeral ?? Curve25519.KeyAgreement.PrivateKey()
            e = eph
            let pub = Array(eph.publicKey.rawRepresentation)
            out += pub; mixHash(pub)
        case 1:
            let eph = testEphemeral ?? Curve25519.KeyAgreement.PrivateKey()
            e = eph
            let pub = Array(eph.publicKey.rawRepresentation)
            out += pub; mixHash(pub)
            mixKey(try P2NoiseCrypto.dh(eph, re!))
            out += try encryptAndHash(s.publicKey)
            mixKey(try P2NoiseCrypto.dh(s.privateKey, re!))
        default:
            out += try encryptAndHash(s.publicKey)
            mixKey(try P2NoiseCrypto.dh(s.privateKey, re!))
        }
        out += try encryptAndHash(payload)
        step += 1
        return out
    }

    public func readMessage(_ message: [UInt8]) throws -> [UInt8] {
        let myTurn = initiator ? step == 1 : (step == 0 || step == 2)
        guard myTurn else { throw P2Error.sequence }
        var offset = 0
        func take(_ length: Int) throws -> [UInt8] {
            guard message.count - offset >= length else { throw P2Error.malformedFrame }
            defer { offset += length }
            return Array(message[offset..<(offset + length)])
        }
        switch step {
        case 0:
            re = try take(32); mixHash(re!)
        case 1:
            re = try take(32); mixHash(re!)
            mixKey(try P2NoiseCrypto.dh(e!, re!))
            rs = try decryptAndHash(try take(48))
            mixKey(try P2NoiseCrypto.dh(e!, rs!))
        default:
            rs = try decryptAndHash(try take(48))
            mixKey(try P2NoiseCrypto.dh(e!, rs!))
        }
        let payload = try decryptAndHash(Array(message[offset...]))
        step += 1
        return payload
    }

    public func split() throws -> P2TransportKeys {
        guard isComplete, let rs else { throw P2Error.sequence }
        let (k1, k2) = P2NoiseCrypto.hkdf2(chainingKey: ck, input: [])
        let (send, receive) = initiator ? (k1, k2) : (k2, k1)
        return P2TransportKeys(send: P2CipherState(key: send), receive: P2CipherState(key: receive),
                               handshakeHash: h, remoteStatic: rs)
    }
}
