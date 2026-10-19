import Foundation
import Testing
@testable import WristBridgeProtocol

// Runs the 68 hand-derived vectors Windows published (test/fixtures/shared/p2-framing-vectors.json)
// against the Swift codec. The expected results were NOT produced by this code. Status stays
// PROPUESTO/NOT_RUN for the joint gate: this only proves Swift agrees with the shared vectors.

private struct Vector: Decodable {
    let id: String
    let schema: String
    let bytesHex: String?
    let framesHex: [String]?
    let context: Context?
    let expected: Expected
    struct Context: Decodable {
        let limitsUniform: Int?
        let knownMaxAttValueBytes: Int?
        let timesMs: [Int64]?
        let outboundInFlight: [Int]?
        let encode: Encode?
    }
    struct Encode: Decodable { let type: String; let flags: Int; let messageId: Int; let maxAttValueBytes: Int; let bodyHex: String }
    struct Expected: Decodable {
        let outcome: String?, error: String?, frameType: String?
        let messageId: Int?, errorCode: Int?, relatedMessageId: Int?
        let steps: [String]?, bodyLength: Int?, bodyHex: String?
        let frameSizes: [Int]?, timedOutCount: Int?, closeSessionSteps: [Int]?
    }
}
private struct Manifest: Decodable { let vectors: [Vector] }

private func hex(_ text: String) -> [UInt8] {
    let clean = text.filter { !$0.isWhitespace }
    var out: [UInt8] = []; var index = clean.startIndex
    while index < clean.endIndex {
        let next = clean.index(index, offsetBy: 2)
        out.append(UInt8(clean[index..<next], radix: 16)!)
        index = next
    }
    return out
}

private func loadVectors() throws -> [Vector] {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }          // file, test dir, Tests, package, packages -> repo root
    url.appendPathComponent("test/fixtures/shared/p2-framing-vectors.json")
    return try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: url)).vectors
}

private func describe(_ step: P2Step) -> String {
    switch step {
    case .partial: "partial"
    case .complete: "complete"
    case .failed(let error, _): "error:\(error.name)"
    case .cancelled: "cancelled"
    case .cancelNoop: "cancel-noop"
    case .ack(let known): "ack:\(known ? "known" : "unknown")"
    case .ignored: "ignored"
    case .peerError(let code, let related): "peer-error:\(code):\(related)"
    }
}

@Test func sharedFramingVectorsAreLoadedAndCoverEveryCase() throws {
    let vectors = try loadVectors()
    #expect(vectors.count >= 68)
    for family in ["F01", "F02", "F03", "F04", "F05", "F06", "F07", "F10", "F11", "F12"] {
        #expect(vectors.contains { $0.id.hasPrefix(family) }, "missing \(family)")
    }
}

@Test func swiftAgreesWithEverySharedFramingVector() throws {
    var failures: [String] = []
    var executed = 0
    let all = try loadVectors()
    for vector in all {
        executed += 1
        switch vector.schema {
        case "p2.frame":
            let result = P2Codec.parse(hex(vector.bytesHex ?? ""))
            let e = vector.expected
            switch (e.outcome, result) {
            case ("ignored", .ignored): break
            case ("error", .failure(let error)) where error.name == e.error: break
            case ("frame", .frame(let frame)):
                let types: [P2FrameType: String] = [.data: "DATA", .ack: "ACK", .error: "ERROR", .cancel: "CANCEL", .handshake: "HANDSHAKE"]
                var ok = types[frame.type] == e.frameType && Int(frame.messageId) == e.messageId
                if frame.type == .error {
                    let code = Int(frame.payload[0]) << 8 | Int(frame.payload[1])
                    let related = Int(frame.payload[2]) << 8 | Int(frame.payload[3])
                    ok = ok && code == e.errorCode && related == e.relatedMessageId
                }
                if !ok { failures.append("\(vector.id): frame mismatch \(frame)") }
            default: failures.append("\(vector.id): expected \(e.outcome ?? "?") \(e.error ?? ""), got \(result)")
            }
        case "p2.encode":
            let enc = vector.context!.encode!
            do {
                _ = try P2Codec.encode(type: enc.type == "DATA" ? .data : .handshake, flags: UInt8(enc.flags),
                                       messageId: UInt16(enc.messageId), body: hex(enc.bodyHex), maxAttValueBytes: enc.maxAttValueBytes)
                failures.append("\(vector.id): expected \(vector.expected.error ?? "error") but encoding succeeded")
            } catch let error as P2Error {
                if error.name != vector.expected.error { failures.append("\(vector.id): got \(error.name)") }
            }
        case "p2.stream":
            let frames = (vector.framesHex ?? []).map(hex)
            var reassembler = P2Reassembler(
                limits: vector.context?.limitsUniform.map(P2Limits.uniform) ?? P2Limits(),
                knownMaxAttValueBytes: vector.context?.knownMaxAttValueBytes,
                outboundInFlight: Set((vector.context?.outboundInFlight ?? []).map { UInt16($0) }))
            let times = vector.context?.timesMs ?? []
            var steps: [String] = [], closed: [Int] = [], lastBody: [UInt8]?
            for (index, frame) in frames.enumerated() {
                let step = reassembler.accept(frame, atMs: index < times.count ? times[index] : 0)
                steps.append(describe(step))
                if case .failed(_, true) = step { closed.append(index) }
                if case .complete(_, _, let body) = step { lastBody = body }
            }
            let e = vector.expected
            if let expected = e.steps, expected != steps { failures.append("\(vector.id): steps \(steps) != \(expected)") }
            if let length = e.bodyLength, lastBody?.count != length { failures.append("\(vector.id): bodyLength") }
            if let body = e.bodyHex, lastBody != hex(body) { failures.append("\(vector.id): body bytes") }
            if let timed = e.timedOutCount, reassembler.timedOutCount != timed { failures.append("\(vector.id): timedOut \(reassembler.timedOutCount)") }
            if let close = e.closeSessionSteps, close != closed { failures.append("\(vector.id): closeSession \(closed) != \(close)") }
            if let sizes = e.frameSizes, sizes != frames.map(\.count) { failures.append("\(vector.id): frameSizes") }
            // The encoder must reproduce the vector's frames exactly.
            if let enc = vector.context?.encode {
                let produced = try? P2Codec.encode(type: enc.type == "DATA" ? .data : .handshake, flags: UInt8(enc.flags),
                                                   messageId: UInt16(enc.messageId), body: hex(enc.bodyHex), maxAttValueBytes: enc.maxAttValueBytes)
                if produced != frames { failures.append("\(vector.id): encoder output differs") }
            }
        default: failures.append("\(vector.id): unknown schema \(vector.schema)")
        }
    }
    #expect(executed == all.count && executed >= 68)
    let report = "\(failures.count) vector(s) differ:\n" + failures.joined(separator: "\n")
    #expect(failures.isEmpty, Comment(rawValue: report))
}

