package com.mosstts.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mosstts.app.MainActivity
import com.mosstts.app.MossTTSApp
import com.mosstts.app.R
import com.mosstts.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 模型下载前台服务。保持前台运行，显示进度通知，防止被系统杀死。
 * 实际下载由 ModelViewModel 执行，本服务只负责通知和保活。
 */
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.mosstts.app.START_DOWNLOAD"
        private const val ACTION_PAUSE = "com.mosstts.app.PAUSE_DOWNLOAD"
        private const val ACTION_RESUME = "com.mosstts.app.RESUME_DOWNLOAD"
        private const val ACTION_CANCEL = "com.mosstts.app.CANCEL_DOWNLOAD"

        // 暂停状态（静态，供 ViewModel 查询）
        @Volatile
        var isPaused = false
            private set

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            isPaused = true
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            isPaused = false
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun cancel(context: Context) {
            isPaused = false
            isRunning = false
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isPaused = false
        startForeground(NOTIFICATION_ID, createNotification("准备下载...", "", 0, false))
        AppLogger.info(TAG, "下载服务已创建")

        // 监听共享 downloader 的进度
        progressJob = serviceScope.launch {
            (application as MossTTSApp).modelDownloader.progress.collectLatest { progress ->
                progress?.let {
                    val percent = if (it.totalBytes > 0) {
                        (it.downloadedBytes * 100 / it.totalBytes).toInt()
                    } else 0
                    val speedStr = formatSpeed(it.speedBytesPerSecond)
                    val title = when {
                        it.isComplete -> "下载完成"
                        isPaused -> "已暂停"
                        else -> "下载中 (${it.currentFileIndex}/${it.totalFiles}) $percent%"
                    }
                    val content = when {
                        it.isComplete -> "所有文件下载完成"
                        isPaused -> "已暂停，点击继续"
                        else -> "${it.fileName} · $speedStr"
                    }
                    updateNotification(title, content, percent, it.isComplete)
                    if (it.isComplete) {
                        // 延迟停止
                        serviceScope.launch {
                            kotlinx.coroutines.delay(3000)
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isRunning = true
                isPaused = false
                updateNotification("开始下载...", "", 0, false)
            }
            ACTION_PAUSE -> {
                isPaused = true
                updateNotification("已暂停", "点击继续", 0, false)
            }
            ACTION_RESUME -> {
                isPaused = false
                updateNotification("继续下载...", "", 0, false)
            }
            ACTION_CANCEL -> {
                isRunning = false
                isPaused = false
                updateNotification("下载已取消", "", 0, true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond <= 0 -> ""
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSecond / 1024.0)
            else -> String.format("%.2f MB/s", bytesPerSecond / (1024.0 * 1024))
        }
    }

    private fun createNotification(title: String, content: String, percent: Int, isComplete: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, MossTTSApp.CHANNEL_ID_DOWNLOAD)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(!isComplete)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!isComplete && percent > 0) {
            builder.setProgress(100, percent, false)
        } else if (!isComplete) {
            builder.setProgress(0, 0, true)
        }

        // 添加暂停/继续按钮
        if (!isComplete) {
            val pauseIntent = Intent(this, ModelDownloadService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            }
            val pausePendingIntent = PendingIntent.getService(
                this, 1, pauseIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                0,
                if (isPaused) "继续" else "暂停",
                pausePendingIntent
            )

            // 添加取消按钮
            val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            val cancelPendingIntent = PendingIntent.getService(
                this, 2, cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "取消", cancelPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(title: String, content: String, percent: Int, isComplete: Boolean) {
        try {
            val notification = createNotification(title, content, percent, isComplete)
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            AppLogger.error(TAG, "更新通知失败: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        progressJob?.cancel()
        serviceScope.cancel()
        AppLogger.info(TAG, "下载服务已销毁")
    }
}
