package com.mosstts.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mosstts.app.util.AppLogger
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(AppLogger.getLogs()) }
    var showCrashes by remember { mutableStateOf(false) }
    var crashText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun loadCrashes() {
        val crashFile = AppLogger.getCrashFile()
        crashText = if (crashFile?.exists() == true) {
            crashFile.readText()
        } else {
            "暂无崩溃记录"
        }
    }

    LaunchedEffect(Unit) {
        loadCrashes()
    }

    fun refreshLogs() {
        logs = AppLogger.getLogs()
        loadCrashes()
    }

    fun exportLogs(): File {
        val exportFile = File(context.getExternalFilesDir(null), "mosstts_logs_${System.currentTimeMillis()}.txt")
        val content = buildString {
            append("========== MOSS TTS Nano 日志导出 ==========\n")
            append("导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n\n")
            append("---------- 运行日志 ----------\n")
            logs.forEach { append(it.format() + "\n") }
            append("\n---------- 崩溃记录 ----------\n")
            append(crashText)
        }
        exportFile.writeText(content)
        return exportFile
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志查看", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    IconButton(onClick = { refreshLogs() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = {
                        AppLogger.clearLogs()
                        refreshLogs()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "清除日志")
                    }
                    IconButton(onClick = {
                        AppLogger.clearCrashes()
                        loadCrashes()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "清除崩溃")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 切换标签
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showCrashes = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("运行日志 (${logs.size})")
                }
                OutlinedButton(
                    onClick = { showCrashes = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("崩溃记录")
                }
            }

            // 导出按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val scope = rememberCoroutineScope()
                var isExporting by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isExporting = true
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    exportLogs()
                                }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                                intent.type = "text/plain"
                                intent.putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(android.content.Intent.createChooser(intent, "分享日志"))
                            } catch (e: Exception) {
                                AppLogger.error("LogViewer", "导出日志失败", e)
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isExporting,
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出中...")
                    } else {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出/分享")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (showCrashes) {
                // 崩溃记录
                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    ) {
                        item {
                            Text(
                                crashText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFFFF6B6B),
                            )
                        }
                    }
                }
            } else {
                // 运行日志
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(logs) { log ->
                        val levelColor = when (log.level) {
                            AppLogger.Level.DEBUG -> Color(0xFF888888)
                            AppLogger.Level.INFO -> Color(0xFF4CAF50)
                            AppLogger.Level.WARN -> Color(0xFFFF9800)
                            AppLogger.Level.ERROR -> Color(0xFFFF5252)
                            AppLogger.Level.CRASH -> Color(0xFFFF0000)
                        }
                        Text(
                            log.format(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = levelColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
