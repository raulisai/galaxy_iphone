import SwiftUI
import WristBridgeProtocol

/// Registration and status of the own secure channel (A-P2-05). The six-digit code must match the one
/// on the watch; the watch confirms first, then this screen. Only after the watch accepts is its key pinned.
struct SecureChannelScreen: View {
    @ObservedObject var model: BridgeModel
    @ObservedObject private var link = P2Link.shared
    @State private var confirmForget = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                statusCard
                if case .awaitingConfirmation(let code) = link.state { codeCard(code) }
                actionsCard
                Card(title: "Cómo vincular") {
                    step(1, "En el reloj abre Conexión › «Vincular iPhone» (la ventana dura 120 s).")
                    step(2, "Aquí pulsa «Vincular reloj». Espera a ver un código de 6 dígitos en ambos.")
                    step(3, "Si coinciden: pulsa «Coincide» en el reloj primero y después aquí.")
                    Text("Después el iPhone y el reloj se reconocen solos; no hace falta repetirlo.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Card(title: "Seguridad") {
                    IconRow(symbol: "exclamationmark.shield.fill", color: .orange, title: "Implementación propia sin revisar",
                            subtitle: "El cifrado (Noise) está probado con vectores, pero aún no lo revisó un tercero (K3). Úsalo solo con tus propios datos.")
                    Divider()
                    IconRow(symbol: "key.fill", color: .gray, title: "Claves solo en este iPhone",
                            subtitle: "Se guardan en el llavero, sin iCloud, y nunca se muestran ni se exportan.")
                }
                if !link.diagnostics.isEmpty {
                    Card(title: "Registro (sin claves ni datos)") {
                        ForEach(Array(link.diagnostics.suffix(8).enumerated()), id: \.offset) { _, line in
                            Text(line).font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .padding(.horizontal).padding(.bottom, 32)
        }
        .scrollIndicators(.hidden)
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Canal seguro")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("¿Olvidar el reloj?", isPresented: $confirmForget, titleVisibility: .visible) {
            Button("Olvidar y borrar claves", role: .destructive) { link.forgetWatch() }
            Button("Cancelar", role: .cancel) {}
        } message: { Text("Habrá que vincular de nuevo en ambos dispositivos. Los datos de salud ya guardados no se borran.") }
    }

    private var statusCard: some View {
        Card {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(tone.opacity(0.18)).frame(width: 56, height: 56)
                    Image(systemName: symbol).font(.title2).foregroundStyle(tone)
                        .symbolEffect(.pulse, options: .repeating, isActive: working)
                        .contentTransition(.symbolEffect(.replace))
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.headline).contentTransition(.interpolate)
                    Text(detail).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine).accessibilityIdentifier("p2Status")
        }
        .animation(Motion.smooth, value: link.state)
    }

    private func codeCard(_ code: String) -> some View {
        Card(title: "Código de seguridad") {
            Text(code.prefix(3) + " " + code.suffix(3))
                .font(.system(size: 52, weight: .semibold, design: .rounded).monospacedDigit())
                .frame(maxWidth: .infinity).accessibilityIdentifier("p2Code")
            Text("Debe ser idéntico al del reloj. Si no coincide, alguien podría estar interponiéndose: pulsa «No coincide».")
                .font(.footnote).foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button { link.confirmCode(match: false) } label: { Label("No coincide", systemImage: "xmark").frame(maxWidth: .infinity) }
                    .buttonStyle(.glass).tint(.red).accessibilityIdentifier("p2CodeMismatch")
                Button { link.confirmCode(match: true) } label: { Label("Coincide", systemImage: "checkmark").frame(maxWidth: .infinity) }
                    .buttonStyle(.glassProminent).tint(.green).accessibilityIdentifier("p2CodeMatch")
            }
        }
    }

    private var actionsCard: some View {
        Card(title: "Acciones") {
            if link.isReady {
                action("Sincronizar salud ahora", "heart.text.square.fill", .pink) { link.syncHealth() }
                    .accessibilityIdentifier("p2SyncHealth")
                action("Hacer sonar el reloj", "bell.and.waves.left.and.right.fill", .teal) { link.requestFindWatch() }
                    .accessibilityIdentifier("p2FindWatch")
                if let status = link.lastFindWatch { Text(status).font(.footnote).foregroundStyle(.secondary) }
                Text("Salud: \(link.healthStatus)").font(.footnote).foregroundStyle(.secondary)
                action("Desactivar canal", "pause.circle.fill", .gray) { link.setEnabled(false) }
            } else {
                action(link.isPaired ? "Conectar al reloj vinculado" : "Vincular reloj", "link.badge.plus", Theme.accent) { link.connectNow() }
                    .disabled(model.isDemo || model.state.channel != .serviceFound)
                    .accessibilityIdentifier("p2Pair")
                if model.state.channel != .serviceFound {
                    Text("Primero conecta el reloj en la pantalla Reloj (hasta ver «Conectado»).").font(.footnote).foregroundStyle(.secondary)
                }
            }
            if link.isPaired {
                Button(role: .destructive) { confirmForget = true } label: { Label("Olvidar reloj", systemImage: "trash").frame(maxWidth: .infinity) }
                    .buttonStyle(.glass).accessibilityIdentifier("p2Forget")
            }
        }
    }

    private func action(_ title: String, _ symbol: String, _ color: Color, _ run: @escaping () -> Void) -> some View {
        Button(action: run) { Label(title, systemImage: symbol).frame(maxWidth: .infinity).padding(.vertical, 2) }
            .buttonStyle(.glass).tint(color)
    }
    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(n)").font(.footnote.weight(.bold)).foregroundStyle(.white).frame(width: 22, height: 22)
                .background(Theme.accent.gradient, in: Circle())
            Text(text).font(.subheadline)
        }
    }

    private var working: Bool {
        switch link.state { case .discovering, .readingCapabilities, .enablingNotifications, .handshaking: true; default: false }
    }
    private var tone: Color {
        switch link.state { case .ready: .green; case .failed: .red; case .off, .waitingForLink: .gray; default: .orange }
    }
    private var symbol: String {
        switch link.state { case .ready: "lock.shield.fill"; case .failed: "exclamationmark.triangle.fill"; case .off: "lock.open"; default: "lock.rotation" }
    }
    private var title: String {
        switch link.state {
        case .off: "Canal desactivado"
        case .waitingForLink: "Esperando al reloj"
        case .discovering, .readingCapabilities, .enablingNotifications: "Preparando el canal…"
        case .handshaking: "Estableciendo sesión segura…"
        case .awaitingConfirmation: "Confirma el código"
        case .ready: "Canal seguro listo"
        case .failed: "No se pudo establecer el canal"
        }
    }
    private var detail: String {
        switch link.state {
        case .off: link.isPaired ? "Reloj vinculado. Pulsa «Conectar»." : "Aún no hay un reloj vinculado."
        case .waitingForLink: "Se iniciará cuando el reloj esté conectado."
        case .awaitingConfirmation: "Compáralo con la pantalla del reloj."
        case .ready(let id): "Sesión \(id.prefix(8)) · cifrada de extremo a extremo."
        case .failed(let reason): reason
        default: "Un momento."
        }
    }
}
