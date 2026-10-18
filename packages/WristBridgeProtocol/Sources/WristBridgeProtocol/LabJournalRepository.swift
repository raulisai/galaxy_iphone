import Foundation

public enum JournalStorageError: Error { case unsupportedOrCorrupt, oversized }

/// Atomic own-result storage. Existing unreadable/future data is never silently replaced.
public struct LabJournalRepository {
    public let file: URL
    public init(file: URL) { self.file = file }
    public func load() throws -> LabJournal {
        guard FileManager.default.fileExists(atPath: file.path) else { return LabJournal() }
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        guard size <= 512 * 1024 else { throw JournalStorageError.oversized }
        let journal = try JSONDecoder().decode(LabJournal.self, from: Data(contentsOf: file))
        guard journal.version == 1, journal.entries.count <= 500 else { throw JournalStorageError.unsupportedOrCorrupt }
        return journal
    }
    public func save(_ journal: LabJournal) throws {
        _ = try load()
        guard journal.version == 1, journal.entries.count <= 500 else { throw JournalStorageError.unsupportedOrCorrupt }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(journal)
        #if os(iOS)
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: file, options: [.atomic])
        #endif
        var resource = file
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try resource.setResourceValues(values)
    }
    public func delete() throws {
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }
}
