package com.mosstts.app.viewmodel

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.mosstts.app.util.AppLogger
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mosstts.app.MossTTSApp
import com.mosstts.app.data.ClonedVoice
import com.mosstts.app.data.ClonedVoiceStore
import com.mosstts.app.data.HistoryStore
import com.mosstts.app.data.SynthesisHistory
import com.mosstts.app.data.ModelManager
import com.mosstts.app.data.PreferencesManager
import com.mosstts.app.engine.StreamingAudioPlayer
import com.mosstts.app.engine.SynthesisResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TTSViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TTSViewModel"
        /** 单帧音频采样点数：48000Hz / 12.5Hz 帧率 = 3840 */
        private const val SAMPLES_PER_FRAME = 3840
        /** 最大录音时长（毫秒） */
        private const val MAX_RECORDING_MS = 60_000L
        /** 最短可用录音时长（毫秒） */
        private const val MIN_RECORDING_MS = 1_000L
    }

    private val app = application as MossTTSApp
    val preferences: PreferencesManager = app.preferences
    val modelManager = app.modelManager
    private val clonedVoiceStore = ClonedVoiceStore(application)
    private val historyStore = HistoryStore(application)

    private val audioPlayer = StreamingAudioPlayer(getApplication(), 48000)

    // UI 状态
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _selectedVoice = MutableStateFlow("")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val _voices = MutableStateFlow<List<String>>(emptyList())
    val voices: StateFlow<List<String>> = _voices.asStateFlow()

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _synthesisProgress = MutableStateFlow(0f)
    val synthesisProgress: StateFlow<Float> = _synthesisProgress.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _lastResult = MutableStateFlow<SynthesisResult?>(null)
    val lastResult: StateFlow<SynthesisResult?> = _lastResult.asStateFlow()

    private val _referenceAudioName = MutableStateFlow("")
    val referenceAudioName: StateFlow<String> = _referenceAudioName.asStateFlow()

    private val _referenceAudioCodes = MutableStateFlow<List<IntArray>?>(null)
    val referenceAudioCodes: StateFlow<List<IntArray>?> = _referenceAudioCodes.asStateFlow()

    // 克隆音色列表
    private val _clonedVoices = MutableStateFlow<List<ClonedVoice>>(emptyList())
    val clonedVoices: StateFlow<List<ClonedVoice>> = _clonedVoices.asStateFlow()

    // 当前选中的克隆音色ID
    private val _selectedClonedVoiceId = MutableStateFlow<String?>(null)
    val selectedClonedVoiceId: StateFlow<String?> = _selectedClonedVoiceId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    private val _history = MutableStateFlow<List<SynthesisHistory>>(emptyList())
    val history: StateFlow<List<SynthesisHistory>> = _history.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    /** 录音数据按块累积，避免逐样本装箱导致内存爆炸 */
    private val recordedChunks = ArrayList<ShortArray>()
    @Volatile
    private var recordedSampleCount = 0
    private var recordingSampleRate = 48000
    /** 播放速度倍率（来自设置偏好，播放时应用到播放器） */
    private var playbackSpeed = 1f

    init {
        viewModelScope.launch {
            preferences.selectedVoice.collect { _selectedVoice.value = it }
        }
        viewModelScope.launch {
            preferences.referenceAudioName.collect { _referenceAudioName.value = it }
        }
        viewModelScope.launch {
            audioPlayer.state.collect { state ->
                _isPlaying.value = state == StreamingAudioPlayer.PlaybackState.PLAYING
            }
        }
        viewModelScope.launch {
            audioPlayer.progress.collect { _playbackProgress.value = it }
        }
        viewModelScope.launch {
            modelManager.synthesisProgress.collect { _synthesisProgress.value = it }
        }
        viewModelScope.launch {
            // 播放速度以字符串形式存储（兼容历史数据），解析失败时回退 1.0 倍速
            playbackSpeed = preferences.playbackSpeed.first().toFloatOrNull()?.coerceIn(0.25f, 4.0f) ?: 1f
        }
        // 加载克隆音色列表
        loadClonedVoices()
        // 加载历史记录
        _history.value = historyStore.getAll()
    }

    private fun loadClonedVoices() {
        _clonedVoices.value = clonedVoiceStore.getAllVoices()
    }

    fun updateText(newText: String) {
        _text.value = newText
    }

    fun updateCpuThreads(threads: Int) {
        viewModelScope.launch { preferences.setCpuThreads(threads) }
    }

    fun updateMaxFrames(frames: Int) {
        viewModelScope.launch { preferences.setMaxFrames(frames) }
    }

    fun updateStreamingPlayback(enabled: Boolean) {
        viewModelScope.launch { preferences.setUseStreaming(enabled) }
    }

    fun updatePlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        viewModelScope.launch { preferences.setPlaybackSpeed(speed.toString()) }
    }

    fun updateDarkMode(mode: String) {
        viewModelScope.launch { preferences.setDarkMode(mode) }
    }

    fun updateHideNavigationBar(hide: Boolean) {
        viewModelScope.launch { preferences.setHideNavigationBar(hide) }
    }

    fun selectVoice(voice: String) {
        _selectedVoice.value = voice
        viewModelScope.launch { preferences.setSelectedVoice(voice) }
    }

    fun loadVoices() {
        _voices.value = modelManager.getBuiltinVoices()
    }

    /**
     * 初始化引擎。未显式指定线程数时，从设置偏好读取用户配置的 CPU 线程数。
     */
    fun initializeEngine(cpuThreads: Int? = null) {
        viewModelScope.launch {
            val threads = cpuThreads ?: preferences.cpuThreads.first()
            val success = modelManager.initialize(threads)
            if (success) {
                loadVoices()
                // 如果没有选中的音色，默认选第一个
                if (_selectedVoice.value.isEmpty() && _voices.value.isNotEmpty()) {
                    selectVoice(_voices.value[0])
                }
                // 加载已保存的参考音频
                loadSavedReferenceAudio()
            }
        }
    }

    private fun loadSavedReferenceAudio() {
        viewModelScope.launch {
            val path = preferences.referenceAudioPath.first()
            val name = preferences.referenceAudioName.first()
            if (path.isNotEmpty() && File(path).exists()) {
                val wav = readWavToPcm(File(path))
                if (wav != null) {
                    val codes = modelManager.encodeReferenceAudio(wav.pcm, wav.sampleRate)
                    if (codes != null) {
                        _referenceAudioCodes.value = codes
                        _referenceAudioName.value = name
                    }
                }
            }
        }
    }

    fun synthesizeAndPlay() {
        val inputText = _text.value.trim()
        if (inputText.isEmpty()) {
            _errorMessage.value = "请输入要合成的文本"
            return
        }
        if (!modelManager.isReady()) {
            _errorMessage.value = "模型尚未加载，请先在模型页面下载模型"
            return
        }

        stopPlayback()
        _isSynthesizing.value = true
        _errorMessage.value = null
        _synthesisProgress.value = 0f

        viewModelScope.launch {
            // 当前播放器仅支持完整播放模式（AudioPlayer 的流式 write 通路为 no-op，
            // 历史遗留的 useStreaming 分支会导致开启后无声，已移除），合成完成后一次性播放
            val maxFrames = preferences.maxFrames.first()
            val voice = if (_referenceAudioCodes.value != null) null else _selectedVoice.value.ifEmpty { null }

            val result = modelManager.synthesize(
                text = inputText,
                voice = voice,
                referenceAudioCodes = _referenceAudioCodes.value,
                maxFrames = maxFrames,
            )

            _isSynthesizing.value = false
            if (result != null) {
                _lastResult.value = result
                playPcm(result.pcm)
                // 保存音频文件到永久缓存
                val audioFile = java.io.File(getApplication<android.app.Application>().filesDir, "tts_${System.currentTimeMillis()}.wav")
                modelManager.saveWavToPath(result.pcm, audioFile.absolutePath)
                // 保存到历史记录
                val voiceName = _referenceAudioName.value.ifEmpty { _selectedVoice.value.ifEmpty { "默认音色" } }
                val historyItem = SynthesisHistory(
                    id = "hist_${System.currentTimeMillis()}",
                    text = inputText,
                    voice = voiceName,
                    createdAt = System.currentTimeMillis(),
                    durationMs = result.durationMs,
                    audioPath = audioFile.absolutePath,
                )
                historyStore.add(historyItem)
                _history.value = historyStore.getAll()
            } else {
                val detail = modelManager.synthesisError.value
                _errorMessage.value = if (detail.isNullOrBlank()) "合成失败，请重试" else "合成失败：$detail"
                audioPlayer.stop()
            }
        }
    }

    fun stopPlayback() {
        audioPlayer.stop()
    }

    fun pausePlayback() {
        audioPlayer.pause()
    }

    /** 播放 PCM（应用当前播放速度偏好） */
    private fun playPcm(pcm: FloatArray) {
        audioPlayer.setSpeed(playbackSpeed)
        audioPlayer.playFull(pcm)
    }

    /** 停止/空闲态下"继续"应重新播放上次音频（播放器内部 resume 仅支持 PAUSED/COMPLETED 态） */
    fun resumePlayback() {
        val state = audioPlayer.state.value
        if ((state == StreamingAudioPlayer.PlaybackState.STOPPED ||
                    state == StreamingAudioPlayer.PlaybackState.IDLE) &&
            _lastResult.value != null
        ) {
            _lastResult.value?.let { playPcm(it.pcm) }
            return
        }
        audioPlayer.resume()
    }

    /**
     * 保存当前合成音频到公共 Download/MOSS_TTS 目录。
     * 通过 MediaStore 写入（API 29+ 无需存储权限）。
     * @return 是否保存成功
     */
    fun saveCurrentAudio(fileName: String? = null): Boolean {
        val result = _lastResult.value ?: return false
        val name = fileName ?: "tts_${System.currentTimeMillis()}.wav"
        // 先写入应用缓存，再通过 MediaStore 落到公共 Download 目录
        val cacheDir = java.io.File(app.cacheDir, "save_tmp").apply { mkdirs() }
        val cacheFile = java.io.File(cacheDir, name)
        if (!modelManager.saveWavToPath(result.pcm, cacheFile.absolutePath)) {
            _errorMessage.value = "保存失败：无法生成音频文件"
            return false
        }
        val ok = copyFileToDownloads(cacheFile, name)
        cacheFile.delete()
        return ok
    }

    /**
     * 将文件复制到公共 Download/MOSS_TTS 目录。
     * API 29+ 使用 MediaStore（无需存储权限）；
     * API 26-28 需要 WRITE_EXTERNAL_STORAGE 权限，无权限时回退到应用外部目录。
     */
    private fun copyFileToDownloads(source: java.io.File, displayName: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        "${android.os.Environment.DIRECTORY_DOWNLOADS}/MOSS_TTS"
                    )
                }
                val uri = app.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: run {
                    _saveMessage.value = "保存失败：无法创建文件"
                    return false
                }
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: run {
                    _saveMessage.value = "保存失败：无法写入文件"
                    return false
                }
                _saveMessage.value = "已保存到 Download/MOSS_TTS/$displayName"
                AppLogger.info(TAG, "音频已保存到 Download/MOSS_TTS/$displayName")
                true
            } else {
                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    app, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val outputDir: java.io.File
                if (hasPermission) {
                    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    outputDir = java.io.File(downloadDir, "MOSS_TTS")
                } else {
                    // 无权限时回退到应用专属外部目录（无需任何权限）
                    outputDir = java.io.File(app.getExternalFilesDir(null), "MOSS_TTS")
                }
                outputDir.mkdirs()
                val dest = java.io.File(outputDir, displayName)
                source.copyTo(dest, overwrite = true)
                _saveMessage.value = if (hasPermission) {
                    "已保存到: ${dest.absolutePath}"
                } else {
                    "已保存到应用目录: ${dest.absolutePath}"
                }
                AppLogger.info(TAG, "音频已保存: ${dest.absolutePath}")
                true
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "保存音频失败: ${e.message}", e)
            _errorMessage.value = "保存失败: ${e.message}"
            false
        }
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    fun deleteHistory(id: String) {
        // 删除关联的音频文件
        historyStore.getAll().find { it.id == id }?.audioPath?.let { path ->
            try {
                java.io.File(path).delete()
            } catch (_: Exception) {}
        }
        historyStore.delete(id)
        _history.value = historyStore.getAll()
    }

    fun deleteHistories(ids: List<String>) {
        ids.forEach { id ->
            historyStore.getAll().find { it.id == id }?.audioPath?.let { path ->
                try {
                    java.io.File(path).delete()
                } catch (_: Exception) {}
            }
        }
        ids.forEach { historyStore.delete(it) }
        _history.value = historyStore.getAll()
    }

    fun clearHistory() {
        // 删除所有音频文件
        historyStore.getAll().forEach { item ->
            item.audioPath?.let { path ->
                try {
                    java.io.File(path).delete()
                } catch (_: Exception) {}
            }
        }
        historyStore.clear()
        _history.value = emptyList()
    }

    fun playFromHistory(text: String) {
        _text.value = text
        synthesizeAndPlay()
    }

    fun saveHistoryAudio(history: SynthesisHistory): Boolean {
        val srcPath = history.audioPath ?: run {
            _saveMessage.value = "保存失败：该记录没有音频文件"
            return false
        }
        val srcFile = java.io.File(srcPath)
        if (!srcFile.exists()) {
            _saveMessage.value = "保存失败：音频文件不存在"
            return false
        }
        val fileName = "MOSS_TTS_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.wav"
        return copyFileToDownloads(srcFile, fileName)
    }

    fun playHistoryAudio(history: SynthesisHistory) {
        if (history.audioPath != null && java.io.File(history.audioPath).exists()) {
            // 从缓存文件播放，不需要重新合成
            val loaded = modelManager.loadWav(history.audioPath)
            if (loaded != null) {
                // 播放器固定 48kHz，非 48kHz 音频先重采样
                val pcm = if (loaded.sampleRate != 48000) {
                    resampleTo48k(loaded.pcm, loaded.sampleRate)
                } else {
                    loaded.pcm
                }
                _lastResult.value = SynthesisResult(
                    pcm = pcm,
                    sampleRate = 48000,
                    channels = 1,
                    // 音频帧率 12.5Hz（48000 / 3840），用采样点数估算帧数
                    generatedFrames = pcm.size / SAMPLES_PER_FRAME,
                    durationMs = pcm.size * 1000L / 48000,
                    elapsedMs = 0,
                    textChunks = emptyList(),
                )
                playPcm(pcm)
            } else {
                // 文件不存在，重新合成
                playFromHistory(history.text)
            }
        } else {
            // 没有缓存，重新合成
            playFromHistory(history.text)
        }
    }

    /**
     * 线性插值重采样到 48kHz。
     */
    private fun resampleTo48k(pcm: FloatArray, fromRate: Int): FloatArray {
        if (fromRate <= 0 || fromRate == 48000) return pcm
        val ratio = 48000.0 / fromRate
        val outSize = (pcm.size * ratio).toInt().coerceAtLeast(0)
        if (outSize == 0) return FloatArray(0)
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val a = pcm.getOrElse(idx) { 0f }
            val b = pcm.getOrElse(idx + 1) { a }
            out[i] = a + (b - a) * frac
        }
        return out
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun showError(message: String) {
        _errorMessage.value = message
    }

    // ==================== 语音克隆参考音频 ====================

    fun startRecording() {
        if (_isRecording.value) return
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // 部分设备不支持 48kHz 录音，依次尝试 48k/44.1k/16k
        var sampleRate = 48000
        var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) {
            sampleRate = 44100
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        }
        if (bufferSize <= 0) {
            sampleRate = 16000
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        }
        if (bufferSize <= 0) {
            _errorMessage.value = "设备不支持所需录音参数，无法启动录音"
            return
        }

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                // 构造失败时未初始化，直接释放避免卡在录音态
                record.release()
                _errorMessage.value = "无法启动录音（麦克风可能被占用）"
                return
            }

            synchronized(recordedChunks) {
                recordedChunks.clear()
                recordedSampleCount = 0
            }
            recordingSampleRate = sampleRate
            audioRecord = record
            record.startRecording()
            _isRecording.value = true
            val startTime = System.currentTimeMillis()

            recordingThread = Thread({
                val buffer = ShortArray(bufferSize)
                try {
                    while (_isRecording.value) {
                        val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (read > 0) {
                            synchronized(recordedChunks) {
                                recordedChunks.add(buffer.copyOf(read))
                                recordedSampleCount += read
                            }
                        }
                        // 达到最大时长，自动停止（切回主线程执行完整收尾逻辑）
                        if (System.currentTimeMillis() - startTime >= MAX_RECORDING_MS) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (_isRecording.value) {
                                    stopRecordingAndUse()
                                }
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    // stop()/release() 后 read 可能抛异常，属正常收尾路径
                    Log.w(TAG, "录音线程结束: ${e.message}")
                }
            }, "AudioRecording").apply { isDaemon = true }
            recordingThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _errorMessage.value = "无法启动录音：${e.message}"
            _isRecording.value = false
            audioRecord?.release()
            audioRecord = null
        }
    }

    fun stopRecordingAndUse() {
        if (!_isRecording.value) return
        _isRecording.value = false
        // 等待录音线程退出，确保数据完整
        recordingThread?.let { thread ->
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        recordingThread = null
        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.stop 失败: ${e.message}")
            }
            try {
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.release 失败: ${e.message}")
            }
        }
        audioRecord = null

        viewModelScope.launch {
            val sampleRate = recordingSampleRate
            val pcm = synchronized(recordedChunks) {
                val total = recordedSampleCount
                val result = FloatArray(total)
                var offset = 0
                for (chunk in recordedChunks) {
                    for (i in chunk.indices) {
                        result[offset++] = chunk[i] / 32768f
                    }
                }
                recordedChunks.clear()
                recordedSampleCount = 0
                result
            }
            val durationMs = pcm.size * 1000L / sampleRate
            if (durationMs < MIN_RECORDING_MS) {
                _errorMessage.value = "录音时间太短，请至少录制 1 秒"
                return@launch
            }
            val codes = try {
                modelManager.encodeReferenceAudio(pcm, sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encode reference audio", e)
                _errorMessage.value = "参考音频编码失败：${e.message ?: "未知错误"}"
                null
            }
            if (codes != null) {
                val voiceId = "voice_${System.currentTimeMillis()}"
                val voiceName = "录音_${System.currentTimeMillis()}"
                // 保存 audio codes 到文件
                val codesFile = File(clonedVoiceStore.getAudioCodesDir(), "$voiceId.codes")
                saveAudioCodes(codes, codesFile)
                // 保存 wav 文件（与 referenceAudioPath 指向同一路径）
                val wavFile = File(app.getExternalFilesDir(null), "ref_$voiceId.wav")
                modelManager.saveWavToPath(pcm, wavFile.absolutePath, sampleRate)
                // 保存到 store
                val voice = ClonedVoice(
                    id = voiceId,
                    name = voiceName,
                    audioCodesPath = codesFile.absolutePath,
                    referenceAudioPath = wavFile.absolutePath,
                )
                clonedVoiceStore.saveVoice(voice)
                loadClonedVoices()
                // 自动选中新创建的音色
                selectClonedVoice(voiceId)
                _saveMessage.value = "克隆音色创建成功：$voiceName"
            } else if (_errorMessage.value == null) {
                _errorMessage.value = "参考音频编码失败，请重试"
            }
        }
    }

    fun cancelRecording() {
        _isRecording.value = false
        recordingThread?.interrupt()
        recordingThread = null
        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.stop 失败: ${e.message}")
            }
            try {
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.release 失败: ${e.message}")
            }
        }
        audioRecord = null
        synchronized(recordedChunks) {
            recordedChunks.clear()
            recordedSampleCount = 0
        }
    }

    fun importReferenceAudio(file: File) {
        viewModelScope.launch {
            // 文件大小检查，超过15MB提示
            if (file.length() > 15 * 1024 * 1024) {
                _errorMessage.value = "音频文件过大（${file.length() / 1024 / 1024}MB），建议使用30秒以内的参考音频"
                return@launch
            }
            val wav = readWavToPcm(file)
            if (wav == null) {
                _errorMessage.value = "无法读取音频文件，请确保是 WAV 格式"
                return@launch
            }
            val codes = try {
                modelManager.encodeReferenceAudio(wav.pcm, wav.sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encode reference audio", e)
                _errorMessage.value = "参考音频编码失败：${e.message ?: "未知错误"}"
                null
            }
            if (codes != null) {
                val voiceId = "voice_${System.currentTimeMillis()}"
                val voiceName = file.nameWithoutExtension
                // 保存 audio codes 到文件
                val codesFile = File(clonedVoiceStore.getAudioCodesDir(), "$voiceId.codes")
                saveAudioCodes(codes, codesFile)
                // 保存到 store
                val voice = ClonedVoice(
                    id = voiceId,
                    name = voiceName,
                    audioCodesPath = codesFile.absolutePath,
                    referenceAudioPath = file.absolutePath,
                )
                clonedVoiceStore.saveVoice(voice)
                loadClonedVoices()
                // 自动选中新创建的音色
                selectClonedVoice(voiceId)
                _saveMessage.value = "克隆音色导入成功：$voiceName"
            } else if (_errorMessage.value == null) {
                _errorMessage.value = "参考音频编码失败，请重试"
            }
        }
    }

    fun clearReferenceAudio() {
        _referenceAudioCodes.value = null
        _referenceAudioName.value = ""
        _selectedClonedVoiceId.value = null
        viewModelScope.launch { preferences.clearReferenceAudio() }
    }

    // 选择克隆音色
    fun selectClonedVoice(voiceId: String?) {
        _selectedClonedVoiceId.value = voiceId
        if (voiceId == null) {
            _referenceAudioCodes.value = null
            _referenceAudioName.value = ""
            return
        }
        val voice = clonedVoiceStore.getVoice(voiceId)
        if (voice != null) {
            val codes = loadAudioCodes(File(voice.audioCodesPath))
            _referenceAudioCodes.value = codes
            _referenceAudioName.value = voice.name
        }
    }

    // 删除克隆音色
    fun deleteClonedVoice(voiceId: String) {
        if (_selectedClonedVoiceId.value == voiceId) {
            clearReferenceAudio()
        }
        clonedVoiceStore.deleteVoice(voiceId)
        loadClonedVoices()
    }

    // 重命名克隆音色
    fun renameClonedVoice(voiceId: String, newName: String) {
        clonedVoiceStore.renameVoice(voiceId, newName)
        loadClonedVoices()
        if (_selectedClonedVoiceId.value == voiceId) {
            _referenceAudioName.value = newName
        }
    }

    // 保存 audio codes 到文件
    private fun saveAudioCodes(codes: List<IntArray>, file: File) {
        try {
            java.io.ObjectOutputStream(java.io.FileOutputStream(file)).use { oos ->
                oos.writeObject(codes.map { it.toList() })
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存 audio codes 失败", e)
        }
    }

    // 从文件加载 audio codes
    private fun loadAudioCodes(file: File): List<IntArray>? {
        return try {
            java.io.ObjectInputStream(java.io.FileInputStream(file)).use { ois ->
                @Suppress("UNCHECKED_CAST")
                val list = ois.readObject() as List<List<Int>>
                list.map { it.toIntArray() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载 audio codes 失败", e)
            null
        }
    }

    /** readWavToPcm 的返回结果：PCM 数据 + 文件实际采样率 */
    private class WavData(val pcm: FloatArray, val sampleRate: Int)

    private fun readWavToPcm(file: File): WavData? {
        return try {
            AppLogger.info(TAG, "读取WAV文件: ${file.name}, 大小: ${file.length()}字节")
            FileInputStream(file).use { fis ->
                // 读取 RIFF 头（循环读满，单次 read 不保证读满）
                val riffHeader = ByteArray(12)
                if (!readFully(fis, riffHeader)) {
                    AppLogger.error(TAG, "WAV文件太小")
                    return null
                }
                val riff = String(riffHeader, 0, 4, Charsets.US_ASCII)
                val wave = String(riffHeader, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") {
                    AppLogger.error(TAG, "不是有效的WAV文件: riff=$riff, wave=$wave")
                    return null
                }

                var sampleRate = 48000
                var channels = 1
                var bitsPerSample = 16
                var dataBytes: ByteArray? = null

                // 遍历所有 chunk
                val chunkHeader = ByteArray(8)
                while (readFully(fis, chunkHeader)) {
                    val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    AppLogger.debug(TAG, "发现chunk: $chunkId, 大小: $chunkSize")

                    // 非法 chunk 大小直接终止解析，避免负数长度/溢出
                    if (chunkSize < 0) {
                        AppLogger.error(TAG, "非法chunk大小: $chunkId=$chunkSize")
                        break
                    }

                    if (chunkId == "fmt ") {
                        if (chunkSize < 16) {
                            AppLogger.error(TAG, "fmt chunk 过小: $chunkSize")
                            return null
                        }
                        val fmtData = ByteArray(chunkSize)
                        if (!readFully(fis, fmtData)) {
                            AppLogger.error(TAG, "fmt chunk 读取失败")
                            return null
                        }
                        val audioFormat = ByteBuffer.wrap(fmtData, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        channels = ByteBuffer.wrap(fmtData, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        sampleRate = ByteBuffer.wrap(fmtData, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        bitsPerSample = ByteBuffer.wrap(fmtData, 14, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        AppLogger.info(TAG, "格式: audioFormat=$audioFormat, channels=$channels, sampleRate=$sampleRate, bits=$bitsPerSample")
                        if (audioFormat != 1 && audioFormat != 3) {
                            AppLogger.error(TAG, "不支持的音频格式: $audioFormat (仅支持PCM)")
                            return null
                        }
                        if (channels <= 0 || channels > 8 || sampleRate <= 0 || sampleRate > 384000) {
                            AppLogger.error(TAG, "非法音频参数: channels=$channels, sampleRate=$sampleRate")
                            return null
                        }
                    } else if (chunkId == "data") {
                        // 限制最大30秒，避免大文件内存溢出（48kHz * 16bit * 立体声 * 30秒 ≈ 5.76MB）
                        val maxBytes = 30 * 48000 * 2 * 2
                        val readSize = minOf(chunkSize, maxBytes)
                        dataBytes = ByteArray(readSize)
                        var totalRead = 0
                        while (totalRead < readSize) {
                            val read = fis.read(dataBytes, totalRead, readSize - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        if (chunkSize > maxBytes) {
                            AppLogger.info(TAG, "音频文件过大，已截断到前30秒: 原大小=$chunkSize, 读取=$totalRead")
                        }
                        AppLogger.info(TAG, "数据块大小: $chunkSize, 实际读取: $totalRead")
                    } else {
                        // 跳过其他 chunk（循环 skip，单次 skip 不保证跳过指定长度；偶数对齐）
                        val skipBytes = minOf(chunkSize + (chunkSize % 2), 64 * 1024 * 1024)
                        var remaining = skipBytes.toLong()
                        while (remaining > 0) {
                            val skipped = fis.skip(remaining)
                            if (skipped <= 0) {
                                if (fis.read() == -1) break
                                remaining -= 1
                            } else {
                                remaining -= skipped
                            }
                        }
                    }
                }

                val bytes = dataBytes ?: run {
                    AppLogger.error(TAG, "未找到data块")
                    return null
                }

                // 转换为 FloatArray（单声道）
                val pcm = when (bitsPerSample) {
                    16 -> {
                        val shortCount = bytes.size / 2
                        if (channels == 2) {
                            FloatArray(shortCount / 2) { i ->
                                val left = (bytes[i * 4].toInt() and 0xFF or (bytes[i * 4 + 1].toInt() shl 8)).toShort()
                                val right = (bytes[i * 4 + 2].toInt() and 0xFF or (bytes[i * 4 + 3].toInt() shl 8)).toShort()
                                ((left + right) / 2f) / 32768f
                            }
                        } else {
                            FloatArray(shortCount) { i ->
                                val sample = (bytes[i * 2].toInt() and 0xFF or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                                sample / 32768f
                            }
                        }
                    }
                    8 -> {
                        val sampleCount = bytes.size / channels
                        FloatArray(sampleCount) { i ->
                            var sum = 0
                            for (ch in 0 until channels) {
                                sum += bytes[i * channels + ch].toInt() - 128
                            }
                            (sum / channels) / 128f
                        }
                    }
                    32 -> {
                        // 32bit float
                        val floatCount = bytes.size / 4 / channels
                        FloatArray(floatCount) { i ->
                            var sum = 0f
                            for (ch in 0 until channels) {
                                val bits = (bytes[i * channels * 4 + ch * 4].toInt() and 0xFF) or
                                    ((bytes[i * channels * 4 + ch * 4 + 1].toInt() and 0xFF) shl 8) or
                                    ((bytes[i * channels * 4 + ch * 4 + 2].toInt() and 0xFF) shl 16) or
                                    ((bytes[i * channels * 4 + ch * 4 + 3].toInt() and 0xFF) shl 24)
                                sum += java.lang.Float.intBitsToFloat(bits)
                            }
                            sum / channels
                        }
                    }
                    else -> {
                        AppLogger.error(TAG, "不支持的位深: $bitsPerSample")
                        null
                    }
                }

                if (pcm == null) null
                else {
                    AppLogger.info(TAG, "WAV读取成功: ${pcm.size} 采样点, $sampleRate Hz")
                    WavData(pcm, sampleRate)
                }
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "读取WAV文件失败: ${e.message}", e)
            null
        }
    }

    /**
     * 循环读取直到缓冲区填满，避免单次 read() 短读导致数据不完整。
     */
    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) return offset == buffer.size
            offset += read
        }
        return true
    }

    private fun ByteArray.toShortArray(): ShortArray {
        val result = ShortArray(size / 2)
        var i = 0
        var j = 0
        while (i < size) {
            result[j++] = ((this[i].toInt() and 0xFF) or (this[i + 1].toInt() shl 8)).toShort()
            i += 2
        }
        return result
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        modelManager.release()
        audioRecord?.release()
    }
}
