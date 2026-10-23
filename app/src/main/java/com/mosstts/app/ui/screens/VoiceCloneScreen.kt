package com.mosstts.app.ui.screens

import com.mosstts.app.R

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    // 克隆/导入成功消息（此前未收集，导致成功后用户看不到任何反馈）
    val saveMessage by ttsViewModel.saveMessage.collectAsState()
    val clonedVoices by ttsViewModel.clonedVoices.collectAsState()
    val selectedClonedVoiceId by ttsViewModel.selectedClonedVoiceId.collectAsState()
    val context = LocalContext.current

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var voiceToDelete by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var voiceToRename by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    val filePickerScope = rememberCoroutineScope()
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 在后台线程复制文件，避免大文件卡顿
            filePickerScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val tempFile = File(context.filesDir, "ref_audio_${System.currentTimeMillis()}.wav")
                    inputStream?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        ttsViewModel.importReferenceAudio(tempFile)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        ttsViewModel.showError("导入音频失败：${e.message ?: "未知错误"}")
                    }
                }
            }
        }
    }

    // 录音权限申请（targetSdk 34 必须动态申请 RECORD_AUDIO，否则 startRecording 会因 SecurityException 失败或无声）
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 授权后自动开始录音
            ttsViewModel.startRecording()
        } else {
            // 被拒绝时给出提示，避免静默失败
            ttsViewModel.showError("需要录音权限才能录制参考音频，请在系统设置中授予后重试")
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { ttsViewModel.clearError() },
            title = { Text(stringResource(R.string.dialog_title_tip)) },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { ttsViewModel.clearError() }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    // 克隆/保存成功消息：与 errorMessage 对话框同样式展示，避免成功反馈走错误通道被误读
    if (saveMessage != null) {
        AlertDialog(
            onDismissRequest = { ttsViewModel.clearSaveMessage() },
            title = { Text(stringResource(R.string.dialog_title_tip)) },
            text = { Text(saveMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { ttsViewModel.clearSaveMessage() }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.action_confirm)) },
            text = { Text(stringResource(R.string.voice_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    voiceToDelete?.let { ttsViewModel.deleteClonedVoice(it) }
                    voiceToDelete = null
                    showDeleteConfirm = false
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 重命名对话框
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.dialog_rename_voice)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.dialog_voice_name)) },
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
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
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
                    stringResource(R.string.voice_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.voice_record_hint),
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
                        Text(stringResource(R.string.voice_my_cloned), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.voice_count, clonedVoices.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        stringResource(R.string.voice_created_at) + " ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(voice.createdAt))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    voiceToRename = voice.id
                                    renameText = voice.name
                                    showRenameDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_rename), modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = {
                                    voiceToDelete = voice.id
                                    showDeleteConfirm = true
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
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
                            stringResource(R.string.voice_current),
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
                            stringResource(R.string.voice_frames, codes.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    // 清除当前参考音频：此前只设置了 showDeleteConfirm 但未设置 voiceToDelete，
                    // 确认对话框里 voiceToDelete 为空或残留上一条记录，会删错音色/无任何反应
                    IconButton(onClick = { ttsViewModel.clearReferenceAudio() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.cd_clear),
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
                    stringResource(R.string.voice_record_section),
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
                            } else if (
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                // 已有录音权限才启动录音，避免 SecurityException / 无声
                                ttsViewModel.startRecording()
                            } else {
                                // 无权限时先申请，授权回调中再启动录音
                                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.cd_stop_record),
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = stringResource(R.string.cd_start_record),
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (isRecording) stringResource(R.string.voice_recording_tap_stop) else stringResource(R.string.voice_tap_start),
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
                    stringResource(R.string.voice_import_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.voice_import_hint),
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
                    Text(stringResource(R.string.voice_select_file))
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
                    stringResource(R.string.voice_tip_1),
                    stringResource(R.string.voice_tip_2),
                    stringResource(R.string.voice_tip_3),
                    stringResource(R.string.voice_tip_4),
                    stringResource(R.string.voice_tip_5)
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
    // 用无限过渡动画驱动正弦相位：此前使用 System.currentTimeMillis() 读取时间，
    // 非动画 Composable 不会因时间流逝而重组，波形是静止的
    val infiniteTransition = rememberInfiniteTransition(label = "recording_waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            // 每根柱子按索引产生相位偏移，随动画相位起伏，形成流动的波形效果
            val amplitude = (Math.sin((index * 0.5f + phase).toDouble()) * 0.5 + 0.5).toFloat()
            val height = (8 + amplitude * 22).toInt().coerceAtLeast(4)
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
