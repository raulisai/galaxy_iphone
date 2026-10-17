import AccessorySetupKit
import CoreBluetooth
import UIKit
import WristBridgeProtocol

@MainActor
final class AccessorySetupAdapter {
    var onAuthorized: ((UUID) -> Void)?
    var onRemoved: (() -> Void)?
    var onEvent: ((BridgeEvent) -> Void)?
    var onMessage: ((String) -> Void)?
    var onAvailability: ((Bool) -> Void)?

    private var session: ASAccessorySession?
    private var selected: ASAccessory?
    private var pendingSelection: ASAccessory?
    private var pickerVisible = false

    func activate() {
        guard session == nil else { return }
        let session = ASAccessorySession()
        self.session = session
        session.activate(on: .main) { [weak self] event in
            // AccessorySetupKit delivers all callbacks on the requested main queue.
            MainActor.assumeIsolated { self?.handle(event) }
        }
    }

    func showPicker() {
        guard let session, !pickerVisible else { return }
        let descriptor = ASDiscoveryDescriptor()
        descriptor.bluetoothServiceUUID = CBUUID(nsuuid: BLEProfile.service)
        // A service UUID alone is insufficient for ASK discovery (Apple documentation).
        // This name is a proposal pending confirmation by the watch implementation.
        descriptor.bluetoothNameSubstring = BLEProfile.proposedAdvertisedName
        descriptor.supportedOptions = [.bluetoothPairingLE]
        guard let image = UIImage(systemName: "applewatch") else {
            onMessage?("No se pudo preparar el selector. Vuelve a abrir la app.")
            return
        }
        let item = ASPickerDisplayItem(name: "Reloj con WristBridge", productImage: image,
                                       descriptor: descriptor)
        pickerVisible = true
        pendingSelection = nil
        onEvent?(.pickerOpened)
        session.showPicker(for: [item]) { [weak self] error in
            MainActor.assumeIsolated {
                guard let self else { return }
                if error != nil {
                    self.pickerVisible = false
                    self.onEvent?(.pickerCancelled)
                    self.onMessage?("No se completó la selección. Acerca el reloj y vuelve a intentarlo.")
                }
            }
        }
    }

    func forget() {
        guard let session, let selected else { return }
        session.removeAccessory(selected) { [weak self] error in
            MainActor.assumeIsolated {
                if error != nil {
                    self?.onMessage?("No se pudo quitar el accesorio. Inténtalo otra vez.")
                    self?.accept(selected)
                }
            }
        }
    }

    private func handle(_ event: ASAccessoryEvent) {
        switch event.eventType {
        case .activated:
            onAvailability?(true)
            // Never pick arbitrarily when more than one authorization exists.
            if let accessories = session?.accessories, accessories.count == 1,
               let accessory = accessories.first {
                accept(accessory)
            } else if (session?.accessories.count ?? 0) > 1 {
                onMessage?("Hay varios accesorios autorizados. Selecciona el reloj que usarás.")
            }
        case .accessoryAdded:
            // Wait until the system picker dismisses before initiating setup UI.
            pendingSelection = event.accessory
        case .pickerDidDismiss:
            pickerVisible = false
            if let pendingSelection { accept(pendingSelection) }
            else { onEvent?(.pickerCancelled) }
            pendingSelection = nil
        case .accessoryChanged:
            if let accessory = event.accessory,
               accessory.bluetoothIdentifier == selected?.bluetoothIdentifier {
                accept(accessory)
            }
        case .accessoryRemoved:
            guard event.accessory?.bluetoothIdentifier == selected?.bluetoothIdentifier else { return }
            selected = nil
            pendingSelection = nil
            onEvent?(.authorizationRemoved)
            onRemoved?()
        case .pickerSetupFailed:
            onMessage?("La autorización no se completó. Revisa que WristBridge esté abierto en el reloj.")
        case .invalidated:
            session = nil
            onAvailability?(false)
            onRemoved?()
            onEvent?(.authorizationRemoved)
            onMessage?("La sesión del accesorio terminó. Vuelve a abrir la app para recuperarla.")
        default:
            break
        }
    }

    private func accept(_ accessory: ASAccessory) {
        guard let identifier = accessory.bluetoothIdentifier else {
            onMessage?("El accesorio elegido no ofrece una conexión Bluetooth compatible.")
            return
        }
        selected = accessory
        onEvent?(.authorized)
        onAuthorized?(identifier)
    }
}
