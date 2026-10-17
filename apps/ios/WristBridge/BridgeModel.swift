import Combine
import Foundation
import WristBridgeProtocol

@MainActor
final class BridgeModel: ObservableObject {
    @Published private(set) var state = BridgeState()
    @Published private(set) var message = "Selecciona el reloj para autorizar su conexión."
    @Published private(set) var pickerAvailable = false
    @Published private(set) var radio = "Sin comprobar"
    @Published private(set) var lastCheck: Date?
    @Published private(set) var stopped = false
    let isDemo: Bool
    private let setup = AccessorySetupAdapter()
    private let transport = BluetoothCentralAdapter()
    private var authorizedID: UUID?
    private let preferences: UserDefaults

    init(isDemo: Bool = false, preferences: UserDefaults = .standard) {
        self.isDemo = isDemo
        self.preferences = preferences
        stopped = preferences.bool(forKey: "bridgeStopped")
        setup.onEvent = { [weak self] in self?.apply($0) }
        setup.onMessage = { [weak self] in self?.message = $0 }
        setup.onAvailability = { [weak self] in self?.pickerAvailable = $0 }
        setup.onAuthorized = { [weak self] identifier in
            guard let self else { return }
            self.authorizedID = identifier
            if !self.stopped { self.transport.authorizeAndConnect(identifier) }
        }
        setup.onRemoved = { [weak self] in
            self?.authorizedID = nil
            self?.transport.revoke()
        }
        transport.onEvent = { [weak self] in self?.apply($0) }
        transport.onMessage = { [weak self] in self?.message = $0 }
        transport.onRadio = { [weak self] in self?.radio = $0 }
        if isDemo {
            pickerAvailable = true
            stopped = false
            message = "Demostración visual. No hay un reloj conectado ni pruebas físicas ejecutadas."
        } else {
            // Activate on app creation, including background restoration launches.
            setup.activate()
        }
    }

    func selectWatch() {
        guard pickerAvailable else { return }
        if isDemo {
            apply(.authorized)
            apply(.connecting)
            apply(.connected)
            apply(.serviceFound)
            radio = "Simulado"
            message = "Conexión simulada. Los avisos del reloj siguen sin comprobarse."
        } else { setup.showPicker() }
    }

    func reconnect() {
        stopped = false
        if !isDemo { preferences.set(false, forKey: "bridgeStopped") }
        if isDemo { selectWatch() }
        else if let authorizedID { transport.authorizeAndConnect(authorizedID) }
    }

    func stop() {
        stopped = true
        if !isDemo { preferences.set(true, forKey: "bridgeStopped") }
        transport.stop()
        apply(.stopped)
        message = "Canal de configuración detenido. Esto no confirma que ANCS se haya detenido en el reloj."
    }

    func forget() {
        if isDemo { apply(.authorizationRemoved) }
        else {
            stopped = true
            preferences.set(true, forKey: "bridgeStopped")
            authorizedID = nil
            transport.revoke()
            setup.forget()
        }
    }

    private func apply(_ event: BridgeEvent) {
        state.apply(event)
        lastCheck = Date()
    }

    var authorizationText: String {
        switch state.authorization {
        case .unknown: "Sin seleccionar"
        case .choosing: "Seleccionando"
        case .authorized: "Accesorio autorizado"
        case .cancelled: "Selección cancelada"
        case .denied: "Permiso denegado"
        case .unavailable: "No disponible"
        }
    }

    var channelText: String {
        if stopped { return "Detenido" }
        return switch state.channel {
        case .idle: "Sin conectar"
        case .connecting: "Conectando"
        case .connected: "Conectado; comprobando servicio"
        case .serviceFound: "Servicio de configuración encontrado"
        case .disconnected: "Desconectado"
        case .unavailable: "No disponible"
        }
    }

    var diagnosticSummary: String {
        """
        WristBridge 0.1.0 (1)
        demo=\(isDemo)
        role=path-A-experiment
        accessory=\(state.authorization.rawValue)
        configuration=\(state.channel.rawValue)
        radio=\(radio)
        ancs=not-verified
        sensitive-channel=disabled
        last-local-check=\(lastCheck?.ISO8601Format() ?? "none")
        device-OS=\(ProcessInfo.processInfo.operatingSystemVersionString)
        """
    }
}
