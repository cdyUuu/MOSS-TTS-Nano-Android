package com.mosstts.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ModelManifest(
    val modelFiles: ModelFiles,
    val ttsConfig: TtsConfig,
    val promptTemplates: PromptTemplates,
    val generationDefaults: GenerationDefaults,
    val builtinVoices: List<BuiltinVoice>,
    val manifestDir: File,
) {
    companion object {
        fun fromJson(json: JSONObject, manifestDir: File): ModelManifest {
            return ModelManifest(
                modelFiles = ModelFiles.fromJson(json.getJSONObject("model_files")),
                ttsConfig = TtsConfig.fromJson(json.getJSONObject("tts_config")),
                promptTemplates = PromptTemplates.fromJson(json.getJSONObject("prompt_templates")),
                generationDefaults = GenerationDefaults.fromJson(json.optJSONObject("generation_defaults")),
                builtinVoices = json.optJSONArray("builtin_voices")?.let { voices ->
                    List(voices.length()) { index -> BuiltinVoice.fromJson(voices.getJSONObject(index)) }
                } ?: emptyList(),
                manifestDir = manifestDir,
            )
        }

        fun loadFromDir(modelRoot: File): ModelManifest {
            val candidates = listOf(
                File(modelRoot, "browser_poc_manifest.json"),
                File(modelRoot, "MOSS-TTS-Nano-100M-ONNX/browser_poc_manifest.json"),
            )
            val manifestFile = candidates.firstOrNull { it.isFile }
                ?: throw IllegalStateException("browser_poc_manifest.json not found in ${modelRoot.absolutePath}")
            val manifestDir = manifestFile.parentFile ?: modelRoot
            return fromJson(JSONObject(manifestFile.readText(Charsets.UTF_8)), manifestDir)
        }
    }

    fun resolveTtsDir(modelRoot: File): File {
        val ttsMetaRel = modelFiles.ttsMeta
        // 优先基于 manifest 所在目录解析
        val directFromManifest = File(manifestDir, ttsMetaRel)
        if (directFromManifest.exists()) {
            return directFromManifest.parentFile ?: manifestDir
        }
        // 基于 modelRoot 解析
        val direct = File(modelRoot, ttsMetaRel).parentFile ?: modelRoot
        if (direct.exists() && File(direct, "tts_browser_onnx_meta.json").exists()) return direct
        // 尝试常见子目录
        val subDir = File(modelRoot, "MOSS-TTS-Nano-100M-ONNX")
        if (subDir.exists()) return subDir
        val alias = ttsMetaRel.replace("MOSS-TTS-Nano-ONNX-CPU", "MOSS-TTS-Nano-100M-ONNX")
        return File(modelRoot, alias).parentFile ?: modelRoot
    }

    fun resolveCodecDir(modelRoot: File): File {
        val codecMetaRel = modelFiles.codecMeta
        // 优先基于 manifest 所在目录解析（处理 ../ 相对路径）
        val directFromManifest = File(manifestDir, codecMetaRel).canonicalFile
        if (directFromManifest.exists()) {
            return directFromManifest.parentFile ?: manifestDir
        }
        // 基于 modelRoot 解析
        val direct = File(modelRoot, codecMetaRel).canonicalFile
        if (direct.exists() && File(direct.parentFile ?: direct, "codec_browser_onnx_meta.json").exists()) {
            return direct.parentFile ?: modelRoot
        }
        // 尝试常见子目录
        val subDir = File(modelRoot, "MOSS-Audio-Tokenizer-Nano-ONNX")
        if (subDir.exists()) return subDir
        val alias = codecMetaRel.replace("MOSS-Audio-Tokenizer-Nano-ONNX-CPU", "MOSS-Audio-Tokenizer-Nano-ONNX")
        return File(modelRoot, alias).parentFile ?: modelRoot
    }
}

data class ModelFiles(
    val ttsMeta: String,
    val codecMeta: String,
) {
    companion object {
        fun fromJson(json: JSONObject): ModelFiles {
            return ModelFiles(
                ttsMeta = json.getString("tts_meta"),
                codecMeta = json.getString("codec_meta"),
            )
        }
    }
}

