import UIKit

@MainActor
enum BridgeRuntime {
    static let model = BridgeModel()
}

@MainActor
final class BridgeAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Ensure the notification delegate exists even when iOS launches us for a notification action.
        _ = NotificationFixtureAdapter.shared
        _ = FindViaNotifications.shared
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--demo") { return true }
        #endif
        if let identifiers = launchOptions?[.bluetoothCentrals] as? [String],
           identifiers.contains(BluetoothCentralAdapter.restorationIdentifier) {
            BridgeRuntime.model.prepareForBluetoothRestoration()
        }
        return true
    }
}
