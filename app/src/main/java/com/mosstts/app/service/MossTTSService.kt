package com.mosstts.app.service

import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.mosstts.app.MossTTSApp
import com.mosstts.app.data.ModelManager
import com.mosstts.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * 系统 TTS 引擎服务：把本地 MOSS-TTS 模型暴露为 Android 系统文字转语音引擎。
 *
 * 其他应用（阅读器、无障碍服务等）在系统设置里选择「MOSS TTS 本地引擎」后，
 * 即可通过标准 TextToSpeech API 调用本引擎朗读文本。
 *
 * 实现说明（基于 compileSdk 34 / minSdk 26 的 TextToSpeechService 现代引擎 API，
 * 以下签名均已对照 android-34/android.jar 核实）：
 * - 合成入口是 [onSynthesizeText]：框架传入 [SynthesisRequest]，
 *   引擎把 PCM 音频通过 [SynthesisCallback.audioAvailable] 分块交给框架，
 *   由框架统一负责播放，语速/音量/音高/音频流等参数也由框架自动应用，
 *   因此引擎侧不需要（也不能）自行使用 AudioTrack 播放，否则会与框架播放叠加。
 * - 队列模式（QUEUE_ADD / QUEUE_FLUSH）由框架在客户端完成排解后再逐个请求引擎，
 *   引擎侧只需保证同一时刻只有一次合成在执行：用 [Mutex] 串行，
 *   防止多个应用并发调用时引擎状态竞争。
 * - [onStop] 表示"停止一切合成"：置中断标记并取消 ONNX 推理；
 *   框架收到停止后会自行停止播放，引擎不操作播放器。
 * - 模型未下载/初始化失败时通过 [SynthesisCallback.error] 通知调用方，绝不抛出异常。
 *
 * 与需求文档中旧式 API 的对应关系（下列成员在 API 34 的 TextToSpeechService 中
 * 均不存在，照搬旧教程将无法编译或永不被系统调用，故作此说明）：
 * - `onInit(status)` → 不存在，服务初始化入口为 [onCreate]；
 * - `onSpeak(text, queueMode, params, utteranceId)` → API 21 起被 [onSynthesizeText] 取代；
 * - `onDone(utteranceId)` / `onError(utteranceId)` → 不存在，对应 [SynthesisCallback.done] /
 *   [SynthesisCallback.error]；
 * - `onStop(utteranceId)` → 不存在，仅剩无参 [onStop]；
 * - `onGetFeatures()` → 实际方法名为 [onGetFeaturesForLanguage]（lang, country, variant）；
 * - `TextToSpeech.Engine.KEY_PARAM_RATE` → 该常量不存在，语速经
 *   [SynthesisRequest.getSpeechRate]（int 百分比，100 为正常速度）传入。
 */
class MossTTSService : TextToSpeechService() {

    companion object {
        private const val TAG = "MossTTSService"

        /** 模型输出固定 48kHz 单声道 */
        private const val SAMPLE_RATE = 48000

        /** 16bit PCM：每个采样点占 2 字节 */
        private const val BYTES_PER_SAMPLE = 2

        /** getMaxBufferSize 返回异常时，单块字节数的兜底值 */
        private const val FALLBACK_CHUNK_BYTES = 16384
    }

    /** 合成互斥锁：多个应用可能同时发起合成请求，串行执行避免引擎内部状态竞争 */
    private val synthMutex = Mutex()

    /**
     * 中断代号：[onStop] 时自增。
     * 每次合成开始前记录当前值，执行过程中发现值变化即说明收到停止指令，提前退出。
     */
    @Volatile
    private var stopToken = 0

    /** 服务级协程作用域：SupervisorJob + Dispatchers.Default，服务销毁时统一取消 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        AppLogger.info(TAG, "系统 TTS 引擎服务已创建")
    }

    // ==================== 语言与特性 ====================

    /**
     * 当前使用的语言：简体中文。
     * TextToSpeechService 约定返回 [语言, 国家, 变体] 三元组（而非 "zh-CN" 字符串本身），
     * 由 Locale 对象正确序列化：Locale.CHINA.toString() = "zh_CN"，框架对外展示为 "zh-CN"。
     */
    override fun onGetLanguage(): Array<String> {
        val locale = Locale.CHINA // 即 Locale("zh", "CN")，对应 "zh-CN"
        return arrayOf(locale.language, locale.country, locale.variant)
    }

