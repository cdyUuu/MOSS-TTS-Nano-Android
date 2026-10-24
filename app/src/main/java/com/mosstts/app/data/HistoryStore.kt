package com.mosstts.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 合成历史记录
 */
data class SynthesisHistory(
    val id: String,
    val text: String,
    val voice: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioPath: String? = null,
)

/**
 * 合成历史记录存储
 */
class HistoryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("synthesis_history", Context.MODE_PRIVATE)
    private val maxHistory = 50

    fun getAll(): List<SynthesisHistory> {
        val json = prefs.getString("history", "[]") ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SynthesisHistory(
                    id = obj.getString("id"),
                    text = obj.getString("text"),
                    voice = obj.optString("voice", "默认"),
                    createdAt = obj.getLong("createdAt"),
                    durationMs = obj.optLong("durationMs", 0),
                    audioPath = obj.optString("audioPath", "").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(history: SynthesisHistory) {
        val list = getAll().toMutableList()
        list.add(0, history)
        // 超出上限时淘汰最旧的记录，并删除其音频文件，避免文件无限累积
        while (list.size > maxHistory) {
            val removed = list.removeAt(list.size - 1)
            deleteAudioFile(removed)
        }
        save(list)
    }

    fun delete(id: String) {
        val list = getAll()
        list.find { it.id == id }?.let { deleteAudioFile(it) }
        save(list.filter { it.id != id })
    }

    fun clear() {
        getAll().forEach { deleteAudioFile(it) }
        save(emptyList())
    }

    private fun deleteAudioFile(history: SynthesisHistory) {
        val path = history.audioPath ?: return
        try {
            val file = java.io.File(path)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun save(list: List<SynthesisHistory>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("voice", item.voice)
                put("createdAt", item.createdAt)
                put("durationMs", item.durationMs)
                put("audioPath", item.audioPath ?: "")
            }
            array.put(obj)
        }
        prefs.edit().putString("history", array.toString()).apply()
    }
}
