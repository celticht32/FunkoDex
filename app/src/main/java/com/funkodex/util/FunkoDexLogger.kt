package com.funkodex.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * FunkoDexLogger
 *
 * Centralised structured logger. Wraps android.util.Log, gates by configurable
 * LogLevel, and writes to a rotating daily file in filesDir/logs/.
 *
 * Usage (from anywhere after init):
 *   FunkoDexLogger.d("MyTag", "Something happened")
 *   FunkoDexLogger.e("MyTag", "Something failed", throwable)
 *
 * File location: <filesDir>/logs/funkodex_YYYY-MM-DD.log
 * Rolling:       Daily rotation, plus within-day rotation past MAX_FILE_MB.
 *                Files older than MAX_LOG_DAYS are pruned at init.
 * Format:        2025-05-25 14:32:01.234 [INFO] CatalogRefresh: Loaded 23940 items
 *
 * Thread safety: writes are dispatched to a single dedicated daemon thread via a
 *                LinkedBlockingQueue so callers are never blocked. Timestamp
 *                formatting uses java.time.DateTimeFormatter, which is immutable
 *                and thread-safe.
 *
 * Level gate:    Calls below the configured LogLevel are ignored for file writes.
 *                Android Log still receives everything (LogCat).
 *
 * Startup crash guard: CrashHandler installs itself before any other init and
 *                      writes uncaught exceptions to filesDir/logs/crash_TIMESTAMP.log.
 *
 * MAINTENANCE NOTES — three defects fixed here, all of which produced the same
 * user-visible symptom ("Diagnostics says no log file for today"):
 *
 *  1. SimpleDateFormat is NOT thread-safe, and two shared instances were being
 *     used concurrently from caller threads, the writer thread, and the UI
 *     thread (via currentLogFile()). Concurrent use can throw or return a
 *     mangled string; writeToFile swallowed the exception, so the file silently
 *     never appeared. Replaced with DateTimeFormatter (immutable, thread-safe).
 *  2. pruneOldLogs() was submitted to a single-thread executor whose only thread
 *     was already occupied forever by the consumer's `while (true)` loop, so it
 *     could never run and retention was never enforced. The consumer now owns a
 *     dedicated thread and prunes once before entering its loop.
 *  3. The consumer loop had no try/catch. A single throw killed the thread and
 *     file logging stopped permanently and silently for the rest of the process.
 *     The loop now survives per-entry failures.
 */
object FunkoDexLogger {

    private const val TAG          = "FunkoDexLogger"
    private const val LOG_DIR      = "logs"
    private const val MAX_FILE_MB  = 5          // rotate within-day past this size
    private const val MAX_LOG_DAYS = 3          // delete log files older than this

    // DateTimeFormatter is immutable and thread-safe — unlike SimpleDateFormat,
    // these may be shared freely across the caller, writer and UI threads.
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val FILE_STAMP = DateTimeFormatter.ofPattern("HHmmss", Locale.US)
    private val CRASH_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    @Volatile private var filesDir: File? = null
    @Volatile var currentLevel: LogLevel = LogLevel.DEFAULT

    private val queue = LinkedBlockingQueue<String>(4096)

    /**
     * Dedicated writer thread. Deliberately NOT an ExecutorService: the loop
     * below never returns, so any other task submitted to a single-thread
     * executor would queue behind it forever (defect 2 above).
     */
    private val writer = Thread({
        // Prune once at startup, on this thread, before entering the loop.
        runCatching { pruneOldLogs() }
        while (true) {
            try {
                writeToFile(queue.take())
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            } catch (e: Throwable) {
                // Never let one bad entry kill file logging for the process.
                Log.e(TAG, "Log writer error (continuing): ${e.message}")
            }
        }
    }, "FunkoDexLogger").apply { isDaemon = true; start() }

    // ── Init ──────────────────────────────────────────────────────────────────

    /**
     * Call once in FunkoDexApp.onCreate() BEFORE anything else.
     * Sets the files directory so file logging works.
     */
    fun init(context: Context, level: LogLevel = LogLevel.DEFAULT) {
        filesDir     = context.filesDir
        currentLevel = level
        i(TAG, "Logger initialised: level=$level dir=${context.filesDir}/$LOG_DIR")
    }

