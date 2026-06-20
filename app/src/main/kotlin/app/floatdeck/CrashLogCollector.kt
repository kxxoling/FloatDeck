package app.floatdeck

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local crash log collector. No network requests are made. */
object CrashLogCollector {

    private const val MAX_LOG_FILES = 10
    private const val LOG_DIR = "crash_logs"

    private lateinit var logDir: File

    /** Call in Application.onCreate. */
    fun init(app: FloatDeckApp) {
        logDir = File(app.filesDir, LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashLog(thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Get all crash log files, sorted by modification time descending. */
    fun getLogFiles(): List<File> =
        logDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** Delete all log files. */
    fun clearLogs() {
        logDir.listFiles()?.forEach { file ->
            val deleted = file.delete()
            if (!deleted) {
                android.util.Log.w("CrashLogCollector", "Failed to delete ${file.name}")
            }
        }
    }

    /** Share a log file via FileProvider. */
    fun shareLog(app: FloatDeckApp, file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Save a log file to the public Downloads directory. */
    fun saveToDownloads(context: Context, file: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: use MediaStore.Downloads (no permission needed)
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(collection, values) ?: return false
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: return false
                true
            } else {
                // Android 9 and below: legacy direct file write
                @Suppress("DEPRECATION")
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val destFile = File(downloadsDir, file.name)
                file.copyTo(destFile, overwrite = true)
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("CrashLogCollector", "Failed to save to downloads", e)
            false
        }
    }

    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        trimOldLogs()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(logDir, "crash_$timestamp.log")
        FileWriter(file).use { writer ->
            PrintWriter(writer).use { pw ->
                pw.println("FloatDeck Crash Log")
                pw.println("Time: $timestamp")
                pw.println("Thread: ${thread.name}")
                pw.println("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                pw.println("---")
                throwable.printStackTrace(pw)
            }
        }
    }

    private fun trimOldLogs() {
        val toRemove = logsToTrim(logDir.listFiles().orEmpty(), MAX_LOG_FILES - 1)
        toRemove.forEach { file ->
            if (!file.delete()) {
                android.util.Log.w("CrashLogCollector", "Failed to delete ${file.name}")
            }
        }
    }

    /**
     * Computes the old logs to delete so that the total stays within [maxKeep]
     * (oldest by modification time are removed first). Pure function, directly unit-testable.
     */
    internal fun logsToTrim(
        files: Array<out File>,
        maxKeep: Int,
    ): List<File> {
        if (maxKeep < 0 || files.size <= maxKeep) return emptyList()
        return files.sortedBy { it.lastModified() }.take(files.size - maxKeep)
    }
}
