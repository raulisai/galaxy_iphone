import CoreBluetooth
import Foundation
import WristBridgeProtocol

/// Path A laboratory transport. No P2 writes, arbitrary scanning or ANCS parsing.
@MainActor
// CoreBluetooth callbacks use the .main queue supplied below. Enforce that
// isolation at runtime when entering its Objective-C delegate requirements.
final class BluetoothCentralAdapter: NSObject, @preconcurrency CBCentralManagerDelegate,
                                     @preconcurrency CBPeripheralDelegate {
    var onEvent: ((BridgeEvent) -> Void)?
    var onMessage: ((String) -> Void)?
    var onRadio: ((String) -> Void)?
    private let diagnostics = BluetoothDiagnostics()
    func clearDiagnostics() { diagnostics.clear() }
    var diagnosticTrace: String { diagnostics.summary }
    var tracePersistenceAvailable: Bool { diagnostics.persistenceAvailable }
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var authorizedID: UUID?
    private var connectionDeadline: Task<Void, Never>?
    private var enabled = false
    private var radioReady = false
    private var discoveryGate = ServiceDiscoveryGate()
    private var retryTask: Task<Void, Never>?
    private var retryPolicy = ConnectionRetryPolicy()
    private(set) var restoredPeripheralCount = 0
    var retryAttempts: Int { retryPolicy.attempts }
    var automaticRecovery = true {
        didSet {
            if !automaticRecovery {
                retryTask?.cancel()
                retryTask = nil
            }
        }
    }
    // Drain cancellation callbacks before reusing a CBPeripheral. Object identity
    // alone cannot distinguish an old disconnect from a new attempt on that object.
    private var cancelling = Set<UUID>()
    static let restorationIdentifier = "WristBridge.central.pathA.v1"
    private var authorizationResolved = false
    private var pendingRestoration: [CBPeripheral] = []
    private var deferredCancellations: [CBPeripheral] = []

    func prepareForRestoration() {
        guard central == nil else { return }
        central = CBCentralManager(delegate: self, queue: .main, options: [
            CBCentralManagerOptionRestoreIdentifierKey: Self.restorationIdentifier,
            CBCentralManagerOptionShowPowerAlertKey: false
        ])
    }

    func rejectRestoration() {
        authorizationResolved = true
        revoke()
    }

    func authorizeAndConnect(_ identifier: UUID) {
        diagnostics.record(.requested)
        authorizationResolved = true
        if authorizedID == identifier, enabled, peripheral != nil {
            diagnostics.record(.alreadyActive, value: peripheral?.state.rawValue)
            onMessage?("Ya hay una conexión o un intento activo. Si no avanza, pulsa Detener configuración antes de iniciar otro intento.")
            return
        }
        onMessage?("Iniciando la conexión con el reloj autorizado…")
        let restored = pendingRestoration
        pendingRestoration.removeAll()
        stop()
        authorizedID = identifier
        enabled = true
        retryPolicy.reset()
        prepareForRestoration()
        consumeRestored(restored)
        connectIfPossible()
    }

    func stop() {
        diagnostics.record(.stopped)
        discoveryGate.reset()
        enabled = false
        retryTask?.cancel()
        retryTask = nil
        connectionDeadline?.cancel()
        connectionDeadline = nil
        let unvalidated = pendingRestoration
        pendingRestoration.removeAll()
        for device in unvalidated { cancel(device) }
        let old = peripheral
        peripheral = nil
        if let old { cancel(old) }
        onEvent?(.stopped)
    }

    func revoke() {
        stop()
        authorizedID = nil
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        diagnostics.record(.radioChanged, value: central.state.rawValue)
        radioReady = central.state == .poweredOn
        switch central.state {
        case .poweredOn:
            onRadio?("Encendido")
            let rejected = deferredCancellations
            deferredCancellations.removeAll()
            for device in rejected { cancel(device) }
            if let peripheral, peripheral.state == .connected {
                beginServiceDiscovery(peripheral)
            } else { connectIfPossible() }
        case .unauthorized:
            stop()
            onRadio?("Sin autorización")
            onEvent?(.authorizationDenied)
            onMessage?("Revisa la autorización del accesorio en Ajustes.")
        case .poweredOff:
            invalidateRadioObjects()
            if !automaticRecovery { enabled = false }
            onRadio?("Apagado")
            onEvent?(.radioUnavailable)
            onMessage?("Bluetooth está apagado. La recuperación respeta el ajuste automático y Detener configuración.")
        case .unsupported:
            stop()
            onRadio?("No disponible")
            onEvent?(.radioUnavailable)
        default:
            invalidateRadioObjects()
            onRadio?("Preparando")
            onEvent?(.radioUnavailable)
        }
    }

    private func invalidateRadioObjects() {
        discoveryGate.reset()
        connectionDeadline?.cancel()
        connectionDeadline = nil
        retryTask?.cancel()
        retryTask = nil
        peripheral?.delegate = nil
        peripheral = nil
        // A powered-off/reset manager invalidates old objects. Their cancellation
        // callbacks may never arrive, so they cannot block the next poweredOn.
        cancelling.removeAll()
        pendingRestoration.removeAll()
        deferredCancellations.removeAll()
    }

    private func connectIfPossible() {
        guard enabled, radioReady, let central, central.state == .poweredOn,
              let authorizedID, peripheral == nil else { return }
        guard cancelling.isEmpty else {
            diagnostics.record(.waitingForCancellation, value: cancelling.count)
            onEvent?(.disconnected)
            onMessage?("Esperando que iOS termine el intento anterior. El nuevo intento comenzará cuando confirme el cierre.")
            return
        }
        guard let device = central.retrievePeripherals(withIdentifiers: [authorizedID]).first else {
            diagnostics.record(.peerUnavailable)
            onEvent?(.disconnected)
            onMessage?("No se encontró el reloj autorizado. Acércalo y vuelve a seleccionarlo.")
            return
        }
        peripheral = device
        device.delegate = self
        onEvent?(.connecting)
        // CoreBluetooth keeps this request pending while the peer is out of range.
        // A suspended app must not depend on a Swift timer to retain the request.
        diagnostics.record(.connectSubmitted, value: device.state.rawValue)
        central.connect(device)
        onMessage?("Esperando al reloj autorizado. Acércalo o pulsa Detener configuración para cancelar.")
    }

    private func armDeadline(for device: CBPeripheral) {
        connectionDeadline?.cancel()
        connectionDeadline = Task { [weak self, weak device] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self, let device, self.peripheral === device else { return }
            self.diagnostics.record(.discoveryTimedOut, value: device.state.rawValue)
            // The watch linked before its service was ready. Do not give up: drop
            // this attempt and let the cancellation callback submit a fresh pending
            // connection, so a watch that comes back is found without relaunching.
            self.discoveryGate.reset()
            self.peripheral = nil
            if !self.automaticRecovery { self.enabled = false }
            self.cancel(device)
            self.onEvent?(.disconnected)
            self.onMessage?(self.automaticRecovery
                ? "El reloj aún no respondía. Se volverá a intentar automáticamente."
                : "No se pudo comprobar el servicio del reloj. Pulsa Reconectar para volver a intentarlo.")
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard enabled, !cancelling.contains(peripheral.identifier), peripheral === self.peripheral,
              peripheral.identifier == authorizedID else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        diagnostics.record(.connected, value: peripheral.state.rawValue)
        beginServiceDiscovery(peripheral)
    }

    private func beginServiceDiscovery(_ device: CBPeripheral) {
        guard enabled, device === peripheral, device.identifier == authorizedID,
              device.state == .connected else { return }
        guard radioReady, central?.state == .poweredOn else {
            diagnostics.record(.waitingForRadio)
            onMessage?("Restaurando el enlace. Esperando a que Bluetooth esté listo para comprobar el reloj…")
            return
        }
        guard discoveryGate.beginIfReady(radioReady: radioReady, authorized: enabled,
                                         connected: device.state == .connected) else { return }
        onEvent?(.connected)
        onMessage?("El enlace respondió. Comprobando el servicio de configuración del reloj…")
        armDeadline(for: device)
        diagnostics.record(.discoverySubmitted, value: central?.state.rawValue)
        device.discoverServices([CBUUID(nsuuid: BLEProfile.service)])
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard enabled, peripheral === self.peripheral, discoveryGate.inFlight else { return }
        discoveryGate.reset()
        diagnostics.record(.discoveryCompleted, value: peripheral.services?.count, error: error)
        connectionDeadline?.cancel()
        connectionDeadline = nil
        if error == nil,
           peripheral.services?.contains(where: { $0.uuid == CBUUID(nsuuid: BLEProfile.service) }) == true {
            retryPolicy.reset()
            diagnostics.record(.serviceVerified)
            onEvent?(.serviceFound)
            onMessage?("Se encontró el servicio de configuración. Los avisos del reloj siguen sin comprobarse.")
        } else {
            stop()
            onEvent?(.disconnected)
            onMessage?("El reloj no ofrece el servicio esperado. Revisa la versión de su app.")
        }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        diagnostics.record(.connectionFailed, error: error)
        finishDisconnection(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        diagnostics.record(.disconnected, error: error)
        finishDisconnection(peripheral, reconnectImmediately: true)
    }

    private func finishDisconnection(_ peripheral: CBPeripheral, reconnectImmediately: Bool = false) {
        if cancelling.remove(peripheral.identifier) != nil {
            diagnostics.record(.cancellationCompleted)
            if enabled { connectIfPossible() }
            return
        }
        guard peripheral === self.peripheral else { return }
        connectionDeadline?.cancel()
        connectionDeadline = nil
        self.peripheral = nil
        discoveryGate.reset()
        onEvent?(.disconnected)
        scheduleRecovery(immediately: reconnectImmediately)
    }

    private func scheduleRecovery(immediately: Bool) {
        guard enabled, automaticRecovery, central?.state == .poweredOn,
              retryTask == nil else {
            onMessage?("El canal de configuración se desconectó. Pulsa Reconectar para volver a intentarlo.")
            return
        }
        guard let delay = retryPolicy.nextDelay() else {
            enabled = false
            diagnostics.record(.retryExhausted)
            onMessage?("Se agotaron los tres reintentos. Pulsa Reconectar para iniciar otra prueba.")
            return
        }
        if immediately {
            // Submit the new pending request inside the Bluetooth callback, before
            // iOS suspends us again. Backoff is only for reported connection failures.
            connectIfPossible()
            return
        }
        onMessage?("El canal se desconectó. Se solicitará de nuevo el enlace al reloj autorizado.")
        diagnostics.record(.recoveryScheduled, value: delay)
        retryTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) } catch { return }
            guard let self else { return }
            self.retryTask = nil
            guard self.enabled, self.automaticRecovery else { return }
            self.connectIfPossible()
        }
    }

    private func cancel(_ device: CBPeripheral) {
        guard let central else { return }
        if device.state == .disconnected { return }
        guard radioReady, central.state == .poweredOn else {
            if !deferredCancellations.contains(where: { $0 === device }) { deferredCancellations.append(device) }
            return
        }
        diagnostics.record(.cancellationSubmitted, value: device.state.rawValue)
        cancelling.insert(device.identifier)
        central.cancelPeripheralConnection(device)
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard enabled, peripheral === self.peripheral else { return }
        diagnostics.record(.servicesInvalidated, value: invalidatedServices.count)
        discoveryGate.reset()
        beginServiceDiscovery(peripheral)
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        restoredPeripheralCount = restored.count
        diagnostics.record(.restorationReceived, value: restored.count)
        guard authorizationResolved else {
            pendingRestoration = restored
            return
        }
        consumeRestored(restored)
    }

    private func consumeRestored(_ restored: [CBPeripheral]) {
        for device in restored {
            guard enabled, device.identifier == authorizedID else {
                cancel(device)
                continue
            }
            peripheral = device
            device.delegate = self
            if device.state == .connected {
                beginServiceDiscovery(device)
            } else if device.state == .connecting {
                onEvent?(.connecting)
            } else {
                peripheral = nil
                connectIfPossible()
            }
        }
    }
}
