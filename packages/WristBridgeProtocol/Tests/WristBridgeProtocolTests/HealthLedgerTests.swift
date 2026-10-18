import Foundation
import Testing
@testable import WristBridgeProtocol

private let now = Date(timeIntervalSince1970: 1_800_000_000)
private func at(_ minutes: Double) -> Date { now.addingTimeInterval(-minutes * 60) }
private func steps(_ id: String, minutesAgo: Double, value: Double = 120, version: Int = 1) -> HealthSample {
    HealthSample(deviceId: "watch-1", metric: .steps, start: at(minutesAgo + 5), end: at(minutesAgo),
                 value: value, syncId: id, syncVersion: version)
}
private func heart(_ id: String, minutesAgo: Double, avg: Double = 70, low: Double = 60, high: Double = 90) -> HealthSample {
    HealthSample(deviceId: "watch-1", metric: .heartRate, start: at(minutesAgo + 5), end: at(minutesAgo),
                 value: avg, minimum: low, maximum: high, syncId: id)
}
private func tempFile() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("health-\(UUID().uuidString).json")
}

// S02 · the same syncId three times, with restarts, is one logical sample.
@Test func sameSyncIdImportedThreeTimesWithRestartsIsOneSample() throws {
    let repo = HealthLedgerRepository(file: tempFile()); defer { try? repo.delete() }
    let batch = [steps("s-1", minutesAgo: 10), heart("h-1", minutesAgo: 10)]
    #expect(try repo.ingest(batch, now: now).inserted == 2)
    #expect(try repo.ingest(batch, now: now).duplicates == 2)       // "restart": fresh load from disk
    #expect(try repo.ingest(batch, now: now).duplicates == 2)
    #expect(try repo.load()?.count == 2)
}

// S03 · crash after save and before ACK: the replay is idempotent and still acceptable.
@Test func replayAfterCrashBetweenSaveAndAckDoesNotDuplicate() throws {
    let repo = HealthLedgerRepository(file: tempFile()); defer { try? repo.delete() }
    let batch = (0..<5).map { steps("c-\($0)", minutesAgo: Double($0) * 5 + 5) }
    let first = try repo.ingest(batch, now: now)
    #expect(first.fullyAccepted && first.inserted == 5)
    let replay = try repo.ingest(batch, now: now)
    #expect(replay.fullyAccepted && replay.inserted == 0 && replay.duplicates == 5)
}

// S04 · more entries than the old 500-id cache, then repeat the first: still no duplicate.
@Test func moreThanFiveHundredSamplesThenRepeatingTheFirstDoesNotDuplicate() {
    var ledger = HealthLedger()
    let batch = (0..<800).map { steps("m-\($0)", minutesAgo: Double($0) * 5 + 5) }
    #expect(ledger.commit(batch: batch, now: now).inserted == 800)
    #expect(ledger.commit(steps("m-0", minutesAgo: 5), now: now) == .duplicate)
    #expect(ledger.count == 800)
}

// S04 (retention side) · evicted samples cannot be resurrected by an old replay.
@Test func expiredSamplesAreRemovedAndCannotComeBackByReplay() {
    var ledger = HealthLedger(retentionDays: 7)
    let old = steps("old", minutesAgo: 8 * 24 * 60)
    #expect(ledger.commit(old, now: now) == .rejected(.expired))
    ledger.commit(steps("recent", minutesAgo: 10), now: now)
    ledger.applyRetention(now: now.addingTimeInterval(8 * 86_400))
    #expect(ledger.count == 0)
}

// S05 · counter reset: never fabricate negative activity, never double count a day.
@Test func counterResetIsNeverImportedAsNegativeActivity() {
    var ledger = HealthLedger()
    #expect(ledger.commit(steps("r-1", minutesAgo: 20, value: 500), now: now) == .inserted)
    #expect(ledger.commit(steps("r-2", minutesAgo: 15, value: -4_500), now: now) == .rejected(.invalidValue))
    #expect(ledger.commit(steps("r-3", minutesAgo: 10, value: 30), now: now) == .inserted)
    #expect(ledger.total(.steps, on: at(15), calendar: utc) == 530)
}

