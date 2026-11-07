package com.kmk.turkishtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny persistence layer: episode positions, watch history, last watched.
 */
object WatchState {
    private const val PREFS = "watch_state"
    private const val HISTORY_MAX = 10

    data class HistoryEntry(
        val seriesId: String, val seriesName: String, val poster: String?,
        val videoId: String, val title: String, val season: Int, val episode: Int,
        var positionMs: Long, var durationMs: Long, val ts: Long
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savePosition(ctx: Context, videoId: String, positionMs: Long) {
        prefs(ctx).edit().putLong("pos_$videoId", positionMs).apply()
    }

    fun getPosition(ctx: Context, videoId: String): Long {
        return prefs(ctx).getLong("pos_$videoId", 0L)
    }

    fun clearPosition(ctx: Context, videoId: String) {
        prefs(ctx).edit().remove("pos_$videoId").apply()
    }

    // ---- watch history (Continue Watching row) ----

    fun history(ctx: Context): List<HistoryEntry> {
        val raw = prefs(ctx).getString("history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<HistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(HistoryEntry(
                    o.getString("seriesId"), o.getString("seriesName"),
                    if (o.isNull("poster")) null else o.optString("poster"),
                    o.getString("videoId"), o.optString("title", ""),
                    o.optInt("season", 1), o.optInt("episode", 1),
                    o.optLong("positionMs", 0), o.optLong("durationMs", 0),
                    o.optLong("ts", 0)
                ))
            }
            out.sortedByDescending { it.ts }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun upsertHistory(ctx: Context, entry: HistoryEntry) {
        val list = history(ctx).filter { it.videoId != entry.videoId }.toMutableList()
        list.add(0, entry)
        val arr = JSONArray()
        list.take(HISTORY_MAX).forEach {
            arr.put(JSONObject().apply {
                put("seriesId", it.seriesId)
                put("seriesName", it.seriesName)
                put("poster", it.poster ?: JSONObject.NULL)
                put("videoId", it.videoId)
                put("title", it.title)
                put("season", it.season)
                put("episode", it.episode)
                put("positionMs", it.positionMs)
                put("durationMs", it.durationMs)
                put("ts", it.ts)
            })
        }
        prefs(ctx).edit().putString("history", arr.toString()).apply()
    }

    fun updateHistoryPosition(ctx: Context, videoId: String, positionMs: Long, durationMs: Long) {
        val list = history(ctx).toMutableList()
        val idx = list.indexOfFirst { it.videoId == videoId }
        if (idx >= 0) {
            list[idx].positionMs = positionMs
            list[idx].durationMs = if (durationMs > 0) durationMs else list[idx].durationMs
            val updated = list[idx]
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("seriesId", it.seriesId)
                    put("seriesName", it.seriesName)
                    put("poster", it.poster ?: JSONObject.NULL)
                    put("videoId", it.videoId)
                    put("title", it.title)
                    put("season", it.season)
                    put("episode", it.episode)
                    put("positionMs", it.positionMs)
                    put("durationMs", it.durationMs)
                    put("ts", if (it.videoId == videoId) System.currentTimeMillis() else it.ts)
                })
            }
            prefs(ctx).edit().putString("history", arr.toString()).apply()
        }
    }
}
