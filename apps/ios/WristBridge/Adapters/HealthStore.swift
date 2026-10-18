import Combine
import Foundation
import WristBridgeProtocol

/// App-side owner of the health ledger (P7-02). It stores what the watch will deliver and
/// exposes it to the UI. Nothing here talks to the watch, HealthKit or the network.
@MainActor
final class HealthStore: ObservableObject {
    @Published private(set) var ledger = HealthLedger()
    @Published private(set) var warning: String?
    /// Demonstration builds show clearly labeled synthetic samples; real builds never do.
    let isSample: Bool
    private let repository: HealthLedgerRepository?
    private var writable = true

    init(isDemo: Bool, retentionDays: Int = 30) {
        isSample = isDemo
        if isDemo {
            repository = nil
            ledger = Self.sampleLedger()
        } else {
            let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("WristBridge/health-ledger.json")
            repository = HealthLedgerRepository(file: file)
            do {
                if var saved = try repository?.load() {
                    saved.setRetention(days: retentionDays)
                    saved.applyRetention()
                    ledger = saved
                } else { ledger.setRetention(days: retentionDays) }
            } catch {
                writable = false
                warning = "No se pudo leer el registro de salud. Se conserva el archivo; bórralo desde Privacidad para empezar de nuevo."
            }
        }
    }

    var hasData: Bool { ledger.count > 0 }

    /// Entry point for the future secure channel. Returns true only if the batch was
    /// persisted and fully valid, i.e. when it is safe to acknowledge to the watch.
    @discardableResult
    func ingest(_ batch: [HealthSample], gaps: [HealthGap] = []) -> Bool {
        guard !isSample, writable, let repository else { return false }
        do {
            let result = try repository.ingest(batch, gaps: gaps)
            if let saved = try repository.load() { ledger = saved }
            return result.fullyAccepted
        } catch {
            writable = false
            warning = "No se pudo guardar el registro de salud. Se detiene hasta revisarlo."
            return false
        }
    }

    func applyRetention(days: Int) {
        guard !isSample, writable else { return }
        ledger.setRetention(days: days)
        ledger.applyRetention()
        persist()
    }

    func delete() {
        do {
            try repository?.delete()
            let days = ledger.retentionDays
            ledger = HealthLedger(retentionDays: days)
            writable = true; warning = nil
        } catch { warning = "No se pudo borrar el registro de salud. Vuelve a intentarlo." }
    }

    private func persist() {
        do { try repository?.save(ledger) } catch { writable = false; warning = "No se pudo actualizar el registro de salud." }
    }

    /// Synthetic, deterministic samples for demonstration only (never used outside --demo).
    private static func sampleLedger() -> HealthLedger {
        var ledger = HealthLedger(retentionDays: 30)
        let now = Date()
        let calendar = Calendar.current
        let startOfDay = calendar.startOfDay(for: now)
        var batch: [HealthSample] = []
        var cursor = startOfDay
        var index = 0
        while cursor.addingTimeInterval(3600) <= now {
            let end = cursor.addingTimeInterval(3600)
            let hour = Double(calendar.component(.hour, from: cursor))
            let active = (7...21).contains(hour) ? 1.0 : 0.05
            batch.append(HealthSample(deviceId: "demo", metric: .steps, start: cursor, end: end,
                                      value: (180 + 90 * sin(hour)).rounded() * active, source: "demo-sample",
                                      syncId: "demo-steps-\(index)"))
            batch.append(HealthSample(deviceId: "demo", metric: .heartRate, start: cursor, end: end,
                                      value: 64 + 14 * active + 4 * sin(hour), minimum: 56, maximum: 92 + 20 * active,
                                      source: "demo-sample", syncId: "demo-hr-\(index)"))
            batch.append(HealthSample(deviceId: "demo", metric: .distance, start: cursor, end: end,
                                      value: 120 * active, source: "demo-sample", syncId: "demo-dist-\(index)"))
            batch.append(HealthSample(deviceId: "demo", metric: .activeEnergy, start: cursor, end: end,
                                      value: 9 * active, source: "demo-sample", syncId: "demo-kcal-\(index)"))
            cursor = end; index += 1
        }
        _ = ledger.commit(batch: batch, now: now)
        return ledger
    }
}
