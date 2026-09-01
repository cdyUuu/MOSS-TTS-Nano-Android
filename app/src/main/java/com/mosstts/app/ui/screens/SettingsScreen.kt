package com.mosstts.app.ui.screens

import com.mosstts.app.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
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
    var appLanguage by remember {
        mutableStateOf(
            context.getSharedPreferences("mosstts_settings", Context.MODE_PRIVATE)
                .getString("app_language", "system") ?: "system"
        )
    }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var tempLanguage by remember { mutableStateOf("system") }
    var isBatteryOptimized by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val darkModeOptions = listOf("system" to stringResource(R.string.settings_dark_system), "light" to stringResource(R.string.settings_dark_light), "dark" to stringResource(R.string.settings_dark_dark))

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
                    Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                // 深色模式
                Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.bodyMedium)
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
                            Text(stringResource(R.string.settings_hide_nav), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.settings_hide_nav_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Spacer(modifier = Modifier.height(12.dp))
                // 语言选择
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            tempLanguage = appLanguage
                            showLanguageDialog = true
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when (appLanguage) {
                                    "zh" -> stringResource(R.string.settings_language_chinese)
                                    "en" -> "English"
                                    else -> stringResource(R.string.settings_language_system)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(stringResource(R.string.settings_battery), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (isBatteryOptimized) stringResource(R.string.settings_battery_optimized) else stringResource(R.string.settings_battery_whitelist),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isBatteryOptimized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isBatteryOptimized) {
                    OutlinedButton(onClick = { requestBatteryOptimization() }) {
                        Text(stringResource(R.string.settings_disable_battery))
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
                    Text(stringResource(R.string.settings_inference), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.settings_cpu_threads) + ": ${cpuThreads.toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = cpuThreads,
                    onValueChange = { cpuThreads = it },
                    valueRange = 1f..8f,
                    steps = 6,
                    onValueChangeFinished = { ttsViewModel.updateCpuThreads(cpuThreads.toInt()) },
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(stringResource(R.string.settings_max_frames_detail, maxFrames.toInt(), (maxFrames / 12.5).toInt()), style = MaterialTheme.typography.bodyMedium)
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
                    Text(stringResource(R.string.settings_playback), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.settings_playback_speed), style = MaterialTheme.typography.bodyMedium)
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
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("MOSS TTS Nano", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_about_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                val context = LocalContext.current
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                Row {
                    Text(stringResource(R.string.settings_version) + ": ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    conn.connectTimeout = 20000
                                    conn.readTimeout = 20000
                                    conn.setRequestProperty("User-Agent", "MOSS-TTS-Android/1.0")
                                    val responseCode = conn.responseCode
                                    if (responseCode != 200) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            updateMessage = context.getString(R.string.about_update_failed) + ": HTTP $responseCode"
                                        }
                                    } else {
                                        val response = conn.inputStream.bufferedReader().readText()
                                        // 解析Atom XML，提取第一个entry的title（最新版本号）
                                        val entryStart = response.indexOf("<entry>")
                                        val titleStart = response.indexOf("<title>", entryStart) + 7
                                        val titleEnd = response.indexOf("</title>", titleStart)
                                        val rawTitle = response.substring(titleStart, titleEnd).trim()
                                        // 用正则提取版本号，支持 "v1.0.7"、"Release v1.0.7"、"1.0.7" 等格式
                                        val versionRegex = Regex("""v?(\d+\.\d+\.\d+)""")
                                        val parsedVersion = versionRegex.find(rawTitle)?.groupValues?.get(1) ?: rawTitle.removePrefix("v")
                                        latestVersion = parsedVersion
                                        val current = packageInfo.versionName
                                        // 解析版本号数字进行比较
                                        val remoteParts = parsedVersion.split(".").map { it.toIntOrNull() ?: 0 }
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
                                                updateMessage = context.getString(R.string.about_new_version) + ": $versionStr"
                                            } else {
                                                updateMessage = context.getString(R.string.about_latest)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        updateMessage = context.getString(R.string.about_update_failed) + ": ${e.message ?: "Network error"}"
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
                            Text(stringResource(R.string.about_checking))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.about_check_update))
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
                            Text(stringResource(R.string.settings_go_download))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.settings_project_intro), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_app_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.settings_features), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                val features = listOf(
                    stringResource(R.string.settings_feature_1),
                    stringResource(R.string.settings_feature_2),
                    stringResource(R.string.settings_feature_3),
                    stringResource(R.string.settings_feature_4),
                                        stringResource(R.string.settings_feature_5),
                    stringResource(R.string.settings_feature_6),
                    stringResource(R.string.settings_feature_7),
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

                Text(stringResource(R.string.settings_tech_stack), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Kotlin + Jetpack Compose + ONNX Runtime + Material 3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.settings_license), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.settings_license_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_model_copyright), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 语言选择对话框
    if (showLanguageDialog) {
        val languages = listOf(
            "system" to "跟随系统",
            "zh" to "简体中文",
            "en" to "English",
        )
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_select_language)) },
            text = {
                Column {
                    languages.forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { tempLanguage = code }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = tempLanguage == code,
                                onClick = { tempLanguage = code },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    appLanguage = tempLanguage
                    // 保存到SharedPreferences（立即生效）
                    context.getSharedPreferences("mosstts_settings", Context.MODE_PRIVATE)
                        .edit()
                        .putString("app_language", tempLanguage)
                        .commit()
                    // 同时保存到DataStore
                    scope.launch {
                        (context.applicationContext as com.mosstts.app.MossTTSApp).preferences.setLanguage(tempLanguage)
                    }
                    showLanguageDialog = false
                    // 重启Activity以应用语言
                    (context as? android.app.Activity)?.recreate()
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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