import Combine
import CoreBluetooth
import Foundation
import UIKit
import WristBridgeProtocol

/// iPhone side of the own secure channel (A-P2-04/05, A-H01, A-K7/K8). It rides on the peripheral that
/// `BluetoothCentralAdapter` already verified (service present) and forwards delegate callbacks here.
///
/// SECURITY: the Noise composition is our own and NOT independently reviewed (K3). Use only the
/// user's own data until that review closes. Diagnostics never contain keys, counters or health values.
@MainActor
final class P2Link: ObservableObject {
    static let shared = P2Link()

    enum State: Equatable {
        case off, waitingForLink, discovering, readingCapabilities, enablingNotifications, handshaking
        case awaitingConfirmation(code: String)
        case ready(sessionId: String)
        case failed(String)
    }

    @Published private(set) var state: State = .off
    @Published private(set) var isPaired: Bool
    @Published private(set) var healthStatus = "Sin sincronizar"
    @Published private(set) var lastFindWatch: String?
    @Published private(set) var diagnostics: [String] = []
    /// User switch: while on, the link starts by itself every time the watch's service is verified.
    @Published private(set) var enabled: Bool

    private static let enabledKey = "WristBridge.p2Enabled"
    private static let cursorKey = "WristBridge.health.cursor"
    private let identity = P2IdentityStore()
    private let defaults = UserDefaults.standard
    private weak var health: HealthStore?

    private var peripheral: CBPeripheral?
    private var capabilities: CBCharacteristic?
    private var requests: CBCharacteristic?
    private var events: CBCharacteristic?
    private var capabilityRecord: [UInt8] = []
    private var maxAtt = 0
    private var writeQueue: [[UInt8]] = []
    private var writing = false
    private var handshake: P2NoiseXX?
    private var handshakeInbox = P2Reassembler()
    private var session: P2SecureSession?
    private var pendingKeysHash: [UInt8]?
    private var pendingWatchKey: [UInt8]?
    private var nextRequestId: Int64 = 100
    private var responders: [Int64: (P2Messages.Envelope) -> Void] = [:]
    private var syncClient: HealthSyncClient?
    private var timeout: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private let clock = ContinuousClock()
    private let epoch = ContinuousClock.now

    init() {
        isPaired = identity.pinnedWatch != nil
        enabled = UserDefaults.standard.bool(forKey: Self.enabledKey)
    }

    var isReady: Bool { if case .ready = state { true } else { false } }
    func attach(health: HealthStore) { self.health = health }

    // MARK: Switches

    func setEnabled(_ on: Bool) {
        if on { connectNow() } else {
            enabled = false; defaults.set(false, forKey: Self.enabledKey)
            close(reason: nil, newState: .off)
        }
    }

    /// "Vincular reloj" / "Reintentar": turn the channel on and start now if the watch link is already verified.
    func connectNow() {
        if !enabled { enabled = true; defaults.set(true, forKey: Self.enabledKey) }
        if let peripheral { linkReady(peripheral) } else { state = .waitingForLink }
    }

    /// "Olvidar reloj": the registration must be repeated on both devices.
    func forgetWatch() {
        close(reason: nil, newState: .off)
        identity.forget(); isPaired = false; enabled = false
        defaults.set(false, forKey: Self.enabledKey); defaults.removeObject(forKey: Self.cursorKey)
        healthStatus = "Sin sincronizar"
        note("registro olvidado")
    }

    // MARK: Link events from BluetoothCentralAdapter

    /// The watch's primary service was verified on this peripheral.
    func linkReady(_ peripheral: CBPeripheral) {
        self.peripheral = peripheral
        guard enabled else { note("servicio verificado; canal desactivado (pulsa «Vincular reloj»)"); return }
        resetSession()
        state = .discovering
        note("buscando características")
        guard let service = peripheral.services?.first(where: { $0.uuid == CBUUID(nsuuid: BLEProfile.service) }) else {
            fail("El reloj no ofrece el canal propio. Actualiza su app."); return
        }
        peripheral.discoverCharacteristics([CBUUID(nsuuid: BLEProfile.capabilities), CBUUID(nsuuid: BLEProfile.requests),
                                            CBUUID(nsuuid: BLEProfile.events)], for: service)
        arm("No se encontraron las características del reloj.", seconds: 15)
    }

    /// Bluetooth link lost, Service Changed, radio off: the application session ends; ANCS is untouched.
    func linkLost() {
        peripheral = nil
        guard state != .off else { return }
        close(reason: nil, newState: enabled ? .waitingForLink : .off)
    }

    // MARK: CoreBluetooth callbacks (forwarded)

