import Foundation

/// Only enumerated transport events and numeric outcomes. Never peripheral IDs,
/// error descriptions, names, payloads or notification content.
public enum BluetoothTraceStep: String, Codable, Sendable {
    case requested, alreadyActive, stopped, radioChanged, waitingForRadio, waitingForCancellation
    case peerUnavailable, connectSubmitted, connected, discoverySubmitted
    case discoveryCompleted, serviceVerified, discoveryTimedOut, connectionFailed, disconnected
    case cancellationSubmitted, cancellationCompleted, recoveryScheduled, retryExhausted
    case servicesInvalidated, restorationReceived, serviceMissing, pairingLost
    case writeWithResponseCapacity, writeWithoutResponseCapacity

    var title: String {
        switch self {
        case .requested: "Conexión solicitada"
        case .alreadyActive: "Ya había un intento activo"
        case .stopped: "Intento anterior detenido"
        case .radioChanged: "Bluetooth cambió de estado"
        case .waitingForRadio: "Esperando a Bluetooth"
        case .waitingForCancellation: "Esperando a que iOS cierre el intento anterior"
        case .peerUnavailable: "iOS no ofrece el reloj autorizado"
        case .connectSubmitted: "Conexión pendiente enviada a iOS"
        case .connected: "Enlace establecido"
        case .discoverySubmitted: "Buscando el servicio del reloj"
        case .discoveryCompleted: "Búsqueda de servicio terminada"
        case .serviceVerified: "Servicio del reloj verificado"
        case .discoveryTimedOut: "El reloj no respondió a la búsqueda"
        case .connectionFailed: "Conexión fallida"
        case .disconnected: "Desconectado"
        case .cancellationSubmitted: "Cancelando intento"
        case .cancellationCompleted: "Intento cancelado"
        case .recoveryScheduled: "Reintento programado (s)"
        case .retryExhausted: "Reintentos agotados"
        case .servicesInvalidated: "El reloj cambió sus servicios"
        case .restorationReceived: "iOS restauró la app en segundo plano"
        case .serviceMissing: "Enlazado, pero la app del reloj no ofrece su servicio"
        case .pairingLost: "EL RELOJ BORRÓ EL EMPAREJAMIENTO"
        case .writeWithResponseCapacity: "Capacidad de escritura"
        case .writeWithoutResponseCapacity: "Capacidad sin respuesta"
        }
    }
}

public struct BluetoothTraceEntry: Codable, Sendable {
    public let date: Date
    public let step: BluetoothTraceStep
    public let value: Int?
    public let errorCode: Int?
}

/// Human-readable CoreBluetooth (`CBErrorDomain`) codes, so an exported log explains itself.
public enum BluetoothErrorText {
    /// CBError.peerRemovedPairingInformation: the watch erased its bond; iOS rejects the old keys until the
    /// device is forgotten (removing the accessory, or Ajustes › Bluetooth › Omitir).
    public static let peerRemovedPairing = 14
    public static let encryptionTimedOut = 15

    public static func describe(_ code: Int) -> String {
        switch code {
        case 0: "desconocido"
        case 3: "no conectado"
        case 6: "tiempo de conexión agotado (el reloj se alejó o dejó de responder)"
        case 7: "el reloj cerró el enlace"
        case 10: "la conexión falló"
        case 11: "límite de conexiones alcanzado"
        case 12: "dispositivo desconocido para iOS"
        case 14: "el reloj borró el emparejamiento; hay que quitarlo y volver a agregarlo"
        case 15: "tiempo de cifrado agotado (claves de emparejamiento distintas)"
        case 16: "demasiados dispositivos emparejados en el iPhone"
        default: "código \(code)"
        }
    }
}

public struct BluetoothTrace: Codable, Sendable {
    public static let capacity = 400
    public private(set) var entries: [BluetoothTraceEntry] = []
    public init() {}

    /// Continues a trace persisted by a previous launch; a missing or unreadable file starts empty.
    public init(restoring data: Data?) {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let data, let previous = try? decoder.decode(BluetoothTrace.self, from: data) {
            entries = Array(previous.entries.suffix(Self.capacity))
        }
    }

    public mutating func record(_ step: BluetoothTraceStep, value: Int? = nil, errorCode: Int? = nil, at date: Date = Date()) {
        entries.append(BluetoothTraceEntry(date: date, step: step, value: value, errorCode: errorCode))
        if entries.count > Self.capacity { entries.removeFirst(entries.count - Self.capacity) }
    }

    public func export() -> String {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(self) else { return "trace-unavailable" }
        return String(decoding: data, as: UTF8.self)
    }

    /// One line per event, oldest first, in local time: "03 nov 07:32:30 · Conexión fallida · error 14: …".
    public func readable(timeZone: TimeZone = .current) -> [String] {
        let format = DateFormatter()
        format.locale = Locale(identifier: "es")
        format.timeZone = timeZone
        format.dateFormat = "dd MMM HH:mm:ss"
        return entries.map { entry in
            var line = "\(format.string(from: entry.date)) · \(entry.step.title)"
            if let value = entry.value { line += " [\(value)]" }
            if let code = entry.errorCode { line += " · error \(code): \(BluetoothErrorText.describe(code))" }
            return line
        }
    }

    /// The link keeps failing because the watch no longer has the bond iOS holds. Retrying cannot fix it.
    public static func isPairingLost(errorCode: Int?) -> Bool {
        errorCode == BluetoothErrorText.peerRemovedPairing
    }
}
