import Foundation

/// Strict JSON for P2 DATA plaintext (draft 3.3): UTF-8 without BOM, depth <= 8, duplicate keys
/// rejected, integers within +-(2^53-1), no non-finite numbers. Objects keep insertion order.
public indirect enum P2JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([P2JSON])
    case object([(String, P2JSON)])

    public static func == (a: P2JSON, b: P2JSON) -> Bool {
        switch (a, b) {
        case (.null, .null): true
        case let (.bool(x), .bool(y)): x == y
        case let (.int(x), .int(y)): x == y
        case let (.double(x), .double(y)): x == y
        case let (.string(x), .string(y)): x == y
        case let (.array(x), .array(y)): x == y
        case let (.object(x), .object(y)): x.count == y.count && zip(x, y).allSatisfy { $0.0 == $1.0 && $0.1 == $1.1 }
        default: false
        }
    }

    public subscript(key: String) -> P2JSON? {
        guard case .object(let pairs) = self else { return nil }
        return pairs.first { $0.0 == key }?.1
    }
    public var stringValue: String? { if case .string(let s) = self { s } else { nil } }
    public var intValue: Int64? { if case .int(let v) = self { v } else { nil } }
    public var boolValue: Bool? { if case .bool(let b) = self { b } else { nil } }
    public var arrayValue: [P2JSON]? { if case .array(let a) = self { a } else { nil } }
    public var numberValue: Double? {
        switch self { case .int(let v): Double(v); case .double(let d): d; default: nil }
    }
    public var isObject: Bool { if case .object = self { true } else { false } }
}

public enum P2StrictJSON {
    public static let maxDepth = 8
    public static let maxSafe: Int64 = 9_007_199_254_740_991

    public static func parse(_ bytes: [UInt8]) throws -> P2JSON {
        if bytes.count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF { throw P2Error.invalidPayload }
        guard let text = String(validatingUTF8: bytes.map { CChar(bitPattern: $0) } + [0]), !bytes.contains(0),
              text.utf8.count == bytes.count else { throw P2Error.invalidPayload }
        var parser = Parser(units: Array(text.unicodeScalars))
        let value = try parser.value(depth: 0)
        parser.skip()
        guard parser.index == parser.units.count else { throw P2Error.invalidPayload }
        return value
    }

    public static func encode(_ value: P2JSON) throws -> [UInt8] {
        var out = ""
        try write(value, into: &out, depth: 0)
        return Array(out.utf8)
    }

