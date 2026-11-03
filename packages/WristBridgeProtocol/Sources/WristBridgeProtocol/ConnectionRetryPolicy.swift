/// Backoff for reported connection failures. It never runs out: while the watch is
/// authorized and the operator has not pressed Detener, the iPhone keeps asking for
/// the link, like the vendor companion apps. The first retry is immediate so a
/// suspended app re-submits its pending connection inside the Bluetooth callback.
/// Waiting for an out-of-range peer still uses CoreBluetooth's pending connection,
/// not a timer that repeatedly cancels and reconnects.
public struct ConnectionRetryPolicy: Sendable {
    public static let delays = [0, 2, 5, 15, 30, 60]
    public private(set) var attempts = 0
    public init() {}
    public mutating func reset() { attempts = 0 }
    public mutating func nextDelay() -> Int {
        let delay = Self.delays[min(attempts, Self.delays.count - 1)]
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
