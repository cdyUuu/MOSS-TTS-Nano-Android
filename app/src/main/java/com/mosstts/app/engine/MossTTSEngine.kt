package com.mosstts.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.mosstts.app.util.AppLogger
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.min

/**
 * MOSS-TTS-Nano ONNX 推理引擎。
 * 完整实现：文本编码 -> prefill -> decode 循环 -> codec 解码 -> PCM 输出。
 * 支持内置音色和语音克隆（参考音频编码）。
 */
class MossTTSEngine(
    private val modelRoot: File,
    private val cpuThreads: Int = 4,
) : AutoCloseable {

    companion object {
        private const val TAG = "MossTTSEngine"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment().also {
        AppLogger.info(TAG, "ONNX Runtime 环境创建成功")
    }
    val manifest: ModelManifest = ModelManifest.loadFromDir(modelRoot).also {
        AppLogger.info(TAG, "Manifest 加载成功，内置音色数: ${it.builtinVoices.size}, n_vq: ${it.ttsConfig.nVq}")
    }
    private val ttsDir: File = manifest.resolveTtsDir(modelRoot).also {
        AppLogger.info(TAG, "TTS 目录: ${it.absolutePath}, 存在: ${it.exists()}")
    }
    private val codecDir: File = manifest.resolveCodecDir(modelRoot).also {
        AppLogger.info(TAG, "Codec 目录: ${it.absolutePath}, 存在: ${it.exists()}")
    }

    private val ttsMeta: TtsMeta = TtsMeta.fromJson(
        JSONObject(File(ttsDir, "tts_browser_onnx_meta.json").readText(Charsets.UTF_8))
    ).also {
        AppLogger.info(TAG, "TTS meta 加载成功，文件列表: prefill=${it.files.prefill}, decode=${it.files.decodeStep}")
    }
    private val codecMeta: CodecMeta = CodecMeta.fromJson(
        JSONObject(File(codecDir, "codec_browser_onnx_meta.json").readText(Charsets.UTF_8))
    ).also {
        AppLogger.info(TAG, "Codec meta 加载成功，采样率: ${it.codecConfig.sampleRate}, 声道: ${it.codecConfig.channels}")
    }

    val tokenizer: SentencePieceTokenizer = SentencePieceTokenizer(File(ttsDir, "tokenizer.model")).also {
        AppLogger.info(TAG, "Tokenizer 加载成功，vocab 大小: ${it.vocabSize}")
    }
    val sampleRate: Int = codecMeta.codecConfig.sampleRate
    val channels: Int = codecMeta.codecConfig.channels
    val builtinVoices: List<BuiltinVoice> = manifest.builtinVoices

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(cpuThreads.coerceAtLeast(1))
        setInterOpNumThreads(1)
        AppLogger.info(TAG, "SessionOptions 创建成功，线程数: ${cpuThreads.coerceAtLeast(1)}")
    }

    private val prefillSession = createSession(File(ttsDir, ttsMeta.files.prefill), "prefill")
    private val decodeSession = createSession(File(ttsDir, ttsMeta.files.decodeStep), "decode_step")
    private val localFixedFrameSession = createSession(File(ttsDir, ttsMeta.files.localFixedSampledFrame), "local_fixed_sampled_frame")
    private val codecDecodeSession = createSession(File(codecDir, codecMeta.files.decodeFull), "codec_decode_full")
    private val codecEncodeSession: OrtSession? = try {
        createSession(File(codecDir, codecMeta.files.encode), "codec_encode")
    } catch (e: Exception) {
        AppLogger.warn(TAG, "Codec encode 模型不可用，语音克隆功能禁用: ${e.message}", e)
        null
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun resetCancel() {
        cancelled = false
    }

    /**
     * 合成语音。
     * @param text 输入文本
     * @param voice 内置音色名称，为空时使用第一个
     * @param referenceAudioCodes 语音克隆参考音频的 audio codes（优先于 voice）
     * @param maxFrames 最大生成帧数，null 时使用 manifest 默认值
     * @param seed 随机种子
     * @param onFrame 每生成一帧 audio codes 的回调（用于流式播放）
     * @param onAudio 每解码出一段 PCM 音频的回调
     * @return 合成结果（完整 PCM 数据）
     */
    fun synthesize(
        text: String,
        voice: String? = null,
        referenceAudioCodes: List<IntArray>? = null,
        maxFrames: Int? = null,
        seed: Long = System.currentTimeMillis(),
        onFrame: ((frameIndex: Int, totalFrames: Int) -> Unit)? = null,
        onAudio: ((pcm: FloatArray, isLast: Boolean) -> Unit)? = null,
    ): SynthesisResult {
        resetCancel()
        val startedAt = System.currentTimeMillis()

        // 文本归一化和分块
        val normalized = TextNormalizer.normalize(text)
        val chunks = TextNormalizer.splitIntoChunks(normalized, { tokenizer.encode(it).size }, 75)

        Log.d(TAG, "Text normalized: $normalized, chunks: ${chunks.size}")

        val allPcm = ArrayList<Float>()
        var totalFrames = 0
        val framesPerChunk = maxFrames ?: manifest.generationDefaults.maxNewFrames
        val estimatedTotalFrames = chunks.size * framesPerChunk

        val promptAudioCodes = referenceAudioCodes ?: selectBuiltinVoiceCodes(voice)

        for (chunkIndex in chunks.indices) {
            if (cancelled) break
            val chunk = chunks[chunkIndex]
            val textTokenIds = tokenizer.encode(chunk)
            if (textTokenIds.isEmpty()) continue

            val inputRows = buildInputRows(textTokenIds, promptAudioCodes)
            val prefillResult = runPrefill(inputRows)
            val audioTokens = runDecode(
                prefillResult,
                framesPerChunk,
                seed + chunkIndex,
                onFrame = { frameIdx, _ ->
                    val currentFrame = chunkIndex * framesPerChunk + frameIdx
                    onFrame?.invoke(currentFrame, estimatedTotalFrames)
                }
            )
            totalFrames += audioTokens.size

            // 解码音频
            val pcm = decodeAudioTokens(audioTokens)
            allPcm.addAll(pcm.toList())
            onAudio?.invoke(pcm, chunkIndex == chunks.size - 1)

            // 块间插入短暂静音
            if (chunkIndex < chunks.size - 1) {
                val pauseSamples = (sampleRate * 0.25f).toInt()
                val pause = FloatArray(pauseSamples) { 0f }
                allPcm.addAll(pause.toList())
                onAudio?.invoke(pause, false)
            }
        }

        val finalPcm = allPcm.toFloatArray()
        val elapsedMs = System.currentTimeMillis() - startedAt

        return SynthesisResult(
            pcm = finalPcm,
            sampleRate = sampleRate,
            channels = 1,
            generatedFrames = totalFrames,
            durationMs = (finalPcm.size.toDouble() / sampleRate * 1000).toLong(),
            elapsedMs = elapsedMs,
            textChunks = chunks,
        )
    }

    /**
     * 编码参考音频为 audio codes（用于语音克隆）。
     * 需要 48kHz 立体声 PCM 数据。
     */
    fun encodeReferenceAudio(pcm: FloatArray, inputSampleRate: Int): List<IntArray> {
        val session = codecEncodeSession
            ?: throw IllegalStateException("语音克隆编码模型不可用，请确保已下载完整模型")

        require(pcm.isNotEmpty()) { "PCM data must not be empty" }

        Log.d(TAG, "encodeReferenceAudio: pcm size=${pcm.size}, inputSampleRate=$inputSampleRate, targetSampleRate=$sampleRate, channels=$channels")

        // 重采样到 48kHz
        val targetPcm = if (inputSampleRate != sampleRate) {
            resample(pcm, inputSampleRate, sampleRate)
        } else pcm

        // 转立体声（如果是单声道）
        val stereoPcm = if (channels == 2) {
            Array(2) { targetPcm }
        } else {
            arrayOf(targetPcm)
        }

        // 转为 [1, channels, samples] 形状
        val numSamples = stereoPcm[0].size
        val flatPcm = FloatArray(channels * numSamples)
        for (ch in 0 until channels) {
            System.arraycopy(stereoPcm[ch], 0, flatPcm, ch * numSamples, numSamples)
        }

        Log.d(TAG, "encodeReferenceAudio: numSamples=$numSamples, flatPcm size=${flatPcm.size}")

        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flatPcm),
            longArrayOf(1, channels.toLong(), numSamples.toLong()),
        ).use { waveformTensor ->
            OnnxTensor.createTensor(
                env,
                IntBuffer.wrap(intArrayOf(numSamples)),
                longArrayOf(1),
            ).use { lengthsTensor ->
                val outputs = session.run(
                    mapOf(
                        "waveform" to waveformTensor,
                        "input_lengths" to lengthsTensor,
                    )
                )
                outputs.use { result ->
                    // 将结果转为 Map，方便按名称获取
                    val outputMap = result.associate { entry -> entry.key to entry.value }
                    Log.d(TAG, "encodeReferenceAudio output names: ${outputMap.keys}")

                    val audioCodesTensor = outputMap["audio_codes"]
                        ?: outputMap.entries.firstOrNull { (name, _) ->
                            name.contains("audio_code", ignoreCase = true) && !name.contains("length", ignoreCase = true)
                        }?.value
                        ?: throw IllegalStateException("找不到 audio_codes 输出，可用输出: ${outputMap.keys}")

                    val audioLengthsTensor = outputMap["audio_code_lengths"]
                        ?: outputMap.entries.firstOrNull { (name, _) ->
                            name.contains("length", ignoreCase = true)
                        }?.value
                        ?: throw IllegalStateException("找不到 audio_code_lengths 输出，可用输出: ${outputMap.keys}")

                    val audioCodes = flattenIntTensorValue(audioCodesTensor.value)
                    val audioCodeLengths = flattenIntTensorValue(audioLengthsTensor.value)
                    val codeLength = audioCodeLengths.firstOrNull() ?: 0
                    val numQuantizers = manifest.ttsConfig.nVq

                    Log.d(TAG, "encodeReferenceAudio: codeLength=$codeLength, numQuantizers=$numQuantizers, audioCodes size=${audioCodes.size}")

                    if (codeLength <= 0) {
                        throw IllegalStateException("参考音频编码失败：生成的帧数为 0")
                    }

                    val result = ArrayList<IntArray>(codeLength)
                    for (frameIdx in 0 until codeLength) {
                        val row = IntArray(numQuantizers) { q ->
                            val idx = frameIdx * numQuantizers + q
                            if (idx < audioCodes.size) audioCodes[idx] else 0
                        }
                        result.add(row)
                    }
                    Log.d(TAG, "Encoded reference audio: $codeLength frames, $numQuantizers quantizers")
                    return result
                }
            }
        }
    }

    /**
     * 将 PCM 写入 WAV 文件。
     */
    fun writeWav(pcm: FloatArray, outputFile: File, sampleRate: Int = this.sampleRate) {
        outputFile.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        val fileSize = 44 + dataSize
        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(fileSize - 8)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(1.toShort()) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (sample in pcm) {
            buffer.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        outputFile.writeBytes(buffer.array())
    }

    // ==================== 内部实现 ====================

    private fun createSession(modelFile: File, name: String = "unknown"): OrtSession {
        AppLogger.info(TAG, "加载模型 []: ${modelFile.name}, 存在: ${modelFile.isFile}, 大小: ${modelFile.length() / 1024}KB")
        require(modelFile.isFile) { "缺少 ONNX 模型文件: ${modelFile.absolutePath}" }
        val startTime = System.currentTimeMillis()
        val session = env.createSession(modelFile.absolutePath, sessionOptions)
        AppLogger.info(TAG, "模型 [] 加载成功，耗时: ${System.currentTimeMillis() - startTime}ms")
        return session
    }

    private fun selectBuiltinVoiceCodes(voice: String?): List<IntArray> {
        val selected = if (voice != null) {
            builtinVoices.firstOrNull { it.voice == voice && it.promptAudioCodes.isNotEmpty() }
        } else null
        val finalVoice = selected ?: builtinVoices.firstOrNull { it.promptAudioCodes.isNotEmpty() }
        return finalVoice?.promptAudioCodes
            ?: throw IllegalStateException("No builtin voice with prompt_audio_codes found")
    }

    private fun buildInputRows(textTokenIds: IntArray, promptAudioCodes: List<IntArray>): InputRows {
        val cfg = manifest.ttsConfig
        val rowWidth = cfg.nVq + 1
        val templates = manifest.promptTemplates

        val prefixTokens = templates.userPromptPrefixTokenIds + cfg.audioStartTokenId
        val suffixTokens = intArrayOf(cfg.audioEndTokenId) +
            templates.userPromptAfterReferenceTokenIds +
            textTokenIds +
            templates.assistantPromptPrefixTokenIds +
            intArrayOf(cfg.audioStartTokenId)

        val rows = ArrayList<IntArray>()
        rows += buildTextRows(prefixTokens, cfg, rowWidth)
        rows += buildAudioRows(promptAudioCodes, cfg, rowWidth)
        rows += buildTextRows(suffixTokens, cfg, rowWidth)

        return InputRows(rows.toTypedArray(), IntArray(rows.size) { 1 })
    }

    private fun buildTextRows(tokens: IntArray, cfg: TtsConfig, rowWidth: Int): List<IntArray> {
        return tokens.map { token ->
            IntArray(rowWidth) { index -> if (index == 0) token else cfg.audioPadTokenId }
        }
    }

    private fun buildAudioRows(audioCodes: List<IntArray>, cfg: TtsConfig, rowWidth: Int): List<IntArray> {
        return audioCodes.map { codeRow ->
            IntArray(rowWidth) { index ->
                when {
                    index == 0 -> cfg.audioUserSlotTokenId
                    index - 1 < min(codeRow.size, cfg.nVq) -> codeRow[index - 1]
                    else -> cfg.audioPadTokenId
                }
            }
        }
    }

    private fun runPrefill(inputRows: InputRows): PrefillResult {
        val seqLen = inputRows.inputIds.size
        val rowWidth = inputRows.inputIds[0].size
        val inputIdsFlat = IntArray(seqLen * rowWidth)
        var offset = 0
        for (row in inputRows.inputIds) {
            for (value in row) {
                inputIdsFlat[offset++] = value
            }
        }

        OnnxTensor.createTensor(
            env,
            IntBuffer.wrap(inputIdsFlat),
            longArrayOf(1, seqLen.toLong(), rowWidth.toLong()),
        ).use { inputIdsTensor ->
            OnnxTensor.createTensor(
                env,
                IntBuffer.wrap(inputRows.attentionMask),
                longArrayOf(1, seqLen.toLong()),
            ).use { maskTensor ->
                val outputs = prefillSession.run(
                    mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to maskTensor,
                    )
                )
                return PrefillResult(
                    globalHidden = extractLastHiddenTensor(outputs.requiredTensor("global_hidden")),
                    pastValidLengths = seqLen,
                    pastResult = outputs,
                )
            }
        }
    }

    private fun runDecode(
        prefillResult: PrefillResult,
        maxFrames: Int,
        seed: Long,
        onFrame: ((frameIndex: Int, totalFrames: Int) -> Unit)? = null,
    ): List<IntArray> {
        val cfg = manifest.ttsConfig
        val audioTokens = ArrayList<IntArray>()
        val rowWidth = cfg.nVq + 1
        val cappedMaxFrames = maxFrames.coerceAtMost(manifest.generationDefaults.maxNewFrames)

        val previousTokenSets = Array(cfg.nVq) { HashSet<Int>() }
        val decodePastInputNames = ttsMeta.onnx.decodeInputNames.drop(2)
        val decodePresentOutputNames = ttsMeta.onnx.decodeOutputNames.drop(1)
        val random = java.util.Random(seed)

        var pastValidLengths = prefillResult.pastValidLengths
        var globalHidden = prefillResult.globalHidden
        var pastResult: OrtSession.Result? = prefillResult.pastResult

        try {
            for (step in 0 until cappedMaxFrames) {
                if (cancelled) break

                val frameResult = runLocalFixedSampledFrame(globalHidden, previousTokenSets, random)
                if (!frameResult.shouldContinue) break

                val audioRow = IntArray(rowWidth) { index ->
                    if (index == 0) cfg.audioAssistantSlotTokenId else cfg.audioPadTokenId
                }
                for (quantizer in 0 until cfg.nVq) {
                    val token = frameResult.frame[quantizer]
                    audioRow[quantizer + 1] = token
                    previousTokenSets[quantizer].add(token)
                }
                audioTokens += frameResult.frame
                onFrame?.invoke(step + 1, cappedMaxFrames)

                OnnxTensor.createTensor(
                    env,
                    IntBuffer.wrap(audioRow),
                    longArrayOf(1, 1, rowWidth.toLong()),
                ).use { inputTensor ->
                    OnnxTensor.createTensor(
                        env,
                        IntBuffer.wrap(intArrayOf(pastValidLengths)),
                        longArrayOf(1),
                    ).use { pastTensor ->
                        val feeds = linkedMapOf<String, OnnxTensorLike>(
                            "input_ids" to inputTensor,
                            "past_valid_lengths" to pastTensor,
                        )
                        val previousPastResult = pastResult ?: error("Missing decode KV cache")
                        for (index in decodePastInputNames.indices) {
                            feeds[decodePastInputNames[index]] =
                                previousPastResult.requiredTensor(decodePresentOutputNames[index])
                        }
                        val outputs = decodeSession.run(feeds)
                        val nextGlobalHidden = extractLastHiddenTensor(outputs.requiredTensor("global_hidden"))
                        globalHidden.close()
                        previousPastResult.close()
                        pastResult = outputs
                        globalHidden = nextGlobalHidden
                        pastValidLengths += 1
                    }
                }
            }
        } finally {
            globalHidden.close()
            pastResult?.close()
        }
        return audioTokens
    }

    private fun runLocalFixedSampledFrame(
        globalHidden: OnnxTensor,
        previousTokenSets: Array<HashSet<Int>>,
        random: java.util.Random,
    ): LocalFrameResult {
        val cfg = manifest.ttsConfig
        val audioCodebookSize = cfg.audioCodebookSizes.firstOrNull() ?: 1024
        val seenMask = IntArray(cfg.nVq * audioCodebookSize)
        for (channelIndex in previousTokenSets.indices) {
            val channelOffset = channelIndex * audioCodebookSize
            for (tokenId in previousTokenSets[channelIndex]) {
                if (tokenId in 0 until audioCodebookSize) {
                    seenMask[channelOffset + tokenId] = 1
                }
            }
        }

        val assistantRandom = floatArrayOf(random.nextDouble().coerceIn(1e-6, 1.0 - 1e-6).toFloat())
        val audioRandom = FloatArray(cfg.nVq) {
            random.nextDouble().coerceIn(1e-6, 1.0 - 1e-6).toFloat()
        }

        OnnxTensor.createTensor(
            env,
            IntBuffer.wrap(seenMask),
            longArrayOf(1, cfg.nVq.toLong(), audioCodebookSize.toLong()),
        ).use { seenTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(assistantRandom), longArrayOf(1)).use { assistantTensor ->
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(audioRandom),
                    longArrayOf(1, cfg.nVq.toLong()),
                ).use { audioTensor ->
                    val outputs = localFixedFrameSession.run(
                        mapOf(
                            "global_hidden" to globalHidden,
                            "repetition_seen_mask" to seenTensor,
                            "assistant_random_u" to assistantTensor,
                            "audio_random_u" to audioTensor,
                        )
                    )
                    outputs.use {
                        return LocalFrameResult(
                            shouldContinue = it.requiredTensor("should_continue").scalarInt() > 0,
                            frame = it.requiredTensor("frame_token_ids").intArrayValue(),
                        )
                    }
                }
            }
        }
    }

    private fun decodeAudioTokens(audioTokens: List<IntArray>): FloatArray {
        require(audioTokens.isNotEmpty()) { "No audio tokens generated" }
        val numFrames = audioTokens.size
        val numQuantizers = manifest.ttsConfig.nVq
        val audioCodesFlat = IntArray(numFrames * numQuantizers)
        var offset = 0
        for (frame in audioTokens) {
            for (quantizer in 0 until numQuantizers) {
                audioCodesFlat[offset++] = frame[quantizer]
            }
        }

        OnnxTensor.createTensor(
            env,
            IntBuffer.wrap(audioCodesFlat),
            longArrayOf(1, numFrames.toLong(), numQuantizers.toLong()),
        ).use { codesTensor ->
            OnnxTensor.createTensor(
                env,
                IntBuffer.wrap(intArrayOf(numFrames)),
                longArrayOf(1),
            ).use { lengthsTensor ->
                val outputs = codecDecodeSession.run(
                    mapOf(
                        "audio_codes" to codesTensor,
                        "audio_code_lengths" to lengthsTensor,
                    )
                )
                outputs.use {
                    val audio = it.requiredTensor("audio").value as Array<*>
                    val batch = audio[0] as Array<*>
                    val channelsData = batch.map { channel -> channel as FloatArray }
                    val reportedLength = it.requiredTensor("audio_lengths").scalarInt()
                    val length = min(reportedLength, channelsData.minOfOrNull { channel -> channel.size } ?: 0)
                    // 混合为单声道
                    return FloatArray(length) { sampleIndex ->
                        channelsData.sumOf { channel -> channel[sampleIndex].toDouble() }.toFloat() / channelsData.size
                    }
                }
            }
        }
    }

    private fun resample(pcm: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return pcm
        val ratio = toRate.toDouble() / fromRate
        val newLength = (pcm.size * ratio).toInt()
        val result = FloatArray(newLength)
        for (i in 0 until newLength) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()
            val a = if (srcIdx < pcm.size) pcm[srcIdx] else 0f
            val b = if (srcIdx + 1 < pcm.size) pcm[srcIdx + 1] else a
            result[i] = a + (b - a) * frac
        }
        return result
    }

    private fun extractLastHiddenTensor(tensor: OnnxTensor): OnnxTensor {
        val shape = tensor.info.shape
        val hidden = when (shape.size) {
            2 -> {
                val value = tensor.value as Array<*>
                value[0] as FloatArray
            }
            3 -> {
                val value = tensor.value as Array<*>
                val batch = value[0] as Array<*>
                batch[batch.size - 1] as FloatArray
            }
            else -> error("Unexpected global_hidden rank: ${shape.size}")
        }
        return OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(hidden.copyOf()),
            longArrayOf(1, hidden.size.toLong()),
        )
    }

    private fun flattenIntTensorValue(raw: Any?): IntArray {
        val values = ArrayList<Int>()
        fun append(value: Any?) {
            when (value) {
                is Int -> values += value
                is Long -> values += value.toInt()
                is Short -> values += value.toInt()
                is Byte -> values += value.toInt()
                is IntArray -> values += value.toList()
                is LongArray -> value.forEach { values += it.toInt() }
                is ShortArray -> value.forEach { values += it.toInt() }
                is ByteArray -> value.forEach { values += it.toInt() }
                is Array<*> -> value.forEach { append(it) }
                null -> Unit
                else -> error("Unsupported int tensor value: ${value.javaClass}")
            }
        }
        append(raw)
        return values.toIntArray()
    }

    private fun OrtSession.Result.requiredValue(name: String): ai.onnxruntime.OnnxValue {
        return get(name).orElseThrow { IllegalStateException("Missing ONNX output: $name") }
    }

    private fun OrtSession.Result.requiredTensor(name: String): OnnxTensor {
        return requiredValue(name) as OnnxTensor
    }

    private fun OnnxTensor.scalarInt(): Int {
        return flattenIntTensorValue(value).firstOrNull() ?: error("Scalar int tensor is empty")
    }

    private fun OnnxTensor.intArrayValue(): IntArray {
        return flattenIntTensorValue(value)
    }

    override fun close() {
        codecEncodeSession?.close()
        codecDecodeSession.close()
        localFixedFrameSession.close()
        decodeSession.close()
        prefillSession.close()
        sessionOptions.close()
    }

    private data class InputRows(val inputIds: Array<IntArray>, val attentionMask: IntArray)
    private data class PrefillResult(
        val globalHidden: OnnxTensor,
        val pastValidLengths: Int,
        val pastResult: OrtSession.Result,
    )
    private data class LocalFrameResult(val shouldContinue: Boolean, val frame: IntArray)
}

data class SynthesisResult(
    val pcm: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val generatedFrames: Int,
    val durationMs: Long,
    val elapsedMs: Long,
    val textChunks: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesisResult) return false
        return pcm.contentEquals(other.pcm) &&
            sampleRate == other.sampleRate &&
            channels == other.channels &&
            generatedFrames == other.generatedFrames &&
            durationMs == other.durationMs &&
            elapsedMs == other.elapsedMs &&
            textChunks == other.textChunks
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + generatedFrames
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + textChunks.hashCode()
        return result
    }
}
