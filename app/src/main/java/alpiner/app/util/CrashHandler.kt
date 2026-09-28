package alpiner.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {
    private const val MAX_LOGS = 10
    private const val FILE_PREFIX = "crash_"
    private var enabled = false

    fun init(context: Context) {
        if (enabled) return
        enabled = true
        val crashDir = crashDir(context)
        crashDir.mkdirs()
        pruneOldLogs(crashDir)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            try {
                FileWriter(File(crashDir, "$FILE_PREFIX$dateStr.log")).use { writer ->
                    writer.write("Time: $dateStr\n")
                    writer.write("Thread: ${thread.name}\n\n")
                    writer.write(throwable.stackTraceToString())
                }
            } catch (e: Exception) {
                Log.e("CrashHandler", "Failed to write crash log", e)
            }
            Log.e("CrashHandler", "Uncaught exception in ${thread.name}", throwable)
            previousHandler?.let {
                // The default handler shows the crash dialog and kills the
                // process itself; only fall back to a manual kill when there
                // is no previous handler installed.
                it.uncaughtException(thread, throwable)
            } ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }
        }
    }

    /** Where crash logs live, and the newest-first list of them (may be empty). */
    fun crashDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash")

    fun logFiles(context: Context): List<File> = listLogs(crashDir(context))

    /** Keeps only the [MAX_LOGS] newest crash logs so they cannot grow unbounded. */
    private fun pruneOldLogs(crashDir: File) {
        listLogs(crashDir).drop(MAX_LOGS).forEach { it.delete() }
    }

    private fun listLogs(crashDir: File): List<File> =
        crashDir.listFiles { f -> f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}
