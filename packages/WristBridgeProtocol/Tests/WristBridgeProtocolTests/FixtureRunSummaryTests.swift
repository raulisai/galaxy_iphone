import Foundation
import Testing
@testable import WristBridgeProtocol

@Test func phoneObservationsAreUniqueAndSeparateFromScheduling() {
    let records = [
        FixtureRecord(runID: "a", fixtureID: "F001", observation: .scheduleAccepted),
        FixtureRecord(runID: "a", fixtureID: "F001", observation: .foregroundCallback),
        FixtureRecord(runID: "a", fixtureID: "F001", observation: .foregroundCallback),
        FixtureRecord(runID: "a", fixtureID: "F001", observation: .deliveredSnapshot),
        FixtureRecord(runID: "a", fixtureID: "F002", observation: .scheduleAccepted),
        FixtureRecord(runID: "a", fixtureID: "F002", observation: .cancelled),
        FixtureRecord(runID: "a", fixtureID: "F003", observation: .scheduleFailed),
        FixtureRecord(runID: "b", fixtureID: "F001", observation: .deliveredSnapshot)
    ]
    let summary = FixtureRunSummary(runID: "a", records: records)
    #expect(summary.scheduleAccepted == 2)
    #expect(summary.scheduleFailed == 1)
    #expect(summary.foregroundCallbacks == 1)
    #expect(summary.deliveredSnapshots == 1)
    #expect(summary.observedOnPhone == 1)
    #expect(summary.cancellationRequests == 1)
    #expect(FixtureRunSummary.grouped(records).map(\.runID) == ["a", "b"])
}

@Test func reportRetainsBoundsAndDoesNotHideIncompleteHistory() throws {
    let record = FixtureRecord(runID: "a", fixtureID: "F001", observation: .scheduleAccepted)
    let full = FixtureLedgerReport(records: Array(repeating: record, count: 1001),
                                   persistenceUncertain: false)
    #expect(full.records.count == 1000)
    #expect(full.historyMayBeIncomplete)
    #expect(full.summaries.first?.scheduleAccepted == 1)
    #expect(full.summaries.first?.observedOnPhone == 0)
    let uncertain = FixtureLedgerReport(records: [], persistenceUncertain: true)
    #expect(uncertain.historyMayBeIncomplete)
    let clean = FixtureLedgerReport(records: [record], persistenceUncertain: false)
    #expect(!clean.historyMayBeIncomplete)
    let data = try JSONEncoder().encode(clean)
    let restored = try JSONDecoder().decode(FixtureLedgerReport.self, from: data)
    #expect(restored.schemaVersion == 1)
    #expect(restored.records == clean.records)
    let text = String(decoding: data, as: UTF8.self)
    #expect(!text.contains("\"body\""))
    #expect(!text.contains("\"title\""))
}
