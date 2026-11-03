import Foundation
import Testing
@testable import WristBridgeProtocol

@Test func recoveryNeverGivesUpAndSuccessRestartsTheBackoff() {
    var policy = ConnectionRetryPolicy()
    let observed = (0..<9).map { _ in policy.nextDelay() }
    #expect(observed == [0, 2, 5, 15, 30, 60, 60, 60, 60])
    #expect(policy.attempts == 9)
    policy.reset()
    #expect(policy.attempts == 0)
    #expect(policy.nextDelay() == 0)
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

@Suite struct LinkResumeTests {
    private func resume(stopped: Bool = false, authorized: Bool = true, auto: Bool = true,
                        campaign: Bool = false, radio: Bool = true,
                        _ channel: ConfigurationChannel) -> Bool {
        LinkResumePolicy.shouldResume(stopped: stopped, authorized: authorized, automaticRecovery: auto,
                                      campaignRunning: campaign, radioOn: radio, channel: channel)
    }

    @Test func idleOrDisconnectedLinkResumesWithoutRelaunch() {
        #expect(resume(.disconnected))
        #expect(resume(.idle))
        #expect(resume(.unavailable))
    }

    @Test func pendingOrHealthyLinkIsNeverRestarted() {
        #expect(!resume(.connecting))
        #expect(!resume(.connected))
        #expect(!resume(.serviceFound))
    }

    @Test func operatorChoicesAndMissingPermissionsAreRespected() {
        #expect(!resume(stopped: true, .disconnected))
        #expect(!resume(authorized: false, .disconnected))
        #expect(!resume(auto: false, .disconnected))
        #expect(!resume(campaign: true, .disconnected))
        #expect(!resume(radio: false, .unavailable))
    }
}

@Test func bluetoothTraceSurvivesRelaunchAndExplainsPairingLoss() throws {
    var trace = BluetoothTrace()
    let date = try #require(ISO8601DateFormatter().date(from: "2026-11-03T07:32:30Z"))
    trace.record(.connectionFailed, errorCode: 14, at: date)
    let restored = BluetoothTrace(restoring: Data(trace.export().utf8))
    #expect(restored.entries.count == 1)
    let line = try #require(restored.readable(timeZone: TimeZone(identifier: "UTC")!).first)
    #expect(line.hasPrefix("03 nov 07:32:30 · Conexión fallida"))
    #expect(line.contains("error 14: el reloj borró el emparejamiento"))
    #expect(BluetoothTrace.isPairingLost(errorCode: 14))
    #expect(!BluetoothTrace.isPairingLost(errorCode: 6))
    #expect(!BluetoothTrace.isPairingLost(errorCode: nil))
    #expect(BluetoothTrace(restoring: Data("corrupt".utf8)).entries.isEmpty)
}