data class TtsConfig(
    val nVq: Int,
    val audioPadTokenId: Int,
    val audioStartTokenId: Int,
    val audioEndTokenId: Int,
    val audioUserSlotTokenId: Int,
    val audioAssistantSlotTokenId: Int,
    val audioCodebookSizes: IntArray,
) {
    companion object {
        fun fromJson(json: JSONObject): TtsConfig {
            return TtsConfig(
                nVq = json.getInt("n_vq"),
                audioPadTokenId = json.getInt("audio_pad_token_id"),
                audioStartTokenId = json.getInt("audio_start_token_id"),
                audioEndTokenId = json.getInt("audio_end_token_id"),
                audioUserSlotTokenId = json.optInt("audio_user_slot_token_id", 8),
                audioAssistantSlotTokenId = json.getInt("audio_assistant_slot_token_id"),
                audioCodebookSizes = json.getJSONArray("audio_codebook_sizes").let { arr ->
                    IntArray(arr.length()) { arr.getInt(it) }
                },
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TtsConfig) return false
        return nVq == other.nVq &&
            audioPadTokenId == other.audioPadTokenId &&
            audioStartTokenId == other.audioStartTokenId &&
            audioEndTokenId == other.audioEndTokenId &&
            audioUserSlotTokenId == other.audioUserSlotTokenId &&
            audioAssistantSlotTokenId == other.audioAssistantSlotTokenId &&
            audioCodebookSizes.contentEquals(other.audioCodebookSizes)
    }

    override fun hashCode(): Int {
        var result = nVq
        result = 31 * result + audioPadTokenId
        result = 31 * result + audioStartTokenId
        result = 31 * result + audioEndTokenId
        result = 31 * result + audioUserSlotTokenId
        result = 31 * result + audioAssistantSlotTokenId
        result = 31 * result + audioCodebookSizes.contentHashCode()
        return result
    }
}

data class PromptTemplates(
    val userPromptPrefixTokenIds: IntArray,
    val userPromptAfterReferenceTokenIds: IntArray,
    val assistantPromptPrefixTokenIds: IntArray,
) {
    companion object {
        fun fromJson(json: JSONObject): PromptTemplates {
            fun JSONArray.toIntArray(): IntArray = IntArray(length()) { getInt(it) }
            return PromptTemplates(
                userPromptPrefixTokenIds = json.getJSONArray("user_prompt_prefix_token_ids").toIntArray(),
                userPromptAfterReferenceTokenIds = json.getJSONArray("user_prompt_after_reference_token_ids").toIntArray(),
                assistantPromptPrefixTokenIds = json.getJSONArray("assistant_prompt_prefix_token_ids").toIntArray(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PromptTemplates) return false
        return userPromptPrefixTokenIds.contentEquals(other.userPromptPrefixTokenIds) &&
            userPromptAfterReferenceTokenIds.contentEquals(other.userPromptAfterReferenceTokenIds) &&
            assistantPromptPrefixTokenIds.contentEquals(other.assistantPromptPrefixTokenIds)
    }

    override fun hashCode(): Int {
        var result = userPromptPrefixTokenIds.contentHashCode()
        result = 31 * result + userPromptAfterReferenceTokenIds.contentHashCode()
        result = 31 * result + assistantPromptPrefixTokenIds.contentHashCode()
        return result
    }
}

data class GenerationDefaults(
    val maxNewFrames: Int = 375,
    val sampleMode: String = "fixed",
    val doSample: Boolean = true,
) {
    companion object {
        fun fromJson(json: JSONObject?): GenerationDefaults {
            return GenerationDefaults(
                maxNewFrames = json?.optInt("max_new_frames", 375) ?: 375,
                sampleMode = json?.optString("sample_mode", "fixed") ?: "fixed",
                doSample = json?.optBoolean("do_sample", true) ?: true,
            )
        }
    }
}

data class BuiltinVoice(
    val voice: String,
    val promptAudioCodes: List<IntArray>,
    val language: String = "",
    val description: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject): BuiltinVoice {
            return BuiltinVoice(
                voice = json.optString("voice", ""),
                promptAudioCodes = json.optJSONArray("prompt_audio_codes")?.let { outer ->
                    List(outer.length()) { index ->
                        val row = outer.getJSONArray(index)
                        IntArray(row.length()) { row.getInt(it) }
                    }
                } ?: emptyList(),
                language = json.optString("language", ""),
                description = json.optString("description", ""),
            )
        }
    }
}

data class TtsMeta(
    val files: TtsFiles,
    val onnx: TtsOnnxNames,
) {
    companion object {
        fun fromJson(json: JSONObject): TtsMeta {
            return TtsMeta(
                files = TtsFiles.fromJson(json.getJSONObject("files")),
                onnx = TtsOnnxNames.fromJson(json.getJSONObject("onnx")),
            )
        }
    }
}

data class TtsFiles(
    val prefill: String,
    val decodeStep: String,
    val localFixedSampledFrame: String,
) {
    companion object {
        fun fromJson(json: JSONObject): TtsFiles {
            return TtsFiles(
                prefill = json.getString("prefill"),
                decodeStep = json.getString("decode_step"),
                localFixedSampledFrame = json.getString("local_fixed_sampled_frame"),
            )
        }
    }
}

data class TtsOnnxNames(
    val decodeInputNames: List<String>,
    val decodeOutputNames: List<String>,
) {
    companion object {
        fun fromJson(json: JSONObject): TtsOnnxNames {
            fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }
            return TtsOnnxNames(
                decodeInputNames = json.getJSONArray("decode_input_names").toStringList(),
                decodeOutputNames = json.getJSONArray("decode_output_names").toStringList(),
            )
        }
    }
}

data class CodecMeta(
    val files: CodecFiles,
    val codecConfig: CodecConfig,
) {
    companion object {
        fun fromJson(json: JSONObject): CodecMeta {
            return CodecMeta(
                files = CodecFiles.fromJson(json.getJSONObject("files")),
                codecConfig = CodecConfig.fromJson(json.getJSONObject("codec_config")),
            )
        }
    }
}

data class CodecFiles(
    val encode: String = "moss_audio_tokenizer_encode.onnx",
    val decodeFull: String,
    val decodeStep: String = "moss_audio_tokenizer_decode_step.onnx",
) {
    companion object {
        fun fromJson(json: JSONObject): CodecFiles {
            return CodecFiles(
                encode = json.optString("encode", "moss_audio_tokenizer_encode.onnx"),
                decodeFull = json.getString("decode_full"),
                decodeStep = json.optString("decode_step", "moss_audio_tokenizer_decode_step.onnx"),
            )
        }
    }
}

data class CodecConfig(
    val sampleRate: Int,
    val channels: Int = 2,
    val numQuantizers: Int = 16,
) {
    companion object {
        fun fromJson(json: JSONObject): CodecConfig {
            return CodecConfig(
                sampleRate = json.getInt("sample_rate"),
                channels = json.optInt("channels", 2),
                numQuantizers = json.optInt("num_quantizers", 16),
            )
        }
    }
}
