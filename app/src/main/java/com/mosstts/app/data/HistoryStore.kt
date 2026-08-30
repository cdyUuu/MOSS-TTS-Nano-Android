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
                    audioPath = obj.optString("audioPath", null),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(history: SynthesisHistory) {
        val list = getAll().toMutableList()
        list.add(0, history)
        if (list.size > maxHistory) {
            list.removeAt(list.size - 1)
        }
        save(list)
    }

    fun delete(id: String) {
        val list = getAll().filter { it.id != id }
        save(list)
    }

    fun clear() {
        save(emptyList())
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
