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
