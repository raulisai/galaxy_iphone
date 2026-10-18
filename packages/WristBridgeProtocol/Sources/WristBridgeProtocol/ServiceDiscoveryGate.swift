/// Serializes discovery across restoration and the subsequent radio-ready callback.
public struct ServiceDiscoveryGate: Sendable {
    public private(set) var inFlight = false
    public init() {}
    public mutating func beginIfReady(radioReady: Bool, authorized: Bool, connected: Bool) -> Bool {
        guard radioReady, authorized, connected, !inFlight else { return false }
        inFlight = true
        return true
    }
    public mutating func reset() { inFlight = false }
}
