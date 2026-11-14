package com.kmk.turkishtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * My List: titles the viewer saved for later. Stored per TV. Holds Turkish
 * series ("series"), English movies ("movie") and English series
 * ("enseries") side by side, and the home screen shows them as one row.
 */
object MyList {
    private const val PREFS = "settings"
    private const val KEY = "my_list"
    private const val MAX = 60

    data class Entry(
        val kind: String, val id: String, val name: String,
        val poster: String?, val background: String?, val year: String?,
        val description: String? = null, val addedAt: Long = System.currentTimeMillis()
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(
                    o.optString("kind"), o.optString("id"), o.optString("name"),
                    o.optString("poster").takeIf { it.isNotEmpty() },
                    o.optString("background").takeIf { it.isNotEmpty() },
                    o.optString("year").takeIf { it.isNotEmpty() },
                    o.optString("description").takeIf { it.isNotEmpty() },
                    o.optLong("addedAt", 0)
                ))
            }
            out.sortedByDescending { it.addedAt }
        } catch (e: Exception) { emptyList() }
    }

    fun has(ctx: Context, kind: String, id: String) = all(ctx).any { it.kind == kind && it.id == id }

    /** add if absent, remove if present; returns true when it is now in the list */
    fun toggle(ctx: Context, e: Entry): Boolean {
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.kind == e.kind && it.id == e.id }
        val nowIn = if (idx >= 0) { list.removeAt(idx); false } else { list.add(0, e); true }
        write(ctx, list.take(MAX))
        return nowIn
    }

    private fun write(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("kind", e.kind); put("id", e.id); put("name", e.name)
                put("poster", e.poster ?: ""); put("background", e.background ?: "")
                put("year", e.year ?: ""); put("description", e.description ?: "")
                put("addedAt", e.addedAt)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun label(inList: Boolean) = if (inList) "✓  In My List" else "＋  My List"
}
