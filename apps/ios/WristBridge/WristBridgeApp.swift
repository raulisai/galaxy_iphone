import SwiftUI

@main
struct WristBridgeApp: App {
    @StateObject private var model: BridgeModel
    @StateObject private var fixtures = NotificationFixtureAdapter()

    init() {
        #if DEBUG
        let demo = ProcessInfo.processInfo.arguments.contains("--demo")
        #else
        let demo = false
        #endif
        _model = StateObject(wrappedValue: BridgeModel(isDemo: demo))
    }

    var body: some Scene {
        WindowGroup {
            ContentView(model: model, fixtures: fixtures)
        }
    }
}
