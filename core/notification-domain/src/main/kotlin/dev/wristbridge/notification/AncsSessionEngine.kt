package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.protocol.ancs.AncsCommands
import dev.wristbridge.protocol.ancs.AncsError
import dev.wristbridge.protocol.ancs.AncsResponse
import dev.wristbridge.protocol.ancs.AncsUid
import dev.wristbridge.protocol.ancs.AppAttributeId
import dev.wristbridge.protocol.ancs.AssemblyFailure
import dev.wristbridge.protocol.ancs.AssemblyResult
import dev.wristbridge.protocol.ancs.DataSourceAssembler
import dev.wristbridge.protocol.ancs.EventFlag
import dev.wristbridge.protocol.ancs.EventId
import dev.wristbridge.protocol.ancs.ExpectedResponse
import dev.wristbridge.protocol.ancs.NotificationAttributeId
import dev.wristbridge.protocol.ancs.NotificationSourceEvent
import dev.wristbridge.protocol.ancs.NotificationSourceParse
import dev.wristbridge.protocol.ancs.NotificationSourceParser
import dev.wristbridge.protocol.contract.AncsLimits
import dev.wristbridge.protocol.gatt.GattOutcome
import dev.wristbridge.protocol.gatt.MonotonicClock

/**
 * Serial owner of one ANCS consumer (P1-07/P1-08, P2-01 foundation). Not thread-safe: the Android
 * adapter calls it from a single BLE thread. Every external input carries the session generation; inputs
 * from older generations never mutate the current session (U19). Only one attribute transaction is active
 * and it ends by complete response, error or monotonic deadline (U14/U15/U20). The engine is pure: it
 * returns [SessionEffect]s for the adapter to perform.
 */
