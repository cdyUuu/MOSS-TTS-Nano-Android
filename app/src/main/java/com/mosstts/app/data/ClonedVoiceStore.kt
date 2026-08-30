package com.mosstts.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 克隆音色数据类
 */
data class ClonedVoice(
    val id: String,
    val name: String,
    val audioCodesPath: String, // 保存的 audio codes 文件路径
    val referenceAudioPath: String, // 原始参考音频路径
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 克隆音色存储管理
 */
class ClonedVoiceStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cloned_voices", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir

    fun getAllVoices(): List<ClonedVoice> {
        val json = prefs.getString("voices", "[]") ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ClonedVoice(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    audioCodesPath = obj.getString("audioCodesPath"),
                    referenceAudioPath = obj.optString("referenceAudioPath", ""),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveVoice(voice: ClonedVoice) {
        val voices = getAllVoices().toMutableList()
        val existingIndex = voices.indexOfFirst { it.id == voice.id }
        if (existingIndex >= 0) {
            voices[existingIndex] = voice
        } else {
            voices.add(voice)
        }
        saveVoices(voices)
    }

    fun deleteVoice(id: String) {
        val voices = getAllVoices().filter { it.id != id }.toMutableList()
        // 删除关联文件
        getAllVoices().find { it.id == id }?.let { voice ->
            try {
                java.io.File(voice.audioCodesPath).delete()
            } catch (_: Exception) {}
        }
        saveVoices(voices)
    }

    fun renameVoice(id: String, newName: String) {
        val voices = getAllVoices().toMutableList()
        val index = voices.indexOfFirst { it.id == id }
        if (index >= 0) {
            voices[index] = voices[index].copy(name = newName)
            saveVoices(voices)
        }
    }

    fun getVoice(id: String): ClonedVoice? = getAllVoices().find { it.id == id }

    private fun saveVoices(voices: List<ClonedVoice>) {
        val array = JSONArray()
        voices.forEach { voice ->
            val obj = JSONObject().apply {
                put("id", voice.id)
                put("name", voice.name)
                put("audioCodesPath", voice.audioCodesPath)
                put("referenceAudioPath", voice.referenceAudioPath)
                put("createdAt", voice.createdAt)
            }
            array.put(obj)
        }
        prefs.edit().putString("voices", array.toString()).apply()
    }

    fun getAudioCodesDir(): java.io.File {
        val dir = java.io.File(filesDir, "cloned_voices")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
