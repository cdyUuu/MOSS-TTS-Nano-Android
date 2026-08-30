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
    }

    private val app = application as MossTTSApp
    val preferences: PreferencesManager = app.preferences
    val modelManager = ModelManager(application)
    private val clonedVoiceStore = ClonedVoiceStore(application)
    private val historyStore = HistoryStore(application)

    private val audioPlayer = StreamingAudioPlayer(48000)

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
    private val recordedPcm = ArrayList<Short>()

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

    fun initializeEngine(cpuThreads: Int = 4) {
        viewModelScope.launch {
            val success = modelManager.initialize(cpuThreads)
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
                val pcm = readWavToPcm(File(path))
                if (pcm != null) {
                    val codes = modelManager.encodeReferenceAudio(pcm, 48000)
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
            val useStreaming = false // 禁用流式播放，等全部合成完再播放
            val maxFrames = preferences.maxFrames.first()
            val voice = if (_referenceAudioCodes.value != null) null else _selectedVoice.value.ifEmpty { null }

            audioPlayer.prepare()
            if (useStreaming) {
                audioPlayer.start()
            }

            val result = modelManager.synthesize(
                text = inputText,
                voice = voice,
                referenceAudioCodes = _referenceAudioCodes.value,
                maxFrames = maxFrames,
                onAudio = { pcm, isLast ->
                    if (useStreaming) {
                        audioPlayer.write(pcm)
                        if (isLast) audioPlayer.endOfStream()
                    }
                },
            )

            _isSynthesizing.value = false
            if (result != null) {
                _lastResult.value = result
                if (useStreaming) {
                    // 流式模式下保存完整PCM，用于暂停后继续播放或重播
                    audioPlayer.setLastPcm(result.pcm)
                } else {
                    audioPlayer.playFull(result.pcm)
                }
                // 保存到历史记录
                val voiceName = _referenceAudioName.value.ifEmpty { _selectedVoice.value.ifEmpty { "默认音色" } }
                val historyItem = SynthesisHistory(
                    id = "hist_${System.currentTimeMillis()}",
                    text = inputText,
                    voice = voiceName,
                    createdAt = System.currentTimeMillis(),
                    durationMs = (result.pcm.size / 48).toLong(), // 48000Hz / 1000 = 48 samples per ms
                )
                historyStore.add(historyItem)
                _history.value = historyStore.getAll()
            } else {
                _errorMessage.value = "合成失败，请重试"
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

    fun resumePlayback() {
        audioPlayer.resume()
    }

    fun saveCurrentAudio(fileName: String? = null): File? {
        val result = _lastResult.value ?: return null
        val name = fileName ?: "tts_${System.currentTimeMillis()}.wav"
        return try {
            // 保存到公共 Download 目录
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val outputDir = java.io.File(downloadDir, "MOSS_TTS")
            outputDir.mkdirs()
            val outputFile = java.io.File(outputDir, name)
            modelManager.saveWavToPath(result.pcm, outputFile.absolutePath)
            _saveMessage.value = "已保存到: ${outputFile.absolutePath}"
            AppLogger.info(TAG, "音频已保存: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            AppLogger.error(TAG, "保存音频失败: ${e.message}", e)
            _errorMessage.value = "保存失败: ${e.message}"
            null
        }
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    fun deleteHistory(id: String) {
        historyStore.delete(id)
        _history.value = historyStore.getAll()
    }

    fun clearHistory() {
        historyStore.clear()
        _history.value = emptyList()
    }

    fun playFromHistory(text: String) {
        _text.value = text
        synthesizeAndPlay()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ==================== 语音克隆参考音频 ====================

    fun startRecording() {
        if (_isRecording.value) return
        try {
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            recordedPcm.clear()
            audioRecord?.startRecording()
            _isRecording.value = true

            recordingThread = Thread({
                val buffer = ShortArray(bufferSize)
                while (_isRecording.value) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        synchronized(recordedPcm) {
                            for (i in 0 until read) {
                                recordedPcm.add(buffer[i])
                            }
                        }
                    }
                }
            }, "AudioRecording").apply { isDaemon = true }
            recordingThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _errorMessage.value = "无法启动录音：${e.message}"
            _isRecording.value = false
        }
    }

    fun stopRecordingAndUse() {
        if (!_isRecording.value) return
        _isRecording.value = false
        recordingThread?.interrupt()
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        viewModelScope.launch {
            val pcm = synchronized(recordedPcm) {
                ShortArray(recordedPcm.size) { recordedPcm[it] }.map { it / 32768f }.toFloatArray()
            }
            if (pcm.size < 48000) { // 至少 1 秒
                _errorMessage.value = "录音时间太短，请至少录制 1 秒"
                return@launch
            }
            val codes = try {
                modelManager.encodeReferenceAudio(pcm, 48000)
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
                // 保存 wav 文件
                val wavFile = File(app.getExternalFilesDir(null), "ref_$voiceId.wav")
                modelManager.saveWav(pcm, wavFile.name)
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
                _errorMessage.value = "克隆音色创建成功：$voiceName"
            } else if (_errorMessage.value == null) {
                _errorMessage.value = "参考音频编码失败，请重试"
            }
        }
    }

    fun cancelRecording() {
        _isRecording.value = false
        recordingThread?.interrupt()
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordedPcm.clear()
    }

    fun importReferenceAudio(file: File) {
        viewModelScope.launch {
            val pcm = readWavToPcm(file)
            if (pcm == null) {
                _errorMessage.value = "无法读取音频文件，请确保是 WAV 格式"
                return@launch
            }
            val codes = try {
                modelManager.encodeReferenceAudio(pcm, 48000)
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
                _errorMessage.value = "克隆音色导入成功：$voiceName"
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

    private fun readWavToPcm(file: File): FloatArray? {
        return try {
            AppLogger.info(TAG, "读取WAV文件: ${file.name}, 大小: ${file.length()}字节")
            FileInputStream(file).use { fis ->
                // 读取 RIFF 头
                val riffHeader = ByteArray(12)
                if (fis.read(riffHeader) != 12) {
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
                while (fis.read(chunkHeader) == 8) {
                    val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    AppLogger.debug(TAG, "发现chunk: $chunkId, 大小: $chunkSize")

                    if (chunkId == "fmt ") {
                        val fmtData = ByteArray(chunkSize)
                        fis.read(fmtData)
                        val audioFormat = ByteBuffer.wrap(fmtData, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        channels = ByteBuffer.wrap(fmtData, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        sampleRate = ByteBuffer.wrap(fmtData, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        bitsPerSample = ByteBuffer.wrap(fmtData, 14, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        AppLogger.info(TAG, "格式: audioFormat=$audioFormat, channels=$channels, sampleRate=$sampleRate, bits=$bitsPerSample")
                        if (audioFormat != 1 && audioFormat != 3) {
                            AppLogger.error(TAG, "不支持的音频格式: $audioFormat (仅支持PCM)")
                            return null
                        }
                    } else if (chunkId == "data") {
                        dataBytes = ByteArray(chunkSize)
                        var totalRead = 0
                        while (totalRead < chunkSize) {
                            val read = fis.read(dataBytes, totalRead, chunkSize - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        AppLogger.info(TAG, "数据块大小: $chunkSize, 实际读取: $totalRead")
                    } else {
                        // 跳过其他 chunk
                        val skipBytes = chunkSize + (chunkSize % 2) // 对齐到偶数
                        fis.skip(skipBytes.toLong())
                    }
                }

                if (dataBytes == null) {
                    AppLogger.error(TAG, "未找到data块")
                    return null
                }

                // 转换为 FloatArray（单声道）
                val pcm = when (bitsPerSample) {
                    16 -> {
                        val shortCount = dataBytes.size / 2
                        if (channels == 2) {
                            FloatArray(shortCount / 2) { i ->
                                val left = (dataBytes[i * 4].toInt() and 0xFF or (dataBytes[i * 4 + 1].toInt() shl 8)).toShort()
                                val right = (dataBytes[i * 4 + 2].toInt() and 0xFF or (dataBytes[i * 4 + 3].toInt() shl 8)).toShort()
                                ((left + right) / 2f) / 32768f
                            }
                        } else {
                            FloatArray(shortCount) { i ->
                                val sample = (dataBytes[i * 2].toInt() and 0xFF or (dataBytes[i * 2 + 1].toInt() shl 8)).toShort()
                                sample / 32768f
                            }
                        }
                    }
                    8 -> {
                        val sampleCount = dataBytes.size / channels
                        FloatArray(sampleCount) { i ->
                            var sum = 0
                            for (ch in 0 until channels) {
                                sum += dataBytes[i * channels + ch].toInt() - 128
                            }
                            (sum / channels) / 128f
                        }
                    }
                    32 -> {
                        // 32bit float
                        val floatCount = dataBytes.size / 4 / channels
                        FloatArray(floatCount) { i ->
                            var sum = 0f
                            for (ch in 0 until channels) {
                                val bits = (dataBytes[i * channels * 4 + ch * 4].toInt() and 0xFF) or
                                    ((dataBytes[i * channels * 4 + ch * 4 + 1].toInt() and 0xFF) shl 8) or
                                    ((dataBytes[i * channels * 4 + ch * 4 + 2].toInt() and 0xFF) shl 16) or
                                    ((dataBytes[i * channels * 4 + ch * 4 + 3].toInt() and 0xFF) shl 24)
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

                AppLogger.info(TAG, "WAV读取成功: ${pcm?.size} 采样点, $sampleRate Hz")
                pcm
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "读取WAV文件失败: ${e.message}", e)
            null
        }
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
