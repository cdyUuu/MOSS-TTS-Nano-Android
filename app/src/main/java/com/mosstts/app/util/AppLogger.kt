package com.mosstts.app.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 应用日志系统。记录日志到内存和文件，支持在设置页查看。
 */
object AppLogger {

    enum class Level(val tag: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),
        CRASH("CRASH"),
    }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    ) {
        fun format(): String {
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
            val throwableStr = throwable?.let { "\n" + getStackTraceString(it) } ?: ""
            return "[$time][${level.tag}][$tag] $message$throwableStr"
        }
    }

    private const val MAX_LOG_ENTRIES = 2000
    private const val LOG_FILE_NAME = "mosstts_log.txt"
    private const val CRASH_FILE_NAME = "mosstts_crash.txt"

    private val logEntries = ConcurrentLinkedQueue<LogEntry>()
    private lateinit var appContext: Context
    private var logFile: File? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        logFile = File(appContext.filesDir, LOG_FILE_NAME)
        // 限制日志文件大小
        if (logFile?.exists() == true && logFile!!.length() > 2 * 1024 * 1024) {
            logFile?.delete()
        }
        info("AppLogger", "日志系统初始化完成")
    }

    fun debug(tag: String, message: String, throwable: Throwable? = null) {
        addLog(Level.DEBUG, tag, message, throwable)
    }

    fun info(tag: String, message: String, throwable: Throwable? = null) {
        addLog(Level.INFO, tag, message, throwable)
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        addLog(Level.WARN, tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        addLog(Level.ERROR, tag, message, throwable)
    }

    fun crash(tag: String, message: String, throwable: Throwable) {
        addLog(Level.CRASH, tag, message, throwable)
        saveCrashToFile(tag, message, throwable)
    }

    private fun addLog(level: Level, tag: String, message: String, throwable: Throwable?) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message, throwable)
        logEntries.add(entry)
        // 限制内存中的日志数量
        while (logEntries.size > MAX_LOG_ENTRIES) {
            logEntries.poll()
        }
        // 写入文件（ERROR 和 CRASH 级别）
        if (level == Level.ERROR || level == Level.CRASH || level == Level.WARN) {
            writeToFile(entry)
        }
    }

    private fun writeToFile(entry: LogEntry) {
        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.write(entry.format() + "\n")
                }
            }
        } catch (e: Exception) {
            // 忽略写入错误
        }
    }

    private fun saveCrashToFile(tag: String, message: String, throwable: Throwable) {
        try {
            val crashFile = File(appContext.filesDir, CRASH_FILE_NAME)
            FileWriter(crashFile, true).use { writer ->
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                writer.write("========== Crash at $time ==========\n")
                writer.write("Tag: $tag\n")
                writer.write("Message: $message\n")
                writer.write("Stack Trace:\n")
                writer.write(getStackTraceString(throwable))
                writer.write("\n\n")
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun getLogs(): List<LogEntry> {
        return logEntries.toList()
    }

    fun getLogsText(): String {
        return logEntries.joinToString("\n") { it.format() }
    }

    fun getLogFile(): File? = logFile

    fun getCrashFile(): File? {
        return if (::appContext.isInitialized) {
            File(appContext.filesDir, CRASH_FILE_NAME)
        } else null
    }

    fun clearLogs() {
        logEntries.clear()
        try {
            logFile?.delete()
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun clearCrashes() {
        try {
            getCrashFile()?.delete()
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}
