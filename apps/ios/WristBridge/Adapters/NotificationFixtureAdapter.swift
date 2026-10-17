import Combine
import Foundation
import UserNotifications
import WristBridgeProtocol

@MainActor
final class NotificationFixtureAdapter: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    @Published private(set) var permission = "Sin comprobar"
    @Published private(set) var records: [FixtureRecord] = []
    @Published private(set) var message = "Los avisos de prueba pertenecen únicamente a esta app."
    @Published private(set) var busy = false
    @Published private(set) var persistenceWarning: String?
    private let center = UNUserNotificationCenter.current()
    private let ledgerURL: URL

    override init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WristBridge", isDirectory: true)
        ledgerURL = directory.appendingPathComponent("fixture-ledger.json")
        super.init()
        if FileManager.default.fileExists(atPath: ledgerURL.path) {
            do {
                let data = try Data(contentsOf: ledgerURL)
                records = Array(try JSONDecoder().decode([FixtureRecord].self, from: data).suffix(1000))
            } catch {
                persistenceWarning = "No se pudo recuperar el registro anterior. No uses estos contadores para cerrar una campaña."
            }
        }
        center.delegate = self
    }

    func refreshPermission() async {
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined: permission = "Sin solicitar"
        case .denied: permission = "Denegado; puedes cambiarlo en Ajustes"
        case .authorized: permission = "Autorizado para esta app"
        case .provisional: permission = "Entrega silenciosa provisional"
        case .ephemeral: permission = "Autorización temporal"
        @unknown default: permission = "Estado desconocido"
        }
    }

    func requestPermission() async {
        do {
            _ = try await center.requestAuthorization(options: [.alert, .sound])
            await refreshPermission()
        } catch { message = "No se pudo solicitar el permiso. Revisa Ajustes." }
    }

    func schedule(runID: String) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let plan = try FixturePlan(runID: runID)
            let settings = await center.notificationSettings()
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
                message = "Autoriza primero los avisos de prueba."
                return
            }
            let pending = await center.pendingNotificationRequests()
            let identifiers = Set(plan.notifications.map(\.id))
            // Avoid replacing an existing campaign and confusing its origin observations.
            guard !records.contains(where: { $0.runID == runID }),
                  !pending.contains(where: { identifiers.contains($0.identifier) }) else {
                message = "Ese ID de ejecución ya se usó. Elige uno nuevo."
                return
            }
            guard pending.count + plan.notifications.count <= 60 else {
                message = "Hay demasiados avisos pendientes. Cancela el lote anterior o espera."
                return
            }
            var accepted = 0
            // Anchor deadlines once so sequential add calls don't stretch the intended spacing.
            let origin = Date()
            for fixture in plan.notifications {
                let content = UNMutableNotificationContent()
                content.title = fixture.title
                content.body = fixture.body
                content.sound = .default
                content.threadIdentifier = fixture.id
                content.userInfo = ["runId": fixture.runID, "fixtureId": fixture.fixtureID]
                let delay = max(1, origin.addingTimeInterval(fixture.delay).timeIntervalSinceNow)
                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false)
                let request = UNNotificationRequest(identifier: fixture.id, content: content, trigger: trigger)
                do {
                    try await center.add(request)
                    accepted += 1
                    append(fixture.runID, fixture.fixtureID, .scheduleAccepted)
                } catch { append(fixture.runID, fixture.fixtureID, .scheduleFailed) }
            }
            message = "\(accepted)/\(plan.notifications.count) solicitudes aceptadas. Bloquea el iPhone; la entrega aún debe observarse."
        } catch { message = "Usa un ID de 1 a 64 letras, números, guion o guion bajo." }
    }

    func observeDelivered() async {
        let delivered = await center.deliveredNotifications()
        for notification in delivered {
            let info = notification.request.content.userInfo
            if let runID = info["runId"] as? String, let fixtureID = info["fixtureId"] as? String,
               !records.contains(where: { $0.runID == runID && $0.fixtureID == fixtureID && $0.observation == .deliveredSnapshot }) {
                append(runID, fixtureID, .deliveredSnapshot)
            }
        }
        message = "Se registraron los avisos de esta app presentes en el centro de notificaciones. Los ausentes no prueban pérdida."
    }

    func cancelPending() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        let pending = await center.pendingNotificationRequests()
        let ours = pending.filter { $0.identifier.hasPrefix("wristbridge.") }
        center.removePendingNotificationRequests(withIdentifiers: ours.map(\.identifier))
        for request in ours {
            if let runID = request.content.userInfo["runId"] as? String,
               let fixtureID = request.content.userInfo["fixtureId"] as? String {
                append(runID, fixtureID, .cancelled)
            }
        }
        message = "Se solicitó cancelar \(ours.count) avisos pendientes; revisa el centro de notificaciones para los ya entregados."
    }

    func exportMetadata() -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(records) else { return "[]" }
        return String(decoding: data, as: UTF8.self)
    }

    private func append(_ runID: String, _ fixtureID: String, _ observation: FixtureObservation) {
        records.append(FixtureRecord(runID: runID, fixtureID: fixtureID, observation: observation))
        records = Array(records.suffix(1000))
        do {
            try FileManager.default.createDirectory(at: ledgerURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(records)
            try data.write(to: ledgerURL, options: [.atomic, .completeUntilFirstUserAuthentication])
        } catch {
            persistenceWarning = "No se pudo guardar el registro de pruebas. Expórtalo antes de cerrar la app."
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let runID = notification.request.content.userInfo["runId"] as? String
        let fixtureID = notification.request.content.userInfo["fixtureId"] as? String
        Task { @MainActor [weak self] in
            if let runID, let fixtureID { self?.append(runID, fixtureID, .foregroundCallback) }
        }
        completionHandler([.banner, .list, .sound])
    }
}
