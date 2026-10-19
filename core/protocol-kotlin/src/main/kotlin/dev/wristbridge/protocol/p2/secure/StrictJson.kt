package dev.wristbridge.protocol.p2.secure

import dev.wristbridge.protocol.p2.P2Error
import dev.wristbridge.protocol.p2.P2Exception

/**
 * Minimal strict JSON for P2 DATA plaintext (draft section 3.3): UTF-8 without BOM, depth <= 8, duplicate keys
 * rejected, integers within +-(2^53-1), no non-finite numbers. Values: Map, List, String, Long, Double, Boolean, null.
 */
object StrictJson {
    const val MAX_DEPTH = 8
    const val MAX_SAFE = 9_007_199_254_740_991L

    private fun bad(msg: String): Nothing = throw P2Exception(P2Error.INVALID_PAYLOAD, msg)

    fun parse(bytes: ByteArray): Any? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bad("BOM")
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            bad("invalid UTF-8")
        }
        val p = Parser(text)
        val v = p.value(0)
        p.skip()
        if (p.i != text.length) bad("trailing data")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) bad("too deep")
            skip()
            if (i >= s.length) bad("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else bad("unexpected character")
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) bad("bad literal")
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skip()
                if (i >= s.length || s[i] != '"') bad("key expected")
                val k = str()
                skip()
                if (i >= s.length || s[i] != ':') bad("colon expected")
                i++
                if (m.containsKey(k)) bad("duplicate key")
                m[k] = value(depth + 1)
                skip()
                if (i >= s.length) bad("unexpected end")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                bad("object syntax")
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l += value(depth + 1)
                skip()
                if (i >= s.length) bad("unexpected end")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                bad("array syntax")
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) bad("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> bad("control character")
                    c == '\\' -> {
                        if (i >= s.length) bad("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) bad("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: bad("bad unicode escape"))
                                i += 4
                            }
                            else -> bad("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isInt = true
            if (i < s.length && s[i] == '.') { isInt = false; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val t = s.substring(st, i)
            if (isInt) {
                val v = t.toLongOrNull() ?: bad("integer out of range")
                if (v > MAX_SAFE || v < -MAX_SAFE) bad("integer outside safe range")
                return v
            }
            val d = t.toDoubleOrNull() ?: bad("bad number")
            if (d.isNaN() || d.isInfinite()) bad("non-finite number")
            return d
        }
    }

    fun encode(v: Any?): ByteArray {
        val sb = StringBuilder()
        write(sb, v, 0)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun write(sb: StringBuilder, v: Any?, depth: Int) {
        if (depth > MAX_DEPTH) bad("too deep")
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int -> sb.append(v)
            is Long -> { if (v > MAX_SAFE || v < -MAX_SAFE) bad("integer outside safe range"); sb.append(v) }
            is Double -> { if (v.isNaN() || v.isInfinite()) bad("non-finite number"); sb.append(v) }
            is String -> quote(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k as String)
                    sb.append(':')
                    write(sb, x, depth + 1)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { idx, x -> if (idx > 0) sb.append(','); write(sb, x, depth + 1) }
                sb.append(']')
            }
            else -> bad("unsupported type")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
