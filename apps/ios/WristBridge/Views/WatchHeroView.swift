import SwiftUI

/// Vector rendering of a round Galaxy Watch-style device whose face mirrors the
/// local link state. Drawn in SwiftUI so it ships no third-party artwork.
///
/// Motion: breathing glow, sweeping seconds ticks, a spinning arc while
/// connecting, a ripple + haptic when the service is verified, and an
/// interactive 3D tilt. Reduce Motion freezes all continuous effects.
struct WatchHeroView: View {
    let tone: LinkTone
    let status: String
    var size: CGFloat = 190
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var tilt: CGSize = .zero
    @State private var appeared = false
    @State private var celebrations = 0

    var body: some View {
        ZStack {
            ambientGlow
            if tone == .working && !reduceMotion { RadarRings(color: tone.glow, diameter: size) }
            strap.offset(y: -size * 0.6)
            strap.rotationEffect(.degrees(180)).offset(y: size * 0.6)
            sideButtons
            watchCase
            WatchFace(tone: tone, size: size * 0.86)
            crystalReflection
            if !reduceMotion { successRipple }
        }
        .frame(width: size, height: size * 1.5)
        .rotation3DEffect(.degrees(reduceMotion ? 0 : Double(tilt.width) / 9), axis: (x: 0, y: 1, z: 0), perspective: 0.6)
        .rotation3DEffect(.degrees(reduceMotion ? 0 : Double(-tilt.height) / 12), axis: (x: 1, y: 0, z: 0), perspective: 0.6)
        .scaleEffect(appeared || reduceMotion ? 1 : 0.82)
        .opacity(appeared ? 1 : 0)
        .simultaneousGesture(DragGesture(minimumDistance: 4)
            .onChanged { value in
                guard !reduceMotion else { return }
                tilt = CGSize(width: max(-140, min(140, value.translation.width)),
                              height: max(-90, min(90, value.translation.height)))
            }
            .onEnded { _ in withAnimation(.spring(response: 0.55, dampingFraction: 0.5)) { tilt = .zero } })
        .onAppear { withAnimation(.spring(response: 0.9, dampingFraction: 0.72).delay(0.05)) { appeared = true } }
        .onChange(of: tone) { _, new in if new == .connected { celebrations += 1 } }
        .sensoryFeedback(.success, trigger: celebrations)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Reloj: \(status)")
    }

    // MARK: Layers

    @ViewBuilder private var ambientGlow: some View {
        let glow = Circle().fill(tone.glow.opacity(tone == .idle ? 0.25 : 0.45))
            .frame(width: size * 1.05, height: size * 1.05)
            .blur(radius: size * 0.22)
        if reduceMotion || scenePhase != .active { glow } else {
            glow.phaseAnimator([false, true]) { view, phase in
                view.scaleEffect(phase ? 1.1 : 0.9).opacity(phase ? 1 : 0.65)
            } animation: { _ in Motion.gentle }
        }
    }

    private var strap: some View {
        UnevenRoundedRectangle(topLeadingRadius: size * 0.12, bottomLeadingRadius: 4,
                               bottomTrailingRadius: 4, topTrailingRadius: size * 0.12)
            .fill(LinearGradient(colors: [Color(white: 0.13), Color(white: 0.26)], startPoint: .top, endPoint: .bottom))
            .overlay(alignment: .top) {
                // Subtle sport-band grooves.
                VStack(spacing: size * 0.035) {
                    ForEach(0..<4, id: \.self) { _ in
                        Capsule().fill(.white.opacity(0.05)).frame(width: size * 0.4, height: 2)
                    }
                }
                .padding(.top, size * 0.05)
            }
            .frame(width: size * 0.56, height: size * 0.3)
    }

    private var sideButtons: some View {
        VStack(spacing: size * 0.14) {
            ForEach(0..<2, id: \.self) { _ in
                Capsule()
                    .fill(LinearGradient(colors: [Color(white: 0.75), Color(white: 0.45)], startPoint: .leading, endPoint: .trailing))
                    .frame(width: size * 0.05, height: size * 0.12)
            }
        }
        .offset(x: size * 0.5)
    }

    private var watchCase: some View {
        ZStack {
            Circle()
                .fill(AngularGradient(colors: [Color(white: 0.86), Color(white: 0.42), Color(white: 0.78),
                                               Color(white: 0.36), Color(white: 0.86)], center: .center))
                .frame(width: size, height: size)
                .shadow(color: .black.opacity(0.5), radius: 20, y: 12)
            Circle()
                .strokeBorder(LinearGradient(colors: [.white.opacity(0.7), .clear, .black.opacity(0.4)],
                                             startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.5)
                .frame(width: size, height: size)
            Circle().fill(.black).frame(width: size * 0.92, height: size * 0.92)
        }
    }

    /// Glass reflection across the crystal.
    private var crystalReflection: some View {
        Circle()
            .fill(LinearGradient(colors: [.white.opacity(0.16), .clear], startPoint: .topLeading, endPoint: .center))
            .frame(width: size * 0.86, height: size * 0.86)
            .allowsHitTesting(false)
    }

    private var successRipple: some View {
        Circle()
            .stroke(tone.glow, lineWidth: 3)
            .frame(width: size * 0.9, height: size * 0.9)
            .keyframeAnimator(initialValue: RippleFrame(), trigger: celebrations) { view, frame in
                view.scaleEffect(frame.scale).opacity(frame.opacity)
            } keyframes: { _ in
                KeyframeTrack(\.scale) {
                    CubicKeyframe(1, duration: 0.01)
                    CubicKeyframe(1.55, duration: 0.9)
                }
                KeyframeTrack(\.opacity) {
                    LinearKeyframe(0.9, duration: 0.05)
                    CubicKeyframe(0, duration: 0.85)
                }
            }
            .allowsHitTesting(false)
    }
}

private struct RippleFrame {
    var scale: CGFloat = 1
    var opacity: Double = 0
}

/// Expanding radar waves shown while the configuration link is being established.
private struct RadarRings: View {
    let color: Color
    let diameter: CGFloat
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: scenePhase != .active)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            ZStack {
                ForEach(0..<3, id: \.self) { index in
                    let phase = (t / 2.1 + Double(index) / 3).truncatingRemainder(dividingBy: 1)
                    Circle()
                        .stroke(color.opacity(0.55 * (1 - phase)), lineWidth: 2)
                        .frame(width: diameter, height: diameter)
                        .scaleEffect(1 + phase * 0.65)
                }
            }
        }
        .allowsHitTesting(false)
    }
}