    func didDiscoverCharacteristics(_ service: CBService, error: Error?) {
        guard state == .discovering else { return }
        capabilities = service.characteristics?.first { $0.uuid == CBUUID(nsuuid: BLEProfile.capabilities) }
        requests = service.characteristics?.first { $0.uuid == CBUUID(nsuuid: BLEProfile.requests) }
        events = service.characteristics?.first { $0.uuid == CBUUID(nsuuid: BLEProfile.events) }
        guard error == nil, let capabilities, requests != nil, events != nil else {
            fail("El reloj aún no expone el canal propio (necesita la app 0.6.0-lab o posterior)."); return
        }
        state = .readingCapabilities
        peripheral?.readValue(for: capabilities)
        arm("El reloj no respondió a la lectura de capacidades.", seconds: 15)
    }

    func didUpdateValue(_ characteristic: CBCharacteristic, error: Error?) {
        if error != nil { if state != .off { fail("No se pudo leer del reloj (¿vínculo cifrado?).") }; return }
        let bytes = Array(characteristic.value ?? Data())
        if characteristic.uuid == CBUUID(nsuuid: BLEProfile.capabilities) {
            guard state == .readingCapabilities else { return }
            guard bytes.count == 16, bytes[0] == 1, bytes[1] == 1, bytes[3] == 1 else {
                fail("Versión del canal incompatible entre la app del reloj y la del iPhone."); return
            }
            capabilityRecord = bytes
            enableNotifications()
        } else if characteristic.uuid == CBUUID(nsuuid: BLEProfile.events) {
            receive(bytes)
        }
    }

    func didUpdateNotificationState(_ characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == CBUUID(nsuuid: BLEProfile.events), state == .enablingNotifications else { return }
        if error != nil { fail("No se pudo activar el canal del reloj."); return }
        if characteristic.isNotifying { beginHandshake() }
        else { peripheral?.setNotifyValue(true, for: characteristic) }   // we first cleared a stale CCCD
    }

    func didWrite(_ characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == CBUUID(nsuuid: BLEProfile.requests) else { return }
        writing = false
        if error != nil { fail("No se pudo escribir al reloj."); return }
        pump()
    }

    // MARK: Steps

    private func enableNotifications() {
        guard let peripheral, let events else { return }
        // The watch fixes its frame size when the CCCD is enabled, so the MTU must already be negotiated:
        // CoreBluetooth negotiates at connection time; the size below is what iOS reports for it.
        // iOS reports 512 here on this phone (measured), which is not ATT_MTU-3, so it cannot be trusted.
        // The watch fixes its frame size from the real MTU (usually 185 -> 182 with iOS). Our own messages are
        // small and always fit ONE frame, which the watch accepts for any capacity >= 182; never exceed it.
        let reported = peripheral.maximumWriteValueLength(for: .withoutResponse)
        maxAtt = min(reported, 182)
        note("capacidad iOS \(reported), usada \(maxAtt)")
        guard maxAtt >= P2Codec.minimumAttValueBytes else { fail(P2Error.capacityTooSmall.userFacingSummary); return }
        state = .enablingNotifications
        if events.isNotifying { peripheral.setNotifyValue(false, for: events) } else { peripheral.setNotifyValue(true, for: events) }
        arm("El reloj no activó las notificaciones del canal.", seconds: 15)
    }

    private func beginHandshake() {
        guard let key = try? identity.localKey() else { fail("No se pudo acceder al llavero del iPhone."); return }
        let prologue = Array("WB-P2|1.0|p2-cand-1|\(BLEProfile.service.uuidString.lowercased())|".utf8) + capabilityRecord
        let noise = P2NoiseXX(initiator: true, staticKey: key, prologue: prologue)
        handshake = noise
        handshakeInbox = P2Reassembler()
        state = .handshaking
        do {
            enqueue(frames: try P2Codec.encode(type: .handshake, messageId: 1, body: try noise.writeMessage(), maxAttValueBytes: maxAtt))
            arm("El reloj no respondió al registro.", seconds: 20)
        } catch { fail(errorText(error)) }
    }

    private func receive(_ bytes: [UInt8]) {
        let parts = epoch.duration(to: clock.now).components
        let now = parts.seconds * 1000 + parts.attoseconds / 1_000_000_000_000_000
        do {
            if let session { try receiveSecure(session, bytes, now: now) } else { try receiveHandshake(bytes, now: now) }
        } catch { fail(errorText(error)) }
    }

