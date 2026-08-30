package com.mosstts.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mosstts.app.viewmodel.TTSViewModel
import java.io.File
import java.io.FileOutputStream

@Composable
fun VoiceCloneScreen(ttsViewModel: TTSViewModel) {
    val isRecording by ttsViewModel.isRecording.collectAsState()
    val referenceAudioName by ttsViewModel.referenceAudioName.collectAsState()
    val referenceAudioCodes by ttsViewModel.referenceAudioCodes.collectAsState()
    val isSynthesizing by ttsViewModel.isSynthesizing.collectAsState()
    val errorMessage by ttsViewModel.errorMessage.collectAsState()
    val clonedVoices by ttsViewModel.clonedVoices.collectAsState()
    val selectedClonedVoiceId by ttsViewModel.selectedClonedVoiceId.collectAsState()
    val context = LocalContext.current

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var voiceToDelete by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var voiceToRename by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 复制到应用私有目录
            val inputStream = context.contentResolver.openInputStream(it)
            val tempFile = File(context.cacheDir, "ref_audio_${System.currentTimeMillis()}.wav")
            inputStream?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            ttsViewModel.importReferenceAudio(tempFile)
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这个克隆音色吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    voiceToDelete?.let { ttsViewModel.deleteClonedVoice(it) }
                    voiceToDelete = null
                    showDeleteConfirm = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 重命名对话框
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名音色") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("音色名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    voiceToRename?.let { id ->
                        if (renameText.isNotBlank()) {
                            ttsViewModel.renameClonedVoice(id, renameText.trim())
                        }
                    }
                    showRenameDialog = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
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
        // 说明卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "语音克隆",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "录制 5-10 秒清晰的参考音频，或导入 WAV 文件，即可用该音色合成语音。参考音频越清晰、噪声越少，克隆效果越好。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // 我的克隆音色列表
        if (clonedVoices.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("我的克隆音色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("${clonedVoices.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    clonedVoices.forEach { voice ->
                        val isSelected = selectedClonedVoiceId == voice.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { ttsViewModel.selectClonedVoice(voice.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(voice.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        "创建于 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(voice.createdAt))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    voiceToRename = voice.id
                                    renameText = voice.name
                                    showRenameDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "重命名", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = {
                                    voiceToDelete = voice.id
                                    showDeleteConfirm = true
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 当前音色状态
        val codes = referenceAudioCodes
        if (codes != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "当前克隆音色",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            referenceAudioName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "${codes.size} 帧参考音频",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "清除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 录音区域
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "录制参考音频",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(24.dp))

                // 录音按钮
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRecording) {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.error,
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                )
                            }
                        )
                        .clickable {
                            if (isRecording) {
                                ttsViewModel.stopRecordingAndUse()
                            } else {
                                ttsViewModel.startRecording()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "停止录音",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "开始录音",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (isRecording) "正在录音，点击停止并使用" else "点击开始录音",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isRecording) {
                    Spacer(modifier = Modifier.height(16.dp))
                    RecordingWaveform()
                }
            }
        }

        // 导入文件
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "导入参考音频",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "支持 WAV 格式音频文件，建议 48kHz 采样率，时长 5-10 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("audio/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("选择音频文件")
                }
            }
        }

        // 使用提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "使用建议",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                val tips = listOf(
                    "在安静环境中录制，避免背景噪声",
                    "参考音频时长建议 5-10 秒，语音清晰",
                    "录制时保持自然语速，不要过快或过慢",
                    "克隆音色设置后，在「语音合成」页面自动使用",
                    "不同语言的参考音频会影响合成效果"
                )
                tips.forEach { tip ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium)
                        Text(tip, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun RecordingWaveform() {
    val barCount = 24
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val height = (20 + Math.sin((index + System.currentTimeMillis() / 100.0) * 0.5) * 12).toInt().coerceAtLeast(8)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}