    /**
     * 是否支持指定语言。
     * 模型只支持中文：语言码取 zh（ISO-639-1）或 zho（ISO-639-2/T）；
     * 国家未指定或为 CN 时按 zh-CN 可用，返回 LANG_AVAILABLE，其余一律 LANG_NOT_SUPPORTED。
     */
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val isChinese = lang.equals("zh", ignoreCase = true) || lang.equals("zho", ignoreCase = true)
        val countryOk = country.isNullOrEmpty() || country.equals("CN", ignoreCase = true)
        return if (isChinese && countryOk) {
            TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    /**
     * 加载语言数据。中文语音数据随模型文件本地就绪，无需额外安装，
     * 支持的语言直接返回 LANG_AVAILABLE。
     */
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return if (onIsLanguageAvailable(lang, country, variant) == TextToSpeech.LANG_NOT_SUPPORTED) {
            TextToSpeech.LANG_NOT_SUPPORTED
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    /**
     * 声明引擎特性集合（API 24+ 的真实方法名，需求文档中的 onGetFeatures() 并不存在）。
     * 本引擎为纯本地推理，不依赖网络、非嵌入式安装，故不声明任何特性，返回空集合。
     */
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): Set<String> {
        return emptySet()
    }

    // ==================== 合成 ====================

    /**
     * 框架请求合成文本。本方法运行在 Binder 线程，内部切到服务协程异步执行后立即返回，
     * 不阻塞框架；合成结果通过 [callback] 异步通知框架。
     */
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: ""
        val params: Bundle? = request.params
        // utteranceId 仅用于日志关联（框架通过 callback 对象本身路由完成/失败通知）
        val utteranceId = params?.get(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID) as? String
        // 语速为百分比（100 为正常速度）：TextToSpeech.Engine 并无 KEY_PARAM_RATE 常量，
        // 语速由框架经 SynthesisRequest.getSpeechRate() 传入；音量为 0.0~1.0。
        // 二者最终由框架应用到播放，此处仅解析记录。
        val ratePercent = request.speechRate
        val volume = parseVolume(params)
        AppLogger.info(
            TAG,
            "收到合成请求: utteranceId=$utteranceId, 语速=${ratePercent}%, 音量=$volume, 文本长度=${text.length}"
        )

        // 记录进入时的中断代号：若在等待互斥锁期间收到 onStop，则直接放弃本次合成
        val token = stopToken
        serviceScope.launch {
            try {
                synthMutex.withLock {
                    if (stopToken != token) {
                        AppLogger.info(TAG, "等待期间收到停止指令，放弃合成: utteranceId=$utteranceId")
                        return@withLock
                    }
                    synthesizeAndDeliver(text, utteranceId, request, callback, token)
                }
            } catch (e: CancellationException) {
                // 协程被取消（服务销毁等）：属正常收尾，重新抛出，不当作合成失败回调
                throw e
            } catch (e: Exception) {
                // 兜底：任何异常都通知调用方合成失败，避免引擎进程崩溃
                AppLogger.error(TAG, "合成流程异常: utteranceId=$utteranceId", e)
                runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
            }
        }
    }

    /**
     * 停止所有合成：中断正在进行的 ONNX 推理，并让音频发送循环退出。
     * 播放由框架负责，框架收到停止后会自行停止播放，引擎无需操作播放器。
     */
    override fun onStop() {
        AppLogger.info(TAG, "onStop: 中断当前合成")
        stopToken++
        modelManagerOrNull()?.cancelSynthesis()
    }

