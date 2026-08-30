package com.mosstts.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mosstts.app.data.AppSettings
import com.mosstts.app.viewmodel.TTSViewModel

@Composable
fun SettingsScreen(
    ttsViewModel: TTSViewModel,
    onHideNavigationBarChanged: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val preferences by ttsViewModel.preferences.settings.collectAsState(initial = AppSettings())

    var cpuThreads by remember { mutableStateOf(preferences.cpuThreads.toFloat()) }
    var maxFrames by remember { mutableStateOf(preferences.maxFrames.toFloat()) }
    var streamingPlayback by remember { mutableStateOf(preferences.streamingPlayback) }
    var playbackSpeed by remember { mutableStateOf(preferences.playbackSpeed) }
    var darkMode by remember { mutableStateOf(preferences.darkMode) }
    var hideNavigationBar by remember { mutableStateOf(preferences.hideNavigationBar) }
    var isBatteryOptimized by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val darkModeOptions = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")

    LaunchedEffect(Unit) {
        ttsViewModel.preferences.settings.collect {
            cpuThreads = it.cpuThreads.toFloat()
            maxFrames = it.maxFrames.toFloat()
            streamingPlayback = it.streamingPlayback
            playbackSpeed = it.playbackSpeed
            darkMode = it.darkMode
            hideNavigationBar = it.hideNavigationBar
        }
    }

    // 检查电池优化状态
    LaunchedEffect(Unit) {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        isBatteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 外观设置
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                // 深色模式
                Text("主题模式", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    darkModeOptions.forEach { (value, label) ->
                        TextButton(
                            onClick = {
                                darkMode = value
                                ttsViewModel.updateDarkMode(value)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                label,
                                color = if (darkMode == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (darkMode == value) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 隐藏导航条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            if (hideNavigationBar) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("隐藏导航条", style = MaterialTheme.typography.bodyMedium)
                            Text("沉浸式全屏，滑动边缘可显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(
                        checked = hideNavigationBar,
                        onCheckedChange = { checked ->
                            hideNavigationBar = checked
                            ttsViewModel.updateHideNavigationBar(checked)
                            onHideNavigationBarChanged?.invoke(checked)
                        },
                    )
                }
            }
        }

        // 电池优化
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryStd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("电池优化", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (isBatteryOptimized) "当前应用受电池优化限制，后台下载可能被系统终止" else "应用已加入电池优化白名单，后台下载更稳定",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isBatteryOptimized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isBatteryOptimized) {
                    OutlinedButton(onClick = { requestBatteryOptimization() }) {
                        Text("关闭电池优化")
                    }
                }
            }
        }

        // 推理设置
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("推理设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text("CPU 线程数: ${cpuThreads.toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = cpuThreads,
                    onValueChange = { cpuThreads = it },
                    valueRange = 1f..8f,
                    steps = 6,
                    onValueChangeFinished = { ttsViewModel.updateCpuThreads(cpuThreads.toInt()) },
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("最大生成帧数: ${maxFrames.toInt()} (约 ${(maxFrames / 12.5).toInt()} 秒)", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = maxFrames,
                    onValueChange = { maxFrames = it },
                    valueRange = 100f..500f,
                    steps = 39,
                    onValueChangeFinished = { ttsViewModel.updateMaxFrames(maxFrames.toInt()) },
                )
                Spacer(modifier = Modifier.height(8.dp))

            }
        }

        // 播放设置
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("播放设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text("播放速度", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    speedOptions.forEach { speed ->
                        TextButton(
                            onClick = {
                                playbackSpeed = speed
                                ttsViewModel.updatePlaybackSpeed(speed)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "${speed}x",
                                color = if (playbackSpeed == speed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // 关于
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("MOSS TTS Nano", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Android 客户端", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                val context = LocalContext.current
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                Row {
                    Text("版本号：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${packageInfo.versionName} (${packageInfo.longVersionCode})", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            isCheckingUpdate = true
                            updateMessage = null
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    // 使用 GitHub Atom Feed，无API限流
                                    val url = java.net.URL("https://github.com/cdyUuu/MOSS-TTS-Nano-Android/releases.atom")
                                    val conn = url.openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 10000
                                    conn.readTimeout = 10000
                                    conn.setRequestProperty("User-Agent", "MOSS-TTS-Android/1.0")
                                    val responseCode = conn.responseCode
                                    if (responseCode != 200) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            updateMessage = "检查更新失败：HTTP $responseCode"
                                        }
                                    } else {
                                        val response = conn.inputStream.bufferedReader().readText()
                                        // 解析Atom XML，提取第一个entry的title（最新版本号）
                                        val entryStart = response.indexOf("<entry>")
                                        val titleStart = response.indexOf("<title>", entryStart) + 7
                                        val titleEnd = response.indexOf("</title>", titleStart)
                                        val parsedVersion = response.substring(titleStart, titleEnd).trim()
                                        latestVersion = parsedVersion
                                        val current = packageInfo.versionName
                                        // 解析版本号数字进行比较
                                        val remoteParts = parsedVersion.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
                                        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
                                        var isNewer = false
                                        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
                                            val r = remoteParts.getOrElse(i) { 0 }
                                            val c = currentParts.getOrElse(i) { 0 }
                                            if (r > c) { isNewer = true; break }
                                            if (r < c) { break }
                                        }
                                        val versionStr = parsedVersion
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            if (isNewer) {
                                                updateMessage = "发现新版本：$versionStr，点击下载"
                                            } else {
                                                updateMessage = "已是最新版本"
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        updateMessage = "检查更新失败：${e.message ?: "网络错误"}"
                                    }
                                } finally {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        isCheckingUpdate = false
                                    }
                                }
                            }
                        },
                        enabled = !isCheckingUpdate,
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("检查中...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("检查更新")
                        }
                    }
                }
                if (updateMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        updateMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateMessage?.contains("最新") == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (latestVersion != null && latestVersion != "v${packageInfo.versionName}") {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/cdyUuu/MOSS-TTS-Nano-Android/releases/latest"))
                            context.startActivity(intent)
                        }) {
                            Text("前往下载 ->")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text("项目介绍", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "基于 OpenMOSS 团队开源的 MOSS-TTS-Nano 模型的 Android 端侧语音合成应用。采用 ONNX Runtime 在设备本地进行推理，无需联网即可完成语音合成和声音克隆，保护用户隐私。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("功能特性", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                val features = listOf(
                    "完全离线运行，保护隐私",
                    "内置多种预设音色",
                    "支持声音克隆（录制/导入参考音频）",
                    "支持多个克隆音色管理",
                                        "支持模型导入导出",
                    "多镜像源下载，国内加速",
                    "深色模式适配",
                )
                features.forEach { feature ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(feature, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text("技术栈", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Kotlin + Jetpack Compose + ONNX Runtime + Material 3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                Text("开源协议", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("本项目基于 Apache License 2.0 开源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("MOSS-TTS-Nano 模型版权归 OpenMOSS 团队所有", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

}

/**
 * 比较两个版本号大小，返回正数表示v1>v2，负数表示v1<v2，0表示相等
 */
fun compareVersions(v1: String, v2: String): Int {
    val parts1 = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val parts2 = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 != p2) return p1 - p2
    }
    return 0
}