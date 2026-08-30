package com.mosstts.app.engine

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频播放器。使用 MediaPlayer 播放 WAV 文件，暂停/继续更可靠。
 */
class StreamingAudioPlayer(
    private val context: Context,
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

    private var mediaPlayer: MediaPlayer? = null
    private var tempWavFile: File? = null
    private var lastPcm: FloatArray? = null
    private var totalDurationMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    /**
     * 写入完整 PCM 并开始播放。
     */
    fun playFull(pcm: FloatArray) {
        stop()
        lastPcm = pcm
        totalDurationMs = (pcm.size * 1000L / sampleRate)

        // 保存为临时 WAV 文件
        tempWavFile = File(context.cacheDir, "tts_playback_${System.currentTimeMillis()}.wav")
        saveWav(pcm, tempWavFile!!)

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempWavFile!!.absolutePath)
                prepare()
                setOnCompletionListener {
                    _progress.value = 1f
                    _state.value = PlaybackState.COMPLETED
                    stopProgressUpdate()
                }
                start()
            }
            _state.value = PlaybackState.PLAYING
            startProgressUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            _state.value = PlaybackState.STOPPED
        }
    }

    /**
     * 重新播放最后一次的音频
     */
    fun replay() {
        lastPcm?.let { playFull(it) }
    }

    /**
     * 设置最后播放的PCM数据
     */
    fun setLastPcm(pcm: FloatArray) {
        lastPcm = pcm
    }

    fun pause() {
        if (_state.value != PlaybackState.PLAYING) return
        try {
            mediaPlayer?.pause()
            _state.value = PlaybackState.PAUSED
            stopProgressUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Pause failed", e)
        }
    }

    fun resume() {
        if (_state.value == PlaybackState.COMPLETED) {
            replay()
            return
        }
        if (_state.value != PlaybackState.PAUSED) return
        try {
            mediaPlayer?.start()
            _state.value = PlaybackState.PLAYING
            startProgressUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Resume failed", e)
        }
    }

    fun stop() {
        stopProgressUpdate()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
        }
        mediaPlayer = null
        // 删除临时文件
        tempWavFile?.let {
            try { it.delete() } catch (_: Exception) {}
        }
        tempWavFile = null
        _state.value = PlaybackState.STOPPED
        _progress.value = 0f
    }

    fun release() {
        stop()
        _state.value = PlaybackState.IDLE
    }

    fun setVolume(volume: Float) {
        try {
            mediaPlayer?.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
        } catch (e: Exception) {
            Log.e(TAG, "Set volume failed", e)
        }
    }

    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressRunnable = object : Runnable {
            override fun run() {
                try {
                    mediaPlayer?.let {
                        if (it.isPlaying && totalDurationMs > 0) {
                            _progress.value = it.currentPosition.toFloat() / totalDurationMs
                        }
                    }
                    handler.postDelayed(this, 100)
                } catch (e: Exception) {
                    Log.e(TAG, "Progress update failed", e)
                }
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdate() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun saveWav(pcm: FloatArray, file: File) {
        val dataSize = pcm.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // WAV header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2) // byte rate
        buffer.putShort(2) // block align
        buffer.putShort(16) // bits per sample
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        // PCM data
        pcm.forEach { sample ->
            val s = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            buffer.putShort(s)
        }

        FileOutputStream(file).use { it.write(buffer.array()) }
    }

    // 保留兼容接口（流式播放已禁用）
    fun prepare() {}
    fun write(pcm: FloatArray) {}
    fun start() {}
    fun endOfStream() {}
}
