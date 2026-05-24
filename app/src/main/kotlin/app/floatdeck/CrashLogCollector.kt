package app.floatdeck

import android.content.Intent
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
        logDir.listFiles()?.forEach { it.delete() }
    }

    /** Share a log file via FileProvider. */
    fun shareLog(app: FloatDeckApp, file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        val files = logDir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        while (files.size >= MAX_LOG_FILES) {
            files.first().delete()
        }
    }
}
