/// A finite budget per operator request or successfully discovered service.
/// Waiting for an out-of-range peer uses CoreBluetooth's pending connection,
/// not a timer that repeatedly cancels and reconnects.
public struct ConnectionRetryPolicy: Sendable {
    public private(set) var attempts = 0
    public init() {}
    public mutating func reset() { attempts = 0 }
    public mutating func nextDelay() -> Int? {
        let delays = [2, 5, 15]
        guard attempts < delays.count else { return nil }
        let delay = delays[attempts]
        attempts += 1
        return delay
    }
}

/// Decides when the app should quietly re-request the link on its own (on
/// foreground and on a slow heartbeat) because the radio path went idle without
/// the operator asking for it. Never overrides Detener, a running campaign or a
/// missing authorization, and never treats a pending connection as stale.
public enum LinkResumePolicy {
    public static func shouldResume(stopped: Bool, authorized: Bool, automaticRecovery: Bool,
                                    campaignRunning: Bool, radioOn: Bool,
                                    channel: ConfigurationChannel) -> Bool {
        guard !stopped, authorized, automaticRecovery, !campaignRunning else { return false }
        switch channel {
        case .idle, .disconnected: return true
        case .unavailable: return radioOn
        case .connecting, .connected, .serviceFound: return false
        }
    }
}
