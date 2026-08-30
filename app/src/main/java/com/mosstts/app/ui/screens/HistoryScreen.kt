package com.mosstts.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mosstts.app.data.SynthesisHistory
import com.mosstts.app.viewmodel.TTSViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    ttsViewModel: TTSViewModel,
    onNavigateToHome: (String) -> Unit = {},
) {
    val history by ttsViewModel.history.collectAsState()
    val isPlaying by ttsViewModel.isPlaying.collectAsState()
    val playbackProgress by ttsViewModel.playbackProgress.collectAsState()

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var currentPlayingItem by remember { mutableStateOf<SynthesisHistory?>(null) }

    // 页面销毁时停止播放
    DisposableEffect(Unit) {
        onDispose {
            ttsViewModel.stopPlayback()
            currentPlayingItem = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("合成历史", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Text("${history.size} 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 操作栏
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (selectedIds.isNotEmpty()) {
                            showDeleteConfirm = true
                        }
                    },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除选中(${selectedIds.size})")
                }
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清空全部")
                }
            }
        }

        // 历史记录列表
        if (history.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("暂无合成历史", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("合成的语音会自动保存到这里", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { item ->
                    val hasCache = item.audioPath != null && java.io.File(item.audioPath).exists()
                    HistoryItem(
                        item = item,
                        isSelected = item.id in selectedIds,
                        isCurrentPlaying = currentPlayingItem?.id == item.id,
                        hasCache = hasCache,
                        onPlay = {
                            if (hasCache) {
                                // 有缓存，在当前页面播放
                                if (currentPlayingItem?.id == item.id) {
                                    // 同一条，切换播放/暂停
                                    if (isPlaying) {
                                        ttsViewModel.pausePlayback()
                                    } else {
                                        ttsViewModel.resumePlayback()
                                    }
                                } else {
                                    // 新的一条，开始播放
                                    currentPlayingItem = item
                                    ttsViewModel.playHistoryAudio(item)
                                }
                            } else {
                                // 没有缓存，跳转到合成页面重新生成
                                onNavigateToHome(item.text)
                            }
                        },
                        onSave = {
                            ttsViewModel.saveHistoryAudio(item)
                        },
                        onToggleSelect = {
                            selectedIds = if (item.id in selectedIds) {
                                selectedIds - item.id
                            } else {
                                selectedIds + item.id
                            }
                        }
                    )
                }
            }
        }

        // 底部播放器控制条
        if (currentPlayingItem != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            if (isPlaying) {
                                ttsViewModel.pausePlayback()
                            } else {
                                ttsViewModel.resumePlayback()
                            }
                        }) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                currentPlayingItem!!.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { playbackProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            ttsViewModel.stopPlayback()
                            currentPlayingItem = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    // 删除确认
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedIds.size} 条历史记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    // 如果删除的是当前播放的，停止播放
                    if (currentPlayingItem?.id in selectedIds) {
                        ttsViewModel.stopPlayback()
                        currentPlayingItem = null
                    }
                    ttsViewModel.deleteHistories(selectedIds.toList())
                    selectedIds = emptySet()
                    showDeleteConfirm = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 清空确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清空") },
            text = { Text("确定要清空所有历史记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ttsViewModel.stopPlayback()
                    currentPlayingItem = null
                    ttsViewModel.clearHistory()
                    selectedIds = emptySet()
                    showClearConfirm = false
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun HistoryItem(
    item: SynthesisHistory,
    isSelected: Boolean,
    isCurrentPlaying: Boolean,
    hasCache: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelect() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrentPlaying -> MaterialTheme.colorScheme.primaryContainer
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${item.voice} · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))} · ${item.durationMs / 1000}s" +
                        if (hasCache) " · 已缓存" else " · 未缓存",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasCache) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasCache) {
                IconButton(onClick = onSave) {
                    Icon(Icons.Default.Save, contentDescription = "保存", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onPlay) {
                Icon(
                    if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (hasCache) "播放" else "去合成",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
