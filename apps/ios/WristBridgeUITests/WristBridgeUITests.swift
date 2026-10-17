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

    func testLiveLaunchRequiresConsentBeforeSchedulingFixtures() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.navigationBars["WristBridge"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Demostración · sin conexión real"].exists)
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
