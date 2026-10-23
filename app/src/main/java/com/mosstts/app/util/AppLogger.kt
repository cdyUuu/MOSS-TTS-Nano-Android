package com.mosstts.app.util

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

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
    private const val MAX_LOG_FILE_SIZE = 2L * 1024 * 1024

    private val logEntries = ConcurrentLinkedQueue<LogEntry>()
    private lateinit var appContext: Context
    private var logFile: File? = null

    // 文件写入锁：AppLogger 会被多个线程（下载/录音/合成/主线程）并发调用，保证写入互斥
    private val writeLock = ReentrantLock()
    // 持久化的文件写入器（懒加载单例），整个应用生命周期内保持打开，避免每条日志都开关文件流
    private var logWriter: BufferedWriter? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        logFile = File(appContext.filesDir, LOG_FILE_NAME)
        // 限制日志文件大小
        if (logFile?.exists() == true && logFile!!.length() > MAX_LOG_FILE_SIZE) {
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
        writeLock.lock()
        try {
            val writer = obtainWriterLocked() ?: return
            writer.write(entry.format() + "\n")
            writer.flush()
            // 超过大小限制时关闭并删除旧文件，下次写入时自动重建（按大小滚动）
            val file = logFile
            if (file != null && file.length() > MAX_LOG_FILE_SIZE) {
                closeWriterLocked()
                file.delete()
            }
        } catch (e: Exception) {
            // 写入失败时关闭当前写入器，下次调用重新打开；静默降级不影响 App 运行
            closeWriterLocked()
        } finally {
            writeLock.unlock()
        }
    }

    private fun obtainWriterLocked(): BufferedWriter? {
        logWriter?.let { return it }
        return try {
            val file = logFile ?: return null
            BufferedWriter(FileWriter(file, true)).also { logWriter = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun closeWriterLocked() {
        try {
            logWriter?.close()
        } catch (e: Exception) {
            // 忽略关闭错误
        } finally {
            logWriter = null
        }
    }

    private fun saveCrashToFile(tag: String, message: String, throwable: Throwable) {
        writeLock.lock()
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
        } finally {
            writeLock.unlock()
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
        writeLock.lock()
        try {
            // 先关闭写入器再删除文件，避免持有已删除文件的句柄
            closeWriterLocked()
            logFile?.delete()
        } catch (e: Exception) {
            // 忽略
        } finally {
            writeLock.unlock()
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
