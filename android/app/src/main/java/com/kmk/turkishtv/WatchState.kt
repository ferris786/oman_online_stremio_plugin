package com.kmk.turkishtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny persistence layer: episode positions, watch history, last watched.
 *
 * v5 adds optional artwork to history entries (episode still, series
 * backdrop) for the Continue Watching stills and the home billboard, and
 * records movies too (videoId "movie:tt…"). Older entries load with those
 * fields empty.
 */
object WatchState {
    private const val PREFS = "watch_state"
    private const val HISTORY_MAX = 12

    data class HistoryEntry(
        val seriesId: String, val seriesName: String, val poster: String?,
        val videoId: String, val title: String, val season: Int, val episode: Int,
        var positionMs: Long, var durationMs: Long, val ts: Long,
        val thumb: String? = null, val backdrop: String? = null
    ) {
        val isMovie: Boolean get() = videoId.startsWith("movie:")
        val isEnSeries: Boolean get() = videoId.startsWith("enseries:")
        val progressPct: Int get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
        /** treated as finished once ≥ 93% was watched */
        val isFinished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 93 / 100
        val minutesLeft: Long get() = if (durationMs > positionMs) (durationMs - positionMs) / 60000 else 0
    }

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
                    o.optLong("ts", 0),
                    if (o.isNull("thumb")) null else o.optString("thumb").takeIf { it.isNotEmpty() },
                    if (o.isNull("backdrop")) null else o.optString("backdrop").takeIf { it.isNotEmpty() }
                ))
            }
            out.sortedByDescending { it.ts }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** most recent entry for a series (or null) */
    fun lastForSeries(ctx: Context, seriesId: String): HistoryEntry? =
        history(ctx).firstOrNull { it.seriesId == seriesId }

    private fun toJson(it: HistoryEntry, ts: Long = it.ts) = JSONObject().apply {
        put("seriesId", it.seriesId)
        put("seriesName", it.seriesName)
        put("poster", it.poster ?: JSONObject.NULL)
        put("videoId", it.videoId)
        put("title", it.title)
        put("season", it.season)
        put("episode", it.episode)
        put("positionMs", it.positionMs)
        put("durationMs", it.durationMs)
        put("ts", ts)
        put("thumb", it.thumb ?: JSONObject.NULL)
        put("backdrop", it.backdrop ?: JSONObject.NULL)
    }

    private fun write(ctx: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.take(HISTORY_MAX).forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString("history", arr.toString()).apply()
    }

    fun upsertHistory(ctx: Context, entry: HistoryEntry) {
        val previous = history(ctx).firstOrNull { it.videoId == entry.videoId }
        val merged = if (previous != null && entry.durationMs == 0L)
            entry.copy(durationMs = previous.durationMs,
                thumb = entry.thumb ?: previous.thumb, backdrop = entry.backdrop ?: previous.backdrop)
        else entry
        val list = history(ctx).filter { it.videoId != entry.videoId }.toMutableList()
        list.add(0, merged)
        write(ctx, list)
    }

    fun removeHistory(ctx: Context, videoId: String) {
        write(ctx, history(ctx).filter { it.videoId != videoId })
    }

    fun updateHistoryPosition(ctx: Context, videoId: String, positionMs: Long, durationMs: Long) {
        val list = history(ctx).toMutableList()
        val idx = list.indexOfFirst { it.videoId == videoId }
        if (idx >= 0) {
            list[idx].positionMs = positionMs
            list[idx].durationMs = if (durationMs > 0) durationMs else list[idx].durationMs
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it, if (it.videoId == videoId) System.currentTimeMillis() else it.ts)) }
            prefs(ctx).edit().putString("history", arr.toString()).apply()
        }
    }
}
