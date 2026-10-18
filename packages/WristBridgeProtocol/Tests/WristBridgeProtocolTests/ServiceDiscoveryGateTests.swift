import Testing
@testable import WristBridgeProtocol

@Test func restorationWaitsForRadioThenStartsExactlyOnce() {
    var gate = ServiceDiscoveryGate()
    let decision1 = gate.beginIfReady(radioReady: false, authorized: true, connected: true)
    #expect(!decision1)
    #expect(!gate.inFlight)
    let decision2 = gate.beginIfReady(radioReady: true, authorized: true, connected: true)
    #expect(decision2)
    let decision3 = gate.beginIfReady(radioReady: true, authorized: true, connected: true)
    #expect(!decision3)
    gate.reset()
    let decision4 = gate.beginIfReady(radioReady: true, authorized: true, connected: true)
    #expect(decision4)
}

@Test func revocationDuringRestorationCannotStartDiscovery() {
    var gate = ServiceDiscoveryGate()
    let decision5 = gate.beginIfReady(radioReady: false, authorized: true, connected: true)
    #expect(!decision5)
    gate.reset()
    let decision6 = gate.beginIfReady(radioReady: true, authorized: false, connected: true)
    #expect(!decision6)
    let decision7 = gate.beginIfReady(radioReady: true, authorized: true, connected: false)
    #expect(!decision7)
    #expect(!gate.inFlight)
}
