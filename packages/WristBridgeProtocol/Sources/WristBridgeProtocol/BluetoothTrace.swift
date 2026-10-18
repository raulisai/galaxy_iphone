import Foundation

/// Only enumerated transport events and numeric outcomes. Never peripheral IDs,
/// error descriptions, names, payloads or notification content.
public enum BluetoothTraceStep: String, Codable, Sendable {
    case requested, alreadyActive, stopped, radioChanged, waitingForRadio, waitingForCancellation
    case peerUnavailable, connectSubmitted, connected, discoverySubmitted
    case discoveryCompleted, serviceVerified, discoveryTimedOut, connectionFailed, disconnected
    case cancellationSubmitted, cancellationCompleted, recoveryScheduled, retryExhausted
    case servicesInvalidated, restorationReceived
    case writeWithResponseCapacity, writeWithoutResponseCapacity
}

public struct BluetoothTraceEntry: Codable, Sendable {
    public let date: Date
    public let step: BluetoothTraceStep
    public let value: Int?
    public let errorCode: Int?
}

public struct BluetoothTrace: Codable, Sendable {
    public private(set) var entries: [BluetoothTraceEntry] = []
    public init() {}
    public mutating func record(_ step: BluetoothTraceStep, value: Int? = nil, errorCode: Int? = nil) {
        entries.append(BluetoothTraceEntry(date: Date(), step: step, value: value, errorCode: errorCode))
        if entries.count > 64 { entries.removeFirst(entries.count - 64) }
    }
    public func export() -> String {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(self) else { return "trace-unavailable" }
        return String(decoding: data, as: UTF8.self)
    }
}
