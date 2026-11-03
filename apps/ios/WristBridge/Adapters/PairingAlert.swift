import Foundation
import UserNotifications

/// Local notice when the watch erased its bond while the app was in the background: the link cannot come
/// back by itself, so the user is told once instead of finding a silent disconnection later.
enum PairingAlert {
    // Not "wristbridge.": that prefix belongs to the notification test batches and is bulk-cancelled.
    private static let identifier = "wb-alert.pairing-lost"

    static func post() {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
            let content = UNMutableNotificationContent()
            content.title = "WristBridge perdió el vínculo con el reloj"
            content.body = "El reloj borró su emparejamiento. Abre WristBridge y pulsa «Reparar vínculo»."
            content.sound = .default
            center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        }
    }

    static func clear() {
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
    }
}