    private static func write(_ value: P2JSON, into out: inout String, depth: Int) throws {
        guard depth <= maxDepth else { throw P2Error.invalidPayload }
        switch value {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let v):
            guard v <= maxSafe, v >= -maxSafe else { throw P2Error.invalidPayload }
            out += String(v)
        case .double(let d):
            guard d.isFinite else { throw P2Error.invalidPayload }
            out += "\(d)"
        case .string(let s): quote(s, into: &out)
        case .array(let items):
            out += "["
            for (i, item) in items.enumerated() { if i > 0 { out += "," }; try write(item, into: &out, depth: depth + 1) }
            out += "]"
        case .object(let pairs):
            out += "{"
            var seen = Set<String>()
            for (i, pair) in pairs.enumerated() {
                guard seen.insert(pair.0).inserted else { throw P2Error.invalidPayload }
                if i > 0 { out += "," }
                quote(pair.0, into: &out); out += ":"
                try write(pair.1, into: &out, depth: depth + 1)
            }
            out += "}"
        }
    }

    private static func quote(_ s: String, into out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 { out += String(format: "\\u%04x", scalar.value) } else { out.unicodeScalars.append(scalar) }
            }
        }
        out += "\""
    }

    private struct Parser {
        let units: [Unicode.Scalar]
        var index = 0
        init(units: [Unicode.Scalar]) { self.units = units }

        mutating func skip() { while index < units.count, " \t\r\n".unicodeScalars.contains(units[index]) { index += 1 } }
        func bad() -> P2Error { .invalidPayload }

        mutating func value(depth: Int) throws -> P2JSON {
            guard depth <= P2StrictJSON.maxDepth else { throw bad() }
            skip()
            guard index < units.count else { throw bad() }
            switch units[index] {
            case "{": return try object(depth: depth)
            case "[": return try array(depth: depth)
            case "\"": return .string(try string())
            case "t": try literal("true"); return .bool(true)
            case "f": try literal("false"); return .bool(false)
            case "n": try literal("null"); return .null
            case "-", "0"..."9": return try number()
            default: throw bad()
            }
        }

        mutating func literal(_ word: String) throws {
            let scalars = Array(word.unicodeScalars)
            guard index + scalars.count <= units.count, Array(units[index..<(index + scalars.count)]) == scalars else { throw bad() }
            index += scalars.count
        }

        mutating func object(depth: Int) throws -> P2JSON {
            index += 1
            var pairs: [(String, P2JSON)] = []; var seen = Set<String>()
            skip()
            if index < units.count, units[index] == "}" { index += 1; return .object(pairs) }
            while true {
                skip()
                guard index < units.count, units[index] == "\"" else { throw bad() }
                let key = try string()
                skip()
                guard index < units.count, units[index] == ":" else { throw bad() }
                index += 1
                guard seen.insert(key).inserted else { throw bad() }
                pairs.append((key, try value(depth: depth + 1)))
                skip()
                guard index < units.count else { throw bad() }
                if units[index] == "," { index += 1; continue }
                if units[index] == "}" { index += 1; return .object(pairs) }
                throw bad()
            }
        }

        mutating func array(depth: Int) throws -> P2JSON {
            index += 1
            var items: [P2JSON] = []
            skip()
            if index < units.count, units[index] == "]" { index += 1; return .array(items) }
            while true {
                items.append(try value(depth: depth + 1))
                skip()
                guard index < units.count else { throw bad() }
                if units[index] == "," { index += 1; continue }
                if units[index] == "]" { index += 1; return .array(items) }
                throw bad()
            }
        }

        mutating func string() throws -> String {
            index += 1
            var utf16: [UInt16] = []
            while true {
                guard index < units.count else { throw bad() }
                let c = units[index]; index += 1
                if c == "\"" { return try finish(utf16) }
                if c.value < 0x20 { throw bad() }
                if c == "\\" {
                    guard index < units.count else { throw bad() }
                    let e = units[index]; index += 1
                    switch e {
                    case "\"", "\\", "/": utf16.append(UInt16(e.value))
                    case "b": utf16.append(8)
                    case "f": utf16.append(12)
                    case "n": utf16.append(10)
                    case "r": utf16.append(13)
                    case "t": utf16.append(9)
                    case "u":
                        guard index + 4 <= units.count, let v = UInt16(String(String.UnicodeScalarView(units[index..<(index + 4)])), radix: 16) else { throw bad() }
                        utf16.append(v); index += 4
                    default: throw bad()
                    }
                } else {
                    utf16.append(contentsOf: String(c).utf16)
                }
            }
        }

        func finish(_ utf16: [UInt16]) throws -> String {
            guard let s = String(utf16CodeUnits: utf16, count: utf16.count) as String?,
                  Array(s.utf16) == utf16 else { throw bad() }   // lone surrogates do not round-trip
            return s
        }

        mutating func number() throws -> P2JSON {
            let start = index
            if units[index] == "-" { index += 1 }
            while index < units.count, ("0"..."9").contains(units[index]) { index += 1 }
            var isInt = true
            if index < units.count, units[index] == "." {
                isInt = false; index += 1
                while index < units.count, ("0"..."9").contains(units[index]) { index += 1 }
            }
            if index < units.count, units[index] == "e" || units[index] == "E" {
                isInt = false; index += 1
                if index < units.count, units[index] == "+" || units[index] == "-" { index += 1 }
                while index < units.count, ("0"..."9").contains(units[index]) { index += 1 }
            }
            let text = String(String.UnicodeScalarView(units[start..<index]))
            if isInt {
                guard let v = Int64(text), v <= P2StrictJSON.maxSafe, v >= -P2StrictJSON.maxSafe else { throw bad() }
                return .int(v)
            }
            guard let d = Double(text), d.isFinite else { throw bad() }
            return .double(d)
        }
    }
}
