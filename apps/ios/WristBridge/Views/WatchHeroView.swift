import SwiftUI

/// Vector rendering of a round Galaxy Watch-style device whose face mirrors the
/// local link state. Drawn in SwiftUI so it ships no third-party artwork.
struct WatchHeroView: View {
    let tone: LinkTone
    let status: String
    var size: CGFloat = 190
    @State private var pulse = false

    var body: some View {
        ZStack {
            strap.offset(y: -size * 0.6)
            strap.rotationEffect(.degrees(180)).offset(y: size * 0.6)
            sideButtons
            Circle()
                .fill(LinearGradient(colors: [Color(white: 0.78), Color(white: 0.38), Color(white: 0.66)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: size, height: size)
                .shadow(color: .black.opacity(0.45), radius: 18, y: 10)
            Circle().fill(.black).frame(width: size * 0.9, height: size * 0.9)
            if tone == .working {
                Circle().stroke(tone.color.opacity(0.6), lineWidth: 3)
                    .frame(width: size * 0.9, height: size * 0.9)
                    .scaleEffect(pulse ? 1.12 : 0.98).opacity(pulse ? 0 : 1)
                    .animation(.easeOut(duration: 1.4).repeatForever(autoreverses: false), value: pulse)
            }
            face.frame(width: size * 0.82, height: size * 0.82).clipShape(Circle())
        }
        .frame(width: size, height: size * 1.5)
        .onAppear { pulse = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Reloj: \(status)")
    }

    private var strap: some View {
        UnevenRoundedRectangle(topLeadingRadius: size * 0.12, bottomLeadingRadius: 4,
                               bottomTrailingRadius: 4, topTrailingRadius: size * 0.12)
            .fill(LinearGradient(colors: [Color(white: 0.16), Color(white: 0.28)], startPoint: .top, endPoint: .bottom))
            .frame(width: size * 0.56, height: size * 0.3)
    }

    private var sideButtons: some View {
        VStack(spacing: size * 0.14) {
            Capsule().fill(Color(white: 0.55)).frame(width: size * 0.05, height: size * 0.12)
            Capsule().fill(Color(white: 0.55)).frame(width: size * 0.05, height: size * 0.12)
        }
        .offset(x: size * 0.5)
    }

    private var face: some View {
        TimelineView(.everyMinute) { context in
            ZStack {
                RadialGradient(colors: [Color(red: 0.08, green: 0.12, blue: 0.24), .black],
                               center: .center, startRadius: 4, endRadius: size * 0.5)
                Circle()
                    .trim(from: 0, to: tone == .connected ? 1 : tone == .working ? 0.55 : 0.12)
                    .stroke(tone.color, style: StrokeStyle(lineWidth: size * 0.025, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(size * 0.04)
                    .animation(.smooth, value: tone)
                VStack(spacing: size * 0.01) {
                    Image(systemName: tone == .connected ? "iphone.radiowaves.left.and.right" : "iphone.slash")
                        .font(.system(size: size * 0.08, weight: .semibold))
                        .foregroundStyle(tone.color)
                    Text(context.date, format: .dateTime.hour(.defaultDigits(amPM: .omitted)).minute())
                        .font(.system(size: size * 0.2, weight: .light, design: .rounded))
                        .lineLimit(1).minimumScaleFactor(0.6)
                        .monospacedDigit()
                        .foregroundStyle(.white)
                    Text(context.date, format: .dateTime.weekday(.abbreviated).day())
                        .font(.system(size: size * 0.065, weight: .medium))
                        .foregroundStyle(Theme.accent)
                        .textCase(.uppercase)
                }
            }
        }
    }
}

#Preview {
    WatchHeroView(tone: .connected, status: "Conectado").padding().background(Theme.heroTop)
}