/// Galaxy-style face: 60 ticks lit by the sweeping second, a status arc and the time.
private struct WatchFace: View {
    let tone: LinkTone
    let size: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            RadialGradient(colors: [Color(red: 0.07, green: 0.11, blue: 0.24), .black],
                           center: .center, startRadius: 2, endRadius: size * 0.55)
            TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion || scenePhase != .active)) { context in
                Canvas { canvas, area in draw(in: &canvas, area: area, date: context.date) }
            }
            centerContent
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    private var centerContent: some View {
        VStack(spacing: size * 0.012) {
            Image(systemName: tone == .connected || tone == .working ? "iphone.radiowaves.left.and.right" : "iphone.slash")
                .font(.system(size: size * 0.085, weight: .semibold))
                .foregroundStyle(tone.glow)
                .symbolEffect(.variableColor.iterative, options: .repeating, isActive: tone == .working && !reduceMotion)
                .symbolEffect(.breathe, options: .repeating, isActive: tone == .connected && !reduceMotion)
                .contentTransition(.symbolEffect(.replace))
            TimelineView(.everyMinute) { context in
                VStack(spacing: size * 0.012) {
                    Text(context.date, format: .dateTime.hour(.defaultDigits(amPM: .omitted)).minute())
                        .font(.system(size: size * 0.23, weight: .thin, design: .rounded))
                        .lineLimit(1).minimumScaleFactor(0.6)
                        .monospacedDigit()
                        .foregroundStyle(.white)
                        .contentTransition(.numericText())
                        .animation(Motion.snappy, value: context.date)
                    Text(context.date, format: .dateTime.weekday(.abbreviated).day())
                        .font(.system(size: size * 0.07, weight: .semibold, design: .rounded))
                        .foregroundStyle(tone.glow)
                        .textCase(.uppercase)
                }
            }
        }
        .animation(Motion.smooth, value: tone)
    }

    private func draw(in canvas: inout GraphicsContext, area: CGSize, date: Date) {
        let center = CGPoint(x: area.width / 2, y: area.height / 2)
        let radius = min(area.width, area.height) / 2
        let components = Calendar.current.dateComponents([.second, .nanosecond], from: date)
        let second = Double(components.second ?? 0) + Double(components.nanosecond ?? 0) / 1e9
        let lit = reduceMotion ? 60 : second

        for index in 0..<60 {
            let angle = Double(index) / 60 * 2 * .pi - .pi / 2
            let major = index % 5 == 0
            let length = radius * (major ? 0.1 : 0.055)
            let outer = radius * 0.96
            var tick = Path()
            tick.move(to: CGPoint(x: center.x + cos(angle) * outer, y: center.y + sin(angle) * outer))
            tick.addLine(to: CGPoint(x: center.x + cos(angle) * (outer - length), y: center.y + sin(angle) * (outer - length)))
            let on = Double(index) <= lit
            canvas.stroke(tick, with: .color(on ? tone.glow : .white.opacity(major ? 0.45 : 0.16)),
                          style: StrokeStyle(lineWidth: major ? 2.2 : 1.2, lineCap: .round))
        }

        let ringRadius = radius * 0.78
        let width = radius * 0.045
        let track = Path { $0.addArc(center: center, radius: ringRadius, startAngle: .zero, endAngle: .degrees(360), clockwise: false) }
        canvas.stroke(track, with: .color(.white.opacity(0.08)), lineWidth: width)

        let start: Double
        let sweep: Double
        switch tone {
        case .connected: start = -90; sweep = 360
        case .working:
            start = reduceMotion ? -90 : (date.timeIntervalSinceReferenceDate * 300).truncatingRemainder(dividingBy: 360)
            sweep = 110
        case .problem: start = -90; sweep = 40
        case .idle: start = -90; sweep = 24
        }
        let arc = Path {
            $0.addArc(center: center, radius: ringRadius, startAngle: .degrees(start),
                      endAngle: .degrees(start + sweep), clockwise: false)
        }
        var glowing = canvas
        glowing.addFilter(.shadow(color: tone.glow.opacity(0.9), radius: width * 1.4))
        glowing.stroke(arc, with: .color(tone.glow), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }
}

#Preview {
    VStack(spacing: 40) {
        WatchHeroView(tone: .connected, status: "Conectado", size: 160)
        WatchHeroView(tone: .working, status: "Conectando", size: 160)
    }
    .padding()
    .background(Theme.heroTop)
}
