import Foundation
import Testing
@testable import WristBridgeProtocol

@Test func recoveryBudgetIsFiniteAndSuccessStartsANewCampaign() {
    var policy = ConnectionRetryPolicy()
    #expect(policy.nextDelay() == 2)
    #expect(policy.nextDelay() == 5)
    #expect(policy.nextDelay() == 15)
    #expect(policy.nextDelay() == nil)
    #expect(policy.nextDelay() == nil)
    #expect(policy.attempts == 3)
    policy.reset()
    #expect(policy.attempts == 0)
    #expect(policy.nextDelay() == 2)
}

@MainActor
@Test func legacyStopMigratesAndSurvivesRelaunch() throws {
    let name = "WristBridge-tests-\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: name))
    defer { defaults.removePersistentDomain(forName: name) }
    defaults.set(true, forKey: "bridgeStopped")
    let store = LocalSettingsStore(defaults: defaults)
    #expect(store.settings.stopped)
    #expect(store.settings.automaticRecovery)
    #expect(defaults.object(forKey: "bridgeStopped") == nil)
    store.update(automaticRecovery: false)
    let relaunched = LocalSettingsStore(defaults: defaults)
    #expect(relaunched.settings.stopped)
    #expect(!relaunched.settings.automaticRecovery)
    store.update(stopped: false)
    #expect(!LocalSettingsStore(defaults: defaults).settings.stopped)
}

@MainActor
@Test(arguments: [
    Data("{\"version\":99,\"stopped\":false,\"automaticRecovery\":true}".utf8),
    Data("corrupt".utf8)
])
func unreadableSettingsFailClosedWithoutOverwritingOriginal(data: Data) throws {
    let name = "WristBridge-tests-\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: name))
    defer { defaults.removePersistentDomain(forName: name) }
    defaults.set(data, forKey: "wristbridge.localSettings")
    let store = LocalSettingsStore(defaults: defaults)
    #expect(store.settings.stopped)
    #expect(!store.settings.automaticRecovery)
    #expect(store.warning != nil)
    store.update(stopped: false, automaticRecovery: true)
    #expect(!store.settings.stopped)
    #expect(defaults.data(forKey: "wristbridge.localSettings") == data)
    #expect(LocalSettingsStore(defaults: defaults).settings.stopped)
}
