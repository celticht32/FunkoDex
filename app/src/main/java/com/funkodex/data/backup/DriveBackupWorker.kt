package com.funkodex.data.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.security.SecureKeyStore
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DriveBackupWorker — E2
 *
 * Daily WorkManager job that backs up the Couchbase Lite database to
 * the user's Google Drive in a folder called "FunkoDex Backups".
 *
 * Authentication: Google Sign-In via GoogleAccountCredential.
 * If no account is signed in the worker exits cleanly (no retry).
 *
 * Backup: FunkoDex_YYYY-MM-DD_HH-mm.zip containing the .cblite2 folder.
 * Keeps the last 7 backups; older ones are deleted automatically.
 *
 * Setup required:
 *  - User signs in via Settings > Backup & Restore > Connect Google Drive
 *  - Drive must be enabled in Google Cloud Console with the app's SHA-1
 */
@HiltWorker
class DriveBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params:  WorkerParameters,
    private val db:             FunkoDexDatabase,
    private val secureKeyStore: SecureKeyStore,
) : CoroutineWorker(context, params) {

    private fun sendBackupNotification(fileName: String) {
        try {
            val nm = applicationContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val notification = androidx.core.app.NotificationCompat
                .Builder(applicationContext, "backup_status")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("FunkoDex backup complete")
                .setContentText(fileName)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(3001, notification)
        } catch (_: Exception) { /* notification failed — non-critical */ }
    }

        companion object {
        const val WORK_NAME        = "drive_backup"
        const val KEY_MANUAL       = "manual_trigger"
        const val PREF_LAST_BACKUP = "drive_last_backup"
        private const val TAG      = "DriveBackupWorker"
        private const val FOLDER   = "FunkoDex Backups"
        private const val MAX_KEEP = 7

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresStorageNotLow(true)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
            )
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<DriveBackupWorker>()
                    .setInputData(workDataOf(KEY_MANUAL to true))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    ).build()
            )
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(applicationContext)
                ?: return@withContext Result.success(workDataOf("skipped" to "not_signed_in"))

            val credential = GoogleAccountCredential
                .usingOAuth2(applicationContext, listOf(DriveScopes.DRIVE_FILE))
                .apply { selectedAccount = account.account }

            val drive = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential,
            ).setApplicationName("FunkoDex").build()

            val zipFile  = zipDatabase()
            val folderId = ensureFolder(drive)

            drive.files().create(
                DriveFile().apply { name = zipFile.name; parents = listOf(folderId) },
                FileContent("application/zip", zipFile),
            ).setFields("id,name").execute()

            pruneOldBackups(drive, folderId)
            secureKeyStore.setWorkerUrl("$PREF_LAST_BACKUP:${LocalDateTime.now()}")
            zipFile.delete()

            Log.i(TAG, "Backup complete: ${zipFile.name}")
            sendBackupNotification(zipFile.name)
            Result.success(workDataOf("file" to zipFile.name))
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun zipDatabase(): File {
        db.close()
        val dbDir   = File(applicationContext.filesDir, "funkodex")
        val ts      = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val zipFile = File(applicationContext.cacheDir, "FunkoDex_${ts}.zip")

        ZipOutputStream(zipFile.outputStream()).use { zos ->
            dbDir.walkTopDown().filter { it.isFile }.forEach { file ->
                zos.putNextEntry(ZipEntry(file.relativeTo(dbDir.parentFile!!).path))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        db.getDatabase()   // reopen
        return zipFile
    }

    private fun ensureFolder(drive: Drive): String {
        val list = drive.files().list()
            .setQ("mimeType='application/vnd.google-apps.folder' and name='$FOLDER' and trashed=false")
            .setFields("files(id)").execute()
        if (list.files.isNotEmpty()) return list.files[0].id
        return drive.files().create(
            DriveFile().apply { name = FOLDER; mimeType = "application/vnd.google-apps.folder" }
        ).setFields("id").execute().id
    }

    private fun pruneOldBackups(drive: Drive, folderId: String) {
        val files = drive.files().list()
            .setQ("'$folderId' in parents and name contains 'FunkoDex_' and trashed=false")
            .setOrderBy("createdTime")
            .setFields("files(id,name)").execute().files ?: return
        if (files.size > MAX_KEEP)
            files.take(files.size - MAX_KEEP).forEach { drive.files().delete(it.id).execute() }
    }
}
