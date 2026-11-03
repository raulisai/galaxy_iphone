import Foundation
import Testing
@testable import WristBridgeProtocol

@Test func diagnosticsRetainLatestFailureWithoutUnboundedGrowth() throws {
    var trace = BluetoothTrace()
    for _ in 0..<(BluetoothTrace.capacity + 50) { trace.record(.requested) }
    trace.record(.discoveryTimedOut, value: 2)
    #expect(trace.entries.count == BluetoothTrace.capacity)
    #expect(trace.entries.last?.step == .discoveryTimedOut)
    let json = trace.export()
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    let decoded = try decoder.decode(BluetoothTrace.self, from: Data(json.utf8))
    #expect(decoded.entries.count == BluetoothTrace.capacity)
    #expect(decoded.entries.last?.value == 2)
    #expect(!json.contains("identifier"))
    #expect(!json.contains("payload"))
}
