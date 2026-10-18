import Foundation

public enum RecoveryCampaignStatus: String, Codable, Sendable { case running, completed, cancelled, interrupted }
public enum RecoveryTrialOutcome: String, Codable, Sendable { case success, timedOut, cancelled, interrupted }
public struct RecoveryTrial: Codable, Identifiable, Sendable {
    public var id: Int { number }
    public let number: Int
    public let outcome: RecoveryTrialOutcome
    public let elapsedSeconds: Double?
}

/// Foreground, controlled configuration-channel trials only. Never an ANCS oracle.
public struct RecoveryCampaign: Codable, Sendable {
    public let version: Int
    public let id: UUID
    public let startedAt: Date
    public private(set) var status: RecoveryCampaignStatus
    public private(set) var trials: [RecoveryTrial]
    public private(set) var pendingTrial: Int?
    public static let target = 20
    public static let deadlineSeconds = 30.0
    public init() {
        version = 1; id = UUID(); startedAt = Date(); status = .running
        trials = []; pendingTrial = nil
    }
    public var withinDeadlineCount: Int {
        trials.filter { $0.outcome == .success && ($0.elapsedSeconds ?? .infinity) <= Self.deadlineSeconds }.count
    }
    public mutating func beginTrial() -> Int? {
        guard status == .running, pendingTrial == nil, trials.count < Self.target else { return nil }
        pendingTrial = trials.count + 1
        return pendingTrial
    }
    @discardableResult
    public mutating func record(trial: Int, outcome: RecoveryTrialOutcome, elapsedSeconds: Double) -> Bool {
        guard status == .running, pendingTrial == trial,
              elapsedSeconds.isFinite, elapsedSeconds >= 0,
              outcome != .timedOut || elapsedSeconds >= Self.deadlineSeconds else { return false }
        let actual: RecoveryTrialOutcome = outcome == .success && elapsedSeconds > Self.deadlineSeconds ? .timedOut : outcome
        trials.append(RecoveryTrial(number: trial, outcome: actual, elapsedSeconds: elapsedSeconds))
        pendingTrial = nil
        if actual == .cancelled { status = .cancelled }
        else if actual == .interrupted { status = .interrupted }
        else if trials.count == Self.target { status = .completed }
        return true
    }
    public mutating func interrupt(cancelled: Bool) {
        guard status == .running else { return }
        if let number = pendingTrial {
            trials.append(RecoveryTrial(number: number, outcome: cancelled ? .cancelled : .interrupted, elapsedSeconds: nil))
        }
        pendingTrial = nil
        status = cancelled ? .cancelled : .interrupted
    }
    public func validate() throws {
        let resultsValid = trials.enumerated().allSatisfy { index, trial in
            guard trial.number == index + 1 else { return false }
            if let seconds = trial.elapsedSeconds {
                guard seconds.isFinite, seconds >= 0 else { return false }
                if trial.outcome == .success { return seconds <= Self.deadlineSeconds }
                if trial.outcome == .timedOut { return seconds >= Self.deadlineSeconds }
                return true
            }
            return trial.outcome == .cancelled || trial.outcome == .interrupted
        }
        let ordinary = trials.filter { $0.outcome == .success || $0.outcome == .timedOut }.count
        let terminalValid: Bool
        switch status {
        case .running: terminalValid = trials.count < Self.target && ordinary == trials.count
        case .completed: terminalValid = trials.count == Self.target && ordinary == trials.count
        case .cancelled, .interrupted:
            let expected: RecoveryTrialOutcome = status == .cancelled ? .cancelled : .interrupted
            terminalValid = ordinary == trials.count || (ordinary == trials.count - 1 && trials.last?.outcome == expected)
        }
        guard version == 1, trials.count <= Self.target, resultsValid, terminalValid,
              pendingTrial == nil || (status == .running && pendingTrial == trials.count + 1 && trials.count < Self.target),
              status == .running || pendingTrial == nil else { throw JournalStorageError.unsupportedOrCorrupt }
    }

    public func export() -> String {
        struct Report: Encodable {
            let scope = "foreground-controlled-configuration-reconnect"
            let source = "local-service-verification"
            let ancsVerified = false
            let jointGateApproved = false
            let deadlineSeconds = RecoveryCampaign.deadlineSeconds
            let campaign: RecoveryCampaign
        }
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(Report(campaign: self)) else { return "campaign-unavailable" }
        return String(decoding: data, as: UTF8.self)
    }
}
