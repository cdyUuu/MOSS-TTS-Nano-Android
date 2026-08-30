package com.mosstts.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "mosstts_settings")

data class AppSettings(
    val selectedVoice: String = "",
    val cpuThreads: Int = 4,
    val maxFrames: Int = 375,
    val playbackSpeed: Float = 1.0f,
    val streamingPlayback: Boolean = false,
    val darkMode: String = "system", // system, light, dark
    val hideNavigationBar: Boolean = false,
    val referenceAudioPath: String = "",
    val referenceAudioName: String = "",
)

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_SELECTED_VOICE = stringPreferencesKey("selected_voice")
        private val KEY_CPU_THREADS = intPreferencesKey("cpu_threads")
        private val KEY_MAX_FRAMES = intPreferencesKey("max_frames")
        private val KEY_PLAYBACK_SPEED = stringPreferencesKey("playback_speed")
        private val KEY_USE_STREAMING = booleanPreferencesKey("use_streaming")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode_string")
        private val KEY_HIDE_NAV_BAR = booleanPreferencesKey("hide_navigation_bar")
        private val KEY_REFERENCE_AUDIO_PATH = stringPreferencesKey("reference_audio_path")
        private val KEY_REFERENCE_AUDIO_NAME = stringPreferencesKey("reference_audio_name")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            selectedVoice = prefs[KEY_SELECTED_VOICE] ?: "",
            cpuThreads = prefs[KEY_CPU_THREADS] ?: 4,
            maxFrames = prefs[KEY_MAX_FRAMES] ?: 375,
            playbackSpeed = (prefs[KEY_PLAYBACK_SPEED] ?: "1.0").toFloatOrNull() ?: 1.0f,
            streamingPlayback = prefs[KEY_USE_STREAMING] ?: false,
            darkMode = prefs[KEY_DARK_MODE] ?: "system",
            hideNavigationBar = prefs[KEY_HIDE_NAV_BAR] ?: false,
            referenceAudioPath = prefs[KEY_REFERENCE_AUDIO_PATH] ?: "",
            referenceAudioName = prefs[KEY_REFERENCE_AUDIO_NAME] ?: "",
        )
    }

    val selectedVoice: Flow<String> = context.dataStore.data.map { it[KEY_SELECTED_VOICE] ?: "" }
    val cpuThreads: Flow<Int> = context.dataStore.data.map { it[KEY_CPU_THREADS] ?: 4 }
    val maxFrames: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_FRAMES] ?: 375 }
    val playbackSpeed: Flow<String> = context.dataStore.data.map { it[KEY_PLAYBACK_SPEED] ?: "1.0" }
    val useStreaming: Flow<Boolean> = context.dataStore.data.map { it[KEY_USE_STREAMING] ?: false }
    val darkMode: Flow<String> = context.dataStore.data.map { it[KEY_DARK_MODE] ?: "system" }
    val hideNavigationBar: Flow<Boolean> = context.dataStore.data.map { it[KEY_HIDE_NAV_BAR] ?: false }
    val referenceAudioPath: Flow<String> = context.dataStore.data.map { it[KEY_REFERENCE_AUDIO_PATH] ?: "" }
    val referenceAudioName: Flow<String> = context.dataStore.data.map { it[KEY_REFERENCE_AUDIO_NAME] ?: "" }

    suspend fun setSelectedVoice(voice: String) {
        context.dataStore.edit { it[KEY_SELECTED_VOICE] = voice }
    }

    suspend fun setCpuThreads(threads: Int) {
        context.dataStore.edit { it[KEY_CPU_THREADS] = threads }
    }

    suspend fun setMaxFrames(frames: Int) {
        context.dataStore.edit { it[KEY_MAX_FRAMES] = frames }
    }

    suspend fun setPlaybackSpeed(speed: String) {
        context.dataStore.edit { it[KEY_PLAYBACK_SPEED] = speed }
    }

    suspend fun setUseStreaming(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_STREAMING] = enabled }
    }

    suspend fun setDarkMode(mode: String) {
        context.dataStore.edit { it[KEY_DARK_MODE] = mode }
    }

    suspend fun setHideNavigationBar(hide: Boolean) {
        context.dataStore.edit { it[KEY_HIDE_NAV_BAR] = hide }
    }

    suspend fun setReferenceAudio(path: String, name: String) {
        context.dataStore.edit {
            it[KEY_REFERENCE_AUDIO_PATH] = path
            it[KEY_REFERENCE_AUDIO_NAME] = name
        }
    }

    suspend fun clearReferenceAudio() {
        context.dataStore.edit {
            it.remove(KEY_REFERENCE_AUDIO_PATH)
            it.remove(KEY_REFERENCE_AUDIO_NAME)
        }
    }
}
