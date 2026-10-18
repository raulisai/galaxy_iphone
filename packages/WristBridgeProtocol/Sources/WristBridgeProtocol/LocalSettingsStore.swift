import Foundation

/// Own settings only: no peripheral identifiers, notification content or keys.
public struct LocalSettings: Codable, Equatable, Sendable {
    public let version: Int
    public var stopped: Bool
    public var automaticRecovery: Bool
    public var retentionDays: Int
    public init(stopped: Bool = false, automaticRecovery: Bool = true, retentionDays: Int = 30) {
        version = 2
        self.stopped = stopped
        self.automaticRecovery = automaticRecovery
        self.retentionDays = [7, 30, 90].contains(retentionDays) ? retentionDays : 30
    }
    private enum CodingKeys: String, CodingKey { case version, stopped, automaticRecovery, retentionDays }
    public init(from decoder: Decoder) throws {
        let data = try decoder.container(keyedBy: CodingKeys.self)
        version = try data.decode(Int.self, forKey: .version)
        stopped = try data.decode(Bool.self, forKey: .stopped)
        automaticRecovery = try data.decode(Bool.self, forKey: .automaticRecovery)
        let days = try data.decodeIfPresent(Int.self, forKey: .retentionDays) ?? 30
        retentionDays = [7, 30, 90].contains(days) ? days : 30
    }
}

@MainActor
public final class LocalSettingsStore {
    public private(set) var settings: LocalSettings
    public private(set) var warning: String?
    private let defaults: UserDefaults
    private var writable = true
    private static let key = "wristbridge.localSettings"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.key) {
            if let decoded = try? JSONDecoder().decode(LocalSettings.self, from: data), [1, 2].contains(decoded.version) {
                settings = LocalSettings(stopped: decoded.stopped, automaticRecovery: decoded.automaticRecovery, retentionDays: decoded.retentionDays)
                if decoded.version == 1 { persist() }
            } else {
                settings = LocalSettings(stopped: true, automaticRecovery: false)
                writable = false
                warning = "Los ajustes guardados no son compatibles. Se conserva el original; los cambios serán solo para esta sesión."
            }
        } else if defaults.object(forKey: Self.key) != nil {
            settings = LocalSettings(stopped: true, automaticRecovery: false)
            writable = false
            warning = "No se pudieron leer los ajustes. Se conserva el original; los cambios serán solo para esta sesión."
        } else {
            settings = LocalSettings(stopped: defaults.bool(forKey: "bridgeStopped"))
            persist()
            defaults.removeObject(forKey: "bridgeStopped")
        }
    }

    /// Explicit actions can operate for this session even if a future schema is preserved.
    public func update(stopped: Bool? = nil, automaticRecovery: Bool? = nil, retentionDays: Int? = nil) {
        if let stopped { settings.stopped = stopped }
        if let automaticRecovery { settings.automaticRecovery = automaticRecovery }
        if let retentionDays, [7, 30, 90].contains(retentionDays) { settings.retentionDays = retentionDays }
        persist()
    }

    public func reset() {
        writable = true
        warning = nil
        settings = LocalSettings(stopped: true)
        defaults.removeObject(forKey: "bridgeStopped")
        persist()
    }

    private func persist() {
        guard writable, let data = try? JSONEncoder().encode(settings) else { return }
        defaults.set(data, forKey: Self.key)
    }
}
