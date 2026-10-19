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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var textSize
    @Namespace private var zoom
    @Namespace private var glass
    @State private var taps: [String: Int] = [:]

    private var authorized: Bool { model.state.authorization == .authorized }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                hero.padding(.top, -8)
                VStack(spacing: 16) {
                    if model.isDemo {
                        Label("Demostración · sin conexión real", systemImage: "testtube.2")
                            .font(.subheadline.weight(.semibold)).foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .accessibilityIdentifier("demoBanner")
                            .appear(0)
                    }
                    statusCard.appear(1).scrollDepth()
                    featureGrid.appear(2)
                    upcoming.appear(3)
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 32)
        }
        .scrollIndicators(.hidden)
        .background(Color(.systemGroupedBackground))
        .navigationTitle("WristBridge")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.heroTop, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    // MARK: Hero

    private var hero: some View {
        VStack(spacing: 16) {
            Text(authorized ? "Galaxy Watch" : "Añade tu reloj")
                .font(.title2.weight(.bold)).foregroundStyle(.white)
                .contentTransition(.interpolate)
                .animation(Motion.smooth, value: authorized)
            WatchHeroView(tone: model.linkTone, status: model.headline, size: 164)
            StatusPill(text: model.headline, tone: model.linkTone)
            quickActions.padding(.top, 2)
        }
        .padding(.top, 10).padding(.bottom, 24)
        .frame(maxWidth: .infinity)
        .background { LivingBackground(tone: model.linkTone) }
        .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 36, bottomTrailingRadius: 36, style: .continuous))
        // Fill the pull-down bounce area with the hero color.
        .background(alignment: .top) { Theme.heroTop.frame(height: 600).offset(y: -600) }
    }

    private var quickActions: some View {
        GlassEffectContainer(spacing: 22) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: textSize.isAccessibilitySize ? 1 : (authorized ? 4 : 2)), spacing: 16) {
                if !authorized {
                    quickAction("Seleccionar reloj", id: "select", symbol: "plus", action: model.selectWatch)
                        .disabled(!model.pickerAvailable)
                        .accessibilityIdentifier("selectWatch")
                } else {
                    quickAction("Reconectar", id: "reconnect", symbol: "arrow.clockwise",
                                spinning: model.state.channel == .connecting, action: model.reconnect)
                        .disabled(model.state.channel == .connecting)
                    quickAction("Detener configuración", id: "stop", symbol: model.stopped ? "pause.circle.fill" : "pause.fill",
                                action: model.stop)
                    quickAction("Quitar accesorio", id: "forget", symbol: "trash") { showForgetConfirmation = true }
                }
                quickAction("Bluetooth", id: "settings", symbol: "gearshape") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
        }
        .animation(Motion.smooth, value: authorized)
    }

    private func quickAction(_ title: String, id: String, symbol: String, spinning: Bool = false,
                             action: @escaping () -> Void) -> some View {
        Button {
            taps[id, default: 0] += 1
            action()
        } label: {
            VStack(spacing: 7) {
                Image(systemName: symbol)
                    .font(.system(size: 19, weight: .semibold))
                    .symbolEffect(.bounce, value: reduceMotion ? 0 : taps[id, default: 0])
                    .symbolEffect(.rotate, options: .repeating, isActive: spinning && !reduceMotion)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 54, height: 54)
                    .glassEffect(.regular, in: .circle)
                    .glassEffectID(id, in: glass)
                Text(title).font(.caption2.weight(.medium)).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity)
            }
            .foregroundStyle(.white)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle(scale: 0.9))
        .sensoryFeedback(.impact(weight: .light), trigger: taps[id, default: 0])
        .transition(.scale(scale: 0.6).combined(with: .opacity))
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
            Text(model.message).font(.footnote).foregroundStyle(.secondary)
                .contentTransition(.opacity)
                .animation(Motion.snappy, value: model.message)
            if model.authorizedChoices.count > 1 {
                ForEach(Array(model.authorizedChoices.enumerated()), id: \.element) { index, identifier in
                    Button("Usar reloj autorizado \(index + 1)") { model.selectAuthorized(identifier) }
                        .buttonStyle(.bordered)
                }
            }
        }
        .animation(Motion.smooth, value: model.linkTone)
    }

    private var featureGrid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: textSize.isAccessibilitySize ? 1 : 2), spacing: 12) {
            Button(action: openNotifications) {
                featureTile("Notificaciones", detail: "Avisos de prueba y resultados", symbol: "bell.fill", color: .orange)
            }
            NavigationLink {
                RecoveryCampaignScreen(model: model, controller: campaign)
                    .navigationTransition(.zoom(sourceID: "recovery", in: zoom))
            } label: {
                featureTile("Reconexión", detail: campaign.campaign.map { "Último ensayo \($0.trials.count)/20" }
                    ?? "Ensayo de 20 intentos", symbol: "arrow.triangle.2.circlepath", color: .green)
                    .matchedTransitionSource(id: "recovery", in: zoom)
            }
            NavigationLink {
                DiagnosticsScreen(model: model)
                    .navigationTransition(.zoom(sourceID: "diagnostics", in: zoom))
            } label: {
                featureTile("Diagnóstico", detail: "Traza Bluetooth sin mensajes", symbol: "waveform.path.ecg", color: .purple)
                    .matchedTransitionSource(id: "diagnostics", in: zoom)
            }
            NavigationLink {
                SecureChannelScreen(model: model)
                    .navigationTransition(.zoom(sourceID: "p2", in: zoom))
            } label: {
                featureTile("Canal seguro", detail: P2Link.shared.isReady ? "Listo · salud y buscar" : "Vincular con el reloj",
                            symbol: "lock.shield.fill", color: .teal)
                    .matchedTransitionSource(id: "p2", in: zoom)
            }
            .accessibilityIdentifier("feature-p2")
            NavigationLink {
                SetupGuideScreen()
                    .navigationTransition(.zoom(sourceID: "guide", in: zoom))
            } label: {
                featureTile("Guía", detail: "Compartir avisos del sistema", symbol: "book.fill", color: .blue)
                    .matchedTransitionSource(id: "guide", in: zoom)
            }
        }
        .buttonStyle(PressableStyle())
    }

    private func featureTile(_ title: String, detail: String, symbol: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                IconTile(symbol: symbol, color: color, size: 38)
                Spacer()
                Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(.tertiary)
            }
            Text(title).font(.headline).foregroundStyle(.primary)
            Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(textSize.isAccessibilitySize ? nil : 2)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.numericText())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    /// Galaxy Wearable features that depend on the watch's secure channel. Each opens
    /// a full screen with what works locally; the rest stays locked, never simulated.
    private var upcoming: some View {
        let still = reduceMotion
        return VStack(alignment: .leading, spacing: 10) {
            Text("Funciones del reloj").font(.headline).padding(.horizontal, 4)
            ScrollView(.horizontal) {
                HStack(spacing: 12) {
                    ForEach(WatchFeature.allCases) { feature in
                        NavigationLink {
                            feature.screen(model: model)
                                .navigationTransition(.zoom(sourceID: feature.id, in: zoom))
                        } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    IconTile(symbol: feature.symbol, color: feature.color, size: 34)
                                    Spacer()
                                    Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(.tertiary)
                                }
                                Text(feature.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                Text(feature.summary).font(.caption2).foregroundStyle(.secondary)
                                    .lineLimit(textSize.isAccessibilitySize ? nil : 2)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(14)
                            .frame(width: textSize.isAccessibilitySize ? 260 : 150, alignment: .leading)
                            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                            .matchedTransitionSource(id: feature.id, in: zoom)
                        }
                        .buttonStyle(PressableStyle())
                        .scrollTransition(.interactive, axis: .horizontal) { view, phase in
                            view.scaleEffect(phase.isIdentity || still ? 1 : 0.92).opacity(phase.isIdentity ? 1 : 0.6)
                        }
                        .accessibilityIdentifier("feature-\(feature.id)")
                    }
                }
                .scrollTargetLayout()
            }
            .scrollIndicators(.hidden)
            .scrollTargetBehavior(.viewAligned)
            .accessibilityIdentifier("watchFeatures")
            .scrollClipDisabled()
            Text("Lo que necesita el canal seguro con el reloj aparece bloqueado; no se simulan datos.")
                .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 4)
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
