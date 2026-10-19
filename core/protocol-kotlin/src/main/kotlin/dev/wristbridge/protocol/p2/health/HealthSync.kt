package dev.wristbridge.protocol.p2.health

import dev.wristbridge.protocol.p2.P2Error
import dev.wristbridge.protocol.p2.P2Exception

/**
 * Health sync messages (proposal P7 + draft P2 section 3.3). The iPhone pulls by cursor; the watch answers with ordered
 * batches; the iPhone confirms ("ack") only after saving. JSON envelope: kind, requestId, capability, payload.
 * Capability "health.sync" is NOT FROZEN and must be authorised by an authenticated session (draft section 8.7).
 */
data class HealthSample(
    /** Watch-local, strictly increasing delivery cursor. */
    val cursor: Long,
    val metric: String,
    val startMs: Long,
    val endMs: Long,
    val value: Double,
    /** Stable id for idempotent storage on the receiver. */
    val sampleId: String,
)

interface HealthSource {
    fun samplesAfter(cursor: Long, limit: Int): List<HealthSample>
    fun hasMoreAfter(cursor: Long): Boolean

    /** The iPhone saved everything up to and including [through]. */
    fun acknowledge(through: Long)
}

object HealthSync {
    const val CAPABILITY = "health.sync"
    const val MAX_SAMPLES_PER_BATCH = 200
    const val MAX_ID_CHARS = 128
    val METRICS = setOf("steps_daily_snapshot", "heart_rate_bpm")

    private fun invalid(m: String): Nothing = throw P2Exception(P2Error.INVALID_PAYLOAD, m)

    fun requestCursor(requestId: Long, after: Long, max: Int): Map<String, Any?> = envelope(
        "req", requestId, mapOf("op" to "cursor", "after" to after, "max" to max.toLong()),
    )

    fun requestAck(requestId: Long, through: Long): Map<String, Any?> = envelope(
        "req", requestId, mapOf("op" to "ack", "through" to through),
    )

    private fun envelope(kind: String, requestId: Long, payload: Map<String, Any?>) = mapOf(
        "kind" to kind, "requestId" to requestId, "capability" to CAPABILITY, "payload" to payload,
    )

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?, what: String): Map<String, Any?> = v as? Map<String, Any?> ?: invalid("$what must be an object")

    private fun long(m: Map<String, Any?>, k: String): Long = m[k] as? Long ?: invalid("$k must be an integer")

    /** Watch side: answers one request. Unknown capability or operation is rejected without effect. */
    fun respond(request: Any?, source: HealthSource): Map<String, Any?> {
        val env = obj(request, "message")
        if (env["kind"] != "req") invalid("kind must be req")
        val id = long(env, "requestId")
        if (env["capability"] != CAPABILITY) throw P2Exception(P2Error.UNKNOWN_CAPABILITY, "capability")
        val p = obj(env["payload"], "payload")
        return when (p["op"]) {
            "cursor" -> {
                val after = long(p, "after")
                val max = long(p, "max")
                if (after < 0 || max !in 1..MAX_SAMPLES_PER_BATCH.toLong()) invalid("cursor range")
                val list = source.samplesAfter(after, max.toInt())
                var prev = after
                for (s in list) {
                    if (s.cursor <= prev) invalid("source returned unordered cursors")
                    prev = s.cursor
                }
                val last = list.lastOrNull()?.cursor ?: after
                envelope(
                    "res", id,
                    mapOf(
                        "op" to "batch", "after" to after, "last" to last,
                        "more" to (list.isNotEmpty() && source.hasMoreAfter(last)),
                        "samples" to list.map {
                            mapOf("c" to it.cursor, "m" to it.metric, "s" to it.startMs, "e" to it.endMs, "v" to it.value, "id" to it.sampleId)
                        },
                    ),
                )
            }
            "ack" -> {
                val through = long(p, "through")
                if (through < 0) invalid("ack range")
                source.acknowledge(through)
                envelope("res", id, mapOf("op" to "acked", "through" to through))
            }
            else -> invalid("unknown op")
        }
    }

    /** iPhone side (reference for tests and for the Swift port): validates a batch response. */
    fun parseBatch(response: Any?): List<HealthSample> {
        val env = obj(response, "message")
        if (env["kind"] != "res" || env["capability"] != CAPABILITY) invalid("not a health response")
        val p = obj(env["payload"], "payload")
        if (p["op"] != "batch") invalid("not a batch")
        val after = long(p, "after")
        val raw = p["samples"] as? List<*> ?: invalid("samples must be an array")
        if (raw.size > MAX_SAMPLES_PER_BATCH) throw P2Exception(P2Error.OVERSIZE, "too many samples")
        var prev = after
        return raw.map {
            val m = obj(it, "sample")
            val s = HealthSample(
                cursor = long(m, "c"),
                metric = m["m"] as? String ?: invalid("metric"),
                startMs = long(m, "s"),
                endMs = long(m, "e"),
                value = (m["v"] as? Number)?.toDouble() ?: invalid("value"),
                sampleId = m["id"] as? String ?: invalid("id"),
            )
            if (s.metric !in METRICS) invalid("unknown metric")
            if (s.sampleId.isEmpty() || s.sampleId.length > MAX_ID_CHARS) invalid("id length")
            if (s.endMs < s.startMs || s.value.isNaN() || s.value.isInfinite()) invalid("sample range")
            if (s.cursor <= prev) invalid("cursor not increasing")
            prev = s.cursor
            s
        }
    }

    fun batchHasMore(response: Any?): Boolean = obj(obj(response, "message")["payload"], "payload")["more"] == true
}
