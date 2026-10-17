import Foundation

public enum AccessoryAuthorization: String, Sendable {
    case unknown, choosing, authorized, cancelled, denied, unavailable
}

public enum ConfigurationChannel: String, Sendable {
    case idle, connecting, connected, serviceFound, disconnected, unavailable
}

public enum BridgeEvent: Sendable {
    case pickerOpened, pickerCancelled, authorized, authorizationRemoved, authorizationDenied
    case connecting, connected, serviceFound, disconnected, radioUnavailable, stopped
}

/// Local observations only. ANCS authorization/readiness belongs to the watch.
public struct BridgeState: Equatable, Sendable {
    public private(set) var authorization: AccessoryAuthorization = .unknown
    public private(set) var channel: ConfigurationChannel = .idle
    public var ancsVerified: Bool { false }
    public var sensitiveChannelEnabled: Bool { false }

    public init() {}

    public mutating func apply(_ event: BridgeEvent) {
        switch event {
        case .pickerOpened:
            if authorization != .authorized { authorization = .choosing }
        case .pickerCancelled:
            if authorization == .choosing { authorization = .cancelled }
        case .authorized:
            authorization = .authorized
        case .authorizationRemoved:
            authorization = .unknown
            channel = .idle
        case .authorizationDenied:
            authorization = .denied
            channel = .unavailable
        case .connecting:
            guard authorization == .authorized else { return }
            channel = .connecting
        case .connected:
            guard authorization == .authorized else { return }
            channel = .connected
        case .serviceFound:
            guard authorization == .authorized, channel == .connected else { return }
            channel = .serviceFound
        case .disconnected:
            channel = .disconnected
        case .radioUnavailable:
            channel = .unavailable
        case .stopped:
            channel = .idle
        }
    }
}
