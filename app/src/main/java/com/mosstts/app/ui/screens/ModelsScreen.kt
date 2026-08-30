package com.mosstts.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mosstts.app.data.ModelManager
import com.mosstts.app.viewmodel.ModelViewModel
import com.mosstts.app.viewmodel.TTSViewModel

@Composable
fun ModelsScreen(
    modelViewModel: ModelViewModel = viewModel(),
    ttsViewModel: TTSViewModel = viewModel(),
) {
    val context = LocalContext.current
    val isModelReady by modelViewModel.isModelReady.collectAsState()
    val isVoiceCloneReady by modelViewModel.isVoiceCloneReady.collectAsState()
    val isDownloading by modelViewModel.isDownloading.collectAsState()
    val isPaused by modelViewModel.isPaused.collectAsState()
    val downloadProgress by modelViewModel.downloadProgress.collectAsState()
    val modelSize by modelViewModel.modelSize.collectAsState()
    val errorMessage by modelViewModel.errorMessage.collectAsState()
    val successMessage by modelViewModel.successMessage.collectAsState()
    val selectedMirrorId by modelViewModel.selectedMirrorId.collectAsState()
    val engineState by ttsViewModel.modelManager.engineState.collectAsState()
    val engineError = ttsViewModel.modelManager.initError
    val isTestingSpeed by modelViewModel.isTestingSpeed.collectAsState()
    val speedTestResults by modelViewModel.speedTestResults.collectAsState()
    val isExporting by modelViewModel.isExporting.collectAsState()
    val exportProgress by modelViewModel.exportProgress.collectAsState()
    val isImporting by modelViewModel.isImporting.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }

    // 导出/导入 launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { modelViewModel.exportModels(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { modelViewModel.importModels(it) }
    }

    // 下载完成后自动初始化引擎
    LaunchedEffect(isModelReady) {
        if (isModelReady && engineState == ModelManager.EngineState.NOT_READY) {
            ttsViewModel.initializeEngine()
        }
    }

    // 下载过程中实时更新已占用空间
    LaunchedEffect(downloadProgress) {
        if (isDownloading) {
            modelViewModel.checkModelStatus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 模型状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isModelReady) Icons.Default.CheckCircle else Icons.Default.Storage,
                        contentDescription = null,
                        tint = if (isModelReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("模型状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                StatusRow("TTS 模型", if (isModelReady) "已就绪" else "未下载", isModelReady)
                StatusRow("语音克隆模型", if (isVoiceCloneReady) "已就绪" else "未下载（可选）", isVoiceCloneReady)
                StatusRow("已占用空间", modelViewModel.formatSize(modelSize), true)
                StatusRow(
                    "推理引擎",
                    when (engineState) {
                        ModelManager.EngineState.READY -> "已加载"
                        ModelManager.EngineState.LOADING -> "加载中..."
                        ModelManager.EngineState.ERROR -> "加载失败"
                        ModelManager.EngineState.SYNTHESIZING -> "合成中"
                        else -> "未加载"
                    },
                    engineState == ModelManager.EngineState.READY,
                )

                // 引擎加载失败错误信息
                if (engineState == ModelManager.EngineState.ERROR && engineError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("引擎加载失败", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(engineError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { ttsViewModel.initializeEngine() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重试加载")
                            }
                        }
                    }
                }
            }
        }

        // 镜像源选择
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载镜像源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { modelViewModel.testMirrorSpeeds() },
                        enabled = !isTestingSpeed && !isDownloading,
                    ) {
                        if (isTestingSpeed) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("测速中")
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("一键测速")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("选择下载速度最快的镜像源，测速后自动选择最快节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                modelViewModel.mirrorSources.forEach { mirror ->
                    val speedResult = speedTestResults.firstOrNull { it.mirrorId == mirror.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(mirror.name, style = MaterialTheme.typography.bodyMedium)
                                if (speedResult != null && speedResult.success) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        modelViewModel.formatSpeed(speedResult.speedBytesPerSecond),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "${speedResult.pingMs}ms",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (speedResult != null && !speedResult.success) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "不可用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Text(mirror.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = selectedMirrorId == mirror.id,
                            onCheckedChange = { checked ->
                                if (checked && !isDownloading) {
                                    modelViewModel.selectMirror(mirror.id)
                                }
                            },
                            enabled = !isDownloading,
                        )
                    }
                }
            }
        }

        // 下载进度
        if (isDownloading || downloadProgress != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isDownloading && !isPaused) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                isPaused -> "已暂停"
                                downloadProgress?.isComplete == true -> "下载完成"
                                else -> "正在下载模型..."
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    downloadProgress?.let { progress ->
                        val percent = if (progress.totalBytes > 0) {
                            (progress.downloadedBytes * 100 / progress.totalBytes).toInt()
                        } else 0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "第 ${progress.currentFileIndex}/${progress.totalFiles} 个文件",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "$percent%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            progress.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${modelViewModel.formatSize(progress.downloadedBytes)} / ${modelViewModel.formatSize(progress.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (progress.speedBytesPerSecond > 0) {
                                Text(
                                    modelViewModel.formatSpeed(progress.speedBytesPerSecond),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (progress.totalBytes > 0) progress.downloadedBytes.toFloat() / progress.totalBytes else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        progress.error?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    // 暂停/继续/取消按钮
                    if (isDownloading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isPaused) {
                                Button(onClick = { modelViewModel.resumeDownload() }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("继续")
                                }
                            } else {
                                OutlinedButton(onClick = { modelViewModel.pauseDownload() }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("暂停")
                                }
                            }
                            OutlinedButton(onClick = { modelViewModel.cancelDownload() }) {
                                Text("取消")
                            }
                        }
                    }
                }
            }
        }

        // 错误提示
        if (errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("操作失败", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { modelViewModel.clearError() }) {
                        Text("关闭")
                    }
                }
            }
        }

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isDownloading) {
                Button(
                    onClick = { modelViewModel.startDownload() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isModelReady) "重新下载" else "下载模型")
                }
            }

            if (isModelReady) {
                OutlinedButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                }
            }
        }

        // 模型导入导出
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("模型备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("导出模型到 zip 文件备份，或从 zip 文件导入模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("mosstts_models.zip") },
                        modifier = Modifier.weight(1f),
                        enabled = isModelReady,
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出模型")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导入模型")
                    }
                    // 导出/导入进度提示
                    if (isExporting && exportProgress.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            exportProgress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (isImporting) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "正在导入模型，请稍候...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // 使用说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("使用说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("1. 首次使用需下载模型文件（约 700MB），建议在 WiFi 环境下下载", style = MaterialTheme.typography.bodySmall)
                Text("2. 下载使用前台服务，可在通知栏暂停/继续，防止被系统杀死", style = MaterialTheme.typography.bodySmall)
                Text("3. 下载完成后引擎会自动加载，返回「合成」页面即可使用", style = MaterialTheme.typography.bodySmall)
                Text("4. 建议在设置中关闭电池优化，以获得更稳定的后台下载", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除模型") },
            text = { Text("确定要删除所有已下载的模型文件吗？删除后需要重新下载才能使用。") },
            confirmButton = {
                TextButton(onClick = {
                    modelViewModel.deleteModels()
                    showDeleteDialog = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
    }
}
