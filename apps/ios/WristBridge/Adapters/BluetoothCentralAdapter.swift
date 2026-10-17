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
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var authorizedID: UUID?
    private var connectionDeadline: Task<Void, Never>?
    private var enabled = false
    // Drain cancellation callbacks before reusing a CBPeripheral. Object identity
    // alone cannot distinguish an old disconnect from a new attempt on that object.
    private var cancelling = Set<UUID>()
    private let restorationID = "WristBridge.central.pathA.v1"

    func authorizeAndConnect(_ identifier: UUID) {
        if authorizedID == identifier, enabled, peripheral != nil { return }
        stop()
        authorizedID = identifier
        enabled = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main, options: [
                CBCentralManagerOptionRestoreIdentifierKey: restorationID,
                CBCentralManagerOptionShowPowerAlertKey: false
            ])
        } else {
            connectIfPossible()
        }
    }

    func retry() {
        guard authorizedID != nil else { return }
        enabled = true
        connectIfPossible()
    }

    func stop() {
        enabled = false
        connectionDeadline?.cancel()
        connectionDeadline = nil
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
        switch central.state {
        case .poweredOn:
            onRadio?("Encendido")
            connectIfPossible()
        case .unauthorized:
            stop()
            onRadio?("Sin autorización")
            onEvent?(.authorizationDenied)
            onMessage?("Revisa la autorización del accesorio en Ajustes.")
        case .poweredOff:
            stop()
            onRadio?("Apagado")
            onEvent?(.radioUnavailable)
            onMessage?("Enciende Bluetooth en Ajustes y pulsa Reconectar.")
        case .unsupported:
            stop()
            onRadio?("No disponible")
            onEvent?(.radioUnavailable)
        default:
            onRadio?("Preparando")
            onEvent?(.radioUnavailable)
        }
    }

    private func connectIfPossible() {
        guard enabled, let central, central.state == .poweredOn,
              let authorizedID, peripheral == nil, cancelling.isEmpty else { return }
        guard let device = central.retrievePeripherals(withIdentifiers: [authorizedID]).first else {
            onEvent?(.disconnected)
            onMessage?("No se encontró el reloj autorizado. Acércalo y vuelve a seleccionarlo.")
            return
        }
        peripheral = device
        device.delegate = self
        onEvent?(.connecting)
        central.connect(device)
        armDeadline(for: device)
    }

    private func armDeadline(for device: CBPeripheral) {
        connectionDeadline?.cancel()
        connectionDeadline = Task { [weak self, weak device] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self, let device, self.peripheral === device else { return }
            self.peripheral = nil
            self.cancel(device)
            self.onEvent?(.disconnected)
            self.onMessage?("El reloj no respondió a tiempo. Acércalo y pulsa Reconectar.")
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard enabled, !cancelling.contains(peripheral.identifier), peripheral === self.peripheral,
              peripheral.identifier == authorizedID else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        onEvent?(.connected)
        // Deadline includes service discovery, not only establishment of the link.
        peripheral.discoverServices([CBUUID(nsuuid: BLEProfile.service)])
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard enabled, peripheral === self.peripheral else { return }
        connectionDeadline?.cancel()
        if error == nil,
           peripheral.services?.contains(where: { $0.uuid == CBUUID(nsuuid: BLEProfile.service) }) == true {
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
        finishDisconnection(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        finishDisconnection(peripheral)
    }

    private func finishDisconnection(_ peripheral: CBPeripheral) {
        if cancelling.remove(peripheral.identifier) != nil {
            connectIfPossible()
            return
        }
        guard peripheral === self.peripheral else { return }
        connectionDeadline?.cancel()
        self.peripheral = nil
        onEvent?(.disconnected)
        // Operator-controlled retries during the spike; no rapid reconnect loop.
        onMessage?("El canal de configuración se desconectó. Pulsa Reconectar cuando el reloj esté cerca.")
    }

    private func cancel(_ device: CBPeripheral) {
        guard let central else { return }
        if device.state == .disconnected { return }
        cancelling.insert(device.identifier)
        central.cancelPeripheralConnection(device)
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard enabled, peripheral === self.peripheral else { return }
        onEvent?(.connected)
        armDeadline(for: peripheral)
        peripheral.discoverServices([CBUUID(nsuuid: BLEProfile.service)])
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        for device in restored {
            guard enabled, device.identifier == authorizedID else {
                central.cancelPeripheralConnection(device)
                continue
            }
            peripheral = device
            device.delegate = self
            armDeadline(for: device)
            if device.state == .connected {
                onEvent?(.connected)
                device.discoverServices([CBUUID(nsuuid: BLEProfile.service)])
            } else if device.state == .connecting {
                onEvent?(.connecting)
            } else {
                peripheral = nil
                connectIfPossible()
            }
        }
    }
}
