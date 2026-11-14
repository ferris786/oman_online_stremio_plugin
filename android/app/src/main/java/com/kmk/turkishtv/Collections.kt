package com.kmk.turkishtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Collections the viewer builds by hand ("Friday nights", "For the kids").
 *
 * Stored on this TV, in the same shape the server would use if these ever
 * need to follow Wahab to a second device: a list of named collections,
 * each holding entries that carry enough to render and open them without
 * another lookup.
 *
 * Franchise collections (Harry Potter, Marvel) are NOT stored here - they
 * come from the server, which knows the real contents.
 */
object Collections {
    private const val PREFS = "settings"
    private const val KEY = "my_collections"
    private const val MAX_COLLECTIONS = 30
    private const val MAX_PER = 100

    data class Item(
        val kind: String,          // movie | series | enseries
        val id: String,
        val name: String,
        val poster: String?,
        val background: String?,
        val year: String?
    )

    data class Collection(val name: String, val items: List<Item>, val createdAt: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Collection> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Collection>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val items = ArrayList<Item>()
                val ia = o.optJSONArray("items") ?: JSONArray()
                for (j in 0 until ia.length()) {
                    val x = ia.getJSONObject(j)
                    items.add(Item(
                        x.optString("kind"), x.optString("id"), x.optString("name"),
                        x.optString("poster").takeIf { it.isNotEmpty() },
                        x.optString("background").takeIf { it.isNotEmpty() },
                        x.optString("year").takeIf { it.isNotEmpty() }))
                }
                out.add(Collection(o.optString("name"), items, o.optLong("createdAt", 0)))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    fun names(ctx: Context): List<String> = all(ctx).map { it.name }

    fun contains(ctx: Context, name: String, kind: String, id: String): Boolean =
        all(ctx).firstOrNull { it.name == name }?.items?.any { it.kind == kind && it.id == id } ?: false

    /** every collection this title already sits in */
    fun membership(ctx: Context, kind: String, id: String): Set<String> =
        all(ctx).filter { c -> c.items.any { it.kind == kind && it.id == id } }.map { it.name }.toSet()

    fun create(ctx: Context, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val list = all(ctx).toMutableList()
        if (list.any { it.name.equals(trimmed, true) }) return
        list.add(0, Collection(trimmed, emptyList(), System.currentTimeMillis()))
        write(ctx, list.take(MAX_COLLECTIONS))
    }

    /** add if absent, remove if present; returns true when it is now a member */
    fun toggle(ctx: Context, name: String, item: Item): Boolean {
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx < 0) return false
        val c = list[idx]
        val has = c.items.any { it.kind == item.kind && it.id == item.id }
        val items = if (has) c.items.filterNot { it.kind == item.kind && it.id == item.id }
                    else (listOf(item) + c.items).take(MAX_PER)
        list[idx] = c.copy(items = items)
        write(ctx, list)
        return !has
    }

    fun delete(ctx: Context, name: String) {
        write(ctx, all(ctx).filterNot { it.name == name })
    }

    private fun write(ctx: Context, list: List<Collection>) {
        val arr = JSONArray()
        for (c in list) {
            val ia = JSONArray()
            for (i in c.items) {
                ia.put(JSONObject().apply {
                    put("kind", i.kind); put("id", i.id); put("name", i.name)
                    put("poster", i.poster ?: ""); put("background", i.background ?: "")
                    put("year", i.year ?: "")
                })
            }
            arr.put(JSONObject().apply {
                put("name", c.name); put("items", ia); put("createdAt", c.createdAt)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
