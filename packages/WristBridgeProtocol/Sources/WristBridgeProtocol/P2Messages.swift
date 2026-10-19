import Foundation

/// JSON envelopes of the own channel (draft revision 3 + docs/windows/handoff-apple-p2-health.md).
/// Envelope: {kind: "req"|"res", requestId, capability, payload}; a failed response carries `error` (wire name).
public enum P2Messages {
    public static let hello = "hello", healthSync = "health.sync", findPhone = "find.phone", findWatch = "find.watch"
    public static let watchSource = "watch-sensor"
    public static let maxSamplesPerBatch = 200

    public static func request(_ id: Int64, _ capability: String, _ payload: [(String, P2JSON)] = []) -> P2JSON {
        .object([("kind", .string("req")), ("requestId", .int(id)), ("capability", .string(capability)), ("payload", .object(payload))])
    }
    public static func response(_ id: Int64, _ capability: String, _ payload: [(String, P2JSON)]) -> P2JSON {
        .object([("kind", .string("res")), ("requestId", .int(id)), ("capability", .string(capability)), ("payload", .object(payload))])
    }
    public static func errorResponse(_ id: Int64, _ capability: String, _ error: P2Error) -> P2JSON {
        .object([("kind", .string("res")), ("requestId", .int(id)), ("capability", .string(capability)), ("error", .string(error.name))])
    }
    public static func helloRequest(_ id: Int64) -> P2JSON {
        request(id, hello, [("protocolMajor", .int(1)), ("protocolMinor", .int(0))])
    }
    public static func healthCursor(_ id: Int64, after: Int64, max: Int = maxSamplesPerBatch) -> P2JSON {
        request(id, healthSync, [("op", .string("cursor")), ("after", .int(after)), ("max", .int(Int64(max)))])
    }
    public static func healthAck(_ id: Int64, through: Int64) -> P2JSON {
        request(id, healthSync, [("op", .string("ack")), ("through", .int(through))])
    }

    public struct Envelope: Equatable {
        public let kind: String, requestId: Int64, capability: String
        public let payload: P2JSON?
        public let error: String?
    }
    public static func envelope(_ json: P2JSON) throws -> Envelope {
        guard let kind = json["kind"]?.stringValue, kind == "req" || kind == "res",
              let id = json["requestId"]?.intValue, let capability = json["capability"]?.stringValue else { throw P2Error.invalidPayload }
        return Envelope(kind: kind, requestId: id, capability: capability, payload: json["payload"], error: json["error"]?.stringValue)
    }

    public struct HealthBatch: Equatable {
        public let deviceId: String
        public let after: Int64
        public let last: Int64
        public let more: Bool
        public let samples: [HealthSample]
    }

    private static func asciiId(_ text: String, max: Int) -> Bool {
        !text.isEmpty && text.utf8.count <= max && text.unicodeScalars.allSatisfy {
            $0.isASCII && ($0.properties.isAlphabetic || ("0"..."9").contains(Character($0)) || "._:-".unicodeScalars.contains($0))
        }
    }