    private func receiveHandshake(_ bytes: [UInt8], now: Int64) throws {
        switch handshakeInbox.accept(bytes, atMs: now) {
        case .partial, .ignored, .cancelled, .cancelNoop, .ack: return
        case .failed(let error, _): throw error
        case .peerError(let code, _):
            throw P2Error(rawValue: code) ?? .notAuthorized
        case .complete(let type, let id, _, let body):
            guard type == .handshake, id == 2, let noise = handshake else { throw P2Error.sequence }
            _ = try noise.readMessage(body)
            enqueue(frames: try P2Codec.encode(type: .handshake, messageId: 3, body: try noise.writeMessage(), maxAttValueBytes: maxAtt))
            let keys = try noise.split()
            handshake = nil
            pendingWatchKey = keys.remoteStatic
            let secure = P2SecureSession(keys: keys, maxAttValueBytes: maxAtt)
            session = secure
            if let pinned = identity.pinnedWatch {
                guard pinned == keys.remoteStatic else { throw P2Error.identityChanged }
                sayHello()
            } else {
                state = .awaitingConfirmation(code: keys.securityCode())
                note("esperando confirmar el código")
                arm("Se agotó el tiempo para confirmar el código.", seconds: 120)
            }
        }
    }

    /// The user compared the six-digit code with the watch's screen. The watch must confirm FIRST.
    func confirmCode(match: Bool) {
        guard case .awaitingConfirmation = state else { return }
        guard match else { close(reason: "Registro cancelado: el código no coincidía.", newState: .failed("Registro cancelado: el código no coincidía.")); return }
        sayHello()
    }

    private func sayHello() {
        state = .handshaking
        let id = takeRequestId()
        responders[id] = { [weak self] env in
            guard let self else { return }
            if env.error != nil {
                self.fail("El reloj no aceptó el registro todavía. Pulsa «Coincide» en el reloj primero y vuelve a intentarlo.")
            } else if let sessionId = env.payload?["sessionId"]?.stringValue, sessionId == self.session?.sessionId {
                self.becomeReady(sessionId)
            } else { self.fail("La identidad de la sesión no coincide.") }
        }
        send(P2Messages.helloRequest(id))
        arm("El reloj no confirmó el registro.", seconds: 20)
    }

    private func becomeReady(_ sessionId: String) {
        timeout?.cancel()
        if let key = pendingWatchKey {
            if identity.pinnedWatch != key {
                identity.pinWatch(key); defaults.removeObject(forKey: Self.cursorKey)   // new watch: its cursors restart
            }
            isPaired = identity.pinnedWatch != nil
        }
        state = .ready(sessionId: sessionId)
        note("canal listo")
        startHeartbeat()
        syncHealth()
    }

    private func receiveSecure(_ session: P2SecureSession, _ bytes: [UInt8], now: Int64) throws {
        switch try session.receive(bytes, atMs: now) {
        case .none: break
        case .peerAck: break
        case .peerError(let code, _):
            if case .awaitingConfirmation = state { break }
            note("el reloj informó \((P2Error(rawValue: code) ?? .notAuthorized).name)")
            if let error = P2Error(rawValue: code), error.closesSession { throw error }
        case .message(let id, let json):
            enqueue(session.ackFrame(for: id))                                 // message-level ACK for every valid DATA
            let env = try P2Messages.envelope(json)
            if env.kind == "res" {
                if let client = routeSync.removeValue(forKey: env.requestId) {
                    pushSync(client.handle(response: json), client)
                } else { responders.removeValue(forKey: env.requestId)?(env) }
            } else { handleRequest(env) }
        }
    }

    // MARK: Requests

    private func takeRequestId() -> Int64 { defer { nextRequestId += 1 }; return nextRequestId }

    private func send(_ json: P2JSON) {
        guard let session else { return }
        do {
            let frames = try session.send(json).frames
            if frames.count > 1 { note("aviso: mensaje en \(frames.count) frames; el reloj exige su misma capacidad") }
            enqueue(frames: frames)
        } catch { fail(errorText(error)) }
    }

    private func handleRequest(_ env: P2Messages.Envelope) {
        switch env.capability {
        case P2Messages.findPhone:
            note("orden «buscar iPhone» recibida")
            // The alarm may start while the app is in the background: keep the process alive long enough to
            // activate audio (the `audio` background mode then keeps it running while it rings).
            var task = UIBackgroundTaskIdentifier.invalid
            task = UIApplication.shared.beginBackgroundTask(withName: "find-iphone") { UIApplication.shared.endBackgroundTask(task) }
            FindPhoneAlarm.shared.start(reason: .watchRequest)
            let ringing = FindPhoneAlarm.shared.isRinging
            note(ringing ? "alarma iniciada" : "alarma NO iniciada: \(FindPhoneAlarm.shared.message)")
            // Honest result: "started" only if it is really ringing; otherwise a visible error to the watch.
            send(ringing ? P2Messages.response(env.requestId, env.capability, [("status", .string("started"))])
                         : P2Messages.errorResponse(env.requestId, env.capability, .busy))
            if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
        default:
            send(P2Messages.errorResponse(env.requestId, env.capability, .unknownCapability))
        }
    }