// Additional failure-mode guarantees beyond the shared vectors.

@Test func nothingPartialIsEverDeliveredAndResetDropsTheOpenMessage() {
    var reassembler = P2Reassembler()
    let frames = try! P2Codec.encode(type: .data, messageId: 9, body: Array(0..<60), maxAttValueBytes: 20)
    #expect(frames.count > 2)
    for frame in frames.dropLast() { #expect(reassembler.accept(frame) == .partial) }
    reassembler.reset()                                        // link lost / Service Changed
    #expect(!reassembler.hasOpenMessage)
    guard case .failed(.sequence, _) = reassembler.accept(frames.last!) else { Issue.record("late tail must be an orphan"); return }
}

@Test func threeConsecutiveMalformedFramesCloseTheSessionButAGoodFrameResetsTheCount() {
    var r = P2Reassembler()
    let bad: [UInt8] = [0x01, 0x81, 0x02, 0, 1, 0, 0, 0, 1]   // reserved flag set
    for index in 0..<2 { guard case .failed(.malformedFrame, false) = r.accept(bad, atMs: Int64(index)) else { Issue.record("close too early"); return } }
    _ = r.accept(P2Codec.encodeAck(messageId: 1))             // a valid frame resets the streak
    for _ in 0..<2 { guard case .failed(.malformedFrame, false) = r.accept(bad) else { Issue.record("streak not reset"); return } }
    guard case .failed(.malformedFrame, true) = r.accept(bad) else { Issue.record("third in a row must close"); return }
}

@Test func errorCodesAreUniqueAndSessionClosingSetMatchesTheDraft() {
    #expect(Set(P2Error.allCases.map(\.rawValue)).count == P2Error.allCases.count)
    #expect(Set(P2Error.allCases.filter(\.closesSession)) ==
            [.incompatibleFrame, .incompatibleMajor, .unsupportedType, .identityChanged])
    for error in P2Error.allCases { #expect(!error.userFacingSummary.isEmpty && P2Error.named(error.name) == error) }
}

@Test func encodedErrorFrameRoundTripsAndKeepsTheHeaderMessageIdAtZero() {
    let bytes = P2Codec.encodeError(.badTag, related: nil)
    #expect(bytes.count == 13)
    guard case .frame(let frame) = P2Codec.parse(bytes) else { Issue.record("must parse"); return }
    #expect(frame.messageId == 0 && frame.payload == [0x00, 0x0A, 0xFF, 0xFF])
}

@Test func oversizeIsRejectedBeforeAnyBodyIsKept() {
    var r = P2Reassembler(limits: .uniform(100))
    let first: [UInt8] = [0x01, 0x81, 0x00, 0, 1, 0, 0, 0, 5, 0, 0, 0x10, 0, 1, 2, 3]   // totalLength 4096
    guard case .failed(.oversize, false) = r.accept(first) else { Issue.record("must be oversize"); return }
    #expect(!r.hasOpenMessage)
}
