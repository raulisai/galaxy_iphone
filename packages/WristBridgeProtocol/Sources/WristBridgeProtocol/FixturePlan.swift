import Foundation

public struct SyntheticNotification: Equatable, Sendable, Identifiable {
    public let runID: String
    public let fixtureID: String
    public let title: String
    public let body: String
    public let delay: TimeInterval
    public var id: String { "wristbridge.\(runID).\(fixtureID)" }
}

public enum FixturePlanError: Error, Equatable {
    case invalidRunID, invalidCount, invalidTiming
}

public struct FixturePlan: Sendable {
    public let notifications: [SyntheticNotification]

    public init(runID: String, count: Int = 10, initialDelay: TimeInterval = 10,
                spacing: TimeInterval = 3) throws {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")
        guard !runID.isEmpty, runID.utf8.count <= 64,
              runID.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
            throw FixturePlanError.invalidRunID
        }
        guard (1...50).contains(count) else { throw FixturePlanError.invalidCount }
        guard initialDelay.isFinite, spacing.isFinite,
              initialDelay >= 5, initialDelay <= 3600, spacing >= 1, spacing <= 60 else {
            throw FixturePlanError.invalidTiming
        }
        notifications = (0..<count).map { index in
            let fixtureID = String(format: "F%03d", index + 1)
            let bodies = [
                "Aviso sintético breve.",
                "Español: acción, pingüino, corazón. 👋🏽 ⌚️ 🇲🇽",
                String(repeating: "Texto largo de laboratorio; sin datos personales. ", count: 40)
            ]
            return SyntheticNotification(runID: runID, fixtureID: fixtureID,
                                         title: "\(runID) · \(fixtureID)",
                                         body: bodies[index % bodies.count],
                                         delay: initialDelay + Double(index) * spacing)
        }
    }
}

public enum FixtureObservation: String, Codable, Sendable {
    case scheduleAccepted, scheduleFailed, foregroundCallback, deliveredSnapshot, cancelled
}

/// Records metadata, never notification bodies or accessory identifiers.
public struct FixtureRecord: Codable, Equatable, Sendable, Identifiable {
    public let id: UUID
    public let runID: String
    public let fixtureID: String
    public let observation: FixtureObservation
    public let recordedAt: Date

    public init(runID: String, fixtureID: String, observation: FixtureObservation,
                recordedAt: Date = Date()) {
        id = UUID()
        self.runID = runID
        self.fixtureID = fixtureID
        self.observation = observation
        self.recordedAt = recordedAt
    }
}
