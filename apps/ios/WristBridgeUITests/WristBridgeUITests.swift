import XCTest

final class WristBridgeUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    override func tearDown() {
        let app = XCUIApplication()
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
        if testRun?.failureCount ?? 0 > 0 {
            let hierarchy = XCTAttachment(string: app.debugDescription)
            hierarchy.lifetime = .keepAlways
            add(hierarchy)
        }
        super.tearDown()
    }

    func testDemoDoesNotClaimANCSReadiness() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Demostración · sin conexión real"].waitForExistence(timeout: 5))
        app.buttons["selectWatch"].tap()
        XCTAssertTrue(app.staticTexts["Servicio de configuración encontrado"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Sin comprobar"].exists)
        app.buttons["Detener configuración"].tap()
        XCTAssertTrue(app.staticTexts["Detenido"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Sin comprobar"].exists)
    }

    func testManualObservationDoesNotPromoteAutomaticReadiness() {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["Pruebas"].tap()
        let save = app.buttons["saveObservation"]
        for _ in 0..<8 where !save.isHittable { app.swipeUp() }
        XCTAssertTrue(save.isHittable)
        // A blocked observation is honest on a simulator without a watch.
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Resultado")).firstMatch.tap()
        app.buttons["No se pudo probar"].tap()
        save.tap()
        let feedback = app.staticTexts["Observación guardada como reporte tuyo; no es una comprobación automática del reloj."]
        for _ in 0..<3 where !feedback.exists { app.swipeUp() }
        XCTAssertTrue(feedback.waitForExistence(timeout: 5))
        app.tabBars.buttons["Conexión"].tap()
        XCTAssertTrue(app.staticTexts["Sin comprobar"].exists)
    }

    func testRecoveryPreferenceSurvivesRelaunch() {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["Ajustes"].tap()
        let recovery = app.switches["automaticRecovery"]
        for _ in 0..<10 where !recovery.isHittable { app.swipeUp() }
        XCTAssertTrue(recovery.isHittable)
        let original = recovery.value as? String
        XCTAssertNotNil(original)
        // SwiftUI exposes a containing switch row and a nested UISwitch.
        // Activate the actual control rather than the middle of its multiline label.
        recovery.switches.firstMatch.tap()
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value != %@", original ?? ""), object: recovery)
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 3), .completed)
        let updated = recovery.value as? String
        XCTAssertNotEqual(updated, original)
        app.terminate()
        app.launch()
        app.tabBars.buttons["Ajustes"].tap()
        for _ in 0..<10 where !recovery.isHittable { app.swipeUp() }
        XCTAssertTrue(recovery.isHittable)
        XCTAssertEqual(recovery.value as? String, updated)
        recovery.switches.firstMatch.tap()
        let restored = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", original ?? ""), object: recovery)
        XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 3), .completed)
    }

    func testLiveLaunchRequiresConsentBeforeSchedulingFixtures() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.navigationBars["WristBridge"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Demostración · sin conexión real"].exists)
        app.tabBars.buttons["Pruebas"].tap()
        let schedule = app.buttons["Programar 10 avisos"]
        for _ in 0..<6 where !schedule.isHittable { app.swipeUp() }
        XCTAssertTrue(schedule.isHittable)
        schedule.tap()
        let feedback = app.staticTexts["Autoriza primero los avisos de prueba."]
        // The feedback is below the action in the scrolling laboratory section.
        for _ in 0..<4 where !feedback.exists { app.swipeUp() }
        XCTAssertTrue(feedback.waitForExistence(timeout: 5))
        XCTAssertFalse(app.alerts.firstMatch.exists)
    }
}
