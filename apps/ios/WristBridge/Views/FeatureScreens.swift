import Charts
import SwiftUI
import WristBridgeProtocol

// Screens for the watch features that depend on the shared secure channel.
// They are complete, usable interfaces for everything that can honestly run on
// the iPhone today (drafts, local status, guidance). Anything that would need
// to write to or read from the watch stays visibly locked: no simulated data.

enum WatchFeature: String, CaseIterable, Identifiable {
    case faces, health, find, findPhone, music
    var id: String { rawValue }
    var title: String {
        switch self {
        case .faces: "Esferas"
        case .health: "Salud"
        case .find: "Buscar reloj"
        case .findPhone: "Buscar iPhone"
        case .music: "Música"
        }
    }
    var symbol: String {
        switch self {
        case .faces: "watchface.applewatch.case"
        case .health: "heart.fill"
        case .find: "location.magnifyingglass"
        case .findPhone: "iphone.radiowaves.left.and.right"
        case .music: "music.note"
        }
    }
    var color: Color {
        switch self {
        case .faces: .indigo
        case .health: .pink
        case .find: .teal
        case .findPhone: .blue
        case .music: .red
        }
    }
    var summary: String {
        switch self {
        case .faces: "Elige tu favorita"
        case .health: "Tus datos, bajo tu control"
        case .find: "Última conexión y ayuda"
        case .findPhone: "Haz sonar tu iPhone"
        case .music: "Controles desde el reloj"
        }
    }
    @MainActor @ViewBuilder func screen(model: BridgeModel) -> some View {
        switch self {
        case .faces: WatchFacesScreen(model: model)
        case .health: HealthScreen(store: model.health, model: model)
        case .find: FindWatchScreen(model: model)
        case .findPhone: FindPhoneScreen(model: model)
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
    @ObservedObject var store: HealthStore
    @ObservedObject var model: BridgeModel

    private struct Tile: Identifiable {
        let metric: HealthMetric; let title: String; let symbol: String; let color: Color
        var id: String { metric.rawValue }
    }
    private let tiles = [
        Tile(metric: .steps, title: "Pasos", symbol: "figure.walk", color: .green),
        Tile(metric: .heartRate, title: "Frecuencia cardiaca", symbol: "heart.fill", color: .red),
        Tile(metric: .distance, title: "Distancia", symbol: "point.topleft.down.to.point.bottomright.curvepath.fill", color: .mint),
        Tile(metric: .activeEnergy, title: "Energía activa", symbol: "flame.fill", color: .orange)
    ]

    var body: some View {
        FeatureScroll(feature: .health, subtitle: store.isSample ? "Vista de demostración con datos de muestra."
                      : store.hasData
                      ? "Datos recibidos del reloj y guardados solo en este iPhone."
                      : "Tus datos de salud solo se moverán cuando tú lo autorices.") {
            if store.isSample {
                Label("Datos de muestra · demostración. No vienen de ningún reloj.", systemImage: "testtube.2")
                    .font(.footnote.weight(.semibold)).foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                    .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .accessibilityIdentifier("healthSampleBanner")
            }
            if let warning = store.warning {
                Label(warning, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange)
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                ForEach(Array(tiles.enumerated()), id: \.element.id) { index, tile in
                    tileView(tile).appear(index)
                }
            }
            if store.ledger.latest(.steps) != nil || store.ledger.latest(.stepsDaily) != nil { StepsChart(ledger: store.ledger) }
            if store.ledger.latest(.heartRate) != nil { HeartRateChart(ledger: store.ledger) }
            Card(title: "Estado de la sincronización") {
                IconRow(symbol: "antenna.radiowaves.left.and.right", color: syncColor, title: syncTitle, subtitle: syncDetail)
                if !store.ledger.gaps.isEmpty {
                    Divider()
                    IconRow(symbol: "questionmark.circle.fill", color: .gray, title: "Huecos sin datos",
                            subtitle: "\(store.ledger.gaps.reduce(0) { $0 + $1.missingIntervals }) intervalos que el reloj no pudo conservar; no se interpolan.")
                }
            }
            Card(title: "Privacidad primero") {
                IconRow(symbol: "hand.raised.fill", color: .blue, title: "Sin acceso a Salud de Apple",
                        subtitle: "WristBridge no ha pedido permiso a Apple Salud ni a Contactos.")
                Divider()
                IconRow(symbol: "internaldrive.fill", color: .gray, title: "Nada sale del dispositivo",
                        subtitle: "Se guarda cifrado por el sistema, fuera de las copias de seguridad. Borra todo desde Ajustes → Privacidad.")
            }
            ReceiveCard(store: store)
        }
    }

    private var syncTitle: String {
        store.hasData ? "Última sincronización" : (store.receiveEnabled ? "Esperando datos del reloj" : "Recepción desactivada")
    }
    private var syncColor: Color { store.hasData ? .green : (store.receiveEnabled ? .orange : .gray) }
    private var syncDetail: String {
        guard let end = HealthMetric.allCases.compactMap({ store.ledger.latest($0)?.end }).max() else {
            return store.receiveEnabled ? "El iPhone está listo; todavía no llega ningún dato del reloj." : "Actívala abajo para que el iPhone acepte datos del reloj."
        }
        return "Dato más reciente: " + end.formatted(.relative(presentation: .named)) + "."
    }

    @ViewBuilder private func tileView(_ tile: Tile) -> some View {
        let value = valueText(tile.metric)
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: tile.symbol).font(.title3).foregroundStyle(tile.color.opacity(value == nil ? 0.5 : 1))
                Spacer()
                if value == nil { Image(systemName: "lock.fill").font(.caption).foregroundStyle(.tertiary) }
            }
            Text(value ?? "—").font(.system(size: 28, weight: .semibold, design: .rounded))
                .foregroundStyle(value == nil ? .tertiary : .primary)
                .contentTransition(.numericText())
                .minimumScaleFactor(0.6).lineLimit(1)
            Text(tile.title).font(.caption).foregroundStyle(.secondary)
            if let detail = detailText(tile.metric) { Text(detail).font(.caption2).foregroundStyle(.tertiary) }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(tile.title): \(value ?? "sin datos, bloqueado")")
    }

