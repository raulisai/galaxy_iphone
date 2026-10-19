import Foundation

/// Counts distinct fixture IDs per run; repeated snapshots do not imply new delivery.
public struct FixtureRunSummary: Codable, Equatable, Sendable, Identifiable {
    public var id: String { runID }
    public let runID: String
    public let scheduleAccepted: Int
    public let scheduleFailed: Int
    public let foregroundCallbacks: Int
    public let deliveredSnapshots: Int
    public let observedOnPhone: Int
    public let cancellationRequests: Int

    public init(runID: String, records: [FixtureRecord]) {
        self.runID = runID
        let matching = records.filter { $0.runID == runID }
        func ids(_ observations: [FixtureObservation]) -> Set<String> {
            Set(matching.filter { observations.contains($0.observation) }.map(\.fixtureID))
        }
        scheduleAccepted = ids([.scheduleAccepted]).count
        scheduleFailed = ids([.scheduleFailed]).count
        foregroundCallbacks = ids([.foregroundCallback]).count
        deliveredSnapshots = ids([.deliveredSnapshot]).count
        observedOnPhone = ids([.foregroundCallback, .deliveredSnapshot]).count
        cancellationRequests = ids([.cancelled]).count
    }

    public static func grouped(_ records: [FixtureRecord]) -> [Self] {
        Dictionary(grouping: records, by: \.runID)
            .map { Self(runID: $0.key, records: $0.value) }
            .sorted { $0.runID < $1.runID }
    }
}

/// Local fixture evidence only; contains no notification text or watch readiness.
public struct FixtureLedgerReport: Codable, Sendable {
    public let schemaVersion: Int
    public let generatedAt: Date
    public let retainedRecordLimit: Int
    public let historyMayBeIncomplete: Bool
    public let summaries: [FixtureRunSummary]
    public let records: [FixtureRecord]

    public init(records: [FixtureRecord], persistenceUncertain: Bool,
                generatedAt: Date = Date()) {
        schemaVersion = 1
        self.generatedAt = generatedAt
        retainedRecordLimit = 1000
        self.records = Array(records.suffix(retainedRecordLimit))
        historyMayBeIncomplete = persistenceUncertain || records.count >= retainedRecordLimit
        summaries = FixtureRunSummary.grouped(self.records)
    }
}
