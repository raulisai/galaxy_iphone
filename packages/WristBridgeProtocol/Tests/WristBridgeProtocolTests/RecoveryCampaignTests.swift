import Foundation
import Testing
@testable import WristBridgeProtocol

@Test func duplicateOrOldResultsCannotCompleteANewTrial() throws {
    var campaign = RecoveryCampaign()
    let pendingFirst = campaign.beginTrial()
    let first = try #require(pendingFirst)
    let accepted = campaign.record(trial: first, outcome: .success, elapsedSeconds: 2)
    #expect(accepted)
    let pendingSecond = campaign.beginTrial()
    let second = try #require(pendingSecond)
    let stale = campaign.record(trial: first, outcome: .success, elapsedSeconds: 1)
    #expect(!stale)
    #expect(campaign.trials.count == 1)
    #expect(campaign.pendingTrial == second)
    let late = campaign.record(trial: second, outcome: .success, elapsedSeconds: 30.01)
    #expect(late)
    #expect(campaign.trials.last?.outcome == .timedOut)
    #expect(campaign.withinDeadlineCount == 1)
}

@Test func twentyLocalTrialsDoNotApproveANCsOrJointGate() throws {
    var campaign = RecoveryCampaign()
    for _ in 0..<20 {
        let pending = campaign.beginTrial()
        let trial = try #require(pending)
        _ = campaign.record(trial: trial, outcome: .success, elapsedSeconds: 1)
    }
    let extra = campaign.beginTrial()
    #expect(extra == nil)
    #expect(campaign.status == .completed)
    #expect(campaign.trials.count == 20)
    let json = campaign.export()
    #expect(json.contains("\"ancsVerified\" : false"))
    #expect(json.contains("\"jointGateApproved\" : false"))
    try campaign.validate()
}

@Test func interruptedProcessNeverRestoresAnActiveTrial() throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let repository = RecoveryCampaignRepository(file: folder.appendingPathComponent("campaign.json"))
    var campaign = RecoveryCampaign()
    _ = campaign.beginTrial()
    try repository.save(campaign)
    let loaded = try repository.load()
    var reopened = try #require(loaded)
    reopened.interrupt(cancelled: false)
    try repository.save(reopened)
    let next = reopened.beginTrial()
    #expect(next == nil)
    #expect(reopened.status == .interrupted)
    #expect(reopened.trials.first?.elapsedSeconds == nil)
    #expect(reopened.withinDeadlineCount == 0)
    let future = Data("{\"version\":99}".utf8)
    try future.write(to: repository.file)
    #expect(throws: (any Error).self) { try repository.save(campaign) }
    #expect(try Data(contentsOf: repository.file) == future)
}

@Test(arguments: [Double.nan, Double.infinity, -1])
func invalidElapsedTimeCannotBecomeSuccess(seconds: Double) throws {
    var campaign = RecoveryCampaign()
    let pending = campaign.beginTrial()
        let trial = try #require(pending)
    let accepted = campaign.record(trial: trial, outcome: .success, elapsedSeconds: seconds)
    #expect(!accepted)
    #expect(campaign.withinDeadlineCount == 0)
    #expect(campaign.trials.isEmpty)
}

@Test func incompleteOrImpossibleStoredSuccessCannotApproveLocalSummary() throws {
    let empty = RecoveryCampaign()
    var object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(empty)) as? [String: Any])
    object["status"] = "completed"
    let invalid = try JSONDecoder().decode(RecoveryCampaign.self, from: JSONSerialization.data(withJSONObject: object))
    #expect(throws: JournalStorageError.self) { try invalid.validate() }
    var active = RecoveryCampaign()
    let pending = active.beginTrial()
    let trial = try #require(pending)
    let impossible = active.record(trial: trial, outcome: .timedOut, elapsedSeconds: 1)
    #expect(!impossible)
    #expect(active.trials.isEmpty)
}
