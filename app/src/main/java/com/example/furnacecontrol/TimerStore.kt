package com.example.furnacecontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FurnaceTimer(
    val id: Int,
    val name: String,
    val endAt: Long = 0L,
    val completed: Boolean = false
) {
    val running: Boolean get() = endAt > 0L && !completed
}

object TimerStore {
    private const val PREFS = "furnace_state"
    private const val KEY = "timers"

    fun load(context: Context): List<FurnaceTimer> {
        val raw = context.getSharedPreferences(PREFS, 0).getString(KEY, null)
        if (raw == null) return (1..8).map { FurnaceTimer(it, "Печь $it") }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FurnaceTimer(o.getInt("id"), o.getString("name"),
                    o.optLong("endAt", 0L), o.optBoolean("completed", false))
            }
        } catch (_: Exception) {
            (1..8).map { FurnaceTimer(it, "Печь $it") }
        }
    }

    fun save(context: Context, timers: List<FurnaceTimer>) {
        val arr = JSONArray()
        timers.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("endAt", it.endAt).put("completed", it.completed))
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, arr.toString()).apply()
    }

    fun update(context: Context, id: Int, transform: (FurnaceTimer) -> FurnaceTimer) {
        val updated = load(context).map { if (it.id == id) transform(it) else it }
        save(context, updated)
    }
}
