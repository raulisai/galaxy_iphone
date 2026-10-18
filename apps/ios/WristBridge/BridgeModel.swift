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
    @Published private(set) var authorizedChoices: [UUID] = []
    @Published private(set) var stopped = false
    @Published private(set) var automaticRecovery = true
    @Published private(set) var settingsWarning: String?
    @Published private(set) var retentionDays = 30
    @Published private(set) var lastLinkedAt: Date?
    let isDemo: Bool
    private let preferences: UserDefaults
    private static let lastLinkedKey = "WristBridge.lastLinkedAt"
    private let setup = AccessorySetupAdapter()
    private let transport = BluetoothCentralAdapter()
    @Published private(set) var authorizedID: UUID?
    #if DEBUG
    private var exercisedLabReconnect = false
    #endif
    private let settingsStore: LocalSettingsStore?
    private var campaignController: RecoveryCampaignController?
    var recoveryCampaign: RecoveryCampaignController {
        if let campaignController { return campaignController }
        let controller = RecoveryCampaignController(bridge: self)
        campaignController = controller
        return controller
    }

    func beginRecoveryProbe(accessory: UUID) -> Bool {
        guard !isDemo, !stopped, state.authorization == .authorized, authorizedID == accessory else { return false }
        transport.stop()
        transport.authorizeAndConnect(accessory)
        return true
    }
    func stopRecoveryProbe() { transport.stop() }

    /// Quietly re-requests the link when it went idle without the operator asking
    /// (watch came back, discovery gave up, radio returned). Called on foreground
    /// and by a slow heartbeat; a pending connection is never restarted.
    func resumeLinkIfNeeded() {
        guard !isDemo, let accessory = authorizedID,
              LinkResumePolicy.shouldResume(stopped: stopped, authorized: state.authorization == .authorized,
                                            automaticRecovery: automaticRecovery,
                                            campaignRunning: campaignController?.isRunning == true,
                                            radioOn: radio == "Encendido", channel: state.channel) else { return }
        transport.authorizeAndConnect(accessory)
    }

    init(isDemo: Bool = false, preferences: UserDefaults = .standard) {
        self.isDemo = isDemo
        self.preferences = preferences
        lastLinkedAt = isDemo ? nil : preferences.object(forKey: Self.lastLinkedKey) as? Date
        settingsStore = isDemo ? nil : LocalSettingsStore(defaults: preferences)
        stopped = settingsStore?.settings.stopped ?? false
        automaticRecovery = settingsStore?.settings.automaticRecovery ?? true
        retentionDays = settingsStore?.settings.retentionDays ?? 30
        settingsWarning = settingsStore?.warning
        transport.automaticRecovery = automaticRecovery
        setup.onChoices = { [weak self] in self?.authorizedChoices = $0 }
        setup.onUnresolvedAuthorization = { [weak self] in self?.transport.rejectRestoration() }
        setup.onEvent = { [weak self] in self?.apply($0) }
        setup.onMessage = { [weak self] in self?.message = $0 }
        setup.onAvailability = { [weak self] in self?.pickerAvailable = $0 }
        setup.onAuthorized = { [weak self] identifier in
            guard let self else { return }
            if self.authorizedID != identifier { self.campaignController?.cancel(interrupted: true) }
            self.authorizedID = identifier
            if !self.stopped { self.transport.authorizeAndConnect(identifier) }
            else { self.transport.rejectRestoration() }
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

    func prepareForBluetoothRestoration() {
        guard !isDemo else { return }
        if stopped { transport.rejectRestoration() }
        transport.prepareForRestoration()
    }

    func refreshAuthorization() { if !isDemo { setup.activate() } }
    func selectAuthorized(_ identifier: UUID) {
        campaignController?.cancel(interrupted: true)
        if !isDemo { setup.selectAuthorized(identifier) }
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
        campaignController?.cancel()
        stopped = false
        settingsStore?.update(stopped: false)
        if isDemo { selectWatch() }
        else if let authorizedID { transport.authorizeAndConnect(authorizedID) }
        else { message = "No hay un reloj autorizado disponible. Espera a que termine la autorización o vuelve a seleccionarlo." }
    }

    func stop() {
        campaignController?.cancel()
        stopped = true
        settingsStore?.update(stopped: true)
        transport.stop()
        apply(.stopped)
        message = "Canal de configuración detenido. Esto no confirma que ANCS se haya detenido en el reloj."
    }

    func forget() {
        campaignController?.cancel(interrupted: true)
        if isDemo { apply(.authorizationRemoved) }
        else {
            stopped = true
            settingsStore?.update(stopped: true)
            authorizedID = nil
            transport.revoke()
            setup.forget()
        }
    }

    func setRetentionDays(_ days: Int) {
        guard [7, 30, 90].contains(days) else { return }
        retentionDays = days
        settingsStore?.update(retentionDays: days)
    }

    func resetLocalSettings() {
        stop()
        settingsStore?.reset()
        automaticRecovery = true
        transport.automaticRecovery = true
        retentionDays = 30
        settingsWarning = nil
        transport.clearDiagnostics()
        campaignController?.delete()
        lastLinkedAt = nil
        preferences.removeObject(forKey: Self.lastLinkedKey)
    }

    func setAutomaticRecovery(_ enabled: Bool) {
        automaticRecovery = enabled
        settingsStore?.update(automaticRecovery: enabled)
        transport.automaticRecovery = enabled
    }

    private func apply(_ event: BridgeEvent) {
        state.apply(event)
        lastCheck = Date()
        // Local observation only: when our own service check last succeeded.
        if case .serviceFound = event, state.channel == .serviceFound {
            lastLinkedAt = Date()
            if !isDemo { preferences.set(lastLinkedAt, forKey: Self.lastLinkedKey) }
        }
        campaignController?.handle(event)
        #if DEBUG
        // Explicit, single-cycle physical regression of stop -> reconnect while
        // cancellation is pending. Never enabled by the normal UI or Release.
        if case .serviceFound = event, !isDemo, !exercisedLabReconnect,
           ProcessInfo.processInfo.arguments.contains("--lab-reconnect-once") {
            exercisedLabReconnect = true
            Task { [weak self] in
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
                guard let self, !self.stopped, self.state.channel == .serviceFound,
                      let identifier = self.authorizedID else { return }
                self.transport.stop()
                self.transport.authorizeAndConnect(identifier)
            }
        }
        #endif
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
        WristBridge \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown") (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"))
        demo=\(isDemo)
        role=path-A-experiment
        accessory=\(state.authorization.rawValue)
        configuration=\(state.channel.rawValue)
        radio=\(radio)
        automatic-recovery=\(automaticRecovery)
        recovery-attempts=\(transport.retryAttempts)
        restored-peripherals=\(transport.restoredPeripheralCount)
        settings-persistence=\(settingsWarning == nil ? "available" : "preserved-unreadable")
        trace-persistence=\(transport.tracePersistenceAvailable)
        bluetooth-trace=\(transport.diagnosticTrace)
        last-verified-link-att-capacity=\(transport.attCapacity)
        ancs=not-verified
        sensitive-channel=disabled
        last-local-check=\(lastCheck?.ISO8601Format() ?? "none")
        device-OS=\(ProcessInfo.processInfo.operatingSystemVersionString)
        """
    }
}
