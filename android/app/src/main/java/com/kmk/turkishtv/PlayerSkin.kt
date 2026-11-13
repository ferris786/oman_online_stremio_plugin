package com.kmk.turkishtv

import android.content.Context

/**
 * The player's look, chosen in Settings. Each skin is a whole activity
 * layout (overlay chrome) plus its own Media3 controller layout, so the
 * Kotlin in PlayerActivity is identical for all three - every layout uses
 * the same view ids.
 *
 * Skins apply to the PLAYER ONLY. The rest of the app keeps its
 * gold-on-charcoal identity.
 */
enum class PlayerSkin(
    val key: String,
    val label: String,
    val layout: Int,
    /** panel background for the code-built dialogs (tracks, diagnostics) */
    val panelBg: Int,
    val accentColor: Int,
    val textColor: Int,
    val dimColor: Int,
    val fontRes: Int?,
    val upper: Boolean
) {
    CLASSIC("classic", "Classic", R.layout.activity_player,
        R.drawable.sheet_bg, R.color.accent, R.color.text, R.color.text_dim, null, false),

    TERMINAL("terminal", "Terminal", R.layout.activity_player_terminal,
        R.drawable.term_panel, R.color.term_cyan, R.color.term_white, R.color.term_grey,
        R.font.vt323, true),

    OTTOMAN("ottoman", "Ottoman", R.layout.activity_player_ottoman,
        R.drawable.ott_panel, R.color.accent, R.color.title_cream, R.color.text_dim,
        R.font.cinzel_medium, true);

    companion object {
        private const val PREFS = "settings"
        private const val PREF_KEY = "player_skin"

        fun load(ctx: Context): PlayerSkin {
            val k = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY, CLASSIC.key)
            return entries.firstOrNull { it.key == k } ?: CLASSIC
        }

        fun save(ctx: Context, skin: PlayerSkin) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, skin.key).apply()
        }
    }
}
