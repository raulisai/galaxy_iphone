import SwiftUI
import WristBridgeProtocol

// Screens for the watch features that depend on the shared secure channel.
// They are complete, usable interfaces for everything that can honestly run on
// the iPhone today (drafts, local status, guidance). Anything that would need
// to write to or read from the watch stays visibly locked: no simulated data.

enum WatchFeature: String, CaseIterable, Identifiable {
    case faces, health, find, music
    var id: String { rawValue }
    var title: String {
        switch self {
        case .faces: "Esferas"
        case .health: "Salud"
        case .find: "Buscar reloj"
        case .music: "Música"
        }
    }
    var symbol: String {
        switch self {
        case .faces: "watchface.applewatch.case"
        case .health: "heart.fill"
        case .find: "location.magnifyingglass"
        case .music: "music.note"
        }
    }
    var color: Color {
        switch self {
        case .faces: .indigo
        case .health: .pink
        case .find: .teal
        case .music: .red
        }
    }
    var summary: String {
        switch self {
        case .faces: "Elige tu favorita"
        case .health: "Tus datos, bajo tu control"
        case .find: "Última conexión y ayuda"
        case .music: "Controles desde el reloj"
        }
    }
    @ViewBuilder func screen(model: BridgeModel) -> some View {
        switch self {
        case .faces: WatchFacesScreen(model: model)
        case .health: HealthScreen()
        case .find: FindWatchScreen(model: model)
        case .music: MusicScreen(model: model)
        }
    }
}

// MARK: - Shared pieces

private struct FeatureHero: View {
    let feature: WatchFeature
    let subtitle: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle().fill(feature.color.opacity(0.25)).frame(width: 130, height: 130).blur(radius: 24)
                Image(systemName: feature.symbol)
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundStyle(.white)
                    .symbolEffect(.pulse, options: .repeating, isActive: !reduceMotion)
                    .frame(width: 96, height: 96)
                    .glassEffect(.regular.tint(feature.color.opacity(0.55)), in: .circle)
            }
            .scaleEffect(shown ? 1 : 0.7).opacity(shown ? 1 : 0)
            Text(feature.title).font(.largeTitle.weight(.bold))
            Text(subtitle).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
        .onAppear { withAnimation(.spring(response: 0.7, dampingFraction: 0.65)) { shown = true } }
    }
}

/// What a locked capability is waiting for. Mirrors AGENTS.md / SEED.md gates.
private struct GateCard: View {
    struct Item: Identifiable { let id = UUID(); let text: String }
    let items: [Item]
    var body: some View {
        Card(title: "Para activarlo falta") {
            ForEach(items) { item in
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "hourglass").foregroundStyle(.orange).frame(width: 22)
                    Text(item.text).font(.subheadline)
                }
            }
            Text("No se simulan datos mientras tanto.").font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct LockedAction: View {
    let title: String
    let symbol: String
    var body: some View {
        Label(title, systemImage: symbol)
            .font(.headline)
            .frame(maxWidth: .infinity).padding(.vertical, 14)
            .foregroundStyle(.secondary)
            .glassEffect(.regular, in: .capsule)
            .overlay(alignment: .trailing) {
                Image(systemName: "lock.fill").font(.footnote).foregroundStyle(.tertiary).padding(.trailing, 20)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(title), bloqueado hasta que exista el canal seguro")
    }
}

private struct FeatureScroll<Content: View>: View {
    let feature: WatchFeature
    let subtitle: String
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                FeatureHero(feature: feature, subtitle: subtitle)
                content
            }
            .padding(.horizontal).padding(.bottom, 32)
        }
        .scrollIndicators(.hidden)
        .background(Color(.systemGroupedBackground))
        .navigationTitle(feature.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Esferas

enum FaceStyle: String, CaseIterable, Identifiable {
    case classic, sport, minimal, neon
    var id: String { rawValue }
    var title: String {
        switch self {
        case .classic: "Clásica"
        case .sport: "Deportiva"
        case .minimal: "Mínima"
        case .neon: "Neón"
        }
    }
    var accent: Color {
        switch self {
        case .classic: .white
        case .sport: .orange
        case .minimal: Color(white: 0.85)
        case .neon: Color(red: 0.2, green: 1, blue: 0.8)
        }
    }
}

private struct FacePreview: View {
    let style: FaceStyle
    var size: CGFloat = 150

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let c = Calendar.current.dateComponents([.hour, .minute, .second], from: context.date)
            let s = Double(c.second ?? 0), m = Double(c.minute ?? 0) + s / 60
            let h = Double((c.hour ?? 0) % 12) + m / 60
            ZStack {
                Circle().fill(.black)
                Circle().strokeBorder(LinearGradient(colors: [Color(white: 0.9), Color(white: 0.35)],
                                                     startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 4)
                if style != .minimal {
                    ForEach(0..<12, id: \.self) { i in
                        Capsule().fill(style.accent.opacity(i % 3 == 0 ? 0.95 : 0.4))
                            .frame(width: i % 3 == 0 ? 3 : 1.5, height: size * (i % 3 == 0 ? 0.075 : 0.05))
                            .offset(y: -size * 0.4).rotationEffect(.degrees(Double(i) * 30))
                    }
                }
                if style == .sport {
                    Circle().trim(from: 0, to: m / 60).stroke(style.accent, style: StrokeStyle(lineWidth: size * 0.04, lineCap: .round))
                        .rotationEffect(.degrees(-90)).padding(size * 0.16)
                }
                if style == .neon {
                    Circle().stroke(style.accent.opacity(0.9), lineWidth: 2).padding(size * 0.12)
                        .shadow(color: style.accent, radius: 6)
                }
                hand(length: 0.24, width: size * 0.035, angle: h * 30, color: .white)
                hand(length: 0.34, width: size * 0.025, angle: m * 6, color: .white)
                if style != .minimal { hand(length: 0.38, width: 1.5, angle: s * 6, color: style == .classic ? .red : style.accent) }
                Circle().fill(style.accent).frame(width: size * 0.06)
            }
            .frame(width: size, height: size)
        }
    }

    private func hand(length: Double, width: CGFloat, angle: Double, color: Color) -> some View {
        Capsule().fill(color).frame(width: width, height: size * length)
            .offset(y: -size * length / 2).rotationEffect(.degrees(angle))
    }
}

struct WatchFacesScreen: View {
    @ObservedObject var model: BridgeModel
    @AppStorage("WristBridge.faceDraft") private var draft = FaceStyle.classic.rawValue
    @State private var selection = FaceStyle.classic