    private func valueText(_ metric: HealthMetric) -> String? {
        let today = Date()
        switch metric {
        case .steps, .stepsDaily: return (store.ledger.total(.stepsDaily, on: today) ?? store.ledger.total(.steps, on: today)).map { Int($0).formatted() }
        case .distance: return store.ledger.total(.distance, on: today).map { String(format: "%.1f km", $0 / 1000) }
        case .activeEnergy: return store.ledger.total(.activeEnergy, on: today).map { "\(Int($0)) kcal" }
        case .heartRate: return store.ledger.latest(.heartRate).map { "\(Int($0.value.rounded())) lpm" }
        }
    }

    private func detailText(_ metric: HealthMetric) -> String? {
        guard metric == .heartRate, let day = store.ledger.heartRate(on: Date()) else {
            return store.ledger.latest(metric) == nil ? nil : "Hoy"
        }
        return "Hoy \(Int(day.minimum))–\(Int(day.maximum)) lpm"
    }
}

/// Real activation: the switch controls whether the iPhone accepts health batches, and the list
/// shows what is true on each side. Turning it on does not create data; it removes the iPhone as a blocker.
private struct ReceiveCard: View {
    @ObservedObject var store: HealthStore
    @ObservedObject private var link = P2Link.shared

    var body: some View {
        Card(title: "Recepción de salud") {
            Toggle(isOn: Binding(get: { store.receiveEnabled }, set: store.setReceiveEnabled)) {
                IconRow(symbol: "heart.text.square.fill", color: .pink, title: "Recibir datos de salud del reloj",
                        subtitle: store.receiveEnabled ? "Activada: el iPhone acepta y guarda lotes del reloj."
                                                        : "Desactivada: el iPhone rechaza cualquier dato de salud.")
            }
            .accessibilityIdentifier("healthReceiveToggle")
            Divider()
            step("iPhone listo para recibir y guardar", done: store.receiveEnabled, detail: store.receiveEnabled ? "Libro local, validación y confirmación tras guardar" : "Activa el interruptor")
            step("Reloj captura pasos y ritmo cardiaco", done: nil, detail: "La app del reloj 0.5.0-lab los guarda en el reloj (informado por Windows); el iPhone no puede comprobarlo")
            step("Canal seguro iPhone ↔ reloj", done: link.isReady,
                 detail: link.isReady ? "Sesión cifrada activa" : "Vincula en Canal seguro (pantalla Reloj). Estado: \(channelText)")
            step("Reloj envía por lotes y espera confirmación", done: link.healthStatus.hasPrefix("Al día"),
                 detail: link.isReady ? link.healthStatus : "Empieza cuando el canal esté listo")
            if link.isReady {
                Button { link.syncHealth() } label: { Label("Sincronizar ahora", systemImage: "arrow.triangle.2.circlepath").frame(maxWidth: .infinity) }
                    .buttonStyle(.glass).disabled(!store.receiveEnabled).accessibilityIdentifier("healthSyncNow")
            }
            Text("Activar la recepción no inventa datos: aparecen solo cuando el reloj los envía por el canal seguro.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private var channelText: String {
        switch link.state {
        case .off: "desactivado"
        case .waitingForLink: "esperando al reloj"
        case .failed(let reason): reason
        case .awaitingConfirmation: "falta confirmar el código"
        case .ready: "listo"
        default: "conectando"
        }
    }

    private func step(_ title: String, done: Bool?, detail: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: done == true ? "checkmark.circle.fill" : (done == false ? "hourglass" : "questionmark.circle"))
                .foregroundStyle(done == true ? .green : (done == false ? .orange : .gray)).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct StepsChart: View {
    let ledger: HealthLedger
    var body: some View {
        let start = Calendar.current.startOfDay(for: Date())
        let snapshots = ledger.series(.stepsDaily, from: start.addingTimeInterval(-90_000), to: Date().addingTimeInterval(3600))
            .filter { Calendar.current.isDateInToday($0.end) }
        let data = ledger.series(.steps, from: start, to: Date().addingTimeInterval(3600))
        Card(title: "Pasos de hoy") {
            Chart {
                if snapshots.isEmpty {
                    ForEach(data) { sample in
                        BarMark(x: .value("Hora", sample.start, unit: .hour), y: .value("Pasos", sample.value))
                            .foregroundStyle(Color.green.gradient).cornerRadius(3)
                    }
                } else {
                    ForEach(snapshots) { sample in
                        AreaMark(x: .value("Hora", sample.end), y: .value("Pasos acumulados", sample.value))
                            .foregroundStyle(Color.green.opacity(0.2))
                        LineMark(x: .value("Hora", sample.end), y: .value("Pasos acumulados", sample.value))
                            .foregroundStyle(Color.green).interpolationMethod(.monotone)
                    }
                }
            }
            .chartXAxis { AxisMarks(values: .stride(by: .hour, count: 6)) { _ in AxisGridLine(); AxisValueLabel(format: .dateTime.hour()) } }
            .frame(height: 140)
            .accessibilityLabel("Gráfica de pasos por hora de hoy")
        }
    }
}

private struct HeartRateChart: View {
    let ledger: HealthLedger
    var body: some View {
        let start = Calendar.current.startOfDay(for: Date())
        let data = ledger.series(.heartRate, from: start, to: Date().addingTimeInterval(3600))
        Card(title: "Ritmo cardiaco de hoy") {
            Chart(data) { sample in
                AreaMark(x: .value("Hora", sample.start), yStart: .value("Mín", sample.minimum ?? sample.value),
                         yEnd: .value("Máx", sample.maximum ?? sample.value))
                    .foregroundStyle(Color.red.opacity(0.25))
                LineMark(x: .value("Hora", sample.start), y: .value("lpm", sample.value))
                    .foregroundStyle(Color.red).interpolationMethod(.monotone)
            }
            .chartYScale(domain: 40...180)
            .chartXAxis { AxisMarks(values: .stride(by: .hour, count: 6)) { _ in AxisGridLine(); AxisValueLabel(format: .dateTime.hour()) } }
            .frame(height: 140)
            .accessibilityLabel("Gráfica de ritmo cardiaco de hoy")
        }
    }
}

// MARK: - Buscar reloj

struct FindWatchScreen: View {
    @ObservedObject var model: BridgeModel
    @ObservedObject private var finder = FindViaNotifications.shared
    @ObservedObject private var link = P2Link.shared
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
            Card(title: "Hacer sonar el reloj") {
                Button {
                    if link.isReady { link.requestFindWatch(); return }
                    Task { if finder.watchBurstActive { finder.stopWatch() } else { await finder.ringWatch() } }
                } label: {
                    Label(finder.watchBurstActive ? "Detener" : "Hacer sonar el reloj",
                          systemImage: finder.watchBurstActive ? "stop.fill" : "bell.and.waves.left.and.right.fill")
                        .frame(maxWidth: .infinity).padding(.vertical, 4)
                }
                .buttonStyle(.glassProminent)
                .tint(finder.watchBurstActive ? .red : .teal)
                .disabled(model.isDemo)
                .sensoryFeedback(.impact, trigger: finder.watchBurstActive)
                .accessibilityIdentifier("ringWatch")
                Text(link.isReady ? (link.lastFindWatch ?? "Usará el canal seguro: el reloj suena y vibra 30 s.") : finder.message)
                    .font(.footnote).foregroundStyle(.secondary)
                Text("Con el canal seguro listo la orden va directa al reloj. Si no, envía 8 avisos al reloj en 30 s por el mismo camino que tus notificaciones. Que vibre o suene depende de los ajustes de avisos del reloj; el iPhone también sonará. Es «solicitado»: la app no puede confirmar que el reloj sonó.")
                    .font(.caption).foregroundStyle(.tertiary)
            }
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

// MARK: - Buscar iPhone

struct FindPhoneScreen: View {
    @ObservedObject var model: BridgeModel
    @ObservedObject private var alarm = FindPhoneAlarm.shared
    @ObservedObject private var finder = FindViaNotifications.shared
    @ObservedObject private var link = P2Link.shared

    var body: some View {
        FeatureScroll(feature: .findPhone, subtitle: "Haz sonar este iPhone para encontrarlo: desde aquí o pulsando un aviso en el reloj.") {
            VStack(spacing: 16) {
                ZStack {
                    Circle().stroke(Color.secondary.opacity(0.15), lineWidth: 12)
                    Circle().trim(from: 0, to: alarm.isRinging ? Double(alarm.remaining) / Double(FindPhoneAlarm.maxSeconds) : 0)
                        .stroke(Color.blue.gradient, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .animation(.linear(duration: 1), value: alarm.remaining)
                    VStack(spacing: 2) {
                        Image(systemName: alarm.isRinging ? "speaker.wave.3.fill" : "speaker.slash.fill")
                            .font(.system(size: 34)).foregroundStyle(alarm.isRinging ? Color.blue : Color.secondary)
                            .symbolEffect(.variableColor.iterative, options: .repeating, isActive: alarm.isRinging)
                            .contentTransition(.symbolEffect(.replace))
                        Text(alarm.isRinging ? "\(alarm.remaining) s" : "En silencio")
                            .font(.title3.weight(.semibold).monospacedDigit()).contentTransition(.numericText())
                    }
                }
                .frame(width: 170, height: 170)
                .animation(Motion.smooth, value: alarm.isRinging)

                Button {
                    if alarm.isRinging { alarm.stop() } else { alarm.start(reason: .localTest) }
                } label: {
                    Label(alarm.isRinging ? "Detener" : "Hacer sonar este iPhone", systemImage: alarm.isRinging ? "stop.fill" : "play.fill")
                        .frame(maxWidth: .infinity).padding(.vertical, 4)
                }
                .buttonStyle(.glassProminent)
                .tint(alarm.isRinging ? .red : .blue)
                .sensoryFeedback(.impact, trigger: alarm.isRinging)
                .accessibilityIdentifier("findPhoneToggle")

                Text(alarm.message).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    .accessibilityIdentifier("findPhoneStatus")
            }
            Card(title: "Desde el reloj") {
                IconRow(symbol: finder.phoneArmed ? "bell.badge.fill" : "bell.slash.fill",
                        color: finder.phoneArmed ? .green : .gray,
                        title: finder.phoneArmed ? "Armado" : "Desarmado",
                        subtitle: finder.phoneArmed
                            ? "El aviso «Buscar iPhone» está en el centro de avisos y el reloj lo recibe."
                            : "Arma el aviso para poder pulsar «Hacer sonar» desde el reloj.")
                Button {
                    Task { if finder.phoneArmed { finder.disarmPhoneFinder() } else { await finder.armPhoneFinder() } }
                } label: {
                    Label(finder.phoneArmed ? "Desarmar" : "Armar «Buscar iPhone» en el reloj",
                          systemImage: finder.phoneArmed ? "xmark.circle.fill" : "applewatch.radiowaves.left.and.right")
                        .frame(maxWidth: .infinity).padding(.vertical, 4)
                }
                .buttonStyle(.glass)
                .disabled(model.isDemo)
                .accessibilityIdentifier("armFindPhone")
                Text(finder.message).font(.footnote).foregroundStyle(.secondary)
                    .accessibilityIdentifier("findPhoneArmStatus")
            }
            Card(title: "Cómo funciona") {
                IconRow(symbol: "lock.shield.fill", color: link.isReady ? .green : .gray, title: "Canal seguro",
                        subtitle: link.isReady ? "Listo: el botón «Buscar iPhone» del reloj (Conexión › Vincular iPhone) hace sonar este iPhone."
                                               : "No listo. Vincúlalo en Canal seguro o usa el aviso armado de arriba.")
                Divider()
                IconRow(symbol: "applewatch", color: .teal, title: "Abre el aviso en el reloj",
                        subtitle: "Lo recibe como cualquier aviso del iPhone (ANCS). Pulsa «Hacer sonar».")
                Divider()
                IconRow(symbol: "bolt.horizontal.fill", color: .indigo, title: "iOS despierta la app",
                        subtitle: "Suena hasta \(FindPhoneAlarm.maxSeconds) s aunque la app esté en segundo plano; se detiene aquí o con «Detener».")
                Divider()
                IconRow(symbol: "exclamationmark.triangle.fill", color: .orange, title: "Ruta experimental",
                        subtitle: "Depende de que el reloj muestre los botones del aviso y de que iOS permita el audio en segundo plano. Se confirma en el reloj; esta app solo registra «solicitado».")
            }
        }
    }
}

// MARK: - Música

struct MusicScreen: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var model: BridgeModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        FeatureScroll(feature: .music, subtitle: "El reloj controla la música del iPhone por sí mismo.") {
            TimelineView(.animation(minimumInterval: 1 / 24, paused: reduceMotion || scenePhase != .active)) { context in
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
