package com.kmk.turkishtv

import android.content.Context
import org.json.JSONArray

/**
 * Lists the viewer chose to keep after spinning The Reel. Kept lists become
 * permanent rows on the home screen, which is the point of the feature: the
 * home screen slowly becomes his, not ours.
 */
object KeptLists {
    private const val PREFS = "settings"
    private const val KEY = "kept_lists"
    private const val MAX = 12

    /** id to title, newest first */
    fun all(ctx: Context): List<Pair<String, String>> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Pair<String, String>>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.getString("id") to o.optString("title", o.getString("id")))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    fun has(ctx: Context, id: String) = all(ctx).any { it.first == id }

    fun add(ctx: Context, id: String, title: String) {
        val list = all(ctx).filter { it.first != id }.toMutableList()
        list.add(0, id to title)
        write(ctx, list.take(MAX))
    }

    fun remove(ctx: Context, id: String) {
        write(ctx, all(ctx).filter { it.first != id })
    }

    private fun write(ctx: Context, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { (id, title) ->
            arr.put(org.json.JSONObject().put("id", id).put("title", title))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    // ---- one-time feature introduction ----

    fun wizardSeen(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("reel_wizard_seen", false)

    fun markWizardSeen(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("reel_wizard_seen", true).apply()
    }
}
