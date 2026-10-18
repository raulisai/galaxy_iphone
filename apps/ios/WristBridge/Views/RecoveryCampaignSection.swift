import SwiftUI
import WristBridgeProtocol

struct RecoveryCampaignSection: View {
    @ObservedObject var bridge: BridgeModel
    @ObservedObject var controller: RecoveryCampaignController
    var body: some View {
        Section("Ensayo de reconexión") {
            Text("Realiza 20 desconexiones y reconexiones del canal de configuración, con un máximo de 30 segundos por intento. Mantén la app abierta y el reloj cerca. Al salir de la app, el ensayo se interrumpe.")
                .font(.footnote).foregroundStyle(.secondary)
            Text("Mide solo configuración. La recepción de avisos y su recuperación se comprueban por separado. Conserva únicamente el último ensayo; compártelo antes de empezar otro.")
                .font(.footnote).foregroundStyle(.secondary)
            Button("Iniciar 20 reconexiones", action: controller.start)
                .disabled(bridge.isDemo || bridge.stopped || bridge.state.channel != .serviceFound || controller.isRunning || controller.warning != nil)
                .accessibilityIdentifier("startRecoveryCampaign")
            if controller.isRunning {
                Button("Detener ensayo", role: .destructive) { controller.cancel() }
                    .accessibilityIdentifier("stopRecoveryCampaign")
            }
            if let campaign = controller.campaign {
                HStack(spacing: 16) {
                    ZStack {
                        Circle().stroke(Color.secondary.opacity(0.2), lineWidth: 8)
                        Circle().trim(from: 0, to: Double(campaign.trials.count) / Double(RecoveryCampaign.target))
                            .stroke(Color.green, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                            .animation(.smooth, value: campaign.trials.count)
                        Text("\(campaign.trials.count)").font(.title3.bold().monospacedDigit())
                    }
                    .frame(width: 64, height: 64)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(campaign.withinDeadlineCount) verificados en 30 s").font(.headline)
                        Text("de \(RecoveryCampaign.target) intentos · solo configuración").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
                .accessibilityHidden(true)
                LabeledContent("Intentos registrados", value: "\(campaign.trials.count)/20")
                LabeledContent("Servicio verificado en 30 s", value: "\(campaign.withinDeadlineCount)")
                if let trial = campaign.pendingTrial { Text("Comprobando intento \(trial) de 20…") }
                ForEach(campaign.trials.suffix(3)) { trial in
                    let seconds = trial.elapsedSeconds.map { String(format: "%.1f s", $0) } ?? "sin medición"
                    Text("Intento \(trial.number): \(label(trial.outcome)) · \(seconds)")
                        .font(.caption)
                }
                ShareLink(item: campaign.export()) {
                    Label("Compartir ensayo de reconexión", systemImage: "square.and.arrow.up")
                }.disabled(controller.isRunning)
            }
            Text(controller.message).font(.callout)
            if let warning = controller.warning {
                Label(warning, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
            }
        }
    }
    private func label(_ outcome: RecoveryTrialOutcome) -> String {
        switch outcome {
        case .success: "servicio verificado"
        case .timedOut: "no recuperó antes del límite"
        case .cancelled: "cancelado"
        case .interrupted: "interrumpido"
        }
    }
}