class AncsSessionEngine(
    private val clock: MonotonicClock,
    private val limits: AncsLimits = AncsLimits(),
    private val writeTimeoutNanos: Long = 5_000_000_000L,
    private val responseTimeoutNanos: Long = 10_000_000_000L,
    /**
     * Last generation used by an earlier engine (previous service instance or process). Generations must keep
     * growing: cards and notification tags of older engines outlive them, and only a higher generation's
     * [SessionEffect.RemoveCardsBefore] retires them.
     */
    initialGeneration: Long = 0,
) {
    private sealed interface Fetch {
        data class Notification(val uid: AncsUid) : Fetch
        data class App(val appIdentifier: String) : Fetch

        /** PerformNotificationAction: completes on the confirmed write; ANCS sends no Data Source reply. */
        data class Action(val uid: AncsUid, val action: ActionId) : Fetch
    }

    private class Entry(
        val uid: AncsUid,
        var event: NotificationSourceEvent,
        var version: Int,
        var card: NotificationCard? = null,
    )

    private enum class Phase { WRITING, AWAITING_DATA }

    private class Transaction(
        val id: Long,
        val fetch: Fetch,
        val expected: ExpectedResponse?,
        val version: Int,
        var phase: Phase,
        var deadline: Long,
        var cancelled: Boolean = false,
    )

    var generation: Long = initialGeneration
        private set
    var sourceDeviceId: String? = null
        private set
    var active: Boolean = false
        private set
    var counters = SessionCounters()
        private set

    private val entries = LinkedHashMap<Long, Entry>()
    private val queue = ArrayDeque<Fetch>()
    private val appNames = HashMap<String, String>()
    private val requestedApps = HashSet<String>()
    private val assembler = DataSourceAssembler(limits.maxResponseBytes)
    private val notificationRequest = AncsCommands.defaultNotificationRequest(limits)
    private var tx: Transaction? = null
    private var nextTxId = 1L

    val pendingFetches: Int get() = queue.size
    val transactionActive: Boolean get() = tx != null
    val visibleCards: Int get() = entries.values.count { it.card != null }

    /**
     * Call only after Data Source and Notification Source subscriptions are confirmed. Cards from older
     * generations are removed; iOS replays existing notifications as PreExisting and they are rebuilt silently.
     */
    fun startSession(sourceDeviceId: String): List<SessionEffect> {
        clearSessionState()
        generation++
        this.sourceDeviceId = sourceDeviceId
        active = true
        return listOf(SessionEffect.RemoveCardsBefore(generation))
    }

    /** Ends [generation]. Visible cards stay until the next session replaces them. */
    fun endSession(generation: Long) {
        if (generation != this.generation || !active) return
        active = false
        clearSessionState()
    }

    fun onNotificationSource(generation: Long, bytes: ByteArray): List<SessionEffect> {
        if (!isCurrent(generation)) return emptyList()
        val parsed = NotificationSourceParser.parse(bytes)
        if (parsed !is NotificationSourceParse.Valid) {
            counters = counters.copy(nsInvalid = counters.nsInvalid + 1)
            return emptyList()
        }
        val e = parsed.event
        counters = counters.copy(
            nsEvents = counters.nsEvents + 1,
            preExisting = counters.preExisting + if (e.preExisting) 1 else 0,
        )
        val out = mutableListOf<SessionEffect>()
        when (e.event) {
            EventId.ADDED, EventId.MODIFIED -> upsert(e, out)
            EventId.REMOVED -> remove(e.uid, out)
        }
        pump(out)
        return out
    }

    /**
     * P2-07: requests the iPhone to perform the positive or negative action of a visible card. Only
     * actions the iPhone advertised for that notification (flag plus label) are accepted. Success means
     * "requested": ANCS confirms the write, not the final effect (Removed/Modified reports that later).
     */
    fun performAction(key: NotificationKey, action: ActionId): List<SessionEffect> {
        val unavailable = listOf(SessionEffect.ActionResult(key, action, ActionStatus.UNAVAILABLE))
        if (!isCurrent(key.sessionGeneration)) return unavailable
        val entry = entries[key.uid.value] ?: return unavailable
        if (!entry.offers(action)) return unavailable
        val fetch = Fetch.Action(key.uid, action)
        if (fetch in queue) return emptyList()
        // Ahead of attribute fetches: a call must be answered before its backlog is read.
        queue.addFirst(fetch)
        val out = mutableListOf<SessionEffect>()
        pump(out)
        return out
    }

    fun onDataSource(generation: Long, bytes: ByteArray): List<SessionEffect> {
        if (!isCurrent(generation)) return emptyList()
        val t = tx
        if (t == null || t.expected == null) {
            counters = counters.copy(unsolicitedData = counters.unsolicitedData + 1)
            return emptyList()
        }
        val out = mutableListOf<SessionEffect>()
        when (val r = assembler.feed(bytes)) {
            AssemblyResult.NeedMore -> {
                // Progress after the write: keep waiting, bounded by a fresh no-progress deadline.
                t.phase = Phase.AWAITING_DATA
                t.deadline = clock.nowNanos() + responseTimeoutNanos
            }
            is AssemblyResult.Complete -> {
                tx = null
                applyResponse(t, r.response, out)
                pump(out)
            }
            is AssemblyResult.Failed -> when (r.failure) {
                is AssemblyFailure.UidMismatch, is AssemblyFailure.CommandMismatch,
                is AssemblyFailure.AppMismatch, AssemblyFailure.Unsolicited -> {
                    // Late bytes of an expired transaction (U10): drop them and keep waiting for ours.
                    counters = counters.copy(discardedResponses = counters.discardedResponses + 1)
                    t.expected?.let(assembler::begin)
                }
                is AssemblyFailure.UnexpectedAttribute, is AssemblyFailure.ResponseTooLarge -> {
                    fail(t, out)
                    pump(out)
                }
            }
        }
        return out
    }

    /** Result of the GATT write for transaction [transactionId] (from [SessionEffect.WriteControlPoint]). */
    fun onControlPointWritten(generation: Long, transactionId: Long, outcome: GattOutcome): List<SessionEffect> {
        if (!isCurrent(generation)) return emptyList()
        val t = tx
        if (t == null || t.id != transactionId || t.phase != Phase.WRITING) return emptyList()
        val out = mutableListOf<SessionEffect>()
        when (outcome) {
            GattOutcome.Success -> if (t.fetch is Fetch.Action) {
                tx = null
                counters = counters.copy(actionsRequested = counters.actionsRequested + 1)
                out += SessionEffect.ActionResult(key(t.fetch.uid), t.fetch.action, ActionStatus.REQUESTED)
                pump(out)
            } else {
                // A confirmed write is not an ANCS response (U15): wait for Data Source.
                t.phase = Phase.AWAITING_DATA
                t.deadline = clock.nowNanos() + responseTimeoutNanos
            }
            is GattOutcome.Failed -> {
                if (AncsError.fromAttStatus(outcome.status) != null) {
                    counters = counters.copy(ancsErrors = counters.ancsErrors + 1)
                }
                fail(t, out)
                pump(out)
            }
            is GattOutcome.Rejected, GattOutcome.TimedOut, GattOutcome.Cancelled -> {
                fail(t, out)
                pump(out)
            }
        }
        return out
    }

    fun onTick(): List<SessionEffect> {
        val t = tx ?: return emptyList()
        if (clock.nowNanos() - t.deadline < 0) return emptyList()
        counters = counters.copy(fetchTimedOut = counters.fetchTimedOut + 1)
        val out = mutableListOf<SessionEffect>()
        fail(t, out, countFailure = false)
        pump(out)
        return out
    }

    fun nextDeadlineNanos(): Long? = tx?.deadline

    private fun isCurrent(generation: Long): Boolean {
        if (active && generation == this.generation) return true
        counters = counters.copy(staleInputsIgnored = counters.staleInputsIgnored + 1)
        return false
    }

    private fun upsert(e: NotificationSourceEvent, out: MutableList<SessionEffect>) {
        val existing = entries[e.uid.value]
        if (existing == null) {
            counters = counters.copy(added = counters.added + 1)
            val entry = Entry(e.uid, e, version = 1)
            entries[e.uid.value] = entry
            enqueueNotification(entry, out)
        } else {
            counters = counters.copy(modified = counters.modified + 1)
            existing.event = e
            existing.version++
            val t = tx
            val fetchingNow = t != null && t.fetch == Fetch.Notification(e.uid) && !t.cancelled
            // Coalesce: an active fetch triggers one refetch on completion; a queued fetch already covers it.
            if (!fetchingNow) enqueueNotification(existing, out)
        }
    }

    private fun enqueueNotification(entry: Entry, out: MutableList<SessionEffect>) {
        val fetch = Fetch.Notification(entry.uid)
        if (fetch in queue) return
        if (queue.count { it is Fetch.Notification } >= limits.maxPendingUids) {
            counters = counters.copy(queueOverflow = counters.queueOverflow + 1)
            if (entry.card == null) postFallback(entry, out)
            return
        }
        queue.addLast(fetch)
    }

    private fun remove(uid: AncsUid, out: MutableList<SessionEffect>) {
        counters = counters.copy(removed = counters.removed + 1)
        val entry = entries.remove(uid.value) ?: return
        queue.remove(Fetch.Notification(uid))
        queue.removeAll { it is Fetch.Action && it.uid == uid }
        tx?.let { if (it.fetch == Fetch.Notification(uid)) it.cancelled = true }
        if (entry.card != null) {
            counters = counters.copy(cardsRemoved = counters.cardsRemoved + 1)
            out += SessionEffect.RemoveCard(key(uid))
        }
    }

    private fun pump(out: MutableList<SessionEffect>) {
        while (tx == null && active) {
            val fetch = queue.removeFirstOrNull() ?: return
            if (fetch is Fetch.Action) {
                val entry = entries[fetch.uid.value]
                if (entry == null || !entry.offers(fetch.action)) {
                    out += SessionEffect.ActionResult(key(fetch.uid), fetch.action, ActionStatus.UNAVAILABLE)
                    continue
                }
                tx = Transaction(
                    id = nextTxId++, fetch = fetch, expected = null, version = entry.version,
                    phase = Phase.WRITING, deadline = clock.nowNanos() + writeTimeoutNanos,
                )
                out += SessionEffect.WriteControlPoint(generation, tx!!.id, AncsCommands.performNotificationAction(fetch.uid, fetch.action))
                continue
            }
            val (bytes, expected, version) = when (fetch) {
                is Fetch.Notification -> {
                    val entry = entries[fetch.uid.value] ?: continue
                    Triple(
                        AncsCommands.getNotificationAttributes(fetch.uid, notificationRequest),
                        ExpectedResponse.notification(fetch.uid, notificationRequest),
                        entry.version,
                    )
                }
                is Fetch.App -> Triple(
                    AncsCommands.getAppAttributes(fetch.appIdentifier, listOf(AppAttributeId.DISPLAY_NAME)),
                    ExpectedResponse.app(fetch.appIdentifier, listOf(AppAttributeId.DISPLAY_NAME)),
                    0,
                )
                is Fetch.Action -> continue
            }
            val t = Transaction(
                id = nextTxId++, fetch = fetch, expected = expected, version = version,
                phase = Phase.WRITING, deadline = clock.nowNanos() + writeTimeoutNanos,
            )
            tx = t
            assembler.begin(expected)
            counters = counters.copy(fetchStarted = counters.fetchStarted + 1)
            out += SessionEffect.WriteControlPoint(generation, t.id, bytes)
        }
    }

    private fun applyResponse(t: Transaction, response: AncsResponse, out: MutableList<SessionEffect>) {
        counters = counters.copy(fetchCompleted = counters.fetchCompleted + 1)
        when (response) {
            is AncsResponse.App -> {
                val name = response.attributes[AppAttributeId.DISPLAY_NAME.raw]?.text.orEmpty()
                appNames[response.appIdentifier] = name
                for (entry in entries.values) {
                    val card = entry.card ?: continue
                    if (card.appIdentifier == response.appIdentifier && card.appName != name) {
                        entry.card = card.copy(appName = name)
                        counters = counters.copy(cardsUpdated = counters.cardsUpdated + 1)
                        out += SessionEffect.UpdateCard(entry.card!!)
                    }
                }
            }
            is AncsResponse.Notification -> {
                val entry = entries[response.uid.value]
                if (t.cancelled || entry == null) {
                    // Removed during fetch: the late response must not resurrect the card (U18/N06).
                    counters = counters.copy(discardedResponses = counters.discardedResponses + 1)
                    return
                }
                val card = buildCard(entry, response)
                publish(entry, card, out)
                if (entry.version > t.version) enqueueNotification(entry, out)
                val app = card.appIdentifier
                if (app != null && app !in appNames && requestedApps.add(app)) queue.addLast(Fetch.App(app))
            }
        }
    }

    private fun fail(t: Transaction, out: MutableList<SessionEffect>, countFailure: Boolean = true) {
        if (countFailure) counters = counters.copy(fetchFailed = counters.fetchFailed + 1)
        assembler.reset()
        tx = null
        when (val f = t.fetch) {
            is Fetch.App -> appNames[f.appIdentifier] = ""
            is Fetch.Notification -> {
                val entry = entries[f.uid.value] ?: return
                if (!t.cancelled && entry.card == null) postFallback(entry, out)
            }
            is Fetch.Action -> {
                counters = counters.copy(actionsFailed = counters.actionsFailed + 1)
                out += SessionEffect.ActionResult(key(f.uid), f.action, ActionStatus.FAILED)
            }
        }
    }

    private fun Entry.offers(action: ActionId): Boolean {
        val c = card ?: return false
        return when (action) {
            ActionId.POSITIVE -> c.positiveActionLabel != null
            ActionId.NEGATIVE -> c.negativeActionLabel != null
        }
    }

    private fun postFallback(entry: Entry, out: MutableList<SessionEffect>) {
        val e = entry.event
        publish(
            entry,
            NotificationCard(
                key = key(entry.uid), category = e.category, appIdentifier = null, appName = null,
                title = categoryLabel(e.category), subtitle = "", message = "", truncated = false,
                important = e.important, preExisting = e.preExisting, silent = e.silent,
                detailsUnavailable = true, malformedText = false,
                positiveActionLabel = null, negativeActionLabel = null, fixture = null,
            ),
            out,
        )
    }

    private fun publish(entry: Entry, card: NotificationCard, out: MutableList<SessionEffect>) {
        val first = entry.card == null
        entry.card = card
        if (first) {
            counters = counters.copy(cardsPosted = counters.cardsPosted + 1)
            out += SessionEffect.PostCard(card, alert = !card.silent && !card.preExisting)
        } else {
            counters = counters.copy(cardsUpdated = counters.cardsUpdated + 1)
            out += SessionEffect.UpdateCard(card)
        }
    }

    private fun buildCard(entry: Entry, r: AncsResponse.Notification): NotificationCard {
        val a = r.attributes
        fun text(id: NotificationAttributeId) = a[id.raw]?.text
        val e = entry.event
        val message = text(NotificationAttributeId.MESSAGE).orEmpty()
        val messageSize = text(NotificationAttributeId.MESSAGE_SIZE)?.trim()?.toIntOrNull()
        val title = text(NotificationAttributeId.TITLE).orEmpty()
        val appId = text(NotificationAttributeId.APP_IDENTIFIER)?.takeIf { it.isNotBlank() }
        return NotificationCard(
            key = key(entry.uid),
            category = e.category,
            appIdentifier = appId,
            appName = appId?.let { appNames[it] },
            title = title,
            subtitle = text(NotificationAttributeId.SUBTITLE).orEmpty(),
            message = message,
            truncated = messageSize != null && messageSize > message.length,
            important = e.important,
            preExisting = e.preExisting,
            silent = e.silent,
            detailsUnavailable = false,
            malformedText = a.values.any { it.malformedUtf8 },
            positiveActionLabel = text(NotificationAttributeId.POSITIVE_ACTION_LABEL)
                ?.takeIf { EventFlag.POSITIVE_ACTION in e.flags && it.isNotEmpty() },
            negativeActionLabel = text(NotificationAttributeId.NEGATIVE_ACTION_LABEL)
                ?.takeIf { EventFlag.NEGATIVE_ACTION in e.flags && it.isNotEmpty() },
            fixture = FixtureTag.fromTitle(title),
        )
    }

    private fun key(uid: AncsUid) = NotificationKey(sourceDeviceId.orEmpty(), generation, uid)

    private fun clearSessionState() {
        tx = null
        assembler.reset()
        queue.clear()
        entries.clear()
        appNames.clear()
        requestedApps.clear()
    }
}
