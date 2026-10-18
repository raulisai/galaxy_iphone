import Combine
import Foundation
import WristBridgeProtocol

@MainActor
final class RecoveryCampaignController: ObservableObject {
    @Published private(set) var campaign: RecoveryCampaign?
    @Published private(set) var isRunning = false
    @Published private(set) var warning: String?
    @Published private(set) var message = "Ensayo del canal de configuración; los avisos del reloj se prueban por separado."
    private weak var bridge: BridgeModel?
    private let repository: RecoveryCampaignRepository?
    private var task: Task<Void, Never>?
    private var timeout: Task<Void, Never>?
    private var completion: CheckedContinuation<RecoveryTrialOutcome, Never>?
    private let clock = ContinuousClock()
    private var trialStarted: ContinuousClock.Instant?
    private var writable = true

    init(bridge: BridgeModel) {
        self.bridge = bridge
        if bridge.isDemo { repository = nil }
        else {
            let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("WristBridge/recovery-campaign.json")
            repository = RecoveryCampaignRepository(file: file)
        }
        do {
            campaign = try repository?.load()
            if campaign?.status == .running {
                campaign?.interrupt(cancelled: false)
                try persist()
                message = "El ensayo anterior se interrumpió al cerrar el proceso. No se reinicia automáticamente."
            }
        } catch {
            writable = false
            warning = "No se pudo leer o actualizar el ensayo anterior. Se conserva el archivo; bórralo desde Privacidad para empezar de nuevo."
        }
    }

    func start() {
        guard writable, !isRunning, let bridge, !bridge.isDemo, !bridge.stopped,
              bridge.state.authorization == .authorized, bridge.state.channel == .serviceFound,
              let accessory = bridge.authorizedID else {
            message = "Primero conecta el reloj y comprueba el servicio. El ensayo requiere la app abierta y un registro local válido."
            return
        }
        let previous = campaign
        campaign = RecoveryCampaign()
        do { try persist() } catch { campaign = previous; failStorage(); return }
        isRunning = true
        message = "Ensayo en curso. Mantén la app abierta y el reloj cerca."
        task = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled, self.campaign?.status == .running,
                  let trial = self.campaign?.beginTrial() {
                do { try self.persist() } catch { self.failStorage(); break }
                self.trialStarted = self.clock.now
                let result = await withCheckedContinuation { continuation in
                    self.completion = continuation
                    let campaignID = self.campaign?.id
                    self.timeout = Task { [weak self] in
                        do { try await Task.sleep(for: .seconds(RecoveryCampaign.deadlineSeconds)) } catch { return }
                        guard let self, self.campaign?.id == campaignID, self.campaign?.pendingTrial == trial else { return }
                        self.finishTrial(.timedOut)
                    }
                    if !bridge.beginRecoveryProbe(accessory: accessory) { self.finishTrial(.interrupted) }
                }
                if Task.isCancelled || result == .cancelled || result == .interrupted { break }
                do { try await Task.sleep(for: .seconds(1)) } catch { break }
            }
            self.isRunning = false
            self.task = nil
            if self.campaign?.status == .running { self.campaign?.interrupt(cancelled: false) }
            do { try self.persist() } catch { self.failStorage() }
            if self.warning == nil {
                self.message = self.campaign?.status == .completed
                    ? "Ensayo terminado. El informe mide solo configuración; no aprueba la entrega de avisos ni un gate conjunto."
                    : "Ensayo interrumpido. Conserva los resultados parciales y reconecta desde Conexión si hace falta."
            }
        }
    }

    func handle(_ event: BridgeEvent) {
        guard isRunning else { return }
        switch event {
        case .serviceFound: finishTrial(.success)
        case .authorizationRemoved, .authorizationDenied, .radioUnavailable: cancel(interrupted: true)
        default: break
        }
    }

    func cancel(interrupted: Bool = false) {
        guard isRunning else { return }
        task?.cancel()
        if completion != nil { finishTrial(interrupted ? .interrupted : .cancelled) }
        campaign?.interrupt(cancelled: !interrupted)
        if bridge?.state.channel != .serviceFound { bridge?.stopRecoveryProbe() }
        do { try persist() } catch { failStorage() }
    }

    func applyRetention(days: Int) {
        guard !isRunning, let campaign else { return }
        let bounded = [7, 30, 90].contains(days) ? days : 30
        if campaign.startedAt < Date().addingTimeInterval(-Double(bounded) * 86400) { delete() }
    }

    func delete() {
        cancel()
        do {
            try repository?.delete()
            campaign = nil; writable = true; warning = nil
            message = "Se borró el último ensayo local."
        } catch { warning = "No se pudo borrar el ensayo local. Vuelve a intentarlo." }
    }

    private func finishTrial(_ outcome: RecoveryTrialOutcome) {
        guard let completion, let trial = campaign?.pendingTrial, let started = trialStarted else { return }
        self.completion = nil
        timeout?.cancel(); timeout = nil; trialStarted = nil
        let duration = started.duration(to: clock.now).components
        let seconds = Double(duration.seconds) + Double(duration.attoseconds) / 1e18
        _ = campaign?.record(trial: trial, outcome: outcome, elapsedSeconds: seconds)
        do { try persist() } catch { failStorage() }
        completion.resume(returning: outcome)
    }

    private func persist() throws {
        if let campaign { try repository?.save(campaign) }
    }
    private func failStorage() {
        writable = false
        warning = "No se pudo guardar el ensayo. Se detiene y no debe usarse para aprobar una campaña."
        campaign?.interrupt(cancelled: false)
        task?.cancel()
    }
}
