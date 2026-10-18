import SwiftUI
import WristBridgeProtocol

struct RecoveryCampaignSection: View {
    @ObservedObject var bridge: BridgeModel
    @ObservedObject var controller: RecoveryCampaignController
    private var canStart: Bool {
        !(bridge.isDemo || bridge.stopped || bridge.state.channel != .serviceFound || controller.isRunning || controller.warning != nil)
    }

    var body: some View {
        Section {
            RecoveryDial(campaign: controller.campaign, running: controller.isRunning)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
        }
        Section {
            Button(action: controller.start) {
                Label("Iniciar 20 reconexiones", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.glassProminent)
            .tint(.green)
            .disabled(!canStart)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            .accessibilityIdentifier("startRecoveryCampaign")
            if controller.isRunning {
                Button(role: .destructive) { controller.cancel() } label: {
                    Label("Detener ensayo", systemImage: "stop.fill").frame(maxWidth: .infinity).padding(.vertical, 4)
                }
                .buttonStyle(.glass)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 0, trailing: 0))
                .transition(.scale.combined(with: .opacity))
                .accessibilityIdentifier("stopRecoveryCampaign")
            }
        } footer: {
            Text("Realiza 20 desconexiones y reconexiones del canal de configuración, con un máximo de 30 segundos por intento. Mantén la app abierta y el reloj cerca; al salir de la app, el ensayo se interrumpe. Mide solo configuración: la recepción de avisos se comprueba por separado.")
                .padding(.top, 8)
        }
        .animation(Motion.snappy, value: controller.isRunning)
        Section {
            Text(controller.message).font(.callout)
                .contentTransition(.opacity)
                .animation(Motion.snappy, value: controller.message)
            if let warning = controller.warning {
                Label(warning, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
            }
            if let campaign = controller.campaign {
                LabeledContent("Intentos registrados", value: "\(campaign.trials.count)/20")
                LabeledContent("Servicio verificado en 30 s", value: "\(campaign.withinDeadlineCount)")
                if let trial = campaign.pendingTrial { Text("Comprobando intento \(trial) de 20…") }
                ForEach(campaign.trials.suffix(3).reversed()) { trial in
                    let seconds = trial.elapsedSeconds.map { String(format: "%.1f s", $0) } ?? "sin medición"
                    HStack(spacing: 10) {
                        Image(systemName: trial.outcome.symbol).foregroundStyle(trial.outcome.color)
                        Text("Intento \(trial.number): \(label(trial.outcome)) · \(seconds)").font(.caption)
                    }
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                ShareLink(item: campaign.export()) {
                    Label("Compartir ensayo de reconexión", systemImage: "square.and.arrow.up")
                }.disabled(controller.isRunning)
            }
        } header: {
            Text("Ensayo de reconexión")
        } footer: {
            Text("Conserva únicamente el último ensayo; compártelo antes de empezar otro.")
        }
        .animation(Motion.snappy, value: controller.campaign?.trials.count)
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

/// Large progress ring plus a 20-dot strip, one dot per trial.
private struct RecoveryDial: View {
    let campaign: RecoveryCampaign?
    let running: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var recorded: Int { campaign?.trials.count ?? 0 }
    private var verified: Int { campaign?.withinDeadlineCount ?? 0 }

    var body: some View {
        VStack(spacing: 22) {
            ZStack {
                Circle().stroke(Color.secondary.opacity(0.14), lineWidth: 14)
                Circle()
                    .trim(from: 0, to: Double(recorded) / Double(RecoveryCampaign.target))
                    .stroke(AngularGradient(colors: [.mint, .green, .mint], center: .center),
                            style: StrokeStyle(lineWidth: 14, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .shadow(color: .green.opacity(0.35), radius: 8)
                if running && !reduceMotion {
                    Circle().stroke(Color.green.opacity(0.25), lineWidth: 2)
                        .phaseAnimator([false, true]) { view, phase in
                            view.scaleEffect(phase ? 1.18 : 1).opacity(phase ? 0 : 1)
                        } animation: { _ in .easeOut(duration: 1.4) }
                }
                VStack(spacing: 2) {
                    Text("\(recorded)")
                        .font(.system(size: 52, weight: .semibold, design: .rounded))
                        .contentTransition(.numericText(value: Double(recorded)))
                    Text("de \(RecoveryCampaign.target) intentos").font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(width: 168, height: 168)
            .animation(Motion.smooth, value: recorded)

            HStack(spacing: 6) {
                Image(systemName: "checkmark.seal.fill").foregroundStyle(.green)
                Text("\(verified) verificados en 30 s")
                    .contentTransition(.numericText(value: Double(verified)))
            }
            .font(.subheadline.weight(.semibold))
            .animation(Motion.snappy, value: verified)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 10), spacing: 8) {
                ForEach(1...RecoveryCampaign.target, id: \.self) { number in
                    TrialDot(state: state(of: number))
                }
            }
            .padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(recorded) de \(RecoveryCampaign.target) intentos, \(verified) verificados en 30 segundos")
    }

    private func state(of number: Int) -> TrialDot.State {
        if let trial = campaign?.trials.first(where: { $0.number == number }) { return .done(trial.outcome) }
        if campaign?.pendingTrial == number { return .pending }
        return .empty
    }
}

private struct TrialDot: View {
    enum State: Equatable { case empty, pending, done(RecoveryTrialOutcome) }
    let state: State
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle().fill(Color.secondary.opacity(0.15))
            switch state {
            case .empty: EmptyView()
            case .pending:
                Circle().fill(Theme.accent)
                    .phaseAnimator([false, true]) { view, phase in
                        view.scaleEffect(phase && !reduceMotion ? 0.55 : 0.9)
                    } animation: { _ in .easeInOut(duration: 0.7) }
            case .done(let outcome):
                Circle().fill(outcome.color.gradient)
                    .transition(.scale(scale: 0.2).combined(with: .opacity))
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .animation(.spring(response: 0.4, dampingFraction: 0.6), value: state)
    }
}

extension RecoveryTrialOutcome {
    var color: Color {
        switch self {
        case .success: .green
        case .timedOut: .orange
        case .cancelled: .gray
        case .interrupted: .red
        }
    }
    var symbol: String {
        switch self {
        case .success: "checkmark.circle.fill"
        case .timedOut: "clock.badge.exclamationmark.fill"
        case .cancelled: "minus.circle.fill"
        case .interrupted: "xmark.circle.fill"
        }
    }
}