    var body: some View {
        FeatureScroll(feature: .faces, subtitle: "Prepara la esfera que quieres llevar. Se guarda como borrador en este iPhone.") {
            TabView(selection: $selection) {
                ForEach(FaceStyle.allCases) { style in
                    VStack(spacing: 12) {
                        FacePreview(style: style, size: 170)
                        Text(style.title).font(.headline)
                    }
                    .tag(style)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .frame(height: 270)
            .onChange(of: selection) { _, new in draft = new.rawValue }
            .onAppear { selection = FaceStyle(rawValue: draft) ?? .classic }
            .sensoryFeedback(.selection, trigger: selection)

            Card(title: "Borrador local") {
                Label("\(selection.title) elegida como favorita", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green).contentTransition(.interpolate)
                    .animation(Motion.snappy, value: selection)
                Text("Es solo una preferencia guardada en el iPhone; el reloj no la ha recibido.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            LockedAction(title: "Enviar al reloj", symbol: "paperplane.fill")
            GateCard(items: [.init(text: "Canal de configuración seguro acordado con el equipo del reloj"),
                             .init(text: "Formato de mensaje y confirmación de recepción en el contrato compartido")])
        }
    }
}

// MARK: - Salud

struct HealthScreen: View {
    private struct Metric: Identifiable { let id: String; let symbol: String; let color: Color }
    private let metrics = [
        Metric(id: "Pasos", symbol: "figure.walk", color: .green),
        Metric(id: "Frecuencia cardiaca", symbol: "heart.fill", color: .red),
        Metric(id: "Sueño", symbol: "bed.double.fill", color: .indigo),
        Metric(id: "Oxígeno en sangre", symbol: "lungs.fill", color: .cyan),
        Metric(id: "Energía", symbol: "flame.fill", color: .orange),
        Metric(id: "Entrenamientos", symbol: "figure.run", color: .mint)
    ]

    var body: some View {
        FeatureScroll(feature: .health, subtitle: "Tus datos de salud solo se moverán cuando tú lo autorices.") {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                ForEach(Array(metrics.enumerated()), id: \.element.id) { index, metric in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Image(systemName: metric.symbol).font(.title3).foregroundStyle(metric.color.opacity(0.5))
                            Spacer()
                            Image(systemName: "lock.fill").font(.caption).foregroundStyle(.tertiary)
                        }
                        Text("—").font(.system(size: 30, weight: .semibold, design: .rounded)).foregroundStyle(.tertiary)
                        Text(metric.id).font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .appear(index)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("\(metric.id): sin datos, bloqueado")
                }
            }
            Card(title: "Privacidad primero") {
                IconRow(symbol: "hand.raised.fill", color: .blue, title: "Sin acceso a Salud",
                        subtitle: "WristBridge no ha pedido permiso a Apple Salud ni a Contactos.")
                Divider()
                IconRow(symbol: "internaldrive.fill", color: .gray, title: "Nada sale del dispositivo",
                        subtitle: "Cuando exista el canal, podrás elegir qué métricas compartir y borrarlas.")
            }
            LockedAction(title: "Activar sincronización de salud", symbol: "heart.text.square.fill")
            GateCard(items: [.init(text: "Gate G2 aprobado con el equipo del reloj"),
                             .init(text: "Sesión segura y esquema de datos de salud acordados"),
                             .init(text: "Permisos de Apple Salud solicitados solo en ese momento")])
        }
    }
}

// MARK: - Buscar reloj

struct FindWatchScreen: View {
    @ObservedObject var model: BridgeModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        FeatureScroll(feature: .find, subtitle: "Lo que esta app sabe del reloj ahora mismo.") {
            ZStack {
                if model.linkTone == .working && !reduceMotion {
                    TimelineView(.animation(minimumInterval: 1 / 30)) { context in
                        let t = context.date.timeIntervalSinceReferenceDate
                        ZStack {
                            ForEach(0..<3, id: \.self) { i in
                                let phase = (t / 2.4 + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                                Circle().stroke(model.linkTone.glow.opacity(0.6 * (1 - phase)), lineWidth: 2)
                                    .scaleEffect(0.35 + phase * 0.65)
                            }
                        }
                    }
                } else {
                    ForEach(0..<3, id: \.self) { i in
                        Circle().stroke(model.linkTone.glow.opacity(0.18), lineWidth: 1.5).scaleEffect(0.4 + Double(i) * 0.3)
                    }
                }
                Image(systemName: "applewatch")
                    .font(.system(size: 40)).foregroundStyle(model.linkTone.glow)
                    .frame(width: 84, height: 84)
                    .glassEffect(.regular.tint(model.linkTone.glow.opacity(0.3)), in: .circle)
            }
            .frame(height: 230)
            .animation(Motion.smooth, value: model.linkTone)

            Card(title: "Estado") {
                IconRow(symbol: "dot.radiowaves.left.and.right", color: model.linkTone.color,
                        title: model.headline, subtitle: model.channelText)
                Divider()
                IconRow(symbol: "clock.fill", color: .teal, title: "Última conexión verificada",
                        subtitle: lastLinked)
                Text("Es la última vez que esta app comprobó su servicio. No es una ubicación.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Card(title: "Si no lo encuentras") {
                tip("Acércalo al iPhone y comprueba que Bluetooth está activo.", "dot.radiowaves.right")
                tip("Pulsa Reconectar en la pantalla Reloj; la app también reintenta sola.", "arrow.clockwise")
                tip("Comprueba en el reloj que su app WristBridge está abierta.", "applewatch")
            }
            LockedAction(title: "Hacer sonar el reloj", symbol: "bell.and.waves.left.and.right.fill")
            GateCard(items: [.init(text: "Comando «buscar» aprobado en el contrato compartido"),
                             .init(text: "Canal seguro y confirmación del reloj")])
        }
    }

    private var lastLinked: String {
        guard let date = model.lastLinkedAt else { return "Aún no se ha verificado" }
        return date.formatted(.relative(presentation: .named)).capitalizedFirst + " · " + date.formatted(date: .abbreviated, time: .shortened)
    }

    private func tip(_ text: String, _ symbol: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol).foregroundStyle(Theme.accent).frame(width: 22)
            Text(text).font(.subheadline)
        }
    }
}

private extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}

// MARK: - Música

struct MusicScreen: View {
    @ObservedObject var model: BridgeModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        FeatureScroll(feature: .music, subtitle: "El reloj controla la música del iPhone por sí mismo.") {
            TimelineView(.animation(minimumInterval: 1 / 24, paused: reduceMotion)) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                HStack(alignment: .bottom, spacing: 6) {
                    ForEach(0..<16, id: \.self) { i in
                        let level = reduceMotion ? 0.4 : (0.5 + 0.5 * sin(t * 2.2 + Double(i) * 0.7)) * 0.8 + 0.1
                        Capsule().fill(LinearGradient(colors: [.pink, .red], startPoint: .top, endPoint: .bottom))
                            .frame(width: 8, height: 12 + 56 * level)
                            .opacity(0.35)
                    }
                }
                .frame(height: 76)
                .accessibilityHidden(true)
            }
            Card(title: "Cómo funciona") {
                IconRow(symbol: "applewatch.radiowaves.left.and.right", color: .red, title: "Directo reloj ↔ iPhone",
                        subtitle: "El reloj usa el servicio de medios de Apple (AMS) cuando el iPhone lo permite. Esta app no interviene.")
                Divider()
                IconRow(symbol: "lock.shield.fill", color: .blue, title: "Sin acceso a tu música",
                        subtitle: "WristBridge no lee tu biblioteca ni lo que suena.")
            }
            Card(title: "Compruébalo") {
                step(1, "Reproduce algo en el iPhone con la pantalla bloqueada.")
                step(2, "Abre los controles de música en el reloj.")
                step(3, "Prueba pausa, siguiente y volumen.")
                Text("Anota en Notificaciones → Lo que observaste si algo falla; la app no puede verificarlo por ti.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Card(title: "Enlace actual") {
                IconRow(symbol: "antenna.radiowaves.left.and.right", color: model.linkTone.color,
                        title: model.headline, subtitle: "El control de música depende del enlace del reloj con iOS.")
            }
        }
    }

    private func step(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)").font(.footnote.weight(.bold)).foregroundStyle(.white)
                .frame(width: 22, height: 22).background(Color.red.gradient, in: Circle())
            Text(text).font(.subheadline)
        }
    }
}
