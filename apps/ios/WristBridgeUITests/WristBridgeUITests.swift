import XCTest

final class WristBridgeUITests: XCTestCase {
    func testDemoDoesNotClaimANCSReadiness() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Demostración · sin conexión real"].waitForExistence(timeout: 5))
        app.buttons["selectWatch"].tap()
        XCTAssertTrue(app.staticTexts["Servicio de configuración encontrado"].exists)
        XCTAssertTrue(app.staticTexts["Sin comprobar"].exists)
        app.buttons["Detener configuración"].tap()
        XCTAssertTrue(app.staticTexts["Detenido"].exists)
        XCTAssertTrue(app.staticTexts["Sin comprobar"].exists)
    }
}
