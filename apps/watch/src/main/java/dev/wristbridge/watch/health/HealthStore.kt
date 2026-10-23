package dev.wristbridge.watch.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import dev.wristbridge.protocol.p2.health.HealthSample
import dev.wristbridge.protocol.p2.health.HealthSource
import java.security.MessageDigest

/** Adapter that lets the P2 health responder read this store. */
class HealthStoreSource(private val store: HealthStore) : HealthSource {
    override fun samplesAfter(cursor: Long, limit: Int) = store.samplesAfter(cursor, limit)
    override fun hasMoreAfter(cursor: Long) = store.hasMoreAfter(cursor)
    override fun maxCursor() = store.maxCursor()
    override fun acknowledge(through: Long) = store.acknowledge(through)
}

data class HealthSummary(
    val pending: Long = 0, val dropped: Long = 0,
    val steps: Long? = null, val stepsAt: Long? = null,
    val heartRate: Double? = null, val heartAt: Long? = null,
)

/**
 * Device-local, backup-excluded staging. Daily steps are cumulative SNAPSHOTS, never interval deltas.
 * Rows are immutable and deduplicated by source timestamps/value. No BLE codec or implicit ACK exists.
 */
class HealthStore internal constructor(context: Context, databaseName: String = "health-local.db") : SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, databaseName).absolutePath, null, 1,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (cursor INTEGER PRIMARY KEY AUTOINCREMENT, sample_id TEXT NOT NULL UNIQUE, metric TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, value REAL NOT NULL)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    data class Point(val metric: String, val start: Long, val end: Long, val value: Double, val sourceIdentity: String? = null)

    @Synchronized fun add(metric: String, start: Long, end: Long, value: Double, sourceIdentity: String? = null) =
        addBatch(listOf(Point(metric, start, end, value, sourceIdentity)))

    /** One durable transaction per callback; no queue of sensor values outlives its service binding. */
    @Synchronized fun addBatch(points: List<Point>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (point in points) {
                val (metric, start, end, value, sourceIdentity) = point
                require(metric in setOf(STEPS_DAILY, HEART_RATE))
                if (start < 0 || end < start || !value.isFinite() || value < 0) continue
                if (metric == HEART_RATE && value <= 0) continue
                val identity = "$metric:${sourceIdentity ?: "$start:$end"}:$value"
                val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val values = ContentValues().apply {
                    put("sample_id", id); put("metric", metric); put("start_ms", start)
                    put("end_ms", end); put("value", value)
                }
                db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            pruneIfDue(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private var lastPruneMs = 0L

    /** Retention only needs to run now and then; pruning on every sensor batch and every screen refresh wastes writes. */
    private fun pruneIfDue(db: SQLiteDatabase) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastPruneMs != 0L && now - lastPruneMs < PRUNE_INTERVAL_MS) return
        lastPruneMs = now
        prune(db)
    }

    private fun prune(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val removed = db.delete("samples", "end_ms < ? OR cursor IN (SELECT cursor FROM samples ORDER BY end_ms DESC,cursor DESC LIMIT -1 OFFSET 4000)", arrayOf(cutoff.toString()))
        if (removed > 0) {
            db.execSQL("INSERT OR IGNORE INTO metadata VALUES ('dropped',0)")
            db.execSQL("UPDATE metadata SET value=value+? WHERE key='dropped'", arrayOf(removed))
        }
    }

    @Synchronized fun summary(): HealthSummary {
        val db = writableDatabase
        db.beginTransaction()
        try { pruneIfDue(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        fun scalar(sql: String): Long = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0 }
        fun latest(metric: String): Pair<Double, Long>? = db.rawQuery(
            "SELECT value,end_ms FROM samples WHERE metric=? ORDER BY end_ms DESC,cursor DESC LIMIT 1",
            arrayOf(metric),
        ).use { if (it.moveToFirst()) it.getDouble(0) to it.getLong(1) else null }
        val steps = latest(STEPS_DAILY)
        val hr = latest(HEART_RATE)
        return HealthSummary(scalar("SELECT COUNT(*) FROM samples"), scalar("SELECT value FROM metadata WHERE key='dropped'"),
            steps?.first?.toLong(), steps?.second, hr?.first, hr?.second)
    }

    /** Samples with a delivery cursor greater than [cursor], oldest first (P7 pull by cursor). */
    @Synchronized fun samplesAfter(cursor: Long, limit: Int): List<HealthSample> = readableDatabase.rawQuery(
        "SELECT cursor,sample_id,metric,start_ms,end_ms,value FROM samples WHERE cursor>? ORDER BY cursor LIMIT ?",
        arrayOf(cursor.toString(), limit.toString()),
    ).use { c ->
        buildList { while (c.moveToNext()) add(HealthSample(c.getLong(0), c.getString(2), c.getLong(3), c.getLong(4), c.getDouble(5), c.getString(1))) }
    }

    @Synchronized fun maxCursor(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(cursor),0) FROM samples", null)
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized fun hasMoreAfter(cursor: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM samples WHERE cursor>? LIMIT 1", arrayOf(cursor.toString()),
    ).use { it.moveToFirst() }

    /** Highest cursor the iPhone confirmed it saved. Rows stay for the local screen until the 7-day retention prunes them. */
    @Synchronized fun acknowledge(through: Long) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO metadata VALUES ('acked',0)")
        db.execSQL("UPDATE metadata SET value=MAX(value,?) WHERE key='acked'", arrayOf(through))
    }

    @Synchronized fun acknowledgedThrough(): Long = readableDatabase.rawQuery(
        "SELECT value FROM metadata WHERE key='acked'", null,
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized fun erase() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("samples", null, null)
            writableDatabase.delete("metadata", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    companion object {
        const val STEPS_DAILY = "steps_daily_snapshot"
        const val HEART_RATE = "heart_rate_bpm"
        private const val PRUNE_INTERVAL_MS = 10L * 60 * 1000
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        @Volatile private var instance: HealthStore? = null
        fun get(context: Context): HealthStore = instance ?: synchronized(this) {
            instance ?: HealthStore(context.applicationContext).also { instance = it }
        }
    }
}
