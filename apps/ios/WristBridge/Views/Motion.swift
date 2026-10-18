import SwiftUI

/// Shared motion vocabulary. Every effect degrades to a plain fade when the
/// user enables Reduce Motion.
enum Motion {
    static let snappy = Animation.spring(response: 0.35, dampingFraction: 0.78)
    static let smooth = Animation.spring(response: 0.6, dampingFraction: 0.86)
    static let gentle = Animation.easeInOut(duration: 2.4)
}

/// Scales and dims slightly while pressed, like system tiles.
struct PressableStyle: ButtonStyle {
    var scale: CGFloat = 0.96
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(Motion.snappy, value: configuration.isPressed)
    }
}

/// Content rises and unblurs into place on first appearance, staggered by index.
private struct StaggeredAppear: ViewModifier {
    let index: Int
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 24)
            .blur(radius: shown || reduceMotion ? 0 : 8)
            .onAppear {
                guard !shown else { return }
                withAnimation(Motion.smooth.delay(reduceMotion ? 0 : Double(index) * 0.06)) { shown = true }
            }
    }
}

/// Cards recede softly as they leave the visible scroll area.
private struct ScrollDepth: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        let still = reduceMotion
        return content.scrollTransition(topLeading: .interactive, bottomTrailing: .identity, axis: .vertical) { view, phase in
            view
                .scaleEffect(phase.isIdentity || still ? 1 : 0.94)
                .opacity(phase.isIdentity ? 1 : 0.55)
        }
    }
}

extension View {
    func appear(_ index: Int) -> some View { modifier(StaggeredAppear(index: index)) }
    func scrollDepth() -> some View { modifier(ScrollDepth()) }
}

/// Slowly drifting mesh gradient tinted by the link state.
struct LivingBackground: View {
    let tone: LinkTone
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion)) { context in
            let t = Float(context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3600))
            let drift: Float = reduceMotion ? 0 : 1
            let cx: Float = 0.5 + 0.12 * sin(t * 0.35) * drift
            let cy: Float = 0.5 + 0.1 * cos(t * 0.28) * drift
            let bx: Float = 0.5 + 0.1 * cos(t * 0.22) * drift
            let points: [SIMD2<Float>] = [
                SIMD2(0, 0), SIMD2(0.5, 0), SIMD2(1, 0),
                SIMD2(0, 0.5), SIMD2(cx, cy), SIMD2(1, 0.5),
                SIMD2(0, 1), SIMD2(bx, 1), SIMD2(1, 1)
            ]
            MeshGradient(
                width: 3, height: 3,
                points: points,
                colors: [
                    Theme.heroTop, Theme.heroTop, Theme.heroTop,
                    Theme.heroBottom, tone.glow.opacity(0.55), Theme.heroBottom,
                    Theme.heroBottom, Theme.heroTop, Theme.heroBottom
                ])
        }
        .animation(Motion.smooth, value: tone)
    }
}

extension LinkTone {
    /// Softer hue for glows and backgrounds.
    var glow: Color {
        switch self {
        case .connected: Color(red: 0.2, green: 0.85, blue: 0.55)
        case .working: Color(red: 1, green: 0.66, blue: 0.2)
        case .idle: Theme.accent
        case .problem: Color(red: 1, green: 0.32, blue: 0.32)
        }
    }
}
