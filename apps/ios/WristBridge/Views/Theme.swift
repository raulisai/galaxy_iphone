import SwiftUI
import WristBridgeProtocol

/// Visual language: Galaxy Wearable layout (watch hero, feature tiles) with
/// iOS materials, SF Symbols and Settings-style icon squares.
enum Theme {
    static let accent = Color(red: 0.12, green: 0.45, blue: 0.98)
    static let heroTop = Color(red: 0.07, green: 0.10, blue: 0.20)
    static let heroBottom = Color(red: 0.11, green: 0.24, blue: 0.48)
}

/// Honest link summary derived only from local state. Never claims ANCS.
enum LinkTone {
    case connected, working, idle, problem
    var color: Color {
        switch self {
        case .connected: .green
        case .working: .orange
        case .idle: .gray
        case .problem: .red
        }
    }
}

extension BridgeModel {
    var linkTone: LinkTone {
        if stopped { return .idle }
        switch state.authorization {
        case .denied, .unavailable: return .problem
        case .authorized: break
        default: return .idle
        }
        return switch state.channel {
        case .serviceFound: .connected
        case .connecting, .connected: .working
        case .unavailable: .problem
        case .idle, .disconnected: .idle
        }
    }

    var headline: String {
        if stopped { return "Detenido" }
        switch state.authorization {
        case .authorized:
            return switch state.channel {
            case .serviceFound: "Conectado"
            case .connecting, .connected: "Conectando…"
            case .unavailable: "Bluetooth no disponible"
            case .idle, .disconnected: "Desconectado"
            }
        case .choosing: return "Seleccionando reloj…"
        case .denied: return "Permiso denegado"
        case .unavailable: return "No disponible"
        case .unknown, .cancelled: return "Sin reloj"
        }
    }
}

/// iOS Settings-style rounded icon square.
struct IconTile: View {
    let symbol: String
    let color: Color
    var size: CGFloat = 30
    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color.gradient, in: RoundedRectangle(cornerRadius: size * 0.26, style: .continuous))
            .accessibilityHidden(true)
    }
}

struct IconRow<Trailing: View>: View {
    let symbol: String
    let color: Color
    let title: String
    var subtitle: String?
    @ViewBuilder var trailing: Trailing
    var body: some View {
        HStack(spacing: 12) {
            IconTile(symbol: symbol, color: color)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let subtitle {
                    Text(subtitle).font(.footnote).foregroundStyle(.secondary)
                        .contentTransition(.interpolate)
                        .animation(Motion.snappy, value: subtitle)
                }
            }
            Spacer(minLength: 8)
            trailing
        }
        .accessibilityElement(children: .combine)
    }
}

extension IconRow where Trailing == EmptyView {
    init(symbol: String, color: Color, title: String, subtitle: String? = nil) {
        self.init(symbol: symbol, color: color, title: title, subtitle: subtitle) { EmptyView() }
    }
}

/// Galaxy Wearable-style rounded card on a grouped background.
struct Card<Content: View>: View {
    var title: String?
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title { Text(title).font(.headline) }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

struct StatusPill: View {
    let text: String
    let tone: LinkTone
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        HStack(spacing: 8) {
            ZStack {
                if tone == .working && !reduceMotion {
                    Circle().fill(tone.glow)
                        .phaseAnimator([false, true]) { view, phase in
                            view.scaleEffect(phase ? 2.6 : 1).opacity(phase ? 0 : 0.8)
                        } animation: { _ in .easeOut(duration: 1.1) }
                }
                Circle().fill(tone.glow)
            }
            .frame(width: 8, height: 8)
            Text(text).font(.subheadline.weight(.semibold))
                .contentTransition(.interpolate)
        }
        .padding(.horizontal, 16).padding(.vertical, 9)
        .foregroundStyle(.white)
        .glassEffect(.regular.tint(tone.glow.opacity(0.22)), in: .capsule)
        .animation(Motion.snappy, value: text)
        .animation(Motion.snappy, value: tone)
    }
}