@Test func totalsAreSummedPerCalendarDayWithoutRepeatingTheSameInterval() {
    var ledger = HealthLedger()
    ledger.commit(steps("d-1", minutesAgo: 30, value: 100), now: now)
    ledger.commit(steps("d-1", minutesAgo: 30, value: 100), now: now)        // duplicate
    ledger.commit(steps("d-2", minutesAgo: 20, value: 50), now: now)
    #expect(ledger.total(.steps, on: at(25), calendar: utc) == 150)
    #expect(ledger.total(.heartRate, on: at(25), calendar: utc) == nil)
}

// S06 · syncVersion policy.
@Test func higherVersionUpdatesLowerIsStaleEqualIsDuplicate() {
    var ledger = HealthLedger()
    #expect(ledger.commit(steps("v-1", minutesAgo: 10, value: 100, version: 2), now: now) == .inserted)
    #expect(ledger.commit(steps("v-1", minutesAgo: 10, value: 150, version: 3), now: now) == .updated)
    #expect(ledger.commit(steps("v-1", minutesAgo: 10, value: 999, version: 1), now: now) == .staleVersion)
    #expect(ledger.commit(steps("v-1", minutesAgo: 10, value: 999, version: 3), now: now) == .duplicate)
    #expect(ledger.latest(.steps)?.value == 150)
}

@Test func invalidSamplesAreRejectedWithAReasonAndBatchIsNotFullyAccepted() {
    var ledger = HealthLedger()
    let bad = [
        HealthSample(deviceId: "w", metric: .steps, start: at(5), end: at(10), value: 1, syncId: "b-1"),            // end < start
        steps("b-2", minutesAgo: 10, value: .nan),
        HealthSample(deviceId: "w", metric: .heartRate, start: at(15), end: at(10), value: 70, syncId: "b-3"),       // missing min/max
        HealthSample(deviceId: "w", metric: .steps, start: at(15), end: at(10), value: 1, syncId: "bad id!"),
        HealthSample(deviceId: "w", metric: .steps, start: at(-20), end: at(-10), value: 1, syncId: "b-5")          // future
    ]
    let result = ledger.commit(batch: bad, now: now)
    #expect(!result.fullyAccepted && result.rejected.count == 5 && ledger.count == 0)
}

@Test func heartRateRequiresConsistentMinAverageMax() {
    var ledger = HealthLedger()
    #expect(ledger.commit(heart("h-1", minutesAgo: 10, avg: 70, low: 80, high: 90), now: now) == .rejected(.invalidValue))
    #expect(ledger.commit(heart("h-2", minutesAgo: 10, avg: 300, low: 60, high: 90), now: now) == .rejected(.invalidValue))
    #expect(ledger.commit(heart("h-3", minutesAgo: 10), now: now) == .inserted)
    let day = ledger.heartRate(on: at(10), calendar: utc)
    #expect(day?.minimum == 60 && day?.maximum == 90 && day?.average == 70)
}

@Test func cursorPointsAtTheNewestCommittedIntervalPerMetric() {
    var ledger = HealthLedger()
    ledger.commit(steps("a", minutesAgo: 30), now: now)
    ledger.commit(steps("b", minutesAgo: 10), now: now)
    ledger.commit(steps("c", minutesAgo: 20), now: now)
    #expect(ledger.cursor(for: .steps)?.syncId == "b")
    #expect(ledger.cursor(for: .heartRate) == nil)
}

@Test func gapsAreRecordedOnceAndNeverInterpolated() {
    var ledger = HealthLedger()
    let gap = HealthGap(metric: .steps, start: at(120), end: at(60), missingIntervals: 12)
    _ = ledger.commit(batch: [], gaps: [gap, gap], now: now)
    #expect(ledger.gaps == [gap] && ledger.count == 0)
}

@Test func ledgerSurvivesReopenAndPreservesUnreadableOrFutureFiles() throws {
    let url = tempFile(); let repo = HealthLedgerRepository(file: url); defer { try? repo.delete() }
    _ = try repo.ingest([steps("p-1", minutesAgo: 10)], now: now)
    #expect(try repo.load()?.latest(.steps)?.syncId == "p-1")
    let future = Data(#"{"version":2,"retentionDays":30,"gaps":[],"store":{}}"#.utf8)
    try future.write(to: url)
    #expect(throws: (any Error).self) { try repo.ingest([steps("p-2", minutesAgo: 10)], now: now) }
    #expect(try Data(contentsOf: url) == future)
}

private var utc: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }
