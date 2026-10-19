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

    private func text(_ app: XCUIApplication, containing value: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", value)).firstMatch
    }

    func testLargestTextKeepsPrimaryActionsReachable() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-UIPreferredContentSizeCategoryName",
                               "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let select = app.buttons["selectWatch"]
        for _ in 0..<8 where !select.isHittable { app.swipeUp() }
        XCTAssertTrue(select.isHittable)
        // Audit the visible primary controls at the largest accessibility text size.
        try app.performAccessibilityAudit(for: [.textClipped])
        select.tap()
        let stop = app.buttons["Detener configuración"]
        for _ in 0..<8 where !stop.isHittable { app.swipeUp() }
        XCTAssertTrue(stop.isHittable)
        try app.performAccessibilityAudit(for: [.textClipped])
        stop.tap()
        XCTAssertTrue(text(app, containing: "Detenido").waitForExistence(timeout: 5))
    }

    func testBatchOptionsPreserveIDWhenConsentIsMissing() {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["Notificaciones"].tap()
        app.buttons["fixtureCount"].tap()
        app.buttons["50 avisos"].tap()
        app.buttons["fixtureDelay"].tap()
        app.buttons["60 segundos"].tap()
        let runID = app.textFields["runID"]
        let original = runID.value as? String
        let schedule = app.buttons["Programar 50 avisos"]
        for _ in 0..<4 where !schedule.isHittable { app.swipeUp() }
        schedule.tap()
        let feedback = app.staticTexts["Autoriza primero los avisos de prueba."]
        for _ in 0..<4 where !feedback.exists { app.swipeUp() }
        XCTAssertTrue(feedback.waitForExistence(timeout: 5))
        for _ in 0..<4 where !runID.exists { app.swipeDown() }
        XCTAssertEqual(runID.value as? String, original)
        XCTAssertFalse(app.alerts.firstMatch.exists)
    }

    func testDemoDoesNotClaimANCSReadiness() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Demostración · sin conexión real"].waitForExistence(timeout: 5))
        app.buttons["selectWatch"].tap()
        XCTAssertTrue(text(app, containing: "Servicio de configuración encontrado").waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, containing: "Sin comprobar").exists)
        app.buttons["Detener configuración"].tap()
        XCTAssertTrue(text(app, containing: "Detenido").waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, containing: "Sin comprobar").exists)
    }

    func testDemoReconnectionScreenCannotStartWithoutRealWatch() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        app.buttons["selectWatch"].tap()
        XCTAssertTrue(text(app, containing: "Servicio de configuración encontrado").waitForExistence(timeout: 5))
        let tile = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Reconexión")).firstMatch
        for _ in 0..<6 where !tile.isHittable { app.swipeUp() }
        tile.tap()
        let start = app.buttons["startRecoveryCampaign"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        // Demo never runs physical trials, even with a simulated service.
        XCTAssertFalse(start.isEnabled)
        XCTAssertTrue(text(app, containing: "de 20 intentos").exists)
    }

    func testWatchFeatureScreensOpenAndStayHonest() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        for (id, title) in [("faces", "Esferas"), ("health", "Salud"), ("find", "Buscar reloj"), ("music", "Música")] {
            let tile = app.buttons["feature-\(id)"]
            let carousel = app.scrollViews["watchFeatures"]
            for _ in 0..<8 where !carousel.isHittable { app.swipeUp() }
            XCTAssertTrue(carousel.isHittable)
            // Swipe the carousel itself. An earlier tile can be offscreen after
            // returning from another feature, so it is not a reliable swipe target.
            for _ in 0..<6 where !tile.isHittable || tile.frame.maxX > app.frame.width {
                carousel.swipeLeft(velocity: .slow)
            }
            XCTAssertTrue(tile.isHittable, "\(title) tile")
            tile.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5), "\(title) screen")
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "feature-\(id)"; shot.lifetime = .keepAlways; add(shot)
            // Capabilities that need the watch are never offered as enabled.
            let actionable = app.buttons.matching(NSPredicate(
                format: "label CONTAINS[c] 'Enviar al reloj' OR label CONTAINS[c] 'Activar sincronización'"))
            XCTAssertEqual(actionable.count, 0, "\(title): " + actionable.allElementsBoundByIndex.map { $0.label }.joined(separator: " | "))
            app.navigationBars[title].buttons.firstMatch.tap()
        }
    }

    func testFindPhoneAlarmStartsAndStopsLocallyAndWatchTriggerStaysLocked() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        let tile = app.buttons["feature-findPhone"]
        for _ in 0..<8 where !tile.exists || tile.frame.maxY > app.frame.height - 140 { app.swipeUp() }
        for _ in 0..<6 where !tile.isHittable || tile.frame.maxX > app.frame.width {
            let carousel = app.buttons["feature-faces"]
            if carousel.isHittable { carousel.swipeLeft(velocity: .slow) } else { app.swipeUp() }
        }
        tile.tap()
        XCTAssertTrue(app.navigationBars["Buscar iPhone"].waitForExistence(timeout: 5))
        let toggle = app.buttons["findPhoneToggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Sonando'")).firstMatch.waitForExistence(timeout: 5))
        toggle.tap()
        XCTAssertTrue(app.staticTexts["Detenido."].waitForExistence(timeout: 5))
        // In demo the notification-based finder is visible but disabled (no real watch).
        let arm = app.buttons["armFindPhone"]
        for _ in 0..<4 where !arm.exists { app.swipeUp() }
        XCTAssertTrue(arm.exists)
        XCTAssertFalse(arm.isEnabled)
    }

    func testHealthScreenShowsLabeledSampleDataOnlyInDemo() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        let tile = app.buttons["feature-health"]
        for _ in 0..<8 where !tile.exists || tile.frame.maxY > app.frame.height - 140 { app.swipeUp() }
        for _ in 0..<6 where !tile.isHittable { app.buttons["feature-faces"].swipeLeft(velocity: .slow) }
        tile.tap()
        XCTAssertTrue(app.navigationBars["Salud"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["healthSampleBanner"].exists || app.descendants(matching: .any)["healthSampleBanner"].exists)
        // Without a real ledger there is nothing to claim; the live app must not show the banner.
    }

    func testHealthReceiveSwitchActivatesIPhoneSideWithoutInventingData() {
        let app = XCUIApplication()
        app.launch() // live build: no sample data, no watch
        let tile = app.buttons["feature-health"]
        for _ in 0..<8 where !tile.exists || tile.frame.maxY > app.frame.height - 140 { app.swipeUp() }
        for _ in 0..<6 where !tile.isHittable { app.buttons["feature-faces"].swipeLeft(velocity: .slow) }
        tile.tap()
        XCTAssertTrue(app.navigationBars["Salud"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.descendants(matching: .any)["healthSampleBanner"].exists)
        let toggle = app.switches["healthReceiveToggle"]
        for _ in 0..<6 where !toggle.exists { app.swipeUp() }
        XCTAssertTrue(toggle.exists)
        let before = toggle.value as? String
        toggle.switches.firstMatch.tap()
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != %@", before ?? ""), object: toggle)
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 3), .completed)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Esperando datos del reloj' OR label CONTAINS 'Recepción desactivada'")).firstMatch.exists)
        // No value is claimed: activating never fabricates a reading.
        XCTAssertTrue(app.staticTexts["Pasos: sin datos, bloqueado"].exists || app.descendants(matching: .any)["Pasos: sin datos, bloqueado"].exists)
        toggle.switches.firstMatch.tap() // restore the original state
    }

    func testSecureChannelScreenOpensAndCannotPairWithoutARealWatch() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        let tile = app.buttons["feature-p2"]
        for _ in 0..<8 where !tile.exists || tile.frame.maxY > app.frame.height - 140 { app.swipeUp() }
        XCTAssertTrue(tile.isHittable)
        tile.tap()
        XCTAssertTrue(app.navigationBars["Canal seguro"].waitForExistence(timeout: 5))
        let pair = app.buttons["p2Pair"]
        XCTAssertTrue(pair.waitForExistence(timeout: 5))
        XCTAssertFalse(pair.isEnabled)                       // demo: no verified watch link
        XCTAssertFalse(app.buttons["p2Forget"].exists)       // nothing paired, nothing to forget
        XCTAssertFalse(app.descendants(matching: .any)["p2Code"].exists)   // no code is ever invented
    }

    func testManualObservationDoesNotPromoteAutomaticReadiness() {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["Notificaciones"].tap()
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
        // The tab bar minimizes while scrolling down; scrolling up restores it.
        let watchTab = app.tabBars.buttons["Reloj"]
        for _ in 0..<4 where !watchTab.exists { app.swipeDown() }
        watchTab.tap()
        XCTAssertTrue(text(app, containing: "Sin comprobar").exists)
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
        app.tabBars.buttons["Notificaciones"].tap()
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
