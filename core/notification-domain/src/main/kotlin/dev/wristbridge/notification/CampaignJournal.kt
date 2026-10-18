package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.protocol.ancs.CategoryId
import dev.wristbridge.protocol.gatt.MonotonicClock

enum class CampaignEventType { SESSION_START, SESSION_END, CARD_RECEIVED, CARD_UPDATED, CARD_REMOVED, ACTION, LINK_DOWN, LINK_UP }

/** Who retired a card: the iPhone (ANCS Removed) or the user swiping it away on the watch. */
enum class RemovedBy { ANCS, WATCH_DISMISS }

/**
 * One journal entry. Only ids, timestamps, sizes, categories and the synthetic fixture tag; never a title,
 * subtitle, body, app name or caller. [wallMs] is epoch time (for correlation with the iPhone export),
 * [monoMs] a monotonic reading (for intervals within one boot).
 *
 * [elapsedMs]: on LINK_UP the outage since the preceding LINK_DOWN; on SESSION_START the time from the last
 * LINK_DOWN until ANCS was ready again. Null when there was no preceding outage.
 */
data class CampaignEvent(
    val seq: Long,
    val wallMs: Long,
    val monoMs: Long,
    val type: CampaignEventType,
    val generation: Long? = null,
    val uid: Long? = null,
    val category: String? = null,
    val fixture: FixtureTag? = null,
    val bytes: Int? = null,
    val alert: Boolean? = null,
    val by: RemovedBy? = null,
    val action: String? = null,
    val status: String? = null,
    val reason: String? = null,
    val elapsedMs: Long? = null,
)

/**
 * Derived campaign figures. Counters are cumulative since the last reset, also for events already dropped
 * from the bounded buffer ([dropped]).
 *
 * [duplicates]: a fixture tag received again in the same generation, or received again with an alert
 * (it would buzz twice). [replays]: a tag received again silently in a later generation (backlog rebuilt
 * after a reconnection); expected, not a loss or a duplicate.
 * [missingFixtures]: per expected run, fixture ids never received. Empty map without an expected list.
 */
data class CampaignStats(
    val totalEvents: Long = 0,
    val retainedEvents: Int = 0,
    val dropped: Long = 0,
    val sessionsStarted: Long = 0,
    val sessionsEnded: Long = 0,
    val received: Long = 0,
    val updated: Long = 0,
    val removedByAncs: Long = 0,
    val removedByWatch: Long = 0,
    val actions: Long = 0,
    /** Actions whose status was not REQUESTED (FAILED or UNAVAILABLE). */
    val actionsNotRequested: Long = 0,
    val linkDowns: Long = 0,
    val linkUps: Long = 0,
    val duplicates: Long = 0,
    val replays: Long = 0,
    /** Distinct fixtures received per runId. */
    val fixturesReceived: Map<String, Int> = emptyMap(),
    val missingFixtures: Map<String, List<String>> = emptyMap(),
    /** LINK_DOWN to LINK_UP (GATT connected again). */
    val reconnectP50Ms: Long? = null,
    val reconnectP95Ms: Long? = null,
    /** LINK_DOWN to SESSION_START (ANCS ready again). */
    val readyP50Ms: Long? = null,
    val readyP95Ms: Long? = null,
    /** Longest outage seen, including one still in progress. */
    val longestOutageMs: Long? = null,
    val lastOutageMs: Long? = null,
    /** Outage in progress right now, if the link is down. */
    val ongoingOutageMs: Long? = null,
    val firstWallMs: Long? = null,
    val lastWallMs: Long? = null,
)

/**
 * Bounded on-watch journal for 24/72 h campaigns (P2-08.K). Records what the watch received, presented and
 * retired so it can be correlated with the iPhone's exported run by (runId, fixtureId). Pure JVM, not
 * thread-safe: the owner serializes access.
 *
 * Memory: at most [capacity] events are kept (oldest dropped, counted in [CampaignStats.dropped]); the
 * counters, fixture tags (capped) and latency samples (capped) are aggregated separately so statistics
 * survive the drops. Content is never accepted: the API has no parameter for text.
 */
