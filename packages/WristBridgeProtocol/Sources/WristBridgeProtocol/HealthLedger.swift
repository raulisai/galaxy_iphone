import Foundation

// P7-02 domain core: a persistent, idempotent ledger for health samples that will
// arrive from the watch. It is deliberately independent of the (not yet frozen)
// wire format: it defines what may be *stored* and when a batch may be
// *acknowledged*. No HealthKit, no permissions, no BLE.

public enum HealthMetric: String, Codable, CaseIterable, Sendable {
    case steps, distance, activeEnergy, heartRate

    public var unit: String {
        switch self {
        case .steps: "count"
        case .distance: "m"
        case .activeEnergy: "kcal"
        case .heartRate: "bpm"
        }
    }
    /// Counters travel as per-interval deltas; heart rate as an interval average.
    public var isCumulativeDelta: Bool { self != .heartRate }
    fileprivate var maxValue: Double {
        switch self {
        case .steps: 100_000
        case .distance: 100_000
        case .activeEnergy: 10_000
        case .heartRate: 250
        }
    }
}

public struct HealthSample: Codable, Equatable, Sendable, Identifiable {
    public var id: String { syncId }
    public let deviceId: String
    public let metric: HealthMetric
    public let start: Date
    public let end: Date
    /// Delta for counters, average for heart rate.
    public let value: Double
    public let minimum: Double?
    public let maximum: Double?
    public let unit: String
    public let source: String
    public let syncId: String
    public let syncVersion: Int

    public init(deviceId: String, metric: HealthMetric, start: Date, end: Date, value: Double,
                minimum: Double? = nil, maximum: Double? = nil, source: String = "watch-sensor",
                syncId: String, syncVersion: Int = 1) {
        self.deviceId = deviceId; self.metric = metric; self.start = start; self.end = end
        self.value = value; self.minimum = minimum; self.maximum = maximum
        self.unit = metric.unit; self.source = source; self.syncId = syncId; self.syncVersion = syncVersion
    }
}

/// The watch dropped intervals it could not keep. Shown as "sin datos", never interpolated.
public struct HealthGap: Codable, Equatable, Sendable {
    public let metric: HealthMetric
    public let start: Date
    public let end: Date
    public let missingIntervals: Int
    public init(metric: HealthMetric, start: Date, end: Date, missingIntervals: Int) {
        self.metric = metric; self.start = start; self.end = end; self.missingIntervals = missingIntervals
    }
}

public enum HealthRejection: String, Codable, Equatable, Sendable {
    case invalidIdentifier, invalidInterval, invalidValue, unitMismatch, expired, ledgerFull
}

public enum HealthCommitOutcome: Equatable, Sendable {
    case inserted, updated, duplicate, staleVersion
    case rejected(HealthRejection)
}

public struct HealthBatchResult: Equatable, Sendable {
    public private(set) var inserted = 0, updated = 0, duplicates = 0, stale = 0
    public private(set) var rejected: [String: HealthRejection] = [:]
    public var accepted: Int { inserted + updated + duplicates + stale }
    /// A batch may be acknowledged to the watch only if nothing was rejected AND it was
    /// persisted (the repository guarantees the latter before returning).
    public var fullyAccepted: Bool { rejected.isEmpty }
    mutating func record(_ outcome: HealthCommitOutcome, for id: String) {
        switch outcome {
        case .inserted: inserted += 1
        case .updated: updated += 1
        case .duplicate: duplicates += 1
        case .staleVersion: stale += 1
        case .rejected(let reason): rejected[id] = reason
        }
    }
}

public struct HealthCursor: Equatable, Sendable {
    public let metric: HealthMetric
    public let syncId: String
    public let end: Date
}

public struct HealthLedger: Codable, Sendable {
    public let version: Int
    public private(set) var retentionDays: Int
    public private(set) var gaps: [HealthGap]
    private var store: [String: HealthSample]

    public static let maxSamples = 50_000
    public static let maxGaps = 500
    public static let maxIntervalSeconds: TimeInterval = 6 * 3600
    public static let allowedRetention = [7, 30, 90]

    public init(retentionDays: Int = 30) {
        version = 1
        self.retentionDays = Self.allowedRetention.contains(retentionDays) ? retentionDays : 30
        gaps = []; store = [:]
    }

    public var samples: [HealthSample] { store.values.sorted { ($0.end, $0.syncId) < ($1.end, $1.syncId) } }
    public var count: Int { store.count }

    // MARK: Commit

    @discardableResult
    public mutating func commit(_ sample: HealthSample, now: Date = Date()) -> HealthCommitOutcome {
        if let reason = Self.validationFailure(sample, retentionDays: retentionDays, now: now) { return .rejected(reason) }
        if let existing = store[sample.syncId] {
            if sample.syncVersion > existing.syncVersion { store[sample.syncId] = sample; return .updated }
            return sample.syncVersion == existing.syncVersion ? .duplicate : .staleVersion
        }
        guard store.count < Self.maxSamples else { return .rejected(.ledgerFull) }
        store[sample.syncId] = sample
        return .inserted
    }

