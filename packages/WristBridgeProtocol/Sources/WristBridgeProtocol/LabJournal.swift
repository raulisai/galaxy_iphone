import Foundation

public enum LabCase: String, Codable, CaseIterable, Sendable {
    case notificationsForeground, notificationsLocked, notificationsBackground, notificationsForceQuit
    case configurationReconnect, bluetoothOffOn, authorizationRemoved, phoneRestart
}
public enum LabOutcome: String, Codable, CaseIterable, Sendable { case pass, fail, blocked }
public struct LabEntry: Codable, Identifiable, Sendable {
    public let id: UUID
    public let date: Date
    public let condition: LabCase
    public let outcome: LabOutcome
    // Private local association only. Never included in the sharing format.
    public let accessoryID: UUID?
    public init(condition: LabCase, outcome: LabOutcome, accessoryID: UUID?, date: Date = Date()) {
        id = UUID(); self.date = date; self.condition = condition
        self.outcome = outcome; self.accessoryID = accessoryID
    }
}

public struct LabJournal: Codable, Sendable {
    public let version: Int
    public private(set) var entries: [LabEntry]
    public init() { version = 1; entries = [] }
    public mutating func append(_ entry: LabEntry) {
        entries.append(entry)
        entries = Array(entries.suffix(500))
    }
    public mutating func retain(days: Int, now: Date = Date()) {
        let bounded = [7, 30, 90].contains(days) ? days : 30
        entries.removeAll { $0.date < now.addingTimeInterval(-Double(bounded) * 86400) }
    }
    public func latest(for accessoryID: UUID) -> LabEntry? {
        entries.last { $0.accessoryID == accessoryID && [.notificationsForeground, .notificationsLocked,
            .notificationsBackground, .notificationsForceQuit].contains($0.condition) }
    }
    public func exportRedacted() -> String {
        struct Observation: Encodable {
            let date: Date
            let condition: LabCase
            let outcome: LabOutcome
            let source = "manual-user-observation"
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let redacted = entries.map { Observation(date: $0.date, condition: $0.condition, outcome: $0.outcome) }
        guard let data = try? encoder.encode(redacted) else { return "[]" }
        return String(decoding: data, as: UTF8.self)
    }
}
