import Foundation
import Testing
@testable import WristBridgeProtocol

@Test func connectionNeverProvesANCS() {
    var state = BridgeState()
    for event: BridgeEvent in [.authorized, .connecting, .connected, .serviceFound] {
        state.apply(event)
        #expect(!state.ancsVerified)
        #expect(!state.sensitiveChannelEnabled)
    }
    #expect(state.channel == .serviceFound)
}

@Test func lateConnectionAfterRevocationIsIgnored() {
    var state = BridgeState()
    state.apply(.authorized)
    state.apply(.connecting)
    state.apply(.authorizationRemoved)
    state.apply(.connected)
    state.apply(.serviceFound)
    #expect(state.authorization == .unknown)
    #expect(state.channel == .idle)
}

@Test func cancelRetryAndStopPreserveHonestState() {
    var state = BridgeState()
    state.apply(.pickerOpened)
    state.apply(.pickerCancelled)
    #expect(state.authorization == .cancelled)
    state.apply(.pickerOpened)
    state.apply(.authorized)
    state.apply(.pickerCancelled)
    #expect(state.authorization == .authorized)
    state.apply(.connecting)
    state.apply(.radioUnavailable)
    #expect(state.channel == .unavailable)
    state.apply(.stopped)
    #expect(state.channel == .idle)
    #expect(state.authorization == .authorized)
}

@Test func fixtureIDsAndTimingAreReproducible() throws {
    let first = try FixturePlan(runID: "run-1042", count: 50)
    #expect(first.notifications == (try FixturePlan(runID: "run-1042", count: 50)).notifications)
    #expect(Set(first.notifications.map(\.id)).count == 50)
    #expect(first.notifications.first?.id == "wristbridge.run-1042.F001")
    #expect(first.notifications.last?.delay == 157)
    #expect(first.notifications[1].body.contains("🇲🇽"))
    #expect(first.notifications[2].body.utf8.count > 1024)
    #expect(first.notifications[0].id != (try FixturePlan(runID: "other")).notifications[0].id)
}

@Test(arguments: ["", "../personal", "a.b", "nombre con espacios", "🇲🇽", String(repeating: "a", count: 65)])
func unsafeRunIDIsRejected(runID: String) {
    #expect(throws: FixturePlanError.invalidRunID) { try FixturePlan(runID: runID) }
}

@Test(arguments: [0, -1, 51, Int.max])
func batchRemainsBelowSystemPendingLimit(count: Int) {
    #expect(throws: FixturePlanError.invalidCount) { try FixturePlan(runID: "run", count: count) }
}

@Test(arguments: [Double.nan, Double.infinity, -1, 0, 4, 3601])
func invalidDelayIsRejected(delay: Double) {
    #expect(throws: FixturePlanError.invalidTiming) { try FixturePlan(runID: "run", initialDelay: delay) }
}

@Test func metadataDistinguishesSchedulingAndDelivery() throws {
    let scheduled = FixtureRecord(runID: "run", fixtureID: "F001", observation: .scheduleAccepted)
    let data = try JSONEncoder().encode(scheduled)
    #expect(try JSONDecoder().decode(FixtureRecord.self, from: data) == scheduled)
    #expect(scheduled.observation != .deliveredSnapshot)
    let text = String(decoding: data, as: UTF8.self)
    #expect(!text.contains("body"))
    #expect(!text.contains("title"))
}