    override fun onDestroy() {
        AppLogger.info(TAG, "系统 TTS 引擎服务销毁")
        // 中断可能仍在进行的合成，并取消服务协程
        stopToken++
        modelManagerOrNull()?.cancelSynthesis()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ==================== 内部实现 ====================

    /**
     * 执行合成并把 PCM 分块交给框架播放。
     * 全程 try-catch（由调用方的 launch 包裹），任何失败都通过 [SynthesisCallback.error]
     * 通知调用方；收到停止指令时安静退出（框架已知晓本次中断，无需再回调）。
     */
    private suspend fun synthesizeAndDeliver(
        text: String,
        utteranceId: String?,
        request: SynthesisRequest,
        callback: SynthesisCallback,
        token: Int,
    ) {
        // 空文本：无内容可合成，按失败通知调用方（等价于旧式 API 的 onError(utteranceId)）
        if (text.isBlank()) {
            AppLogger.warn(TAG, "文本为空，按错误处理: utteranceId=$utteranceId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        // NPE 防护：进程被系统重建等场景下 application 可能不是 MossTTSApp，或单例构造失败
        val manager = modelManagerOrNull()
        if (manager == null) {
            AppLogger.error(TAG, "ModelManager 不可用（应用未正常初始化）: utteranceId=$utteranceId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        // 确保模型已下载并加载（initialize 内部幂等，READY 时直接返回 true）
        if (!manager.isReady() && !manager.initialize()) {
            // 模型未下载或初始化失败：通知调用方，不弹通知、不崩溃
            AppLogger.error(TAG, "模型未下载或初始化失败，无法合成: utteranceId=$utteranceId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        AppLogger.info(
            TAG,
            "开始合成: utteranceId=$utteranceId, voice=${request.voiceName}, 文本=${text.take(30)}"
        )

        // 完整合成（不走流式回调，合成完成后一次性分块交给框架，逻辑简单可靠）
        val result = manager.synthesize(
            text = text,
            // 应用未指定音色时使用默认内置音色；指定了不存在的音色时引擎内部会回退到首个内置音色
            voice = request.voiceName,
            referenceAudioCodes = null, // 系统 TTS 不使用克隆音色
            maxFrames = null, // 不限制帧数，按模型默认配置生成
        )

        // 合成过程中若收到停止指令：ONNX 推理被 cancel 中断后 result 会为 null，
        // 此时框架已知晓本次中断，安静退出即可，不再回调 error/done
        if (stopToken != token) {
            AppLogger.info(TAG, "合成被中断，停止发送音频: utteranceId=$utteranceId")
            return
        }

        if (result == null) {
            AppLogger.error(TAG, "合成失败: utteranceId=$utteranceId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        // 向框架声明音频格式：48kHz / 单声道 / 16bit PCM（MOSS-TTS 模型输出固定格式）
        val sampleRate = if (result.sampleRate > 0) result.sampleRate else SAMPLE_RATE
        val startRet = callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        if (startRet != TextToSpeech.SUCCESS) {
            AppLogger.error(TAG, "callback.start 失败: $startRet, utteranceId=$utteranceId")
            return
        }

        // 分块发送 PCM，块大小受框架 getMaxBufferSize 限制
        val maxBytes = callback.getMaxBufferSize()
        val chunkBytes = if (maxBytes > 0) maxBytes else FALLBACK_CHUNK_BYTES
        val chunkSamples = (chunkBytes / BYTES_PER_SAMPLE).coerceAtLeast(1)

        var offset = 0
        while (offset < result.pcm.size) {
            if (stopToken != token) {
                AppLogger.info(TAG, "发送音频期间收到停止指令: utteranceId=$utteranceId")
                return
            }
            val end = minOf(offset + chunkSamples, result.pcm.size)
            // FloatArray（-1.0 ~ 1.0）→ 16bit 小端 PCM 字节
            val bytes = floatToPcm16Bytes(result.pcm, offset, end)
            val ret = callback.audioAvailable(bytes, 0, bytes.size)
            if (ret != TextToSpeech.SUCCESS) {
                // 客户端已停止或出错，框架不再接收音频，安静退出即可
                AppLogger.warn(TAG, "audioAvailable 返回 $ret，停止发送: utteranceId=$utteranceId")
                return
            }
            offset = end
        }

        if (stopToken != token) {
            AppLogger.info(TAG, "发送音频完成后收到停止指令: utteranceId=$utteranceId")
            return
        }

        val doneRet = callback.done()
        AppLogger.info(
            TAG,
            "合成播报完成: utteranceId=$utteranceId, 采样点=${result.pcm.size}, done=$doneRet"
        )
    }

    /**
     * 获取全局共享的 ModelManager。
     * NPE 防护：进程重建/类加载异常等场景下返回 null，由调用方走错误分支，绝不崩溃。
     */
    private fun modelManagerOrNull(): ModelManager? {
        return try {
            (application as? MossTTSApp)?.modelManager
        } catch (e: Exception) {
            AppLogger.error(TAG, "获取 ModelManager 失败: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 params 中的音量参数（KEY_PARAM_VOLUME，0.0 ~ 1.0）。
     * 音量最终由框架应用到播放，此处解析仅用于日志；解析失败时按 1.0 处理，保证不崩。
     */
    private fun parseVolume(params: Bundle?): Float {
        if (params == null) return 1f
        val raw: Any? = params.get(TextToSpeech.Engine.KEY_PARAM_VOLUME)
        return when (raw) {
            is Float -> raw.coerceIn(0f, 1f)
            is String -> raw.trim().toFloatOrNull()?.coerceIn(0f, 1f)
            else -> null
        } ?: 1f
    }

    /**
     * FloatArray（-1.0 ~ 1.0）转 16bit 小端 PCM 字节数组（仅转换 [from, to) 区间）。
     * 转换公式与应用内 AudioPlayer.saveWav 保持一致：(sample * 32767).toInt().toShort()。
     */
    private fun floatToPcm16Bytes(pcm: FloatArray, from: Int, to: Int): ByteArray {
        val bytes = ByteArray((to - from) * BYTES_PER_SAMPLE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) {
            val sample = pcm[i].coerceIn(-1f, 1f)
            buffer.putShort((sample * 32767f).toInt().toShort())
        }
        return bytes
    }
}
