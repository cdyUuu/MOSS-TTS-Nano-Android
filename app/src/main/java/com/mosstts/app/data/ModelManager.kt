package com.mosstts.app.data

import android.content.Context
import android.util.Log
import com.mosstts.app.engine.MossTTSEngine
import com.mosstts.app.engine.SynthesisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import com.mosstts.app.util.AppLogger

/**
 * 模型管理器：负责引擎的初始化、生命周期管理和合成调度。
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
    }

    enum class EngineState {
        NOT_READY, LOADING, READY, ERROR, SYNTHESIZING
    }

    private val _engineState = MutableStateFlow(EngineState.NOT_READY)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _synthesisProgress = MutableStateFlow(0f)
    val synthesisProgress: StateFlow<Float> = _synthesisProgress.asStateFlow()

    private var engine: MossTTSEngine? = null
    private val downloader = ModelDownloader(context)

    fun isReady(): Boolean = engine != null && _engineState.value == EngineState.READY

    fun getEngine(): MossTTSEngine? = engine

    fun getBuiltinVoices(): List<String> {
        return engine?.builtinVoices?.map { it.voice } ?: emptyList()
    }

    fun getSampleRate(): Int = engine?.sampleRate ?: 48000

    suspend fun initialize(cpuThreads: Int = 4): Boolean = withContext(Dispatchers.IO) {
        if (_engineState.value == EngineState.READY) return@withContext true
        if (!downloader.isModelReady()) {
            Log.w(TAG, "Model not ready, need download first")
            _engineState.value = EngineState.NOT_READY
            return@withContext false
        }

        _engineState.value = EngineState.LOADING
        try {
            val modelDir = downloader.getModelDir()
            AppLogger.info(TAG, "开始初始化引擎，模型目录: ${modelDir.absolutePath}")
            AppLogger.info(TAG, "CPU 线程数: $cpuThreads")

            // 检查模型文件
            val ttsDir = File(modelDir, "MOSS-TTS-Nano-100M-ONNX")
            val codecDir = File(modelDir, "MOSS-Audio-Tokenizer-Nano-ONNX")
            AppLogger.info(TAG, "TTS 目录存在: ${ttsDir.exists()}, 文件数: ${ttsDir.listFiles()?.size ?: 0}")
            AppLogger.info(TAG, "Codec 目录存在: ${codecDir.exists()}, 文件数: ${codecDir.listFiles()?.size ?: 0}")

            // 确保 manifest 文件存在（从 assets 复制）
            ensureManifestFile(modelDir)

            AppLogger.info(TAG, "开始创建 MossTTSEngine...")
            val startTime = System.currentTimeMillis()
            engine = MossTTSEngine(modelDir, cpuThreads)
            val elapsed = System.currentTimeMillis() - startTime
            _engineState.value = EngineState.READY
            AppLogger.info(TAG, "引擎初始化成功，耗时: ${elapsed}ms, 音色数: ${engine?.builtinVoices?.size}")
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "引擎初始化失败: ${e.message}", e)
            _engineState.value = EngineState.ERROR
            _initError = e.message ?: "未知错误"
            false
        }
    }

    private var _initError: String? = null
    val initError: String? get() = _initError

    /**
     * 确保模型目录中有 browser_poc_manifest.json 文件。
     * 如果不存在，从 assets 中复制默认的 manifest 文件。
     */
    private fun ensureManifestFile(modelDir: File) {
        val ttsDir = File(modelDir, "MOSS-TTS-Nano-100M-ONNX")
        val manifestFile = File(ttsDir, "browser_poc_manifest.json")
        if (manifestFile.exists() && manifestFile.length() > 0) {
            Log.d(TAG, "Manifest file already exists: ${manifestFile.absolutePath}")
            return
        }
        try {
            ttsDir.mkdirs()
            context.assets.open("browser_poc_manifest.json").use { input ->
                manifestFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied manifest file from assets to: ${manifestFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy manifest file from assets", e)
        }
    }

    suspend fun synthesize(
        text: String,
        voice: String? = null,
        referenceAudioCodes: List<IntArray>? = null,
        maxFrames: Int? = null,
        seed: Long = System.currentTimeMillis(),
        onFrame: ((Int, Int) -> Unit)? = null,
        onAudio: ((FloatArray, Boolean) -> Unit)? = null,
    ): SynthesisResult? = withContext(Dispatchers.Default) {
        val eng = engine
        if (eng == null) {
            Log.e(TAG, "Engine not initialized")
            return@withContext null
        }

        _engineState.value = EngineState.SYNTHESIZING
        _synthesisProgress.value = 0f

        try {
            val result = eng.synthesize(
                text = text,
                voice = voice,
                referenceAudioCodes = referenceAudioCodes,
                maxFrames = maxFrames,
                seed = seed,
                onFrame = { frame, total ->
                    _synthesisProgress.value = if (total > 0) frame.toFloat() / total else 0f
                    onFrame?.invoke(frame, total)
                },
                onAudio = onAudio,
            )
            _synthesisProgress.value = 1f
            result
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            null
        } finally {
            if (_engineState.value == EngineState.SYNTHESIZING) {
                _engineState.value = EngineState.READY
            }
        }
    }

    fun cancelSynthesis() {
        engine?.cancel()
    }

    suspend fun encodeReferenceAudio(pcm: FloatArray, sampleRate: Int): List<IntArray>? {
        return try {
            engine?.encodeReferenceAudio(pcm, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode reference audio", e)
            null
        }
    }

    fun saveWav(pcm: FloatArray, fileName: String): File? {
        return try {
            val outputDir = File(context.getExternalFilesDir(null), "output")
            outputDir.mkdirs()
            val outputFile = File(outputDir, fileName)
            engine?.writeWav(pcm, outputFile)
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WAV", e)
            null
        }
    }

    fun release() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing engine", e)
        }
        engine = null
        _engineState.value = EngineState.NOT_READY
    }
}
