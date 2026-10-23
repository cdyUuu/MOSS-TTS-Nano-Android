package com.mosstts.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.mosstts.app.data.ModelDownloader
import com.mosstts.app.data.ModelManager
import com.mosstts.app.data.PreferencesManager
import com.mosstts.app.util.AppLogger
import com.mosstts.app.util.CrashHandler
import java.util.Locale

class MossTTSApp : Application() {
    lateinit var preferences: PreferencesManager
        private set
    lateinit var modelDownloader: ModelDownloader
        private set

    /**
     * 全局共享的模型管理器单例。
     * 供应用界面（TTSViewModel）与系统 TTS 引擎（MossTTSService）复用，
     * 避免同一进程内重复创建 ModelManager、重复加载 ONNX 模型。
     */
    val modelManager: ModelManager by lazy { ModelManager(this) }

    companion object {
        lateinit var instance: MossTTSApp
            private set
        const val CHANNEL_ID_DOWNLOAD = "model_download"

        /**
         * 从指定Context获取当前设置的语言对应的Locale，null表示跟随系统
         */
        fun getLocaleFromContext(context: Context): Locale? {
            val lang = context.getSharedPreferences("mosstts_settings", Context.MODE_PRIVATE)
                .getString("app_language", "system") ?: "system"
            return when (lang) {
                "zh" -> Locale.SIMPLIFIED_CHINESE
                "en" -> Locale.ENGLISH
                else -> null
            }
        }

        /**
         * 包装Context，应用语言设置
         */
        fun wrapContext(context: Context): Context {
            val locale = getLocaleFromContext(context) ?: return context
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    }

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

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(wrapContext(base))
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
            // 部分定制ROM上 getSystemService 可能返回 null，空安全保护避免启动即崩溃
            manager?.createNotificationChannel(channel)
        }
    }
}
