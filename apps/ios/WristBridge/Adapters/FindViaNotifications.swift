import Combine
import Foundation
import UserNotifications

/// "Buscar" without a custom channel, using only what already works end to end: local
/// notifications that iOS shares with the watch through ANCS.
///
/// - Find watch (iPhone → watch): a short burst of audible notifications the watch shows
///   (vibration/sound follow the watch's own settings). Stopping removes them; removal is
///   synchronised to the watch by ANCS.
/// - Find iPhone (watch → iPhone): an "armed" notification with action buttons stays in
///   Notification Center. The watch shows it with iOS's action labels; pressing «Hacer sonar»
///   makes iOS deliver the action to this app in the background, which starts the alarm.
///
/// This is an experimental route: whether the watch shows the card, which buttons it
/// offers and whether iOS lets the app start audio from the background must be verified on
/// the physical pair. Results are "solicitado", never "completado".
@MainActor
final class FindViaNotifications: ObservableObject {
    static let shared = FindViaNotifications()

    static let categoryID = "wristbridge.findphone"
    static let ringAction = "ring"
    static let stopAction = "stop"
    private static let armedID = "wristbridge.findphone.armed"
    private static let armedKey = "WristBridge.findPhoneArmed"
    private static let watchBurst = 8
    private static let watchIDs = (0..<8).map { "wristbridge.findwatch.\($0)" }

    @Published private(set) var phoneArmed = false
    @Published private(set) var watchBurstActive = false
    @Published private(set) var message = "Usa avisos del iPhone, que el reloj recibe por ANCS."
    @Published private(set) var permissionGranted = false

    private let center = UNUserNotificationCenter.current()
    private let defaults = UserDefaults.standard
    private var burstReset: Task<Void, Never>?

    init() {
        phoneArmed = defaults.bool(forKey: Self.armedKey)
        registerCategories()
    }

    func registerCategories() {
        let ring = UNNotificationAction(identifier: Self.ringAction, title: "Hacer sonar", options: [])
        let stop = UNNotificationAction(identifier: Self.stopAction, title: "Detener", options: [])
        let category = UNNotificationCategory(identifier: Self.categoryID, actions: [ring, stop],
                                              intentIdentifiers: [], options: [])
        center.setNotificationCategories([category])
    }

    func refreshPermission() async {
        let status = await center.notificationSettings().authorizationStatus
        permissionGranted = status == .authorized || status == .provisional
    }

    private func ensurePermission() async -> Bool {
        await refreshPermission()
        if !permissionGranted {
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
            await refreshPermission()
        }
        if !permissionGranted { message = "Autoriza los avisos de WristBridge en Ajustes para usar esta función." }
        return permissionGranted
    }

    // MARK: Find iPhone (watch → iPhone)

    func armPhoneFinder() async {
        guard await ensurePermission() else { return }
        await postArmedNotification()
        phoneArmed = true
        defaults.set(true, forKey: Self.armedKey)
        message = "Armado. En el reloj abre el aviso «Buscar iPhone» y pulsa «Hacer sonar»."
    }

    func disarmPhoneFinder() {
        center.removePendingNotificationRequests(withIdentifiers: [Self.armedID])
        center.removeDeliveredNotifications(withIdentifiers: [Self.armedID])
        phoneArmed = false
        defaults.set(false, forKey: Self.armedKey)
        FindPhoneAlarm.shared.stop()
        message = "Desarmado."
    }

    private func postArmedNotification() async {
        let content = UNMutableNotificationContent()
        content.title = "Buscar iPhone"
        content.body = "Pulsa «Hacer sonar» aquí o en el reloj."
        content.categoryIdentifier = Self.categoryID
        content.userInfo = ["wristbridge": "findphone"]
        let request = UNNotificationRequest(identifier: Self.armedID, content: content,
                                            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false))
        try? await center.add(request)
    }

    /// Re-posts the armed notification if the user armed it earlier but iOS removed it.
    func restoreArmedIfNeeded() async {
        guard phoneArmed, await ensurePermissionQuiet() else { return }
        let delivered = await center.deliveredNotifications()
        if !delivered.contains(where: { $0.request.identifier == Self.armedID }) { await postArmedNotification() }
    }

    private func ensurePermissionQuiet() async -> Bool {
        await refreshPermission()
        return permissionGranted
    }

    /// Called by the notification delegate when the user (or the watch via ANCS) acts on the notification.
    func handle(actionIdentifier: String, categoryIdentifier: String) {
        guard categoryIdentifier == Self.categoryID else { return }
        switch actionIdentifier {
        case Self.stopAction:
            FindPhoneAlarm.shared.stop()
            message = "Detenido desde el aviso."
        default: // «Hacer sonar» or a tap on the notification
            FindPhoneAlarm.shared.start(reason: .watchRequest)
            message = "Orden recibida desde el aviso. Sonando."
        }
        if phoneArmed {
            // iOS removes a notification once an action ran: arm it again for the next use.
            Task { try? await Task.sleep(for: .seconds(2)); await self.postArmedNotification() }
        }
    }

    // MARK: Find watch (iPhone → watch)

    func ringWatch() async {
        guard await ensurePermission() else { return }
        stopWatch(silently: true)
        for (index, id) in Self.watchIDs.enumerated() {
            let content = UNMutableNotificationContent()
            content.title = "Buscar reloj"
            content.body = "Tu reloj está sonando (\(index + 1)/\(Self.watchBurst))."
            content.sound = .default
            content.userInfo = ["wristbridge": "findwatch"]
            let request = UNNotificationRequest(identifier: id, content: content,
                                                trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1 + Double(index) * 4, repeats: false))
            try? await center.add(request)
        }
        watchBurstActive = true
        message = "Solicitado: \(Self.watchBurst) avisos al reloj en 30 s. Si no vibra, revisa los ajustes de avisos del reloj."
        burstReset?.cancel()
        burstReset = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(Double(Self.watchBurst) * 4 + 2)) } catch { return }
            self?.watchBurstActive = false
        }
    }

    func stopWatch(silently: Bool = false) {
        center.removePendingNotificationRequests(withIdentifiers: Self.watchIDs)
        center.removeDeliveredNotifications(withIdentifiers: Self.watchIDs)
        burstReset?.cancel()
        watchBurstActive = false
        if !silently { message = "Detenido. Los avisos pendientes se retiraron también del reloj." }
    }
}
