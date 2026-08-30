package com.mosstts.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式音频播放器。支持边生成边播放，也支持完整 PCM 播放。
 */
class StreamingAudioPlayer(
    private val sampleRate: Int = 48000,
) {
    companion object {
        private const val TAG = "StreamingAudioPlayer"
    }

    enum class PlaybackState {
        IDLE, PLAYING, PAUSED, STOPPED, COMPLETED
    }

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var audioTrack: AudioTrack? = null
    private val pcmQueue = LinkedBlockingQueue<ByteArray>()
    private val isPlaying = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private var totalSamples = 0L
    private var playedSamples = 0L

    fun prepare() {
        release()
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize * 4, sampleRate * 2) // 至少 1 秒缓冲

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        totalSamples = 0L
        playedSamples = 0L
        _progress.value = 0f
    }

    /**
     * 流式写入 PCM 数据（FloatArray，范围 -1.0 到 1.0）。
     */
    fun write(pcm: FloatArray) {
        if (audioTrack == null) prepare()
        totalSamples += pcm.size
        val pcm16 = FloatArrayToPCM16(pcm)
        pcmQueue.offer(pcm16)
    }

    /**
     * 写入完整 PCM 并开始播放。
     */
    fun playFull(pcm: FloatArray) {
        stop()
        prepare()
        totalSamples = pcm.size.toLong()
        val pcm16 = FloatArrayToPCM16(pcm)
        pcmQueue.offer(pcm16)
        // 标记结束
        pcmQueue.offer(ByteArray(0))
        start()
    }

    /**
     * 开始播放（流式模式）。
     */
    fun start() {
        if (isPlaying.get()) return
        isPlaying.set(true)
        _state.value = PlaybackState.PLAYING
        audioTrack?.play()

        playbackThread = Thread({
            try {
                while (isPlaying.get()) {
                    val chunk = pcmQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        if (chunk.isEmpty()) {
                            // 流结束标记
                            break
                        }
                        val written = audioTrack?.write(chunk, 0, chunk.size) ?: 0
                        if (written > 0) {
                            playedSamples += written / 2 // 16-bit = 2 bytes per sample
                            if (totalSamples > 0) {
                                _progress.value = playedSamples.toFloat() / totalSamples
                            }
                        }
                    }
                }
                // 等待 AudioTrack 播放完缓冲区
                audioTrack?.let {
                    while (it.playState == AudioTrack.PLAYSTATE_PLAYING &&
                        it.playbackHeadPosition < totalSamples
                    ) {
                        Thread.sleep(10)
                    }
                }
                if (isPlaying.get()) {
                    _state.value = PlaybackState.COMPLETED
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Playback thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
                _state.value = PlaybackState.STOPPED
            } finally {
                isPlaying.set(false)
            }
        }, "AudioPlayback").apply { isDaemon = true }
        playbackThread?.start()
    }

    fun pause() {
        if (!isPlaying.get()) return
        isPlaying.set(false)
        audioTrack?.pause()
        _state.value = PlaybackState.PAUSED
    }

    fun resume() {
        if (_state.value != PlaybackState.PAUSED) return
        isPlaying.set(true)
        audioTrack?.play()
        _state.value = PlaybackState.PLAYING
        // 重启播放线程
        playbackThread = Thread({
            try {
                while (isPlaying.get()) {
                    val chunk = pcmQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        if (chunk.isEmpty()) break
                        val written = audioTrack?.write(chunk, 0, chunk.size) ?: 0
                        if (written > 0) {
                            playedSamples += written / 2
                            if (totalSamples > 0) {
                                _progress.value = playedSamples.toFloat() / totalSamples
                            }
                        }
                    }
                }
                if (isPlaying.get()) _state.value = PlaybackState.COMPLETED
            } catch (e: Exception) {
                Log.e(TAG, "Resume playback error", e)
            } finally {
                isPlaying.set(false)
            }
        }, "AudioPlayback").apply { isDaemon = true }
        playbackThread?.start()
    }

    fun stop() {
        isPlaying.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        audioTrack?.stop()
        pcmQueue.clear()
        _state.value = PlaybackState.STOPPED
        _progress.value = 0f
    }

    /**
     * 标记流式输入结束。
     */
    fun endOfStream() {
        pcmQueue.offer(ByteArray(0))
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        _state.value = PlaybackState.IDLE
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    private fun FloatArrayToPCM16(input: FloatArray): ByteArray {
        val output = ByteArray(input.size * 2)
        var i = 0
        var j = 0
        while (i < input.size) {
            val sample = (input[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            output[j++] = sample.toByte()
            output[j++] = (sample.toInt() shr 8).toByte()
            i++
        }
        return output
    }
}
