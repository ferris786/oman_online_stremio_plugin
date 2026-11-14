package com.kmk.turkishtv.mux

import android.content.Context

/**
 * Stream mux engine models (AIOStreams-inspired). Single source of truth for
 * source parsing, filtering, sorting and formatting - replaces the three
 * divergent ranking implementations that used to live in MovieDetailActivity,
 * Api.kt and the server list consumer.
 */

enum class QualityProfile { BALANCED, CINEMA, DATA_SAVER }

data class StreamAttrs(
    val resolution: Int,        // 2160, 1080, 720, 480, 0=unknown/SD
    val quality: String,        // Remux | BluRay | WEB-DL | DVDRip | WEB
    val encode: String,         // x264 | x265 | AV1 | "" (unknown)
    val hdr: Boolean,
    val dv: Boolean
) {
    val resolutionLabel: String get() = if (resolution > 0) "${resolution}p" else "SD"
}

data class MuxStream(
    val name: String,           // release name
    val hash: String,           // infohash (lowercase)
    val sizeBytes: Long,
    val seeders: Int,
    val provider: String,       // YTS | TPB | ADDON
    val attrs: StreamAttrs,
    var cached: Boolean = false,
    val fileIdx: Int = -1       // episode's file inside a season pack (Torrentio), else -1
) {
    /** a whole-season or complete-series upload rather than one episode */
    val isPack: Boolean get() = PACK_RE.containsMatchIn(name)

    companion object {
        private val PACK_RE = Regex("""\bS\d{1,2}(?!E\d)|\bSeason\s*\d|Complete\.?Series|\bS\d{2}-S\d{2}""", RegexOption.IGNORE_CASE)
    }
    /** AIOStreams-style uniform chip title */
    fun formattedTitle(): String {
        val gb = sizeBytes / 1e9
        val size = when {
            sizeBytes <= 0 -> "—"
            gb >= 1 -> String.format("%.1fGB", gb)
            else -> "${sizeBytes / 1048576}MB"
        }
        val bits = listOfNotNull(
            attrs.resolutionLabel,
            attrs.quality.takeIf { it.isNotBlank() },
            attrs.encode.takeIf { it.isNotBlank() },
            "HDR".takeIf { attrs.hdr },
            "DV".takeIf { attrs.dv }
        ).joinToString(" · ")
        val flag = if (cached) "⚡" else "⏳"
        return "$bits · $size · ${seeders}↑ · $provider $flag"
    }
}

data class MuxConfig(
    val profile: QualityProfile = QualityProfile.BALANCED,
    val showUncached: Boolean = true,
    val hide4k: Boolean = false,
    val prewarm: Boolean = true,
    val minSeeders: Int = 3,
    val maxResults: Int = 30
) {
    /** Resolution preference order for this profile (lexicographic sort key) */
    fun resolutionOrder(): List<Int> = when (profile) {
        // user preference: all the 4K up front, then lesser quality
        QualityProfile.BALANCED -> listOf(2160, 1080, 720, 480, 0)
        QualityProfile.CINEMA -> listOf(2160, 1080, 720, 480, 0)
        QualityProfile.DATA_SAVER -> listOf(720, 1080, 480, 0, 2160)
    }

    /** Size caps in bytes per resolution (0 = no cap) */
    fun sizeCap(resolution: Int): Long = when (profile) {
        QualityProfile.DATA_SAVER -> when (resolution) {
            2160 -> 0L            // hidden by preference anyway
            1080 -> 8_000_000_000L
            else -> 4_000_000_000L
        }
        else -> when (resolution) {
            2160 -> 60_000_000_000L   // user prefers chunky 10-30GB encodes
            1080 -> 30_000_000_000L
            else -> 0L
        }
    }

    companion object {
        private const val PREFS = "settings"

        fun load(ctx: Context): MuxConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return MuxConfig(
                profile = try {
                    QualityProfile.valueOf(p.getString("mux_profile", "BALANCED") ?: "BALANCED")
                } catch (e: Exception) { QualityProfile.BALANCED },
                showUncached = p.getBoolean("mux_show_uncached", true),
                hide4k = p.getBoolean("mux_hide_4k", false),
                prewarm = p.getBoolean("mux_prewarm", true)
            )
        }

        fun save(ctx: Context, cfg: MuxConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mux_profile", cfg.profile.name)
                .putBoolean("mux_show_uncached", cfg.showUncached)
                .putBoolean("mux_hide_4k", cfg.hide4k)
                .putBoolean("mux_prewarm", cfg.prewarm)
                .apply()
        }
    }
}
