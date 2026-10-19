import SwiftUI
import UIKit
import WristBridgeProtocol

struct ContentView: View {
    @ObservedObject var model: BridgeModel
    @ObservedObject var fixtures: NotificationFixtureAdapter
    @StateObject private var observations: LabJournalStore
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var runID = Self.newRunID()
    @State private var fixtureCount = 10
    @State private var fixtureDelay = 30.0
    @State private var condition: LabCase = .notificationsLocked
    @State private var outcome: LabOutcome = .pass
    @State private var showForgetConfirmation = false
    @State private var showDeleteConfirmation = false

    init(model: BridgeModel, fixtures: NotificationFixtureAdapter) {
        self.model = model; self.fixtures = fixtures
        _observations = StateObject(wrappedValue: LabJournalStore(isDemo: model.isDemo))
    }

    private enum Tab: Hashable { case watch, notifications, settings }
    @State private var tab: Tab = .watch

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack {
                HomeView(model: model, observations: observations, campaign: model.recoveryCampaign,
                         showForgetConfirmation: $showForgetConfirmation) { tab = .notifications }
            }
            .tabItem { Label("Reloj", systemImage: "watch.analog") }
            .tag(Tab.watch)
            NavigationStack {
                List {
                    notificationSummary
                    if !model.isDemo { fixtureSection }
                    observationSection
                    if !observations.journal.entries.isEmpty { historySection }
                }
                .navigationTitle("Notificaciones")
            }
            .tabItem { Label("Notificaciones", systemImage: "bell.badge") }
            .tag(Tab.notifications)
            NavigationStack {
                List {
                    settingsHeader
                    recoverySection
                    Section {
                        NavigationLink {
                            RecoveryCampaignScreen(model: model, controller: model.recoveryCampaign)
                        } label: {
                            IconRow(symbol: "arrow.triangle.2.circlepath", color: .green, title: "Ensayo de reconexión",
                                    subtitle: "20 intentos del canal de configuración")
                        }
                        NavigationLink { SecureChannelScreen(model: model) } label: {
                            IconRow(symbol: "lock.shield.fill", color: .teal, title: "Canal seguro con el reloj",
                                    subtitle: "Vincular, salud y buscar")
                        }
                        NavigationLink { SetupGuideScreen() } label: {
                            IconRow(symbol: "book.fill", color: .blue, title: "Guía de configuración")
                        }
                    }
                    DiagnosticsSection(model: model)
                    privacySection
                }
                .navigationTitle("Ajustes")
            }
            .tabItem { Label("Ajustes", systemImage: "gearshape") }
            .tag(Tab.settings)
        }
        .transaction { transaction in
            if reduceMotion {
                transaction.animation = nil
                transaction.disablesAnimations = true
            }
        }
        .tint(Theme.accent)
        .tabBarMinimizeBehavior(.onScrollDown)
        .confirmationDialog("¿Quitar la autorización del accesorio?", isPresented: $showForgetConfirmation,
                            titleVisibility: .visible) {
            Button("Quitar accesorio", role: .destructive, action: model.forget)
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Para volver a conectarlo tendrás que seleccionarlo de nuevo. El vínculo y los avisos del sistema se comprueban por separado.")
        }
        .confirmationDialog("¿Borrar los datos locales de WristBridge?", isPresented: $showDeleteConfirmation,
                            titleVisibility: .visible) {
            Button("Borrar datos locales", role: .destructive) {
                model.resetLocalSettings()
                observations.delete()
                Task { await fixtures.deleteLocalData() }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Borra observaciones, ensayos de reconexión, registros de prueba y diagnóstico. Detiene la configuración y restablece sus ajustes. Solicita retirar los avisos sintéticos de esta app. La autorización del accesorio se quita por separado.")
        }
        .task {
            if !model.isDemo {
                await fixtures.refreshPermission()
                fixtures.applyRetention(days: model.retentionDays)
                observations.applyRetention(days: model.retentionDays)
                model.recoveryCampaign.applyRetention(days: model.retentionDays)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.recoveryCampaign.cancel(interrupted: true) }
            if phase == .active && !model.isDemo {
                model.refreshAuthorization()
                model.resumeLinkIfNeeded()
                Task { await fixtures.refreshPermission() }
            }
        }
        // Slow heartbeat while the app is visible: the link recovers in real time
        // even if iOS never delivered a reconnect callback.
        .task(id: scenePhase) {
            guard scenePhase == .active, !model.isDemo else { return }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(8)) } catch { return }
                model.resumeLinkIfNeeded()
            }
        }
    }

    private var recoverySection: some View {
        Section {
            Toggle(isOn: Binding(get: { model.automaticRecovery }, set: model.setAutomaticRecovery)) {
                IconRow(symbol: "arrow.clockwise.circle.fill", color: .green, title: "Recuperar configuración automáticamente")
            }
            .accessibilityIdentifier("automaticRecovery")
            if let warning = model.settingsWarning { Label(warning, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
        } header: { Text("Recuperación") } footer: {
            Text("Tras una desconexión se solicita de nuevo el enlace al reloj autorizado, con hasta tres intentos. Detener configuración los cancela. Bloquear el iPhone es distinto de cerrar la app deslizando; la recuperación depende de iOS y se prueba por separado de los avisos.")
        }
    }

    private var settingsHeader: some View {
        Section {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(LinearGradient(colors: [Theme.heroTop, Theme.heroBottom], startPoint: .top, endPoint: .bottom))
                    Image(systemName: "watch.analog").font(.title2).foregroundStyle(.white)
                }
                .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(model.state.authorization == .authorized ? "Galaxy Watch" : "Añade tu reloj").font(.headline)
                    HStack(spacing: 6) {
                        Circle().fill(model.linkTone.color).frame(width: 8, height: 8)
                        Text(model.headline).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
        }
    }

    private var privacySection: some View {
        Section("Privacidad y datos") {
            Text("Solo se guardan ajustes, metadatos de pruebas y tus observaciones. Esta app no lee ni guarda el contenido de avisos de otras apps.")
            Picker("Conservar registros", selection: Binding(get: { model.retentionDays }, set: { days in
                model.setRetentionDays(days)
                observations.applyRetention(days: days)
                model.recoveryCampaign.applyRetention(days: days)
                fixtures.applyRetention(days: days)
            })) {
                ForEach([7, 30, 90], id: \.self) { Text("\($0) días").tag($0) }
            }
            Text("La limpieza se aplica al abrir la app o cambiar este ajuste. El diagnóstico conserva solo los últimos 64 pasos del proceso actual. Los archivos nuevos se excluyen de copias de seguridad; las exportaciones que compartas se gestionan fuera de la app.")
                .font(.footnote).foregroundStyle(.secondary)
            Button("Borrar datos locales", role: .destructive) { showDeleteConfirmation = true }
                .disabled(fixtures.busy).accessibilityIdentifier("deleteLocalData")
        }
    }

    private var observationSection: some View {
        Section("Lo que observaste") {
            Text("Estos resultados son reportes tuyos. No activan permisos ni convierten la conexión en una comprobación de avisos.")
                .font(.footnote).foregroundStyle(.secondary)
            Picker("Prueba", selection: $condition) {
                ForEach(LabCase.allCases, id: \.self) { Text($0.title).tag($0) }
            }
            Picker("Resultado", selection: $outcome) {
                ForEach(LabOutcome.allCases, id: \.self) { Text($0.title).tag($0) }
            }
            Button("Guardar observación") {
                observations.record(condition, outcome: outcome, accessoryID: model.authorizedID, retentionDays: model.retentionDays)
            }
            .disabled(model.isDemo)
            .accessibilityIdentifier("saveObservation")
            Text(observations.message).font(.callout)
            if let warning = observations.warning { Label(warning, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
        }
    }

    private var historySection: some View {
        Section("Resultados registrados") {
            ForEach(Array(observations.journal.entries.suffix(20).reversed())) { entry in
                HStack(spacing: 12) {
                    Image(systemName: entry.outcome.symbol).font(.title3).foregroundStyle(entry.outcome.color)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.condition.title)
                        Text("\(entry.outcome.title) · observado por ti").font(.subheadline).foregroundStyle(.secondary)
                        Text(entry.date, style: .date).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Text("Se muestran los últimos 20; el registro conserva como máximo 500 dentro del plazo elegido.")
                .font(.footnote).foregroundStyle(.secondary)
            ShareLink(item: observations.journal.exportRedacted()) {
                Label("Compartir observaciones sin identificadores", systemImage: "square.and.arrow.up")
            }
        }
    }

    private var fixtureSection: some View {
        Section("Avisos de prueba") {
            IconRow(symbol: "bell.badge.fill", color: .orange, title: "Permiso de esta app", subtitle: fixtures.permission)
            Text("Solo se generan avisos sintéticos de WristBridge.").font(.footnote).foregroundStyle(.secondary)
            Button("Autorizar avisos de prueba") { Task { await fixtures.requestPermission() } }
            TextField("ID de ejecución", text: $runID).textInputAutocapitalization(.never)
                .autocorrectionDisabled().disabled(fixtures.busy).accessibilityIdentifier("runID")
            Picker("Cantidad", selection: $fixtureCount) {
                ForEach([10, 25, 50], id: \.self) { Text("\($0) avisos").tag($0) }
            }.disabled(fixtures.busy).accessibilityIdentifier("fixtureCount")
            Picker("Espera para bloquear", selection: $fixtureDelay) {
                ForEach([10.0, 30.0, 60.0], id: \.self) { Text("\(Int($0)) segundos").tag($0) }
            }.disabled(fixtures.busy).accessibilityIdentifier("fixtureDelay")
            Button("Programar \(fixtureCount) avisos") {
                let submittedID = runID
                let submittedCount = fixtureCount
                let submittedDelay = fixtureDelay
                Task {
                    if await fixtures.schedule(runID: submittedID, count: submittedCount, initialDelay: submittedDelay) {
                        runID = Self.newRunID()
                    }
                }
            }.disabled(fixtures.busy)
            Text("Primera solicitud dentro de \(Int(fixtureDelay)) segundos; después una cada 3 segundos. Un lote completo prepara un ID nuevo para la siguiente prueba.")
                .font(.footnote).foregroundStyle(.secondary)
            Button("Observar avisos entregados") { Task { await fixtures.observeDelivered() } }.disabled(fixtures.busy)
            Button("Cancelar avisos pendientes", role: .destructive) { Task { await fixtures.cancelPending() } }.disabled(fixtures.busy)
            if let lastBatchID = fixtures.lastBatchID { LabeledContent("ID del último lote", value: lastBatchID) }
            Text(fixtures.message).font(.callout)
            if let warning = fixtures.persistenceWarning { Label(warning, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
            Text("Contadores de hasta 1000 registros locales dentro del plazo elegido. Programado no equivale a entregado ni recibido por el reloj.")
                .font(.footnote).foregroundStyle(.secondary)
            ShareLink(item: fixtures.exportMetadata()) { Label("Compartir registro de pruebas", systemImage: "square.and.arrow.up") }
        }
    }

    private static func newRunID() -> String {
        "run-\(Int(Date().timeIntervalSince1970))-\(UUID().uuidString.prefix(8))"
    }

    private var notificationSummary: some View {
        let accepted = fixtures.records.filter { $0.observation == .scheduleAccepted }.count
        let delivered = fixtures.records.filter { $0.observation == .deliveredSnapshot }.count
        return Section {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 14) {
                    Image(systemName: "bell.badge.fill")
                        .font(.system(size: 26, weight: .semibold))
                        .symbolRenderingMode(.hierarchical)
                        .symbolEffect(.wiggle, options: .nonRepeating, value: accepted)
                        .frame(width: 52, height: 52)
                        .glassEffect(.regular.tint(.white.opacity(0.15)), in: .circle)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Avisos de prueba").font(.title3.weight(.bold))
                        Text(model.isDemo ? "No disponible en demostración" : fixtures.permission)
                            .font(.subheadline).opacity(0.85)
                            .contentTransition(.interpolate)
                    }
                }
                HStack(spacing: 12) {
                    counter("Programación aceptada", accepted)
                    counter("Observados en el centro", delivered)
                }
            }
            .foregroundStyle(.white)
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [Color(red: 1, green: 0.55, blue: 0.2), Color(red: 0.93, green: 0.27, blue: 0.35)],
                                       startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            .appear(0)
        } footer: {
            Text("Programado no equivale a entregado ni recibido por el reloj.")
        }
    }

    private func counter(_ title: String, _ value: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)").font(.system(size: 34, weight: .semibold, design: .rounded).monospacedDigit())
                .contentTransition(.numericText(value: Double(value)))
                .animation(Motion.snappy, value: value)
            Text(title).font(.caption).opacity(0.85).lineLimit(2, reservesSpace: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
    }

}

extension LabCase {
    var title: String {
        switch self {
        case .notificationsForeground: "Avisos con la app abierta"
        case .notificationsLocked: "Avisos con el iPhone bloqueado"
        case .notificationsBackground: "Avisos con la app en segundo plano"
        case .notificationsForceQuit: "Avisos después de cerrar la app deslizando"
        case .configurationReconnect: "Recuperación de configuración"
        case .bluetoothOffOn: "Apagar y encender Bluetooth"
        case .authorizationRemoved: "Retirada de autorización"
        case .phoneRestart: "Reinicio del iPhone"
        }
    }
}
extension LabOutcome {
    var title: String {
        switch self { case .pass: "Funcionó"; case .fail: "Falló"; case .blocked: "No se pudo probar" }
    }
    var symbol: String {
        switch self { case .pass: "checkmark.circle.fill"; case .fail: "xmark.circle.fill"; case .blocked: "minus.circle.fill" }
    }
    var color: Color {
        switch self { case .pass: .green; case .fail: .red; case .blocked: .gray }
    }
}
