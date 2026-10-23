package dev.wristbridge.watch.bridge

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent diagnostic log. Every bridge event, warning, error, uncaught crash and system kill of the process is
 * appended to rotating files that survive reboots and process death (4 x 256 KiB), so a failure on the street can be
 * analysed later. Lines carry timestamps, pseudonymous peer ids, status codes and sizes only: never notification text,
 * health values, keys or security codes.
 *
 * Pull with `scripts/windows/pull-watch-logs.sh`, or from the iPhone through the secure channel (`diag.logs`).
 */
object DiagnosticLog {
    private const val TAG = "WristBridge"
    private const val FILE = "wb.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val KEEP = 4
    private const val PREF_LAST_EXIT = "diag_last_exit_ts"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "wb-log").apply { isDaemon = true } }
    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        val app = context.applicationContext
        if (dir != null) return
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        dir = File(base, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            writeNow("F", "Excepción no controlada en ${thread.name}: ${error.javaClass.name}: ${error.message}\n" +
                error.stackTrace.take(25).joinToString("\n") { "    at $it" })
            previous?.uncaughtException(thread, error)
        }
        io.execute { recordPreviousExits(app) }
        i("Registro iniciado versión ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()}")
    }

    fun i(message: String) = add("I", message)

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        add("W", message + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        add("E", message + (error?.let { " (${it.javaClass.simpleName}: ${it.message})\n" + it.stackTrace.take(12).joinToString("\n") { s -> "    at $s" } } ?: ""))
    }

    private fun add(level: String, message: String) {
        if (dir == null) return
        io.execute { writeNow(level, message) }
    }

    @Synchronized
    private fun writeNow(level: String, message: String) {
        val d = dir ?: return
        try {
            val file = File(d, FILE)
            if (file.length() > MAX_BYTES) rotate(d)
            file.appendText("${stamp.get()!!.format(Date())} $level $message\n")
        } catch (_: Exception) {
        }
    }

    private fun rotate(d: File) {
        File(d, "wb.${KEEP - 1}.log").delete()
        for (n in KEEP - 2 downTo 1) File(d, "wb.$n.log").renameTo(File(d, "wb.${n + 1}.log"))
        File(d, FILE).renameTo(File(d, "wb.1.log"))
    }

    /** Why the previous processes ended (crash, system kill, low memory, user force-stop), once per exit. */
    private fun recordPreviousExits(context: Context) {
        try {
            val prefs = context.getSharedPreferences("wristbridge", Context.MODE_PRIVATE)
            val last = prefs.getLong(PREF_LAST_EXIT, 0)
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8).filter { it.timestamp > last }
            for (x in exits.sortedBy { it.timestamp }) {
                writeNow("W", "Proceso anterior terminó: ${reason(x.reason)} importancia=${x.importance} " +
                    "hora=${stamp.get()!!.format(Date(x.timestamp))} ${x.description.orEmpty()}")
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(PREF_LAST_EXIT, it).apply() }
        } catch (_: Exception) {
        }
    }

    private fun reason(code: Int) = when (code) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "CODE_$code"
    }

    fun files(): List<File> = (dir?.listFiles()?.filter { it.name.startsWith("wb") }?.sortedBy { it.name }) ?: emptyList()

    fun totalBytes(): Long = files().sumOf { it.length() }

    /** Whole log, oldest first (rotated files first). */
    private fun allText(): String = files().sortedByDescending { it.name }.joinToString("") { runCatching { it.readText() }.getOrDefault("") }

    /** Page [pageFromEnd] (0 = newest) of [pageBytes] characters, cut on a line boundary; returns the text and the page count. */
    fun page(pageFromEnd: Int, pageBytes: Int): Pair<String, Int> {
        val text = allText()
        if (text.isEmpty()) return "" to 0
        val pages = (text.length + pageBytes - 1) / pageBytes
        val end = text.length - pageFromEnd * pageBytes
        if (end <= 0) return "" to pages
        val start = (end - pageBytes).coerceAtLeast(0)
        return text.substring(start, end) to pages
    }
}