class CampaignJournal(
    private val clock: MonotonicClock,
    private val wallClockMs: () -> Long,
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private enum class Link { UP, DOWN }

    private val buffer = ArrayDeque<CampaignEvent>()
    private var seq = 0L
    private var dropped = 0L
    private var total = 0L
    private val counts = LongArray(CampaignEventType.entries.size)
    private var removedByAncs = 0L
    private var removedByWatch = 0L
    private var actionsNotRequested = 0L
    private var duplicates = 0L
    private var replays = 0L
    private val tagGenerations = HashMap<FixtureTag, MutableSet<Long>>()
    private val reconnect = ArrayDeque<Long>()
    private val ready = ArrayDeque<Long>()
    private var longestOutage: Long? = null
    private var lastOutage: Long? = null
    private var firstWall: Long? = null
    private var lastWall: Long? = null

    // Live state, not restored from disk (monotonic readings do not survive a reboot).
    private var openGeneration: Long? = null
    private var link: Link? = null
    private var downSinceMono = 0L
    private var readySinceMono: Long? = null

    fun sessionStart(generation: Long): CampaignEvent {
        val sinceDown = readySinceMono?.let { monoMs() - it }
        readySinceMono = null
        openGeneration = generation
        return add(CampaignEventType.SESSION_START, generation = generation, elapsedMs = sinceDown)
    }

    /** Ignored (null) unless [generation] is the open session, because several paths may close a link. */
    fun sessionEnd(generation: Long, reason: String): CampaignEvent? {
        if (openGeneration != generation) return null
        openGeneration = null
        return add(CampaignEventType.SESSION_END, generation = generation, reason = reason)
    }

    fun cardReceived(
        generation: Long, uid: Long, category: CategoryId, fixture: FixtureTag?, bytes: Int, alert: Boolean,
    ): CampaignEvent = add(
        CampaignEventType.CARD_RECEIVED, generation = generation, uid = uid, category = category.name,
        fixture = fixture, bytes = bytes, alert = alert,
    )

    fun cardUpdated(generation: Long, uid: Long, category: CategoryId, fixture: FixtureTag?, bytes: Int): CampaignEvent =
        add(
            CampaignEventType.CARD_UPDATED, generation = generation, uid = uid, category = category.name,
            fixture = fixture, bytes = bytes,
        )

    fun cardRemoved(generation: Long, uid: Long, by: RemovedBy): CampaignEvent =
        add(CampaignEventType.CARD_REMOVED, generation = generation, uid = uid, by = by)

    fun action(generation: Long, uid: Long, action: ActionId, status: ActionStatus): CampaignEvent =
        add(
            CampaignEventType.ACTION, generation = generation, uid = uid, action = action.name, status = status.name,
        )

    /** Null when the link was already down (repeated failures of a background reconnection are one outage). */
    fun linkDown(reason: String? = null): CampaignEvent? {
        if (link == Link.DOWN) return null
        val now = monoMs()
        link = Link.DOWN
        downSinceMono = now
        if (readySinceMono == null) readySinceMono = now
        return add(CampaignEventType.LINK_DOWN, reason = reason)
    }

    /** Null when the link was already up. The first LINK_UP after a LINK_DOWN carries the outage length. */
    fun linkUp(): CampaignEvent? {
        if (link == Link.UP) return null
        val outage = if (link == Link.DOWN) monoMs() - downSinceMono else null
        link = Link.UP
        return add(CampaignEventType.LINK_UP, elapsedMs = outage)
    }

    /** Re-inserts events read back from disk (oldest first). Live link/session state is left untouched. */
    fun restore(events: List<CampaignEvent>) {
        for (e in events) {
            append(e)
            if (e.seq >= seq) seq = e.seq + 1
        }
    }

    fun reset() {
        buffer.clear()
        seq = 0
        dropped = 0
        total = 0
        counts.fill(0)
        removedByAncs = 0
        removedByWatch = 0
        actionsNotRequested = 0
        duplicates = 0
        replays = 0
        tagGenerations.clear()
        reconnect.clear()
        ready.clear()
        longestOutage = null
        lastOutage = null
        firstWall = null
        lastWall = null
        openGeneration = null
        link = null
        readySinceMono = null
    }

    /** Retained events, oldest first. */
    fun events(): List<CampaignEvent> = buffer.toList()

    /**
     * @param expected optional expected fixtures per runId (for example F001..F050), used for
     *   [CampaignStats.missingFixtures].
     */
    fun stats(expected: Map<String, Set<String>> = emptyMap()): CampaignStats {
        val seenByRun = tagGenerations.keys.groupBy({ it.runId }, { it.fixtureId })
        val ongoing = if (link == Link.DOWN) monoMs() - downSinceMono else null
        val longest = listOfNotNull(longestOutage, ongoing).maxOrNull()
        val reconnectSorted = reconnect.sorted()
        val readySorted = ready.sorted()
        return CampaignStats(
            totalEvents = total,
            retainedEvents = buffer.size,
            dropped = dropped,
            sessionsStarted = counts[CampaignEventType.SESSION_START.ordinal],
            sessionsEnded = counts[CampaignEventType.SESSION_END.ordinal],
            received = counts[CampaignEventType.CARD_RECEIVED.ordinal],
            updated = counts[CampaignEventType.CARD_UPDATED.ordinal],
            removedByAncs = removedByAncs,
            removedByWatch = removedByWatch,
            actions = counts[CampaignEventType.ACTION.ordinal],
            actionsNotRequested = actionsNotRequested,
            linkDowns = counts[CampaignEventType.LINK_DOWN.ordinal],
            linkUps = counts[CampaignEventType.LINK_UP.ordinal],
            duplicates = duplicates,
            replays = replays,
            fixturesReceived = seenByRun.mapValues { it.value.toSet().size }.toSortedMap(),
            missingFixtures = expected.mapValues { (run, ids) -> (ids - (seenByRun[run]?.toSet() ?: emptySet())).sorted() }
                .toSortedMap(),
            reconnectP50Ms = percentile(reconnectSorted, 50),
            reconnectP95Ms = percentile(reconnectSorted, 95),
            readyP50Ms = percentile(readySorted, 50),
            readyP95Ms = percentile(readySorted, 95),
            longestOutageMs = longest,
            lastOutageMs = lastOutage,
            ongoingOutageMs = ongoing,
            firstWallMs = firstWall,
            lastWallMs = lastWall,
        )
    }

    /** Retained events as JSON Lines (one object per line, each terminated by a newline). */
    fun toJsonLines(): String {
        val sb = StringBuilder(buffer.size * 160)
        for (e in buffer) sb.append(toJsonLine(e)).append('\n')
        return sb.toString()
    }

    /** [stats] as a single JSON object. */
    fun summaryJson(expected: Map<String, Set<String>> = emptyMap()): String = summaryJson(stats(expected))

    private fun monoMs() = clock.nowNanos() / 1_000_000

    private fun add(
        type: CampaignEventType,
        generation: Long? = null,
        uid: Long? = null,
        category: String? = null,
        fixture: FixtureTag? = null,
        bytes: Int? = null,
        alert: Boolean? = null,
        by: RemovedBy? = null,
        action: String? = null,
        status: String? = null,
        reason: String? = null,
        elapsedMs: Long? = null,
    ): CampaignEvent {
        val e = CampaignEvent(
            seq++, wallClockMs(), monoMs(), type, generation, uid, category, fixture, bytes, alert, by, action, status,
            reason, elapsedMs,
        )
        append(e)
        return e
    }

    /** Buffers [e] (dropping the oldest beyond [capacity]) and folds it into the aggregates. */
    private fun append(e: CampaignEvent) {
        buffer.addLast(e)
        if (buffer.size > capacity) {
            buffer.removeFirst()
            dropped++
        }
        total++
        counts[e.type.ordinal]++
        if (firstWall == null) firstWall = e.wallMs
        lastWall = e.wallMs
        when (e.type) {
            CampaignEventType.CARD_RECEIVED -> e.fixture?.let { noteFixture(it, e) }
            CampaignEventType.CARD_REMOVED -> if (e.by == RemovedBy.WATCH_DISMISS) removedByWatch++ else removedByAncs++
            CampaignEventType.ACTION -> if (e.status != ActionStatus.REQUESTED.name) actionsNotRequested++
            CampaignEventType.LINK_UP -> e.elapsedMs?.let {
                sample(reconnect, it)
                lastOutage = it
                if (longestOutage?.let { max -> it > max } != false) longestOutage = it
            }
            CampaignEventType.SESSION_START -> e.elapsedMs?.let { sample(ready, it) }
            else -> Unit
        }
    }

    private fun noteFixture(tag: FixtureTag, e: CampaignEvent) {
        val gen = e.generation ?: -1L
        val gens = tagGenerations[tag]
        if (gens == null) {
            if (tagGenerations.size < MAX_TAGS) tagGenerations[tag] = mutableSetOf(gen)
            return
        }
        if (gen in gens || e.alert == true) duplicates++ else replays++
        gens += gen
    }

    private fun sample(into: ArrayDeque<Long>, value: Long) {
        into.addLast(value)
        if (into.size > MAX_SAMPLES) into.removeFirst()
    }

    companion object {
        const val DEFAULT_CAPACITY = 2000
        private const val MAX_TAGS = 10_000
        private const val MAX_SAMPLES = 2000

        /** Nearest-rank percentile of an ascending list; null when empty. */
        fun percentile(sorted: List<Long>, p: Int): Long? {
            if (sorted.isEmpty()) return null
            val rank = ((p * sorted.size + 99) / 100).coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        /** One event as a single-line JSON object. Absent fields are omitted. */
        fun toJsonLine(e: CampaignEvent): String {
            val w = JsonObjectWriter()
            w.num("seq", e.seq)
            w.num("wall", e.wallMs)
            w.num("mono", e.monoMs)
            w.str("type", e.type.name)
            w.num("gen", e.generation)
            w.num("uid", e.uid)
            w.str("cat", e.category)
            w.str("run", e.fixture?.runId)
            w.str("fix", e.fixture?.fixtureId)
            w.num("bytes", e.bytes?.toLong())
            w.bool("alert", e.alert)
            w.str("by", e.by?.name)
            w.str("action", e.action)
            w.str("status", e.status)
            w.str("reason", e.reason)
            w.num("ms", e.elapsedMs)
            return w.finish()
        }

        /** Parses a line written by [toJsonLine]; null for anything malformed. Unknown keys are ignored. */
        fun parseLine(line: String): CampaignEvent? {
            val m = JsonObjectReader(line).read() ?: return null
            val seq = m["seq"] as? Long ?: return null
            val wall = m["wall"] as? Long ?: return null
            val mono = m["mono"] as? Long ?: return null
            val type = (m["type"] as? String)?.let { n -> CampaignEventType.entries.firstOrNull { it.name == n } } ?: return null
            val run = m["run"] as? String
            val fix = m["fix"] as? String
            return CampaignEvent(
                seq = seq, wallMs = wall, monoMs = mono, type = type,
                generation = m["gen"] as? Long,
                uid = m["uid"] as? Long,
                category = m["cat"] as? String,
                fixture = if (run != null && fix != null) FixtureTag(run, fix) else null,
                bytes = (m["bytes"] as? Long)?.toInt(),
                alert = m["alert"] as? Boolean,
                by = (m["by"] as? String)?.let { n -> RemovedBy.entries.firstOrNull { it.name == n } },
                action = m["action"] as? String,
                status = m["status"] as? String,
                reason = m["reason"] as? String,
                elapsedMs = m["ms"] as? Long,
            )
        }

        /** Parses many lines, skipping blank and malformed ones (a torn last line after a crash is expected). */
        fun parseLines(text: String): List<CampaignEvent> =
            text.lineSequence().filter { it.isNotBlank() }.mapNotNull { parseLine(it) }.toList()

        fun summaryJson(s: CampaignStats): String {
            val w = JsonObjectWriter()
            w.num("events", s.totalEvents)
            w.num("retained", s.retainedEvents.toLong())
            w.num("dropped", s.dropped)
            w.num("sessionsStarted", s.sessionsStarted)
            w.num("sessionsEnded", s.sessionsEnded)
            w.num("received", s.received)
            w.num("updated", s.updated)
            w.num("removedByAncs", s.removedByAncs)
            w.num("removedByWatch", s.removedByWatch)
            w.num("actions", s.actions)
            w.num("actionsNotRequested", s.actionsNotRequested)
            w.num("linkDowns", s.linkDowns)
            w.num("linkUps", s.linkUps)
            w.num("duplicates", s.duplicates)
            w.num("replays", s.replays)
            w.raw("fixturesReceived", s.fixturesReceived.entries.joinToString(",", "{", "}") { "${JsonObjectWriter.quote(it.key)}:${it.value}" })
            w.raw(
                "missingFixtures",
                s.missingFixtures.entries.joinToString(",", "{", "}") { (run, ids) ->
                    "${JsonObjectWriter.quote(run)}:" + ids.joinToString(",", "[", "]") { id -> JsonObjectWriter.quote(id) }
                },
            )
            w.numOrNull("reconnectP50Ms", s.reconnectP50Ms)
            w.numOrNull("reconnectP95Ms", s.reconnectP95Ms)
            w.numOrNull("readyP50Ms", s.readyP50Ms)
            w.numOrNull("readyP95Ms", s.readyP95Ms)
            w.numOrNull("longestOutageMs", s.longestOutageMs)
            w.numOrNull("lastOutageMs", s.lastOutageMs)
            w.numOrNull("ongoingOutageMs", s.ongoingOutageMs)
            w.numOrNull("firstWallMs", s.firstWallMs)
            w.numOrNull("lastWallMs", s.lastWallMs)
            return w.finish()
        }
    }
}

/** Minimal JSON object builder (no libraries). Null values are omitted unless written with [numOrNull]. */
internal class JsonObjectWriter {
    private val sb = StringBuilder("{")
    private var first = true

    private fun key(k: String) {
        if (!first) sb.append(',')
        first = false
        sb.append(quote(k)).append(':')
    }

    fun num(k: String, v: Long?) {
        if (v == null) return
        key(k); sb.append(v)
    }

    fun numOrNull(k: String, v: Long?) {
        key(k); if (v == null) sb.append("null") else sb.append(v)
    }

    fun bool(k: String, v: Boolean?) {
        if (v == null) return
        key(k); sb.append(v)
    }

    fun str(k: String, v: String?) {
        if (v == null) return
        key(k); sb.append(quote(v))
    }

    /** [json] must already be valid JSON. */
    fun raw(k: String, json: String) {
        key(k); sb.append(json)
    }

    fun finish(): String = sb.append('}').toString()

    companion object {
        /** JSON string literal: quotes, backslash and every control character below 0x20 are escaped. */
        fun quote(s: String): String {
            val out = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c == '\b' -> out.append("\\b")
                    c == '\u000C' -> out.append("\\f")
                    c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}

/**
 * Reads one flat JSON object whose values are strings, integers, booleans or null (what
 * [JsonObjectWriter] emits). Anything else, or trailing garbage, yields null.
 */
internal class JsonObjectReader(private val s: String) {
    private var i = 0

    fun read(): Map<String, Any?>? {
        val out = HashMap<String, Any?>()
        skipWs()
        if (!eat('{')) return null
        skipWs()
        if (eat('}')) return if (atEnd()) out else null
        while (true) {
            skipWs()
            val k = string() ?: return null
            skipWs()
            if (!eat(':')) return null
            skipWs()
            if (i >= s.length) return null
            val v: Any? = when (s[i]) {
                '"' -> string() ?: return null
                't' -> if (s.startsWith("true", i)) { i += 4; true } else return null
                'f' -> if (s.startsWith("false", i)) { i += 5; false } else return null
                'n' -> if (s.startsWith("null", i)) { i += 4; null } else return null
                else -> integer() ?: return null
            }
            out[k] = v
            skipWs()
            if (eat(',')) continue
            if (eat('}')) return if (atEnd()) out else null
            return null
        }
    }

    private fun atEnd(): Boolean {
        skipWs()
        return i == s.length
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun eat(c: Char): Boolean {
        if (i < s.length && s[i] == c) { i++; return true }
        return false
    }

    private fun integer(): Long? {
        val start = i
        if (i < s.length && s[i] == '-') i++
        val digits = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == digits) return null
        return s.substring(start, i).toLongOrNull()
    }

    private fun string(): String? {
        if (!eat('"')) return null
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) return null
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 > s.length) return null
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                }
                c < ' ' -> return null
                else -> sb.append(c)
            }
        }
        return null
    }
}