    /// iPhone → watch. Result is "solicitado": the watch answers `requested`, never "sonó".
    func requestFindWatch() {
        guard isReady else { lastFindWatch = "El canal seguro no está listo."; return }
        let id = takeRequestId()
        lastFindWatch = "Solicitado al reloj…"
        responders[id] = { [weak self] env in
            self?.lastFindWatch = env.error == nil ? "El reloj aceptó la orden (solicitado; la app no puede confirmar que sonó)." : "El reloj rechazó la orden (\(env.error ?? "")))."
        }
        send(P2Messages.request(id, P2Messages.findWatch))
    }

    // MARK: Health

    func syncHealth() {
        guard isReady, let health else { return }
        guard health.receiveEnabled else { healthStatus = "Recepción desactivada"; return }
        let client = HealthSyncClient(
            loadCursor: { [defaults] in Int64(defaults.integer(forKey: Self.cursorKey)) },
            saveCursor: { [defaults] in defaults.set(Int($0), forKey: Self.cursorKey) },
            ingest: { [weak health] samples in health?.ingest(samples) ?? false })
        syncClient = client
        pushSync(client.start(), client)
    }

    private func pushSync(_ messages: [P2JSON], _ client: HealthSyncClient) {
        for message in messages {
            guard let env = try? P2Messages.envelope(message) else { continue }
            routeSync[env.requestId] = client
            send(message)
        }
        healthStatus = describe(client.status)
    }
    private var routeSync: [Int64: HealthSyncClient] = [:]
    private func describe(_ status: HealthSyncClient.Status) -> String {
        switch status {
        case .idle: "Sin sincronizar"
        case .requesting: "Pidiendo datos al reloj…"
        case .saving: "Guardando…"
        case .upToDate: "Al día" + (syncClient.map { $0.totalSaved > 0 ? " · \($0.totalSaved) muestras" : "" } ?? "")
        case .stalled(let reason): "Detenida: \(reason)"
        }
    }

    // MARK: Writing

    private func enqueue(_ frame: [UInt8]) { writeQueue.append(frame); pump() }
    private func enqueue(frames: [[UInt8]]) { writeQueue += frames; pump() }

    private func pump() {
        guard !writing, !writeQueue.isEmpty, let peripheral, let requests else { return }
        writing = true
        peripheral.writeValue(Data(writeQueue.removeFirst()), for: requests, type: .withResponse)
    }

    // MARK: Failure and cleanup

    private func arm(_ message: String, seconds: Int) {
        timeout?.cancel()
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(seconds)) } catch { return }
            self?.fail(message)
        }
    }

    private func startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(60)) } catch { return }
                self?.syncHealth()
            }
        }
    }

    private func fail(_ message: String) { close(reason: "fallo: \(message)", newState: .failed(message)) }

    private func close(reason: String?, newState: State) {
        timeout?.cancel(); heartbeat?.cancel()
        responders.removeAll(); routeSync.removeAll(); syncClient = nil
        resetSession()
        if let reason { note(reason) }
        state = newState
    }

    private func resetSession() {
        session?.reset(); session = nil; handshake = nil; pendingWatchKey = nil
        writeQueue.removeAll(); writing = false
        handshakeInbox = P2Reassembler()
    }

    private func errorText(_ error: Error) -> String { (error as? P2Error)?.userFacingSummary ?? "Error del canal seguro." }
    private func note(_ text: String) {
        let line = "\(Date().formatted(.iso8601.time(includingFractionalSeconds: false))) \(text)"
        diagnostics = Array((diagnostics + [line]).suffix(60))
        persistDiagnostics()
    }

    /// Short, secret-free trace kept on the device so a failed attempt can be inspected afterwards.
    private func persistDiagnostics() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("WristBridge")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let text = (["state=\(diagnosticSummary)"] + diagnostics).joined(separator: "\n")
        try? text.write(to: dir.appendingPathComponent("p2-diagnostics.txt"), atomically: true, encoding: .utf8)
    }

    var diagnosticSummary: String {
        let stateText: String
        switch state {
        case .off: stateText = "off"
        case .waitingForLink: stateText = "waiting"
        case .discovering, .readingCapabilities, .enablingNotifications, .handshaking: stateText = "connecting"
        case .awaitingConfirmation: stateText = "awaiting-code"
        case .ready: stateText = "ready"
        case .failed: stateText = "failed"
        }
        return "p2=\(stateText) paired=\(isPaired) att=\(maxAtt)"
    }
}