    public mutating func commit(batch: [HealthSample], gaps newGaps: [HealthGap] = [], now: Date = Date()) -> HealthBatchResult {
        var result = HealthBatchResult()
        for sample in batch { result.record(commit(sample, now: now), for: sample.syncId) }
        for gap in newGaps where gap.end > gap.start && gap.missingIntervals > 0
            && !gaps.contains(gap) && gaps.count < Self.maxGaps {
            gaps.append(gap)
        }
        return result
    }

    private static func validationFailure(_ s: HealthSample, retentionDays: Int, now: Date) -> HealthRejection? {
        func okId(_ text: String, max: Int) -> Bool {
            !text.isEmpty && text.utf8.count <= max
                && text.unicodeScalars.allSatisfy { $0.isASCII && ($0.properties.isAlphabetic || ("0"..."9").contains(Character($0))
                    || "._:-".unicodeScalars.contains($0)) }
        }
        guard okId(s.syncId, max: 128), okId(s.deviceId, max: 64), okId(s.source, max: 32),
              s.syncVersion >= 1 else { return .invalidIdentifier }
        guard s.unit == s.metric.unit else { return .unitMismatch }
        let length = s.end.timeIntervalSince(s.start)
        guard s.start.timeIntervalSince1970.isFinite, s.end.timeIntervalSince1970.isFinite,
              length > 0, length <= maxIntervalSeconds,
              s.end <= now.addingTimeInterval(300) else { return .invalidInterval }
        if s.end < now.addingTimeInterval(-Double(retentionDays) * 86_400) { return .expired }
        let limit = s.metric.maxValue
        func ok(_ v: Double) -> Bool { v.isFinite && v >= 0 && v <= limit }
        guard ok(s.value) else { return .invalidValue }
        if s.metric == .heartRate {
            guard s.value >= 20, let low = s.minimum, let high = s.maximum, ok(low), ok(high), low >= 20,
                  low <= s.value, s.value <= high else { return .invalidValue }
        } else if s.minimum != nil || s.maximum != nil { return .invalidValue }
        return nil
    }

    // MARK: Reading

    /// Last committed sample per metric, by interval end. This is the cursor the iPhone
    /// would send to ask only for newer data once the channel exists.
    public func cursor(for metric: HealthMetric) -> HealthCursor? {
        store.values.filter { $0.metric == metric }
            .max { ($0.end, $0.syncId) < ($1.end, $1.syncId) }
            .map { HealthCursor(metric: metric, syncId: $0.syncId, end: $0.end) }
    }

    public func latest(_ metric: HealthMetric) -> HealthSample? {
        store.values.filter { $0.metric == metric }.max { ($0.end, $0.syncId) < ($1.end, $1.syncId) }
    }

    /// Sum of deltas whose interval *starts* on the given calendar day. Deltas never produce
    /// negative activity: a counter reset on the watch must arrive as a new, non-negative delta.
    public func total(_ metric: HealthMetric, on day: Date, calendar: Calendar = .current) -> Double? {
        guard metric.isCumulativeDelta else { return nil }
        let matching = store.values.filter { $0.metric == metric && calendar.isDate($0.start, inSameDayAs: day) }
        return matching.isEmpty ? nil : matching.reduce(0) { $0 + $1.value }
    }

    public struct HeartRateDay: Equatable, Sendable { public let minimum: Double, average: Double, maximum: Double, samples: Int }
    public func heartRate(on day: Date, calendar: Calendar = .current) -> HeartRateDay? {
        let matching = store.values.filter { $0.metric == .heartRate && calendar.isDate($0.start, inSameDayAs: day) }
        guard !matching.isEmpty else { return nil }
        let low = matching.compactMap(\.minimum).min() ?? 0, high = matching.compactMap(\.maximum).max() ?? 0
        let weighted = matching.reduce(0.0) { $0 + $1.value * $1.end.timeIntervalSince($1.start) }
        let span = matching.reduce(0.0) { $0 + $1.end.timeIntervalSince($1.start) }
        return HeartRateDay(minimum: low, average: span > 0 ? weighted / span : 0, maximum: high, samples: matching.count)
    }

    public func series(_ metric: HealthMetric, from: Date, to: Date) -> [HealthSample] {
        samples.filter { $0.metric == metric && $0.start >= from && $0.end <= to }
    }

    // MARK: Retention

    public mutating func setRetention(days: Int) {
        retentionDays = Self.allowedRetention.contains(days) ? days : 30
    }

    /// Removes expired data. Expired samples are also *rejected* on commit, so a replayed
    /// old batch cannot resurrect them after eviction (S04).
    public mutating func applyRetention(now: Date = Date()) {
        let cutoff = now.addingTimeInterval(-Double(retentionDays) * 86_400)
        store = store.filter { $0.value.end >= cutoff }
        gaps.removeAll { $0.end < cutoff }
    }

    public func validate() throws {
        guard version == 1, Self.allowedRetention.contains(retentionDays),
              store.count <= Self.maxSamples, gaps.count <= Self.maxGaps else {
            throw JournalStorageError.unsupportedOrCorrupt
        }
        for (key, sample) in store {
            guard key == sample.syncId,
                  Self.validationFailure(sample, retentionDays: 90_000, now: .distantFuture) != .invalidIdentifier,
                  sample.unit == sample.metric.unit else { throw JournalStorageError.unsupportedOrCorrupt }
        }
    }
}
