package dev.wristbridge.protocol

import java.io.File

fun hex(s: String): ByteArray {
    val clean = s.replace(" ", "")
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

fun repoFile(path: String): File {
    val root = System.getProperty("wristbridge.repoRoot") ?: error("wristbridge.repoRoot not set")
    return File(root, path)
}

/** Minimal JSON reader for fixtures, so expectations never depend on production code. */
object MiniJson {
    fun parse(text: String): Any? = Reader(text).run { val v = value(); ws(); require(i == s.length); v }

    private class Reader(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("bad json at $i")
            }
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); require(s[i++] == ':')
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i++] == ']') return l
            }
        }

        fun str(): String {
            require(s[i++] == '"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                        }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Long {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i).toLong()
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.list(): List<Any?> = this as List<Any?>
