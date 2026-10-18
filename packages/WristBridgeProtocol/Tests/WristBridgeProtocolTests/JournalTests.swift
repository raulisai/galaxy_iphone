import Foundation
import Testing
@testable import WristBridgeProtocol

@Test func manualEvidenceIsBoundedScopedAndRedacted() throws {
    let first = UUID(), other = UUID()
    var journal = LabJournal()
    for _ in 0..<600 { journal.append(LabEntry(condition: .notificationsLocked, outcome: .pass, accessoryID: first)) }
    journal.append(LabEntry(condition: .configurationReconnect, outcome: .fail, accessoryID: first))
    #expect(journal.entries.count == 500)
    #expect(journal.latest(for: first)?.condition == .notificationsLocked)
    #expect(journal.latest(for: other) == nil)
    let exported = journal.exportRedacted()
    #expect(!exported.contains(first.uuidString))
    #expect(!exported.contains("accessoryID"))
    #expect(exported.contains("manual-user-observation"))
    var state = BridgeState()
    state.apply(.authorized)
    #expect(!state.ancsVerified)
}

@Test func ownResultsSurviveReopenAndFutureFileIsPreserved() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let file = directory.appendingPathComponent("journal.json")
    let repository = LabJournalRepository(file: file)
    var journal = LabJournal()
    journal.append(LabEntry(condition: .configurationReconnect, outcome: .blocked, accessoryID: nil))
    try repository.save(journal)
    let reopened = try LabJournalRepository(file: file).load()
    #expect(reopened.entries.count == 1)
    #expect(reopened.entries.first?.outcome == .blocked)
    let future = Data("{\"version\":99,\"entries\":[]}".utf8)
    try future.write(to: file)
    #expect(throws: (any Error).self) { try repository.save(journal) }
    #expect(try Data(contentsOf: file) == future)
    try repository.delete()
    #expect(try repository.load().entries.isEmpty)
}

@Test func retentionRemovesExpiredResultsWithoutDroppingRecentOnes() {
    let now = Date(timeIntervalSince1970: 2_000_000_000)
    var journal = LabJournal()
    journal.append(LabEntry(condition: .phoneRestart, outcome: .pass, accessoryID: nil, date: now.addingTimeInterval(-31 * 86400)))
    journal.append(LabEntry(condition: .phoneRestart, outcome: .fail, accessoryID: nil, date: now.addingTimeInterval(-6 * 86400)))
    journal.retain(days: 30, now: now)
    #expect(journal.entries.count == 1)
    #expect(journal.entries.first?.outcome == .fail)
}

@MainActor @Test func versionOneSettingsMigrateWithoutEnablingStoppedLink() throws {
    let suite = "WristBridge-test-\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }
    defaults.set(Data("{\"version\":1,\"stopped\":true,\"automaticRecovery\":false}".utf8), forKey: "wristbridge.localSettings")
    let settings = LocalSettingsStore(defaults: defaults)
    #expect(settings.settings.version == 2)
    #expect(settings.settings.stopped)
    #expect(!settings.settings.automaticRecovery)
    #expect(settings.settings.retentionDays == 30)
    settings.update(retentionDays: 7)
    #expect(LocalSettingsStore(defaults: defaults).settings.retentionDays == 7)
    settings.reset()
    #expect(LocalSettingsStore(defaults: defaults).settings.stopped)
}