    /** Update the log level at runtime (e.g. when user changes Settings). */
    fun setLevel(level: LogLevel) {
        currentLevel = level
        i(TAG, "Log level changed to $level")
    }

    // ── Logging API ───────────────────────────────────────────────────────────

    fun v(tag: String, msg: String) = log(LogLevel.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(LogLevel.DEBUG,   tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.INFO,    tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN,  tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, msg, t)

    // ── Core ──────────────────────────────────────────────────────────────────

    private fun log(level: LogLevel, tag: String, msg: String, t: Throwable? = null) {
        // Always forward to Android LogCat (visible in Android Studio / adb logcat)
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, msg, t)
            LogLevel.DEBUG   -> Log.d(tag, msg, t)
            LogLevel.INFO    -> Log.i(tag, msg, t)
            LogLevel.WARN    -> Log.w(tag, msg, t)
            LogLevel.ERROR   -> Log.e(tag, msg, t)
        }

        // Only write to file if at or above the configured level
        if (level.androidPriority < currentLevel.androidPriority) return

        val entry = buildString {
            append(LocalDateTime.now().format(TIMESTAMP))
            append(" [${level.name.padEnd(7)}] $tag: $msg")
            if (t != null) {
                append("\n")
                append(t.stackTraceToString().trimEnd())
            }
            append("\n")
        }

        // Non-blocking: offer to queue (discard if full — never block the caller)
        if (!queue.offer(entry)) {
            Log.w(TAG, "Log queue full — dropping entry for $tag")
        }
    }

    // ── File writing ──────────────────────────────────────────────────────────

    private fun writeToFile(entry: String) {
        val dir = filesDir ?: return
        try {
            val logDir = File(dir, LOG_DIR).also { it.mkdirs() }
            val today  = LocalDate.now().format(DATE_STAMP)
            val file   = File(logDir, "funkodex_$today.log")

            // Rotate if file exceeds size limit
            if (file.exists() && file.length() > MAX_FILE_MB * 1024 * 1024) {
                val ts = LocalDateTime.now().format(FILE_STAMP)
                file.renameTo(File(logDir, "funkodex_${today}_$ts.log"))
            }

            FileWriter(file, true).use { it.write(entry) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log entry: ${e.message}")
        }
    }

    private fun pruneOldLogs() {
        val dir = File(filesDir ?: return, LOG_DIR)
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - MAX_LOG_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.name.endsWith(".log") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    // ── Log file access ───────────────────────────────────────────────────────

    /** The logs directory, created if absent. Null before [init]. */
    fun logDir(): File? = filesDir?.let { File(it, LOG_DIR).also { d -> d.mkdirs() } }

    /** Returns the path to today's log file, or null if not yet written. */
    fun currentLogFile(): File? {
        val dir   = File(filesDir ?: return null, LOG_DIR)
        val today = LocalDate.now().format(DATE_STAMP)
        return File(dir, "funkodex_$today.log").takeIf { it.exists() }
    }

    /** All log files, newest first. For the Settings share/save actions. */
    fun allLogFiles(): List<File> {
        val dir = File(filesDir ?: return emptyList(), LOG_DIR)
        return dir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Force every queued entry to disk and return today's file.
     *
     * Writes are asynchronous, so a "save" or "share" issued moments after an
     * event can otherwise miss the very line the user is trying to capture.
     * Blocks the caller briefly — call from a background coroutine, never the
     * main thread. Returns null if nothing has been written yet.
     */
    fun flushBlocking(timeoutMs: Long = 2_000): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
        }
        return currentLogFile()
    }

    /** Write a one-off line to a crash log — used before DataStore is available. */
    fun writeCrashEntry(dir: File, message: String) {
        try {
            val logDir = File(dir, LOG_DIR).also { it.mkdirs() }
            val ts     = LocalDateTime.now().format(CRASH_STAMP)
            File(logDir, "crash_$ts.log").writeText(
                "${LocalDateTime.now().format(TIMESTAMP)} [CRASH] $message\n"
            )
        } catch (_: Exception) { /* last resort — nothing we can do */ }
    }
}
