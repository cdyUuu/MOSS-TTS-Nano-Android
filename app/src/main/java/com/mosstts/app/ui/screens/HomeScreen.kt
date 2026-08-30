package com.mosstts.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mosstts.app.viewmodel.ModelViewModel
import com.mosstts.app.viewmodel.TTSViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ttsViewModel: TTSViewModel,
    modelViewModel: ModelViewModel,
) {
    val text by ttsViewModel.text.collectAsState()
    val voices by ttsViewModel.voices.collectAsState()
    val selectedVoice by ttsViewModel.selectedVoice.collectAsState()
    val clonedVoices by ttsViewModel.clonedVoices.collectAsState()
    val selectedClonedVoiceId by ttsViewModel.selectedClonedVoiceId.collectAsState()
    val isSynthesizing by ttsViewModel.isSynthesizing.collectAsState()
    val isPlaying by ttsViewModel.isPlaying.collectAsState()
    val saveMessage by ttsViewModel.saveMessage.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    val synthesisProgress by ttsViewModel.synthesisProgress.collectAsState()
    val playbackProgress by ttsViewModel.playbackProgress.collectAsState()
    val lastResult by ttsViewModel.lastResult.collectAsState()
    val referenceAudioName by ttsViewModel.referenceAudioName.collectAsState()
    val referenceAudioCodes by ttsViewModel.referenceAudioCodes.collectAsState()
    val errorMessage by ttsViewModel.errorMessage.collectAsState()
    val isModelReady by modelViewModel.isModelReady.collectAsState()
    val engineState by ttsViewModel.modelManager.engineState.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var saveFileName by remember { mutableStateOf("") }

    // 监听模型就绪状态，自动初始化引擎
    LaunchedEffect(isModelReady, engineState) {
        if (isModelReady && engineState == com.mosstts.app.data.ModelManager.EngineState.NOT_READY) {
            ttsViewModel.initializeEngine()
        }
    }

    // 引擎就绪后加载音色
    LaunchedEffect(engineState) {
        if (engineState == com.mosstts.app.data.ModelManager.EngineState.READY) {
            ttsViewModel.loadVoices()
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { ttsViewModel.clearError() },
            title = { Text("提示") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { ttsViewModel.clearError() }) {
                    Text("确定")
                }
            }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存音频") },
            text = {
                OutlinedTextField(
                    value = saveFileName,
                    onValueChange = { saveFileName = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (saveFileName.isNotEmpty()) {
                        ttsViewModel.saveCurrentAudio(if (saveFileName.endsWith(".wav")) saveFileName else "$saveFileName.wav")
                        showSaveDialog = false
                        saveFileName = ""
                    }
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 模型状态卡片
        if (!isModelReady) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "模型未下载",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "请先前往「模型管理」页面下载 MOSS-TTS-Nano 模型文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else if (engineState == com.mosstts.app.data.ModelManager.EngineState.LOADING) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("正在加载模型引擎...")
                }
            }
        }

        // 文本输入
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "输入文本",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { ttsViewModel.updateText(it) },
                    placeholder = { Text("输入要合成的文本，支持中文、英文等 20 种语言...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    maxLines = 8,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${text.length} 字符",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        TextButton(onClick = { ttsViewModel.updateText("") }) {
                            Text("清空")
                        }
                        TextButton(onClick = {
                            ttsViewModel.updateText("欢迎使用 MOSS-TTS-Nano，这是一个轻量级的多语言语音合成模型，支持语音克隆和实时流式生成。")
                        }) {
                            Text("示例")
                        }
                    }
                }
            }
        }

        // 音色选择（统一显示内置+克隆）
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "选择音色",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                var expanded by remember { mutableStateOf(false) }
                val currentDisplayName = when {
                    selectedClonedVoiceId != null -> clonedVoices.find { it.id == selectedClonedVoiceId }?.name ?: "克隆音色"
                    selectedVoice.isNotEmpty() -> selectedVoice
                    else -> "请选择音色"
                }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = currentDisplayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("音色（内置 + 克隆）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        // 内置音色分组
                        DropdownMenuItem(
                            text = { Text("内置音色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                            onClick = {},
                            enabled = false,
                        )
                        voices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text("  $voice") },
                                onClick = {
                                    ttsViewModel.selectVoice(voice)
                                    ttsViewModel.clearReferenceAudio()
                                    expanded = false
                                }
                            )
                        }
                        // 克隆音色分组
                        if (clonedVoices.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("克隆音色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                onClick = {},
                                enabled = false,
                            )
                            clonedVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text("  ${voice.name}") },
                                    onClick = {
                                        ttsViewModel.selectClonedVoice(voice.id)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "前往「音色克隆」页面可录制或导入参考音频，创建自定义音色",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 合成进度
        AnimatedVisibility(visible = isSynthesizing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "正在合成语音...",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "${(synthesisProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { synthesisProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 波形动画
                    WaveformAnimation(isPlaying = true)
                }
            }
        }

        // 播放控制
        AnimatedVisibility(visible = lastResult != null || isPlaying) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "合成结果",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            lastResult?.let {
                                Text(
                                    "时长: ${it.durationMs / 1000.0}s | ${it.generatedFrames} 帧 | ${it.textChunks.size} 段",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row {
                            IconButton(onClick = { showSaveDialog = true }) {
                                Icon(Icons.Default.Save, contentDescription = "保存")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // 播放进度条
                    LinearProgressIndicator(
                        progress = { playbackProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // 播放控制按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isPlaying) {
                            Button(
                                onClick = { ttsViewModel.pausePlayback() },
                                modifier = Modifier.size(56.dp),
                                shape = CircleShape,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "暂停", modifier = Modifier.size(28.dp))
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (lastResult != null) ttsViewModel.resumePlayback()
                                },
                                modifier = Modifier.size(56.dp),
                                shape = CircleShape,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                enabled = lastResult != null
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "播放", modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(24.dp))
                        FilledTonalButton(
                            onClick = { ttsViewModel.stopPlayback() },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "停止", modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(24.dp))
                        OutlinedButton(
                            onClick = { ttsViewModel.saveCurrentAudio() },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            enabled = lastResult != null
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "保存", modifier = Modifier.size(24.dp))
                        }
                    }
                    // 保存成功消息
                    if (saveMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    saveMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { ttsViewModel.clearSaveMessage() }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 合成按钮
        Button(
            onClick = { ttsViewModel.synthesizeAndPlay() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isSynthesizing && text.isNotBlank() && isModelReady,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (isSynthesizing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("合成中...", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始合成", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun WaveformAnimation(isPlaying: Boolean) {
    val barCount = 32
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val animatedValues = List(barCount) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600 + index * 30, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        animatedValues.forEach { animated ->
            val barHeight = ((if (isPlaying) animated.value else 0.2f) * 32f).dp
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
            )
        }
    }
}
