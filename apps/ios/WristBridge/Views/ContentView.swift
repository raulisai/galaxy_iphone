import SwiftUI
import UIKit
import WristBridgeProtocol

struct ContentView: View {
    @ObservedObject var model: BridgeModel
    @ObservedObject var fixtures: NotificationFixtureAdapter
    @Environment(\.scenePhase) private var scenePhase
    @State private var runID = "run-\(Int(Date().timeIntervalSince1970))"
    @State private var showForgetConfirmation = false

    var body: some View {
        NavigationStack {
            List {
                if model.isDemo {
                    Section {
                        Label("Demostración · sin conexión real", systemImage: "testtube.2")
                            .font(.headline)
                            .foregroundStyle(.orange)
                            .accessibilityIdentifier("demoBanner")
                    }
                }
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Image(systemName: "applewatch.radiowaves.left.and.right")
                            .font(.largeTitle)
                            .foregroundStyle(.tint)
                            .accessibilityHidden(true)
                        Text("Tu iPhone y tu reloj")
                            .font(.title2.bold())
                        Text("Configura el enlace y comprueba cada paso. Los avisos viajan directamente del iPhone al reloj.")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }
                Section("Estado del enlace") {
                    statusRow("Accesorio", value: model.authorizationText)
                    statusRow("Configuración", value: model.channelText)
                    statusRow("Avisos en el reloj", value: "Sin comprobar")
                        .accessibilityIdentifier("ancsStatus")
                    Text(model.message)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    if model.state.authorization != .authorized {
                        Button("Seleccionar reloj", systemImage: "plus.circle", action: model.selectWatch)
                            .disabled(!model.pickerAvailable)
                            .accessibilityIdentifier("selectWatch")
                    } else {
                        Button("Reconectar", systemImage: "arrow.clockwise", action: model.reconnect)
                            .disabled(model.state.channel == .connecting)
                        Button("Detener configuración", systemImage: "pause.circle", action: model.stop)
                        Button("Quitar accesorio", role: .destructive) { showForgetConfirmation = true }
                    }
                }
                Section("Comprobar los avisos") {
                    Text("Autorizar el accesorio y conectar Bluetooth son pasos distintos de recibir avisos. Esta app todavía no puede confirmar el estado de ANCS del reloj.")
                    Text("Si iOS ofrece Compartir notificaciones del sistema para el accesorio en Ajustes → Bluetooth, actívalo. La opción depende del vínculo real.")
                    Text("Enfoque y la privacidad de previsualizaciones pueden cambiar lo que llega. No restablezcas el reloj para estas pruebas.")
                    Button("Abrir Ajustes", systemImage: "gear") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }
                if !model.isDemo { fixtureSection }
                Section("Diagnóstico") {
                    statusRow("Bluetooth", value: model.radio)
                    if let date = model.lastCheck {
                        LabeledContent("Última comprobación local") {
                            Text(date, style: .time)
                        }
                    }
                    Text("El canal de datos sensibles sigue desactivado. No hay mediciones de Internet o IA en esta fase.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ShareLink(item: model.diagnosticSummary) {
                        Label("Compartir diagnóstico sin mensajes", systemImage: "square.and.arrow.up")
                    }
                }
                Section("Recuperación") {
                    Text("Para la primera prueba deja la app en segundo plano con el gesto de inicio y bloquea el iPhone. Deslizar la app fuera del selector fuerza su cierre: ese caso se prueba por separado.")
                    Text("La recuperación depende de iOS, los permisos y los eventos Bluetooth pendientes. Abre la app y pulsa Reconectar si el canal no se recupera.")
                }
            }
            .navigationTitle("WristBridge")
            .confirmationDialog("¿Quitar la autorización del accesorio?", isPresented: $showForgetConfirmation,
                                titleVisibility: .visible) {
                Button("Quitar accesorio", role: .destructive, action: model.forget)
                Button("Cancelar", role: .cancel) {}
            } message: {
                Text("Para volver a conectarlo tendrás que seleccionarlo de nuevo. El vínculo y los avisos del sistema se comprueban por separado.")
            }
            .task { if !model.isDemo { await fixtures.refreshPermission() } }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active && !model.isDemo {
                    Task { await fixtures.refreshPermission() }
                }
            }
        }
    }

    private var fixtureSection: some View {
        Section("Laboratorio de avisos") {
            Text("Solo se generan avisos sintéticos de WristBridge; esta app no lee los avisos de otras apps.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            statusRow("Permiso de esta app", value: fixtures.permission)
            Button("Autorizar avisos de prueba") { Task { await fixtures.requestPermission() } }
            TextField("ID de ejecución", text: $runID)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("runID")
            Button("Programar 10 avisos") { Task { await fixtures.schedule(runID: runID) } }
                .disabled(fixtures.busy)
            Text("El primero se solicita para dentro de 10 segundos; después, uno cada 3 segundos. Usa un ID nuevo por lote.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Button("Observar avisos entregados") { Task { await fixtures.observeDelivered() } }
                .disabled(fixtures.busy)
            Button("Cancelar avisos pendientes", role: .destructive) { Task { await fixtures.cancelPending() } }
                .disabled(fixtures.busy)
            Text(fixtures.message).font(.callout)
            if let warning = fixtures.persistenceWarning {
                Label(warning, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            }
            statusRow("Programación aceptada", value: "\(fixtures.records.filter { $0.observation == .scheduleAccepted }.count)")
            statusRow("Observados en el centro", value: "\(fixtures.records.filter { $0.observation == .deliveredSnapshot }.count)")
            Text("Los contadores cubren los últimos 1000 registros locales. Programado no equivale a entregado ni recibido por el reloj.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            ShareLink(item: fixtures.exportMetadata()) {
                Label("Compartir registro de pruebas", systemImage: "square.and.arrow.up")
            }
        }
    }

    private func statusRow(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body)
        }
        .accessibilityElement(children: .combine)
    }
}
