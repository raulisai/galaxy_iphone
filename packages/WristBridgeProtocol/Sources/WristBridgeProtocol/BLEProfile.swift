import Foundation

/// Reserved identifiers from Doc/01_CONTRATO_COMPARTIDO.md §2.
/// No characteristic properties or P2 wire format are implied by this list.
public enum BLEProfile {
    public static let service = UUID(uuidString: "6f06b8b1-21d9-4c65-9d7e-118880e36a01")!
    public static let capabilities = UUID(uuidString: "6f06b8b1-21d9-4c65-9d7e-118880e36a02")!
    public static let requests = UUID(uuidString: "6f06b8b1-21d9-4c65-9d7e-118880e36a03")!
    public static let events = UUID(uuidString: "6f06b8b1-21d9-4c65-9d7e-118880e36a04")!

    /// Apple-side proposal; Windows must confirm the advertisement before H02.
    public static let proposedAdvertisedName = "WristBridge"
}
