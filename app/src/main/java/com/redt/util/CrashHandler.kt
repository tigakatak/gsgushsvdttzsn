package com.redt.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CrashHandler {
    private var enabled = false

    fun init(context: Context) {
        if (enabled) return
        enabled = true
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val crashDir = File(base, "crash")
        crashDir.mkdirs()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date())
            val file = File(crashDir, "crash_$dateStr.log")
            try {
                FileWriter(file).use { writer ->
                    writer.write("Time: $dateStr\n")
                    writer.write("Thread: ${thread.name}\n")
                    writer.write("Message: ${throwable.message}\n\n")
                    writer.write("Stack trace:\n")
                    writeStackTrace(writer, throwable, 0)
                }
            } catch (e: Exception) {
                Log.e("CrashHandler", "Failed to write crash log", e)
            }
            Log.e("CrashHandler", "Uncaught exception in ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    private fun writeStackTrace(writer: java.io.Writer, throwable: Throwable, depth: Int) {
        if (depth > 10) return
        for (element in throwable.stackTrace) {
            writer.write("\tat ${element.toString()}\n")
        }
        val cause = throwable.cause
        if (cause != null && cause !== throwable) {
            writer.write("\nCaused by: $cause\n")
            writeStackTrace(writer, cause, depth + 1)
        }
    }
}
