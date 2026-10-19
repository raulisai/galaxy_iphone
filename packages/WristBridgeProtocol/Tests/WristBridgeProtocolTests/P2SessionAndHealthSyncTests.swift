import Foundation
import Testing
@testable import WristBridgeProtocol

// MARK: Strict JSON

@Test func strictJsonRejectsWhatTheDraftForbids() throws {
    func bytes(_ s: String) -> [UInt8] { Array(s.utf8) }
    #expect(throws: P2Error.self) { try P2StrictJSON.parse([0xEF, 0xBB, 0xBF] + bytes("{}")) }          // BOM
    #expect(throws: P2Error.self) { try P2StrictJSON.parse(bytes(#"{"a":1,"a":2}"#)) }                   // duplicate key
    #expect(throws: P2Error.self) { try P2StrictJSON.parse(bytes("9007199254740992")) }                  // beyond 2^53-1
    #expect(throws: P2Error.self) { try P2StrictJSON.parse(bytes("[[[[[[[[[[1]]]]]]]]]]")) }             // depth > 8
    #expect(throws: P2Error.self) { try P2StrictJSON.parse(bytes("{} trailing")) }
    #expect(throws: P2Error.self) { try P2StrictJSON.parse([0x22, 0xC3, 0x28, 0x22]) }                   // invalid UTF-8
    #expect(throws: P2Error.self) { try P2StrictJSON.parse(bytes(#""\ud800""#)) }                        // lone surrogate
    #expect(try P2StrictJSON.parse(bytes(#"{"a":[1,true,null,"xé"],"b":{"c":-5}}"#))["a"]?.arrayValue?.count == 4)
    #expect(try P2StrictJSON.parse(bytes("9007199254740991")).intValue == 9_007_199_254_740_991)
}

@Test func strictJsonEncodeParseRoundTripKeepsOrderAndUnicode() throws {
    let value = P2JSON.object([("kind", .string("req")), ("n", .int(-3)), ("t", .string("ñ \"q\" \n")), ("l", .array([.bool(true), .null]))])
    #expect(try P2StrictJSON.parse(P2StrictJSON.encode(value)) == value)
    #expect(throws: P2Error.self) { try P2StrictJSON.encode(.object([("a", .int(1)), ("a", .int(2))])) }
    #expect(throws: P2Error.self) { try P2StrictJSON.encode(.int(Int64.max)) }
}

// MARK: Secure session

private func pairedSessions(maxAtt: Int = 64) throws -> (iphone: P2SecureSession, watch: P2SecureSession) {
    let i = P2NoiseXX(initiator: true, staticKey: .generate(), prologue: [4])
    let r = P2NoiseXX(initiator: false, staticKey: .generate(), prologue: [4])
    let m1 = try i.writeMessage(); _ = try r.readMessage(m1)
    let m2 = try r.writeMessage(); _ = try i.readMessage(m2)
    let m3 = try i.writeMessage(); _ = try r.readMessage(m3)
    return (P2SecureSession(keys: try i.split(), maxAttValueBytes: maxAtt), P2SecureSession(keys: try r.split(), maxAttValueBytes: maxAtt))
}

@Test func messagesRoundTripFragmentedAcrossFramesAndBothCodesMatch() throws {
    let (phone, watch) = try pairedSessions(maxAtt: 20)
    #expect(phone.securityCode == watch.securityCode && phone.sessionId == watch.sessionId)
    let message = P2Messages.helloRequest(16)
    let (id, frames) = try phone.send(message)
    #expect(id == 16 && frames.count > 3 && frames.allSatisfy { $0.count <= 20 })
    var received: P2SecureSession.Inbound = .none
    for frame in frames { received = try watch.receive(frame, atMs: 0) }
    #expect(received == .message(messageId: 16, json: message))
    // ACK for a known message is reported once; an unknown one is ignored.
    #expect(try phone.receive(watch.ackFrame(for: 16), atMs: 0) == .peerAck(messageId: 16))
    #expect(try phone.receive(watch.ackFrame(for: 16), atMs: 0) == .none)
}

