package com.antigravity.deadframeremover.logging

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val CRASH_FILE_NAME = "last_crash_log.txt"

        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        fun getSavedCrashLog(context: Context): String? {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            return if (file.exists() && file.length() > 0) {
                try {
                    file.readText()
                } catch (_: Exception) {
                    null
                }
            } else null
        }

        fun clearCrashLog(context: Context) {
            try {
                val file = File(context.filesDir, CRASH_FILE_NAME)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val crashReport = buildString {
                appendLine("***************** UNCAUGHT EXCEPTION CRASH *****************")
                appendLine("Timestamp   : $dateStr")
                appendLine("Thread      : ${thread.name} (ID: ${thread.id})")
                appendLine("Exception   : ${throwable.javaClass.name}")
                appendLine("Message     : ${throwable.localizedMessage ?: throwable.message ?: "No error message"}")
                appendLine("Device Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("Brand/Board : ${Build.BRAND} / ${Build.BOARD}")
                appendLine("Android OS  : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("ABIs        : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine()
                appendLine("--- STACK TRACE ---")
                appendLine(stackTrace)
                appendLine("--- RECENT APP LOGS ---")
                AppLogManager.logsFlow.value.takeLast(60).forEach {
                    appendLine("[${it.timestamp}] [${it.level.name}] [${it.tag}]: ${it.message}")
                }
                appendLine()
                appendLine("--- SYSTEM LOGCAT TAIL ---")
                appendLine(AppLogManager.getSystemLogcat(70))
                appendLine("***************** END OF CRASH REPORT *****************")
            }

            val crashFile = File(context.filesDir, CRASH_FILE_NAME)
            crashFile.writeText(crashReport)
            AppLogManager.log(LogLevel.ERROR, "CRASH", "Unhandled crash written to ${crashFile.name}")
        } catch (_: Exception) {
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
