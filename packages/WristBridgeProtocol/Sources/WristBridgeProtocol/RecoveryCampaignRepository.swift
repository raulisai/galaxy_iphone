import Foundation

public struct RecoveryCampaignRepository {
    public let file: URL
    public init(file: URL) { self.file = file }
    public func load() throws -> RecoveryCampaign? {
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 64 * 1024 else {
            throw JournalStorageError.oversized
        }
        let saved = try JSONDecoder().decode(RecoveryCampaign.self, from: Data(contentsOf: file))
        try saved.validate()
        return saved
    }
    public func save(_ campaign: RecoveryCampaign) throws {
        _ = try load()
        try campaign.validate()
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(campaign)
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