@Test func forgedReplayedAndReorderedMessagesAreRejectedWithoutEffect() throws {
    let (phone, watch) = try pairedSessions()
    let first = try phone.send(P2Messages.helloRequest(1)).frames
    let second = try phone.send(P2Messages.helloRequest(2)).frames
    // Out of order: message 2 before 1 -> counter mismatch.
    #expect(throws: P2Error.badTag) { for f in second { _ = try watch.receive(f, atMs: 0) } }
    // The state was not advanced by the failure: in-order delivery of 1 still works.
    var got: P2SecureSession.Inbound = .none
    for f in first { got = try watch.receive(f, atMs: 0) }
    if case .message(_, let json) = got { #expect(json["requestId"]?.intValue == 1) } else { Issue.record("message 1 must be accepted") }
    // Replay of message 1.
    #expect(throws: P2Error.self) { for f in first { _ = try watch.receive(f, atMs: 0) } }
    // Bit flip in the ciphertext.
    var tampered = try phone.send(P2Messages.helloRequest(3)).frames
    tampered[0][tampered[0].count - 1] ^= 1
    #expect(throws: P2Error.badTag) { for f in tampered { _ = try watch.receive(f, atMs: 0) } }
}

// MARK: health.sync client against a fake watch

private final class FakeWatch {
    var samples: [(cursor: Int64, json: P2JSON)]
    var acked: Int64 = 0
    init(count: Int, steps: Bool = true) {
        samples = (1...count).map { i in
            let c = Int64(i)
            let base: [(String, P2JSON)] = [("c", .int(c)), ("m", .string("stepsDaily")), ("s", .int(1_800_000_000_000 - 3_600_000)),
                                            ("e", .int(1_800_000_000_000 + c * 1000)), ("v", .double(Double(i * 10))),
                                            ("src", .string("watch-sensor")), ("id", .string("id-\(i)")), ("ver", .int(1))]
            return (c, .object(base))
        }
    }
    func answer(_ request: P2JSON) -> P2JSON {
        let env = try! P2Messages.envelope(request)
        let p = env.payload!
        if p["op"]?.stringValue == "ack" {
            acked = p["through"]!.intValue!
            return P2Messages.response(env.requestId, "health.sync", [("op", .string("acked")), ("through", .int(acked))])
        }
        let after = p["after"]!.intValue!, max = Int(p["max"]!.intValue!)
        let list = samples.filter { $0.cursor > after }.prefix(max)
        let last = list.last?.cursor ?? after
        return P2Messages.response(env.requestId, "health.sync", [
            ("op", .string("batch")), ("deviceId", .string("watch-0123456789ab")), ("after", .int(after)), ("last", .int(last)),
            ("more", .bool(samples.contains { $0.cursor > last })), ("samples", .array(list.map(\.json)))])
    }
}

private func runSync(_ watch: FakeWatch, saved: inout [HealthSample], cursor: inout Int64, ingestResult: Bool = true) -> HealthSyncClient.Status {
    var store: [HealthSample] = saved; var cur = cursor
    let client = HealthSyncClient(loadCursor: { cur }, saveCursor: { cur = $0 },
                                  ingest: { batch in if ingestResult { store += batch }; return ingestResult })
    var outbox = client.start()
    var guardLoop = 0
    while !outbox.isEmpty, guardLoop < 20 {
        guardLoop += 1
        outbox = outbox.flatMap { client.handle(response: watch.answer($0)) }
    }
    saved = store; cursor = cur
    return client.status
}

@Test func syncPullsBatchesSavesThenAcknowledgesAndResumesFromTheCursor() {
    let watch = FakeWatch(count: 450)
    var saved: [HealthSample] = [], cursor: Int64 = 0
    #expect(runSync(watch, saved: &saved, cursor: &cursor) == .upToDate)
    #expect(saved.count == 450 && cursor == 450 && watch.acked == 450)
    // Nothing new: no further data and the cursor does not move.
    #expect(runSync(watch, saved: &saved, cursor: &cursor) == .upToDate)
    #expect(saved.count == 450)
}

@Test func syncNeverAcknowledgesWhenTheLedgerRefusesTheBatch() {
    let watch = FakeWatch(count: 10)
    var saved: [HealthSample] = [], cursor: Int64 = 0
    let status = runSync(watch, saved: &saved, cursor: &cursor, ingestResult: false)
    guard case .stalled = status else { Issue.record("must stall"); return }
    #expect(watch.acked == 0 && cursor == 0)          // nothing confirmed, nothing advanced
}

@Test func batchWithAnInvalidSampleIsRejectedAsAWholeAndLateResponsesAreIgnored() throws {
    let bad = P2Messages.response(1, "health.sync", [
        ("op", .string("batch")), ("deviceId", .string("w")), ("after", .int(0)), ("last", .int(2)), ("more", .bool(false)),
        ("samples", .array([
            .object([("c", .int(1)), ("m", .string("heartRate")), ("s", .int(1000)), ("e", .int(2000)), ("v", .int(300)),
                     ("src", .string("watch-sensor")), ("id", .string("a")), ("ver", .int(1)), ("min", .int(300)), ("max", .int(300))])]))])
    #expect(throws: P2Error.invalidPayload) { try P2Messages.parseBatch(bad) }
    let client = HealthSyncClient(loadCursor: { 0 }, saveCursor: { _ in }, ingest: { _ in true })
    #expect(client.handle(response: bad).isEmpty && client.status == .idle)            // not requested: ignored
}

@Test func syncedSamplesLandInTheLedgerAndRepeatingTheBatchDoesNotDuplicate() throws {
    var ledger = HealthLedger()
    let watch = FakeWatch(count: 5)
    let batch = try P2Messages.parseBatch(watch.answer(P2Messages.healthCursor(1, after: 0)))
    let now = Date(timeIntervalSince1970: 1_800_000_100)
    #expect(ledger.commit(batch: batch.samples, now: now).inserted == 5)
    #expect(ledger.commit(batch: batch.samples, now: now).duplicates == 5)
    #expect(ledger.total(.stepsDaily, on: batch.samples[0].end) == 50)
}
