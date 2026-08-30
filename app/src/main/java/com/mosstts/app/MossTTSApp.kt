package com.mosstts.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mosstts.app.data.ModelDownloader
import com.mosstts.app.data.PreferencesManager
import com.mosstts.app.util.AppLogger
import com.mosstts.app.util.CrashHandler

class MossTTSApp : Application() {
    lateinit var preferences: PreferencesManager
        private set

    lateinit var modelDownloader: ModelDownloader
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 先初始化日志系统
        AppLogger.init(this)
        // 初始化崩溃捕获
        CrashHandler.init(this)
        preferences = PreferencesManager(this)
        // 创建共享的模型下载器实例
        modelDownloader = ModelDownloader(this)
        createNotificationChannel()
        AppLogger.info("MossTTSApp", "应用启动完成")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_DOWNLOAD,
                "模型下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "模型文件下载进度通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID_DOWNLOAD = "model_download"
        lateinit var instance: MossTTSApp
            private set
    }
}
