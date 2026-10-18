import SwiftUI
import UIKit
import WristBridgeProtocol

/// "Reloj" tab: Galaxy Wearable-style dashboard built on iOS materials.
struct HomeView: View {
    @ObservedObject var model: BridgeModel
    @ObservedObject var observations: LabJournalStore
    @ObservedObject var campaign: RecoveryCampaignController
    @Binding var showForgetConfirmation: Bool
    let openNotifications: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                hero.padding(.top, -8)
                VStack(spacing: 16) {
                    if model.isDemo {
                        Label("Demostración · sin conexión real", systemImage: "testtube.2")
                            .font(.headline).foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .accessibilityIdentifier("demoBanner")
                    }
                    statusCard
                    featureGrid
                    upcomingCard
                    helpCard
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 24)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("WristBridge")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.heroTop, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    // MARK: Hero

    private var hero: some View {
        VStack(spacing: 14) {
            Text(model.state.authorization == .authorized ? "Galaxy Watch" : "Añade tu reloj")
                .font(.title2.bold()).foregroundStyle(.white)
            WatchHeroView(tone: model.linkTone, status: model.headline, size: 160)
            StatusPill(text: model.headline, tone: model.linkTone)
            quickActions.padding(.top, 4)
        }
        .padding(.top, 8).padding(.bottom, 22)
        .frame(maxWidth: .infinity)
        .background(LinearGradient(colors: [Theme.heroTop, Theme.heroBottom], startPoint: .top, endPoint: .bottom))
        .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 32, bottomTrailingRadius: 32, style: .continuous))
        // Fill the pull-down bounce area with the hero color.
        .background(alignment: .top) { Theme.heroTop.frame(height: 600).offset(y: -600) }
    }

    private var quickActions: some View {
        GlassEffectContainer(spacing: 18) {
            HStack(alignment: .top, spacing: 18) {
                if model.state.authorization != .authorized {
                    quickAction("Seleccionar reloj", symbol: "plus", action: model.selectWatch)
                        .disabled(!model.pickerAvailable)
                        .accessibilityIdentifier("selectWatch")
                } else {
                    quickAction("Reconectar", symbol: "arrow.clockwise", action: model.reconnect)
                        .disabled(model.state.channel == .connecting)
                    quickAction("Detener configuración", symbol: "pause.fill", action: model.stop)
                    quickAction("Quitar accesorio", symbol: "trash") { showForgetConfirmation = true }
                }
                quickAction("Bluetooth", symbol: "gearshape") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
        }
    }

    private func quickAction(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.system(size: 18, weight: .semibold))
                    .frame(width: 50, height: 50)
                    .glassEffect(.regular, in: .circle)
                Text(title).font(.caption2).multilineTextAlignment(.center).lineLimit(2)
                    .frame(width: 72)
            }
            .foregroundStyle(.white)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    // MARK: Cards

    private var statusCard: some View {
        Card(title: "Estado del enlace") {
            IconRow(symbol: "checkmark.shield.fill", color: .blue, title: "Accesorio", subtitle: model.authorizationText)
            Divider()
            IconRow(symbol: "antenna.radiowaves.left.and.right", color: model.linkTone.color,
                    title: "Configuración", subtitle: model.channelText)
            Divider()
            IconRow(symbol: "bell.badge.fill", color: .red, title: "Avisos · comprobación automática", subtitle: "Sin comprobar")
                .accessibilityIdentifier("ancsStatus")
            Divider()
            IconRow(symbol: "dot.radiowaves.right", color: .indigo, title: "Bluetooth", subtitle: model.radio)
            if let identifier = model.authorizedID, let entry = observations.journal.latest(for: identifier) {
                Divider()
                IconRow(symbol: "person.fill.checkmark", color: .teal, title: "Última observación tuya",
                        subtitle: "\(entry.condition.title): \(entry.outcome.title) · \(entry.date.formatted(date: .abbreviated, time: .omitted))")
                Text("Reporte manual; no confirma el estado actual automáticamente.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Text(model.message).font(.callout).foregroundStyle(.secondary)
            if model.authorizedChoices.count > 1 {
                ForEach(Array(model.authorizedChoices.enumerated()), id: \.element) { index, identifier in
                    Button("Usar reloj autorizado \(index + 1)") { model.selectAuthorized(identifier) }
                        .buttonStyle(.bordered)
                }
            }
        }
    }

    private var featureGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
            Button(action: openNotifications) {
                featureTile("Notificaciones", detail: "Avisos de prueba y resultados", symbol: "bell.fill", color: .orange)
            }
            NavigationLink { RecoveryCampaignScreen(model: model, controller: campaign) } label: {
                featureTile("Reconexión", detail: campaign.campaign.map { "Último ensayo \($0.trials.count)/20" }
                    ?? "Ensayo de 20 intentos", symbol: "arrow.triangle.2.circlepath", color: .green)
            }
            NavigationLink { DiagnosticsScreen(model: model) } label: {
                featureTile("Diagnóstico", detail: "Traza Bluetooth sin mensajes", symbol: "waveform.path.ecg.rectangle", color: .purple)
            }
            NavigationLink { SetupGuideScreen() } label: {
                featureTile("Guía", detail: "Compartir avisos del sistema", symbol: "book.fill", color: .blue)
            }
        }
        .buttonStyle(.plain)
    }

    private func featureTile(_ title: String, detail: String, symbol: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            IconTile(symbol: symbol, color: color, size: 38)
            Text(title).font(.headline).foregroundStyle(.primary)
            Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(2, reservesSpace: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(Rectangle())
    }

    /// Galaxy Wearable features that depend on the watch's secure channel.
    /// Shown as locked roadmap items, never simulated.
    private var upcomingCard: some View {
        Card(title: "Próximamente en el reloj") {
            ForEach(Self.upcoming, id: \.title) { item in
                IconRow(symbol: item.symbol, color: .gray, title: item.title, subtitle: item.reason) {
                    Image(systemName: "lock.fill").foregroundStyle(.tertiary)
                }
            }
            Text("Estas funciones necesitan el canal seguro acordado con el reloj. No se simulan datos mientras tanto.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private static let upcoming: [(title: String, symbol: String, reason: String)] = [
        ("Esferas", "watchface.applewatch.case", "Requiere canal de configuración seguro"),
        ("Salud", "heart.fill", "Requiere gate G2 y permisos de salud"),
        ("Buscar reloj", "location.magnifyingglass", "Requiere comando aprobado en el contrato"),
        ("Música", "music.note", "Se controla desde el reloj (AMS)")
    ]

    private var helpCard: some View {
        Card(title: "Comprobar los avisos") {
            Text("Los avisos viajan directamente del iPhone al reloj. Si iOS ofrece Compartir notificaciones del sistema en Ajustes → Bluetooth para el accesorio, actívala. Enfoque y la privacidad de previsualizaciones pueden cambiar lo que llega.")
                .font(.callout).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Secondary screens

struct RecoveryCampaignScreen: View {
    @ObservedObject var model: BridgeModel
    @ObservedObject var controller: RecoveryCampaignController
    var body: some View {
        List { RecoveryCampaignSection(bridge: model, controller: controller) }
            .navigationTitle("Reconexión")
    }
}

struct DiagnosticsScreen: View {
    @ObservedObject var model: BridgeModel
    var body: some View {
        List { DiagnosticsSection(model: model) }.navigationTitle("Diagnóstico")
    }
}

struct DiagnosticsSection: View {
    @ObservedObject var model: BridgeModel
    var body: some View {
        Section("Diagnóstico") {
            IconRow(symbol: "dot.radiowaves.right", color: .indigo, title: "Bluetooth", subtitle: model.radio)
            if let date = model.lastCheck {
                LabeledContent("Última comprobación local") { Text(date, style: .time) }
            }
            Text("La app todavía no recibe un estado automático de avisos desde el reloj. El intercambio de datos sensibles permanece desactivado.")
                .font(.footnote).foregroundStyle(.secondary)
            ShareLink(item: model.diagnosticSummary) {
                Label("Compartir diagnóstico sin mensajes", systemImage: "square.and.arrow.up")
            }
            LabeledContent("Versión", value: Bundle.main.versionLabel)
        }
    }
}

struct SetupGuideScreen: View {
    var body: some View {
        List {
            Section("Primeros pasos") {
                IconRow(symbol: "1.circle.fill", color: .blue, title: "Selecciona el reloj",
                        subtitle: "Pulsa + en Reloj y elige tu Galaxy Watch en la hoja de iOS.")
                IconRow(symbol: "2.circle.fill", color: .blue, title: "Comparte los avisos",
                        subtitle: "En Ajustes → Bluetooth → (i) del reloj, activa Compartir notificaciones del sistema si aparece.")
                IconRow(symbol: "3.circle.fill", color: .blue, title: "Prueba",
                        subtitle: "En Notificaciones programa 10 avisos, bloquea el iPhone y anota lo que recibes en el reloj.")
            }
            Section("Bueno saber") {
                Text("Bloquear el iPhone o dejar la app en segundo plano es distinto de cerrarla deslizando. La recuperación depende de iOS y se prueba por separado de los avisos.")
                Text("WristBridge no lee ni guarda el contenido de avisos de otras apps.")
            }
            Section {
                Button("Abrir Ajustes", systemImage: "gear") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
        }
        .navigationTitle("Guía")
    }
}

extension Bundle {
    var versionLabel: String {
        "\(object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—") (\(object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—"))"
    }
}
