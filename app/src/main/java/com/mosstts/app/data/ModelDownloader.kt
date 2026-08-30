package com.mosstts.app.data

import android.content.Context
import android.util.Log
import com.mosstts.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 模型下载管理器。支持从 HuggingFace 或多个国内镜像站下载 ONNX 模型文件。
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        // HuggingFace 仓库地址
        private const val HF_TTS_REPO = "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX"
        private const val HF_CODEC_REPO = "OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX"

        // 镜像源列表
        data class MirrorSource(val id: String, val name: String, val baseUrl: String)

        val MIRROR_SOURCES = listOf(
            MirrorSource("none", "官方直连", "https://huggingface.co"),
            MirrorSource("hf_mirror", "HF-Mirror", "https://hf-mirror.com"),
            MirrorSource("modelscope", "ModelScope 魔搭", "https://www.modelscope.cn"),
            MirrorSource("ghproxy", "GhProxy 加速", "https://gh-proxy.com"),
        )

        // TTS 模型关键文件（用于检查是否下载完成）
        private val TTS_REQUIRED_FILES = listOf(
            "moss_tts_prefill.onnx",
            "moss_tts_decode_step.onnx",
            "moss_tts_local_fixed_sampled_frame.onnx",
            "moss_tts_global_shared.data",
            "moss_tts_local_shared.data",
            "tokenizer.model",
            "tts_browser_onnx_meta.json",
        )

        // TTS 模型可选文件（有则下载，无则跳过）
        private val TTS_OPTIONAL_FILES = listOf(
            "moss_tts_local_decoder.onnx",
            "moss_tts_local_cached_step.onnx",
            "browser_poc_manifest.json",
        )

        // Codec 模型关键文件
        private val CODEC_REQUIRED_FILES = listOf(
            "moss_audio_tokenizer_decode_full.onnx",
            "moss_audio_tokenizer_decode_shared.data",
            "codec_browser_onnx_meta.json",
        )

        // Codec 可选文件（用于语音克隆）
        private val CODEC_OPTIONAL_FILES = listOf(
            "moss_audio_tokenizer_encode.onnx",
            "moss_audio_tokenizer_encode.data",
            "moss_audio_tokenizer_decode_step.onnx",
        )
    }

    data class DownloadProgress(
        val fileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFileIndex: Int,
        val totalFiles: Int,
        val speedBytesPerSecond: Long = 0,
        val isComplete: Boolean = false,
        val error: String? = null,
    )

    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // 测速专用 client，短超时
    private val speedTestClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun getModelDir(): File {
        return File(context.filesDir, "models")
    }

    fun getTtsDir(): File = File(getModelDir(), "MOSS-TTS-Nano-100M-ONNX")
    fun getCodecDir(): File = File(getModelDir(), "MOSS-Audio-Tokenizer-Nano-ONNX")

    /**
     * 检查模型是否就绪（只检查关键文件）。
     */
    fun isModelReady(): Boolean {
        val ttsDir = getTtsDir()
        val codecDir = getCodecDir()
        val ttsReady = TTS_REQUIRED_FILES.all {
            val f = File(ttsDir, it)
            f.exists() && f.length() > 0
        }
        val codecReady = CODEC_REQUIRED_FILES.all {
            val f = File(codecDir, it)
            f.exists() && f.length() > 0
        }
        return ttsReady && codecReady
    }

    /**
     * 检查语音克隆所需的编码模型是否就绪。
     */
    fun isVoiceCloneReady(): Boolean {
        val codecDir = getCodecDir()
        return CODEC_OPTIONAL_FILES.all {
            val f = File(codecDir, it)
            f.exists() && f.length() > 0
        }
    }

    fun getModelSize(): Long {
        var total = 0L
        (TTS_REQUIRED_FILES + TTS_OPTIONAL_FILES).forEach {
            total += File(getTtsDir(), it).length()
        }
        (CODEC_REQUIRED_FILES + CODEC_OPTIONAL_FILES).forEach {
            total += File(getCodecDir(), it).length()
        }
        return total
    }

    suspend fun downloadAll(mirrorId: String = "hf_mirror"): Boolean = withContext(Dispatchers.IO) {
        val mirror = MIRROR_SOURCES.firstOrNull { it.id == mirrorId } ?: MIRROR_SOURCES[0]
        val baseUrl = mirror.baseUrl

        val allFiles = (TTS_REQUIRED_FILES + TTS_OPTIONAL_FILES).map { "tts" to it } +
            (CODEC_REQUIRED_FILES + CODEC_OPTIONAL_FILES).map { "codec" to it }
        val totalFiles = allFiles.size

        getModelDir().mkdirs()
        getTtsDir().mkdirs()
        getCodecDir().mkdirs()

        var successCount = 0
        var requiredSuccess = true

        for ((index, pair) in allFiles.withIndex()) {
            // 检查暂停状态
            while (com.mosstts.app.service.ModelDownloadService.isPaused) {
                kotlinx.coroutines.delay(500)
            }
            val (type, fileName) = pair
            val repo = if (type == "tts") HF_TTS_REPO else HF_CODEC_REPO
            val targetDir = if (type == "tts") getTtsDir() else getCodecDir()
            val targetFile = File(targetDir, fileName)
            val isRequired = if (type == "tts") {
                fileName in TTS_REQUIRED_FILES
            } else {
                fileName in CODEC_REQUIRED_FILES
            }

            // 已存在且大小合理则跳过
            if (targetFile.exists() && targetFile.length() > 0) {
                Log.d(TAG, "Skipping existing file: $fileName")
                _progress.value = DownloadProgress(
                    fileName = fileName,
                    downloadedBytes = targetFile.length(),
                    totalBytes = targetFile.length(),
                    currentFileIndex = index + 1,
                    totalFiles = totalFiles,
                )
                successCount++
                continue
            }

            val url = buildDownloadUrl(baseUrl, repo, fileName, mirror.id)
            val success = downloadFile(url, targetFile, index + 1, totalFiles)
            if (success) {
                successCount++
            } else if (isRequired) {
                // 关键文件下载失败，尝试其他镜像
                Log.w(TAG, "Failed to download required file $fileName from $baseUrl, trying other mirrors")
                var fallbackSuccess = false
                for (fallbackMirror in MIRROR_SOURCES) {
                    if (fallbackMirror.id == mirror.id) continue
                    val fallbackUrl = buildDownloadUrl(fallbackMirror.baseUrl, repo, fileName, fallbackMirror.id)
                    if (downloadFile(fallbackUrl, targetFile, index + 1, totalFiles)) {
                        fallbackSuccess = true
                        successCount++
                        break
                    }
                }
                if (!fallbackSuccess) {
                    requiredSuccess = false
                    _progress.value = DownloadProgress(
                        fileName = fileName,
                        downloadedBytes = 0,
                        totalBytes = 0,
                        currentFileIndex = index + 1,
                        totalFiles = totalFiles,
                        error = "关键文件下载失败: $fileName",
                    )
                    // 关键文件失败，继续尝试下载其他文件
                }
            }
        }

        if (requiredSuccess) {
            _progress.value = DownloadProgress(
                fileName = "",
                downloadedBytes = 0,
                totalBytes = 0,
                currentFileIndex = totalFiles,
                totalFiles = totalFiles,
                isComplete = true,
            )
        }
        Log.d(TAG, "Download complete: $successCount/$totalFiles files, requiredSuccess=$requiredSuccess")
        requiredSuccess
    }

    private fun buildDownloadUrl(baseUrl: String, repo: String, fileName: String, mirrorId: String): String {
        return when (mirrorId) {
            "modelscope" -> {
                // ModelScope 路径格式不同
                val modelName = repo.split("/").last()
                "$baseUrl/models/openmoss/$modelName/resolve/master/$fileName"
            }
            "ghproxy" -> {
                "$baseUrl/https://huggingface.co/$repo/resolve/main/$fileName"
            }
            else -> {
                "$baseUrl/$repo/resolve/main/$fileName"
            }
        }
    }

    private fun downloadFile(
        url: String,
        targetFile: File,
        currentIndex: Int,
        totalFiles: Int,
    ): Boolean {
        return try {
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: $url, code: ${response.code}")
                response.close()
                return false
            }

            val body = response.body ?: return false
            val totalBytes = body.contentLength()
            val inputStream = body.byteStream()
            val outputStream = tempFile.outputStream()
            val buffer = ByteArray(8192)
            var downloaded = 0L
            var read: Int
            var lastUpdateTime = System.currentTimeMillis()
            var lastDownloaded = 0L
            var currentSpeed = 0L

            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                downloaded += read
                val now = System.currentTimeMillis()
                val elapsed = now - lastUpdateTime
                if (elapsed >= 500) { // 每500ms更新一次
                    currentSpeed = if (elapsed > 0) {
                        (downloaded - lastDownloaded) * 1000 / elapsed
                    } else 0
                    lastUpdateTime = now
                    lastDownloaded = downloaded
                    _progress.value = DownloadProgress(
                        fileName = targetFile.name,
                        downloadedBytes = downloaded,
                        totalBytes = totalBytes,
                        currentFileIndex = currentIndex,
                        totalFiles = totalFiles,
                        speedBytesPerSecond = currentSpeed,
                    )
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            response.close()

            if (tempFile.renameTo(targetFile)) {
                Log.d(TAG, "Downloaded: ${targetFile.name}, size: ${targetFile.length()}")
                true
            } else {
                Log.e(TAG, "Failed to rename temp file: ${tempFile.name}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            false
        }
    }


    /**
     * 镜像测速结果
     */
    data class MirrorSpeedTestResult(
        val mirrorId: String,
        val mirrorName: String,
        val pingMs: Long,
        val speedBytesPerSecond: Long,
        val success: Boolean,
        val error: String? = null,
    )

    /**
     * 测试所有镜像源的下载速度。
     * 下载 tokenizer.model 的前 200KB 来测试速度。
     */
    suspend fun testAllMirrorsSpeed(): List<MirrorSpeedTestResult> {
        val testFile = "tokenizer.model"
        val testBytes = 50 * 1024 // 测试下载 50KB，加快测速速度

        return kotlinx.coroutines.coroutineScope {
            // 并行测试所有镜像源
            val deferredResults = MIRROR_SOURCES.map { mirror ->
                async(Dispatchers.IO) {
                    testSingleMirror(mirror, testFile, testBytes)
                }
            }

            val results = deferredResults.awaitAll()
            // 按速度排序（降序），不可用的排最后
            results.sortedWith(compareByDescending<MirrorSpeedTestResult> { it.success }
                .thenByDescending { it.speedBytesPerSecond })
        }
    }

    private fun testSingleMirror(
        mirror: MirrorSource,
        testFile: String,
        testBytes: Int,
    ): MirrorSpeedTestResult {
        return try {
            val url = buildDownloadUrl(mirror.baseUrl, HF_TTS_REPO, testFile, mirror.id)
            AppLogger.info("ModelDownloader", "测试镜像源: ${mirror.name} ($url)")

            // 测试延迟（HEAD 请求）
            val pingStart = System.currentTimeMillis()
            val headRequest = Request.Builder().url(url).head().build()
            val headResponse = speedTestClient.newCall(headRequest).execute()
            val pingMs = System.currentTimeMillis() - pingStart
            headResponse.close()

            if (!headResponse.isSuccessful && headResponse.code != 405) {
                return MirrorSpeedTestResult(
                    mirrorId = mirror.id,
                    mirrorName = mirror.name,
                    pingMs = pingMs,
                    speedBytesPerSecond = 0,
                    success = false,
                    error = "HTTP ${headResponse.code}",
                )
            }

            // 测试下载速度（GET 请求，只读前 50KB）
            val getRequest = Request.Builder().url(url).build()
            val getResponse = speedTestClient.newCall(getRequest).execute()
            if (!getResponse.isSuccessful) {
                getResponse.close()
                return MirrorSpeedTestResult(
                    mirrorId = mirror.id,
                    mirrorName = mirror.name,
                    pingMs = pingMs,
                    speedBytesPerSecond = 0,
                    success = false,
                    error = "HTTP ${getResponse.code}",
                )
            }

            val inputStream = getResponse.body?.byteStream()
            if (inputStream == null) {
                getResponse.close()
                return MirrorSpeedTestResult(
                    mirrorId = mirror.id,
                    mirrorName = mirror.name,
                    pingMs = pingMs,
                    speedBytesPerSecond = 0,
                    success = false,
                    error = "无法获取响应体",
                )
            }

            val buffer = ByteArray(8192)
            var downloaded = 0L
            val downloadStart = System.currentTimeMillis()
            while (downloaded < testBytes) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                downloaded += read
            }
            val downloadTime = System.currentTimeMillis() - downloadStart
            inputStream.close()
            getResponse.close()

            val speed = if (downloadTime > 0) {
                downloaded * 1000 / downloadTime
            } else 0

            AppLogger.info("ModelDownloader", "镜像源 ${mirror.name}: 延迟=${pingMs}ms, 速度=${speed}B/s")
            MirrorSpeedTestResult(
                mirrorId = mirror.id,
                mirrorName = mirror.name,
                pingMs = pingMs,
                speedBytesPerSecond = speed,
                success = true,
            )
        } catch (e: Exception) {
            AppLogger.error("ModelDownloader", "镜像源 ${mirror.name} 测速失败: ${e.message}", e)
            MirrorSpeedTestResult(
                mirrorId = mirror.id,
                mirrorName = mirror.name,
                pingMs = 0,
                speedBytesPerSecond = 0,
                success = false,
                error = if (e.message?.contains("timeout", true) == true) "连接超时" else e.message ?: "连接失败",
            )
        }
    }

    fun deleteModels() {
        getTtsDir().deleteRecursively()
        getCodecDir().deleteRecursively()
        _progress.value = null
    }
}
