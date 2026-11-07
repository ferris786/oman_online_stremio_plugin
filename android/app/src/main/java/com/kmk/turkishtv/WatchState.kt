package com.kmk.turkishtv

import android.content.Context

/**
 * Tiny persistence layer: episode playback positions + last-watched pointers.
 */
object WatchState {
    private const val PREFS = "watch_state"

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

    fun saveLast(ctx: Context, seriesId: String, seriesName: String,
                 videoId: String, videoTitle: String, season: Int, episode: Int) {
        prefs(ctx).edit()
            .putString("last_seriesId", seriesId)
            .putString("last_seriesName", seriesName)
            .putString("last_videoId", videoId)
            .putString("last_videoTitle", videoTitle)
            .putInt("last_season", season)
            .putInt("last_episode", episode)
            .apply()
    }

    data class Last(val seriesId: String, val seriesName: String, val videoId: String,
                    val videoTitle: String, val season: Int, val episode: Int)

    fun last(ctx: Context): Last? {
        val p = prefs(ctx)
        val videoId = p.getString("last_videoId", null) ?: return null
        return Last(
            p.getString("last_seriesId", "") ?: "",
            p.getString("last_seriesName", "") ?: "",
            videoId,
            p.getString("last_videoTitle", "") ?: "",
            p.getInt("last_season", 1),
            p.getInt("last_episode", 1)
        )
    }
}
