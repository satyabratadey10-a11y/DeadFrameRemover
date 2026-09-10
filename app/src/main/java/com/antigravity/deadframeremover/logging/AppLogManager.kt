package com.antigravity.deadframeremover.logging

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, FFMPEG
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

object AppLogManager {
    private const val MAX_LOGS = 600
    private val logList = ArrayDeque<LogEntry>(MAX_LOGS)
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow = _logsFlow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "app_logs.txt")
        log(LogLevel.INFO, "AppLogManager", "DeadFrameRemover logging & crash catcher initialized.")
    }

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String) {
        val time = timeFormat.format(Date())
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.FFMPEG -> Log.i("FFmpegKit", message)
        }

        if (logList.size >= MAX_LOGS) {
            logList.removeFirst()
        }
        val entry = LogEntry(time, level, tag, message)
        logList.addLast(entry)
        _logsFlow.value = logList.toList()

        try {
            logFile?.appendText("[$time] [${level.name}] [$tag]: $message\n")
        } catch (_: Exception) {
        }
    }

    fun getSystemLogcat(maxLines: Int = 120): String {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -v time")
            val reader = process.inputStream.bufferedReader()
            val lines = reader.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "Unable to query system logcat: ${e.message}"
        }
    }

    fun getEntireLog(context: Context): String {
        val sb = StringBuilder()
        val dateHeader = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("=====================================================\n")
        sb.append("      DEADFRAME REMOVER - COMPLETE DIAGNOSTIC LOG    \n")
        sb.append("=====================================================\n")
        sb.append("Generated At: $dateHeader\n\n")

        val crashDump = CrashHandler.getSavedCrashLog(context)
        if (!crashDump.isNullOrBlank()) {
            sb.append("---------------- LAST CRASH REPORT -----------------\n")
            sb.append(crashDump)
            sb.append("\n----------------------------------------------------\n\n")
        } else {
            sb.append("No active crash record found.\n\n")
        }

        sb.append("---------------- APPLICATION EVENT LOGS ------------\n")
        synchronized(this) {
            logList.forEach {
                sb.append("[${it.timestamp}] [${it.level.name}] [${it.tag}]: ${it.message}\n")
            }
        }
        sb.append("----------------------------------------------------\n\n")

        sb.append("---------------- RECENT SYSTEM LOGCAT --------------\n")
        sb.append(getSystemLogcat(100))
        sb.append("\n================ END DIAGNOSTIC LOG ================\n")

        return sb.toString()
    }

    fun copyEntireLogToClipboard(context: Context): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = getEntireLog(context)
            val clip = ClipData.newPlainText("DeadFrameRemover Complete Log", text)
            clipboard.setPrimaryClip(clip)
            log(LogLevel.INFO, "AppLogManager", "Complete log copied to clipboard successfully.")
            true
        } catch (e: Exception) {
            log(LogLevel.ERROR, "AppLogManager", "Failed to copy log to clipboard: ${e.message}")
            false
        }
    }

    @Synchronized
    fun clearLogs() {
        logList.clear()
        _logsFlow.value = emptyList()
        try {
            logFile?.writeText("")
        } catch (_: Exception) {
        }
    }
}
