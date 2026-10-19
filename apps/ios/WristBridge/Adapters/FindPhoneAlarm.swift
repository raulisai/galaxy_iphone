import AVFoundation
import Combine
import Foundation

/// Plays a loud, repeating alarm so the user can find this iPhone. Works today as a
/// local test; the watch-initiated trigger is wired in only after the secure channel and
/// the "find iPhone" command exist (see docs/apple/health-find-integration-status.md).
/// The system volume cannot be raised by an app, and the silent switch is bypassed by
/// the playback category.
@MainActor
final class FindPhoneAlarm: ObservableObject {
    enum Reason: String { case localTest, watchRequest }

    static let shared = FindPhoneAlarm()
    static let maxSeconds = 60

    @Published private(set) var isRinging = false
    @Published private(set) var remaining = FindPhoneAlarm.maxSeconds
    @Published private(set) var message = "Listo para probar. El volumen lo controla el iPhone."
    private(set) var lastReason: Reason?

    private var engine: AVAudioEngine?
    private var countdown: Task<Void, Never>?
    private var interruption: NSObjectProtocol?

    func start(reason: Reason) {
        guard !isRinging else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
        } catch {
            message = "No se pudo activar el audio del iPhone (\(error.localizedDescription))."
            return
        }
        let engine = AVAudioEngine()
        let rate = 44_100.0
        var phase = 0.0
        var clock = 0.0
        let node = AVAudioSourceNode { _, _, frames, buffers -> OSStatus in
            let list = UnsafeMutableAudioBufferListPointer(buffers)
            for frame in 0..<Int(frames) {
                // 0.25 s alternating 1.0 kHz / 1.5 kHz, then a short pause: easy to localize.
                let slot = Int(clock / 0.25) % 5
                let frequency = slot % 2 == 0 ? 1_000.0 : 1_500.0
                let audible = slot < 4
                phase += 2 * .pi * frequency / rate
                if phase > 2 * .pi { phase -= 2 * .pi }
                let value = audible ? Float(sin(phase)) * 0.9 : 0
                clock += 1 / rate
                for buffer in list { buffer.mData?.assumingMemoryBound(to: Float.self)[frame] = value }
            }
            return noErr
        }
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        do { try engine.start() } catch {
            message = "No se pudo reproducir el sonido."
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            return
        }
        self.engine = engine
        lastReason = reason
        isRinging = true
        remaining = Self.maxSeconds
        message = "Sonando. Se detiene sola en \(Self.maxSeconds) s o con Detener."
        interruption = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if raw == AVAudioSession.InterruptionType.began.rawValue { Task { @MainActor in self?.stop() } }
        }
        countdown = Task { [weak self] in
            while let self, !Task.isCancelled, self.remaining > 0 {
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
                self.remaining -= 1
            }
            self?.stop()
        }
    }

    func stop() {
        guard isRinging else { return }
        countdown?.cancel(); countdown = nil
        if let interruption { NotificationCenter.default.removeObserver(interruption) }
        interruption = nil
        engine?.stop(); engine = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        isRinging = false
        message = "Detenido."
    }
}
