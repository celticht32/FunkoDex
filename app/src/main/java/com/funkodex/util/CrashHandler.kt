package com.funkodex.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashHandler
 *
 * Installed as Thread.defaultUncaughtExceptionHandler in FunkoDexApp.onCreate()
 * BEFORE any other initialisation (CouchbaseLite, Hilt, etc.).
 *
 * If the app crashes before DataStore is readable (e.g. during Hilt graph
 * construction, CouchbaseLite init, or catalog preload), the default Android
 * crash dialog fires with no log trail. This handler catches that and writes
 * a crash report to filesDir/logs/crash_TIMESTAMP.log — always, regardless
 * of the configured LogLevel.
 *
 * The crash report includes:
 *   - Timestamp
 *   - Thread name
 *   - Full stack trace
 *   - Device info (model, SDK, available RAM)
 *
 * After writing, the previous handler (ACRA/Firebase or Android's default)
 * is invoked so the system crash dialog still appears.
 */
class CrashHandler private constructor(
    private val filesDir: File,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG       = "CrashHandler"
        private const val LOG_DIR   = "logs"
        private const val MAX_CRASH = 10   // keep at most 10 crash logs

        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.filesDir, previous)
            )
            Log.i(TAG, "Crash handler installed")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            writeCrashReport(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "CrashHandler itself failed: ${e.message}")
        } finally {
            // Always invoke the previous handler so the system crash dialog still works
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(thread: Thread, throwable: Throwable) {
        val logDir = File(filesDir, LOG_DIR).also { it.mkdirs() }
        val ts     = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file   = File(logDir, "crash_$ts.log")

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val report = buildString {
            appendLine("=" .repeat(60))
            appendLine("FunkoDex Crash Report")
            appendLine("Timestamp:  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("Thread:     ${thread.name} (id=${thread.id})")
            appendLine("Model:      ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android:    ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("RAM free:   ${Runtime.getRuntime().let { "${it.freeMemory() / 1024 / 1024}MB free / ${it.maxMemory() / 1024 / 1024}MB max" }}")
            appendLine("=" .repeat(60))
            appendLine()
            appendLine(sw.toString())
        }

        file.writeText(report)
        Log.e(TAG, "Crash written to ${file.absolutePath}")

        // Prune old crash logs
        logDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH)
            ?.forEach { it.delete() }
    }
}