    /// Validates a `batch` response against what the watch promised and returns ledger samples. Anything
    /// off is rejected as a whole: a partially trusted batch is never saved.
    public static func parseBatch(_ json: P2JSON) throws -> HealthBatch {
        let env = try envelope(json)
        guard env.kind == "res", env.capability == healthSync, env.error == nil, let p = env.payload,
              p["op"]?.stringValue == "batch",
              let device = p["deviceId"]?.stringValue, asciiId(device, max: 64),
              let after = p["after"]?.intValue, let last = p["last"]?.intValue,
              let more = p["more"]?.boolValue, let raw = p["samples"]?.arrayValue else { throw P2Error.invalidPayload }
        guard raw.count <= maxSamplesPerBatch else { throw P2Error.oversize }
        guard last >= after else { throw P2Error.invalidPayload }
        var previous = after
        var samples: [HealthSample] = []
        for item in raw {
            guard let cursor = item["c"]?.intValue, let metricName = item["m"]?.stringValue,
                  let startMs = item["s"]?.intValue, let endMs = item["e"]?.intValue,
                  let value = item["v"]?.numberValue, let id = item["id"]?.stringValue,
                  let source = item["src"]?.stringValue, source == watchSource,
                  let version = item["ver"]?.intValue, version >= 1 else { throw P2Error.invalidPayload }
            guard let metric = HealthMetric(rawValue: metricName), metric == .stepsDaily || metric == .heartRate else { throw P2Error.invalidPayload }
            guard asciiId(id, max: 128), cursor > previous, cursor <= last, endMs > startMs, value.isFinite, value >= 0 else { throw P2Error.invalidPayload }
            previous = cursor
            var low: Double?, high: Double?
            if metric == .heartRate {
                guard let lo = item["min"]?.numberValue, let hi = item["max"]?.numberValue,
                      value >= 20, value <= 250, lo >= 20, hi <= 250, lo <= value, value <= hi else { throw P2Error.invalidPayload }
                low = lo; high = hi
            } else if item["min"] != nil || item["max"] != nil { throw P2Error.invalidPayload }
            samples.append(HealthSample(deviceId: device, metric: metric,
                                        start: Date(timeIntervalSince1970: Double(startMs) / 1000),
                                        end: Date(timeIntervalSince1970: Double(endMs) / 1000),
                                        value: value, minimum: low, maximum: high, source: source,
                                        syncId: id, syncVersion: Int(version)))
        }
        return HealthBatch(deviceId: device, after: after, last: last, more: more, samples: samples)
    }
}

/// iPhone side of `health.sync` (A-H01). Pull by cursor; save; and only then acknowledge.
/// Pure state machine: the caller feeds decrypted responses and sends what is returned.
public final class HealthSyncClient {
    public enum Status: Equatable { case idle, requesting, saving, upToDate, stalled(String) }
    public private(set) var status: Status = .idle
    public private(set) var totalSaved = 0

    private let loadCursor: () -> Int64
    private let saveCursor: (Int64) -> Void
    private let ingest: ([HealthSample]) -> Bool
    private var nextId: Int64 = 1
    private var pending: [Int64: String] = [:]
    private var pendingMore = false
    private var pendingLast: Int64 = 0

    /// `ingest` must persist and return true only when the whole batch is stored (HealthStore.ingest).
    public init(loadCursor: @escaping () -> Int64, saveCursor: @escaping (Int64) -> Void, ingest: @escaping ([HealthSample]) -> Bool) {
        self.loadCursor = loadCursor; self.saveCursor = saveCursor; self.ingest = ingest
    }

    public func start() -> [P2JSON] { requestBatch(after: loadCursor()) }

    private func requestBatch(after: Int64) -> [P2JSON] {
        let id = nextId; nextId += 1
        pending[id] = "cursor"; status = .requesting
        return [P2Messages.healthCursor(id, after: after)]
    }

    private func stall(_ reason: String) -> [P2JSON] { status = .stalled(reason); pending.removeAll(); return [] }

    /// Returns the messages to send next (possibly none).
    public func handle(response json: P2JSON) -> [P2JSON] {
        guard let env = try? P2Messages.envelope(json), env.kind == "res", env.capability == P2Messages.healthSync,
              let op = pending.removeValue(forKey: env.requestId) else { return [] }   // unknown/late response: ignore
        if let error = env.error { return stall("el reloj respondió \(error)") }
        if op == "cursor" {
            let batch: P2Messages.HealthBatch
            do { batch = try P2Messages.parseBatch(json) } catch { return stall("lote inválido del reloj") }
            guard batch.after == loadCursor() else { return stall("lote fuera de orden") }
            status = .saving
            if !batch.samples.isEmpty || batch.last > batch.after {
                guard ingest(batch.samples) else { return stall("el iPhone no pudo guardar el lote; no se confirmó") }
                totalSaved += batch.samples.count
            }
            if batch.last == batch.after { status = .upToDate; return [] }
            // Saved. Persist our cursor first, then acknowledge: a crash in between only repeats the ack.
            saveCursor(batch.last)
            pendingMore = batch.more; pendingLast = batch.last
            let id = nextId; nextId += 1
            pending[id] = "ack"
            return [P2Messages.healthAck(id, through: batch.last)]
        }
        // ack answered
        guard env.payload?["op"]?.stringValue == "acked", env.payload?["through"]?.intValue == pendingLast else { return stall("confirmación inesperada") }
        if pendingMore { return requestBatch(after: pendingLast) }
        status = .upToDate
        return []
    }
}
