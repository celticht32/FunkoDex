package com.funkodex.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
 * Rolling:       Daily rotation. Maximum 7 log files kept.
 * Format:        2025-05-25 14:32:01.234 [INFO] CatalogRefresh: Loaded 23940 items
 *
 * Thread safety: writes are dispatched to a single background thread via a
 *                LinkedBlockingQueue so callers are never blocked.
 *
 * Level gate:    Calls below the configured LogLevel are ignored for file writes.
 *                Android Log still receives everything in debug builds (LogCat).
 *
 * Startup crash guard: CrashHandler installs itself before any other init and
 *                      writes uncaught exceptions to filesDir/logs/crash_TIMESTAMP.log.
 *                      This captures crashes that happen before DataStore is readable.
 */
object FunkoDexLogger {

    private const val TAG         = "FunkoDexLogger"
    private const val LOG_DIR     = "logs"
    private const val MAX_FILE_MB   = 5          // rotate within-day if file exceeds this
    private const val MAX_LOG_DAYS  = 3          // delete log files older than this

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile private var filesDir: File? = null
    @Volatile var currentLevel: LogLevel = LogLevel.DEFAULT

    // Single background thread for all file I/O — never blocks callers
    private val queue    = LinkedBlockingQueue<String>(4096)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FunkoDexLogger").apply { isDaemon = true }
    }

    init {
        executor.submit {
            while (true) {
                val entry = queue.take()   // blocks until something arrives
                writeToFile(entry)
            }
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    /**
     * Call once in FunkoDexApp.onCreate() BEFORE anything else.
     * Sets the files directory so file logging works, and prunes old logs.
     */
    fun init(context: Context, level: LogLevel = LogLevel.DEFAULT) {
        filesDir    = context.filesDir
        currentLevel = level
        executor.submit { pruneOldLogs() }
        i(TAG, "Logger initialised: level=$level dir=${context.filesDir}/logs")
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

        val timestamp = formatter.format(Date())
        val levelTag  = level.name.padEnd(7)
        val entry     = buildString {
            append("$timestamp [$levelTag] $tag: $msg")
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
            val today  = dateStamp.format(Date())
            val file   = File(logDir, "funkodex_$today.log")

            // Rotate if file exceeds size limit
            if (file.exists() && file.length() > MAX_FILE_MB * 1024 * 1024) {
                val ts = SimpleDateFormat("HHmmss", Locale.US).format(Date())
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
        val cutoff = System.currentTimeMillis() - MAX_LOG_DAYS * 24 * 60 * 60 * 1000L
        dir.listFiles { f -> f.name.endsWith(".log") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    // ── Log file access ───────────────────────────────────────────────────────

    /** Returns the path to today's log file, or null if not yet written. */
    fun currentLogFile(): File? {
        val dir   = File(filesDir ?: return null, LOG_DIR)
        val today = dateStamp.format(Date())
        return File(dir, "funkodex_$today.log").takeIf { it.exists() }
    }

    /** All log files, newest first. For the Settings share sheet. */
    fun allLogFiles(): List<File> {
        val dir = File(filesDir ?: return emptyList(), LOG_DIR)
        return dir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Write a one-off line to a crash log — used before DataStore is available. */
    fun writeCrashEntry(dir: File, message: String) {
        try {
            val logDir = File(dir, LOG_DIR).also { it.mkdirs() }
            val ts     = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(logDir, "crash_$ts.log").writeText(
                "${formatter.format(Date())} [CRASH] $message\n"
            )
        } catch (_: Exception) { /* last resort — nothing we can do */ }
    }
}
