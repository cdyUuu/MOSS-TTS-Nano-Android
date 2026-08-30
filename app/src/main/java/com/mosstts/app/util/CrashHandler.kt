package com.mosstts.app.util

import android.content.Context
import kotlin.system.exitProcess

/**
 * 全局崩溃捕获处理器。记录崩溃日志并优雅退出。
 */
class CrashHandler private constructor(
    private val context: Context,
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            AppLogger.crash(
                "CrashHandler",
                "线程 ${thread.name} 发生未捕获异常",
                throwable,
            )
            // 额外记录设备信息
            AppLogger.error(
                "CrashHandler",
                "设备信息: SDK=${android.os.Build.VERSION.SDK_INT}, " +
                    "Model=${android.os.Build.MODEL}, " +
                    "Manufacturer=${android.os.Build.MANUFACTURER}, " +
                    "Memory=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB",
            )
        } catch (e: Exception) {
            // 忽略日志记录错误
        }

        try {
            // 等待一小段时间确保日志写入文件
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            // 忽略
        }

        // 调用默认处理器（让系统显示崩溃对话框）
        defaultHandler?.uncaughtException(thread, throwable)

        // 确保进程退出
        try {
            exitProcess(1)
        } catch (e: Exception) {
            // 忽略
        }
    }

    companion object {
        fun init(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            AppLogger.info("CrashHandler", "全局崩溃处理器已初始化")
        }
    }
}
