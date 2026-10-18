import Foundation
import WristBridgeProtocol

@MainActor
final class BluetoothDiagnostics {
    private var trace = BluetoothTrace()
    private(set) var persistenceAvailable = true
    private let file: URL?

    init() {
        file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?.appendingPathComponent("bluetooth-diagnostics.json")
    }

    func record(_ step: BluetoothTraceStep, value: Int? = nil, error: Error? = nil) {
        trace.record(step, value: value, errorCode: error.map { ($0 as NSError).code })
        guard let file else { persistenceAvailable = false; return }
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try Data(trace.export().utf8).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var resource = file
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try resource.setResourceValues(values)
        } catch { persistenceAvailable = false }
    }

    func clear() {
        trace = BluetoothTrace()
        persistenceAvailable = true
        if let file {
            do { if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) } }
            catch { persistenceAvailable = false }
        }
    }

    var summary: String { trace.export() }
}
