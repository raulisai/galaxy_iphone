import Foundation

/// Atomic, protected, backup-excluded storage for the health ledger.
/// `ingest` is the only path that may lead to an acknowledgement toward the watch:
/// it returns only after the file was written (recibido ≠ guardado).
public struct HealthLedgerRepository {
    public let file: URL
    public static let maxFileBytes = 8 * 1024 * 1024
    public init(file: URL) { self.file = file }

    public func load() throws -> HealthLedger? {
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= Self.maxFileBytes else {
            throw JournalStorageError.oversized
        }
        let saved = try JSONDecoder().decode(HealthLedger.self, from: Data(contentsOf: file))
        try saved.validate()
        return saved
    }

    public func save(_ ledger: HealthLedger) throws {
        _ = try load() // never overwrite an unreadable/future file (it is preserved for the user)
        try ledger.validate()
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(ledger)
        #if os(iOS)
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: file, options: [.atomic])
        #endif
        var resource = file
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try resource.setResourceValues(values)
    }

    /// Commits a batch and persists it. Throws if it could not be stored; callers must not
    /// acknowledge on a throw. Re-ingesting the same batch (crash between save and ACK) is a no-op.
    public func ingest(_ batch: [HealthSample], gaps: [HealthGap] = [], now: Date = Date()) throws -> HealthBatchResult {
        var ledger = try load() ?? HealthLedger()
        let result = ledger.commit(batch: batch, gaps: gaps, now: now)
        if result.inserted + result.updated > 0 || !gaps.isEmpty { try save(ledger) }
        return result
    }

    public func delete() throws {
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }
}
