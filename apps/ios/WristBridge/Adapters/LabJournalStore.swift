import Combine
import Foundation
import WristBridgeProtocol

@MainActor
final class LabJournalStore: ObservableObject {
    @Published private(set) var journal = LabJournal()
    @Published private(set) var warning: String?
    @Published private(set) var message = "Registra solo lo que hayas observado en tus dispositivos."
    private let file: URL?
    private var writable = true

    init(isDemo: Bool = false) {
        file = isDemo ? nil : FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?.appendingPathComponent("WristBridge/lab-journal.json")
        guard let file, FileManager.default.fileExists(atPath: file.path) else { return }
        do {
            journal = try LabJournalRepository(file: file).load()
        } catch {
            writable = false
            warning = "No se pudo leer el registro anterior. Se conserva el original; puedes borrarlo desde Privacidad para empezar de nuevo."
        }
    }

    func record(_ condition: LabCase, outcome: LabOutcome, accessoryID: UUID?, retentionDays: Int) {
        guard writable else {
            message = "No se puede guardar sobre el registro anterior ilegible. Revisa Privacidad para borrarlo explícitamente."
            return
        }
        var updated = journal
        updated.append(LabEntry(condition: condition, outcome: outcome, accessoryID: accessoryID))
        updated.retain(days: retentionDays)
        if save(updated) { message = "Observación guardada como reporte tuyo; no es una comprobación automática del reloj." }
    }

    func applyRetention(days: Int) {
        guard writable else { return }
        var updated = journal
        updated.retain(days: days)
        _ = save(updated)
    }

    func delete() {
        do {
            if let file { try LabJournalRepository(file: file).delete() }
            journal = LabJournal(); writable = true; warning = nil
            message = "Se borraron las observaciones locales."
        } catch { warning = "No se pudo borrar el registro. Vuelve a intentarlo." }
    }

    @discardableResult
    private func save(_ updated: LabJournal) -> Bool {
        do {
            if let file {
                try LabJournalRepository(file: file).save(updated)
            }
            journal = updated
            warning = nil
            return true
        } catch {
            warning = "No se pudo guardar la observación. No se ha confirmado su persistencia."
            return false
        }
    }
}
