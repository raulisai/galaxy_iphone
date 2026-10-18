import SwiftUI

@main
struct WristBridgeApp: App {
    @UIApplicationDelegateAdaptor(BridgeAppDelegate.self) private var appDelegate
    @StateObject private var model: BridgeModel
    @StateObject private var fixtures = NotificationFixtureAdapter()

    init() {
        #if DEBUG
        let demo = ProcessInfo.processInfo.arguments.contains("--demo")
        #else
        let demo = false
        #endif
        _model = StateObject(wrappedValue: demo ? BridgeModel(isDemo: true) : BridgeRuntime.model)
    }

    var body: some Scene {
        WindowGroup {
            ContentView(model: model, fixtures: fixtures)
        }
    }
}
