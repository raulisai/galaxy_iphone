import CryptoKit
import Foundation
import Testing
@testable import WristBridgeProtocol

// Oracle: test/fixtures/shared/p2-noise-xx-vectors.json (Kotlin, PROPOSED). Swift runs on different
// primitive implementations (CryptoKit), so agreement here is real cross-implementation evidence for
// the handshake composition, not for its security (K3 is still open).

private struct NoiseVector: Decodable {
    let protocolName: String
    let prologueHex, initiatorStaticPrivateHex, responderStaticPrivateHex: String
    let initiatorEphemeralPrivateHex, responderEphemeralPrivateHex: String
    let message1Hex, message2PayloadUtf8, message2Hex, message3PayloadUtf8, message3Hex: String
    let handshakeHashHex, securityCode, transportAdHex, initiatorToResponderHex, responderToInitiatorHex: String
    enum CodingKeys: String, CodingKey {
        case protocolName = "protocol"
        case prologueHex, initiatorStaticPrivateHex, responderStaticPrivateHex, initiatorEphemeralPrivateHex
        case responderEphemeralPrivateHex, message1Hex, message2PayloadUtf8, message2Hex, message3PayloadUtf8, message3Hex
        case handshakeHashHex, securityCode, transportAdHex, initiatorToResponderHex, responderToInitiatorHex
    }
}
private func hex(_ s: String) -> [UInt8] {
    var out: [UInt8] = []; var i = s.startIndex
    while i < s.endIndex { let n = s.index(i, offsetBy: 2); out.append(UInt8(s[i..<n], radix: 16)!); i = n }
    return out
}
private func hx(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
private func loadNoiseVector() throws -> NoiseVector {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    url.appendPathComponent("test/fixtures/shared/p2-noise-xx-vectors.json")
    return try JSONDecoder().decode(NoiseVector.self, from: Data(contentsOf: url))
}
private func eph(_ h: String) throws -> Curve25519.KeyAgreement.PrivateKey { try .init(rawRepresentation: Data(hex(h))) }

@Test func swiftReproducesTheKotlinNoiseXXVectorByteForByte() throws {
    let v = try loadNoiseVector()
    #expect(v.protocolName == "Noise_XX_25519_ChaChaPoly_SHA256")
    let prologue = hex(v.prologueHex)
    let initiator = P2NoiseXX(initiator: true, staticKey: try P2StaticKey(rawPrivate: hex(v.initiatorStaticPrivateHex)),
                              prologue: prologue, testEphemeral: try eph(v.initiatorEphemeralPrivateHex))
    let responder = P2NoiseXX(initiator: false, staticKey: try P2StaticKey(rawPrivate: hex(v.responderStaticPrivateHex)),
                              prologue: prologue, testEphemeral: try eph(v.responderEphemeralPrivateHex))
    let m1 = try initiator.writeMessage()
    #expect(hx(m1) == v.message1Hex)
    _ = try responder.readMessage(m1)
    let m2 = try responder.writeMessage(payload: Array(v.message2PayloadUtf8.utf8))
    #expect(hx(m2) == v.message2Hex)
    #expect(try initiator.readMessage(m2) == Array(v.message2PayloadUtf8.utf8))
    let m3 = try initiator.writeMessage(payload: Array(v.message3PayloadUtf8.utf8))
    #expect(hx(m3) == v.message3Hex)
    #expect(try responder.readMessage(m3) == Array(v.message3PayloadUtf8.utf8))
    var ti = try initiator.split(), tr = try responder.split()
    #expect(hx(ti.handshakeHash) == v.handshakeHashHex && ti.handshakeHash == tr.handshakeHash)
    #expect(ti.securityCode() == v.securityCode && tr.securityCode() == v.securityCode)
    let ad = hex(v.transportAdHex)
    let c1 = try ti.send.seal(ad: ad, plaintext: Array("initiator-to-responder".utf8))
    #expect(hx(c1) == v.initiatorToResponderHex)
    let c2 = try tr.send.seal(ad: ad, plaintext: Array("responder-to-initiator".utf8))
    #expect(hx(c2) == v.responderToInitiatorHex)
    #expect(try tr.receive.open(ad: ad, ciphertext: c1) == Array("initiator-to-responder".utf8))
    #expect(try ti.receive.open(ad: ad, ciphertext: c2) == Array("responder-to-initiator".utf8))
}

@Test func handshakeSizesMatchTheDraft() throws {
    let ka = P2StaticKey.generate(), kb = P2StaticKey.generate()
    let a = P2NoiseXX(initiator: true, staticKey: ka, prologue: [1])
    let b = P2NoiseXX(initiator: false, staticKey: kb, prologue: [1])
    let m1 = try a.writeMessage(); _ = try b.readMessage(m1)
    let m2 = try b.writeMessage(); _ = try a.readMessage(m2)
    let m3 = try a.writeMessage(); _ = try b.readMessage(m3)
    let sizes: [Int] = [m1.count, m2.count, m3.count]
    #expect(sizes == [Int]([32, 96, 64]))
    // Mutual authentication: each side learned the other's static key.
    #expect(try a.split().remoteStatic == kb.publicKey)
    #expect(try b.split().remoteStatic == ka.publicKey)
}

@Test func differentPrologueTamperingReplayAndOutOfTurnAreRejected() throws {
    // Different prologue (e.g. a downgraded Capabilities record): the handshake must fail.
    let a = P2NoiseXX(initiator: true, staticKey: .generate(), prologue: [1, 2])
    let b = P2NoiseXX(initiator: false, staticKey: .generate(), prologue: [1, 3])
    let m1 = try a.writeMessage(); _ = try b.readMessage(m1)
    let m2 = try b.writeMessage()
    #expect(throws: P2Error.self) { try a.readMessage(m2) }
    // Out of turn.
    let c = P2NoiseXX(initiator: true, staticKey: .generate(), prologue: [])
    #expect(throws: P2Error.self) { try c.readMessage([UInt8](repeating: 0, count: 96)) }
    // Tampered message 2 / short message.
    let x = P2NoiseXX(initiator: true, staticKey: .generate(), prologue: [9])
    let y = P2NoiseXX(initiator: false, staticKey: .generate(), prologue: [9])
    let n1 = try x.writeMessage(); _ = try y.readMessage(n1)
    var n2 = try y.writeMessage(); n2[40] ^= 0x01
    #expect(throws: P2Error.self) { try x.readMessage(n2) }
    #expect(throws: P2Error.self) { try P2NoiseXX(initiator: false, staticKey: .generate(), prologue: []).readMessage([1, 2, 3]) }
}

@Test func transportRejectsTamperingWrongAdAndReplayWithoutAdvancingTheCounter() throws {
    let a = P2NoiseXX(initiator: true, staticKey: .generate(), prologue: [7])
    let b = P2NoiseXX(initiator: false, staticKey: .generate(), prologue: [7])
    let m1 = try a.writeMessage(); _ = try b.readMessage(m1)
    let m2 = try b.writeMessage(); _ = try a.readMessage(m2)
    let m3 = try a.writeMessage(); _ = try b.readMessage(m3)
    var ta = try a.split(), tb = try b.split()
    let ad: [UInt8] = [1, 2, 3]
    let c = try ta.send.seal(ad: ad, plaintext: Array("hola".utf8))
    var forged = c; forged[0] ^= 0x80
    #expect(throws: P2Error.self) { try tb.receive.open(ad: ad, ciphertext: forged) }
    #expect(throws: P2Error.self) { try tb.receive.open(ad: [9], ciphertext: c) }
    #expect(tb.receive.counter == 0)                                   // failures did not advance it
    #expect(try tb.receive.open(ad: ad, ciphertext: c) == Array("hola".utf8))
    #expect(throws: P2Error.self) { try tb.receive.open(ad: ad, ciphertext: c) }   // replay
}

@Test func lowOrderPublicKeyIsRejected() throws {
    let a = P2NoiseXX(initiator: true, staticKey: .generate(), prologue: [])
    _ = try a.writeMessage()
    let zeros = [UInt8](repeating: 0, count: 32) + [UInt8](repeating: 0, count: 48 + 16)   // e = 0 -> all-zero DH
    #expect(throws: P2Error.self) { try a.readMessage(zeros) }
}
