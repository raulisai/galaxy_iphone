package dev.wristbridge.watch.bridge

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.wristbridge.notification.CampaignJournal
import dev.wristbridge.notification.CampaignEvent
import dev.wristbridge.notification.CampaignStats
import dev.wristbridge.notification.RemovedBy
import dev.wristbridge.notification.SessionEffect
import dev.wristbridge.protocol.gatt.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * P2-08.K campaign journal on the watch: owns a [CampaignJournal], persists each batch of events to
 * `filesDir/campaign/journal.jsonl` and exposes the derived [stats] to the Diagnostics screen.
 *
 * Only ids, timestamps, sizes, categories and the synthetic fixture tag are recorded; titles, bodies and
 * app names never reach this class. The files live in `filesDir`, which the manifest excludes from backup
 * (allowBackup=false). Written from the BLE thread; [reset] and reads come from the UI thread, so every
 * entry point is synchronized. Never alters ANCS behavior: failures here are logged and swallowed.
 */
class CampaignRecorder private constructor(context: Context) {
    private val dir = File(context.filesDir, "campaign")
    private val file = File(dir, FILE_NAME)
    private val backup = File(dir, "$FILE_NAME.1")
    private val journal = CampaignJournal(MonotonicClock { SystemClock.elapsedRealtimeNanos() }, System::currentTimeMillis)
    private val mutableStats = MutableStateFlow(CampaignStats())

    /** Derived figures since the last reset (rebuilt from the files after a process restart). */
    val stats: StateFlow<CampaignStats> = mutableStats

    init {
        synchronized(this) {
            // Oldest first: the rotated backup, then the live file. A torn last line is skipped by the parser.
            val restored = listOf(backup, file).flatMap { f -> CampaignJournal.parseLines(readText(f)) }
            journal.restore(restored)
            mutableStats.value = journal.stats()
        }
    }

    @Synchronized
    fun sessionStart(generation: Long) = persist(listOf(journal.sessionStart(generation)))

    @Synchronized
    fun sessionEnd(generation: Long, reason: String) = persist(listOfNotNull(journal.sessionEnd(generation, reason)))

    @Synchronized
    fun linkUp() = persist(listOfNotNull(journal.linkUp()))

    @Synchronized
    fun linkDown(reason: String) = persist(listOfNotNull(journal.linkDown(reason)))

    /** The user swiped the card away on the watch and the dismissal was forwarded as a clear request. */
    @Synchronized
    fun cardDismissed(generation: Long, uid: Long) =
        persist(listOf(journal.cardRemoved(generation, uid, RemovedBy.WATCH_DISMISS)))

    /** Records the card-related [effects] of one engine batch with a single file write. */
    @Synchronized
    fun effects(effects: List<SessionEffect>) {
        val events = ArrayList<CampaignEvent>(effects.size)
        for (e in effects) {
            when (e) {
                is SessionEffect.PostCard -> events += journal.cardReceived(
                    e.card.key.sessionGeneration, e.card.key.uid.value, e.card.category, e.card.fixture,
                    e.card.message.toByteArray().size, e.alert,
                )
                is SessionEffect.UpdateCard -> events += journal.cardUpdated(
                    e.card.key.sessionGeneration, e.card.key.uid.value, e.card.category, e.card.fixture,
                    e.card.message.toByteArray().size,
                )
                is SessionEffect.RemoveCard ->
                    events += journal.cardRemoved(e.key.sessionGeneration, e.key.uid.value, RemovedBy.ANCS)
                is SessionEffect.ActionResult ->
                    events += journal.action(e.key.sessionGeneration, e.key.uid.value, e.action, e.status)
                is SessionEffect.WriteControlPoint, is SessionEffect.RemoveCardsBefore -> Unit
            }
        }
        persist(events)
    }

    /** Clears the in-memory journal and deletes both files. */
    @Synchronized
    fun reset() {
        journal.reset()
        try {
            file.delete()
            backup.delete()
        } catch (e: SecurityException) {
            DiagnosticLog.w("campaign reset failed", e)
        }
        mutableStats.value = journal.stats()
    }

    /** Appends [events] (one write and flush per batch), rotating first when the file is full. */
    private fun persist(events: List<CampaignEvent>) {
        mutableStats.value = journal.stats()
        if (events.isEmpty()) return
        try {
            dir.mkdirs()
            if (file.length() >= MAX_FILE_BYTES) {
                backup.delete()
                file.renameTo(backup)
            }
            val text = StringBuilder(events.size * 160)
            for (e in events) text.append(CampaignJournal.toJsonLine(e)).append('\n')
            FileOutputStream(file, true).use {
                it.write(text.toString().toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (e: IOException) {
            DiagnosticLog.w("campaign journal write failed", e)
        } catch (e: SecurityException) {
            DiagnosticLog.w("campaign journal write failed", e)
        }
    }

    private fun readText(f: File): String = try {
        if (f.isFile && f.length() <= MAX_FILE_BYTES * 2) f.readText(Charsets.UTF_8) else ""
    } catch (e: IOException) {
        DiagnosticLog.w("campaign journal read failed", e)
        ""
    }

    companion object {
        private const val TAG = "WbCampaign"
        const val FILE_NAME = "journal.jsonl"

        /** The live file rotates to `journal.jsonl.1` (replacing the previous backup) at this size. */
        const val MAX_FILE_BYTES = 512L * 1024

        @Volatile
        private var instance: CampaignRecorder? = null

        /** Process-wide recorder; safe from any thread. */
        fun get(context: Context): CampaignRecorder =
            instance ?: synchronized(this) {
                instance ?: CampaignRecorder(context.applicationContext).also { instance = it }
            }
    }
}
