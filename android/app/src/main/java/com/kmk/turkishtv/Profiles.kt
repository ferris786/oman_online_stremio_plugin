package com.kmk.turkishtv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Viewer profiles.
 *
 * The split is deliberate. Some things belong to the BOX and should not
 * change when somebody else sits down: the TorBox account, the player
 * engine, how trailers open, whether diagnostics are on. Some things are
 * personal: what you have watched and where you got to, your list, your
 * collections, the Reel rows you kept, your player skin.
 *
 * Personal data therefore lives in a prefs file per profile. The FIRST
 * profile keeps the original file names ("settings" and "watch_state"), so
 * the viewer who has been using the app all along loses nothing and no
 * migration has to run. Extra profiles get their own files and start empty.
 */
object Profiles {

    /** box-wide: the roster and who is watching. Never per-profile. */
    private const val ROSTER = "profiles"
    private const val ACTIVE = "profile_active"
    private const val DEVICE = "settings"

    /** the original profile uses the original files - id is deliberately empty */
    const val PRIMARY = ""

    /** avatar colours, in the app's own palette rather than Netflix's */
    val COLOURS = intArrayOf(
        0xFFC9A227.toInt(),   // gold
        0xFF7E9B6B.toInt(),   // olive
        0xFF9B5D4A.toInt(),   // terracotta
        0xFF5E7C9B.toInt(),   // slate blue
        0xFF8E6B9B.toInt(),   // plum
        0xFFB5844E.toInt(),   // brass
        0xFF4F8A80.toInt(),   // teal
        0xFFA8574F.toInt()    // rust
    )

    /**
     * The people who actually use this app.
     *
     * There is no sign-in and typing a name on a remote is miserable, so
     * naming a profile is a choice from this list. Order matters: a name's
     * position fixes its colour, so the same person is the same colour on
     * every TV in the household, which is what makes the "watching now" and
     * "sent by" rows readable at a glance.
     */
    val KNOWN = listOf("Wahab", "Farhana", "Baboo", "Khalid", "Zeenat", "Laila", "Aaria", "Wasim")

    fun colourFor(name: String): Int {
        val i = KNOWN.indexOfFirst { it.equals(name.trim(), ignoreCase = true) }
        if (i >= 0) return COLOURS[i % COLOURS.size]
        // someone typed their own name; keep it stable for that spelling
        return COLOURS[Math.abs(name.trim().lowercase().hashCode()) % COLOURS.size]
    }

    data class Profile(val id: String, val name: String, val colour: Int,
                       val icon: String = "") {
        val initial: String get() = name.trim().take(1).uppercase().ifEmpty { "?" }
        val isPrimary: Boolean get() = id == PRIMARY
    }

    private fun device(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DEVICE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- the roster

    fun all(ctx: Context): List<Profile> {
        val raw = device(ctx).getString(ROSTER, null)
        if (raw.isNullOrBlank()) return listOf(Profile(PRIMARY, "Wahab", COLOURS[0]))
        val out = ArrayList<Profile>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Profile(o.optString("id"), o.optString("name", "Viewer"),
                    o.optInt("colour", COLOURS[i % COLOURS.size]),
                    o.optString("icon", "")))
            }
        }
        // the primary profile cannot be deleted away - it owns the legacy data
        if (out.none { it.isPrimary }) out.add(0, Profile(PRIMARY, "Wahab", COLOURS[0]))
        return out
    }

    private fun save(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject()
            .put("id", p.id).put("name", p.name).put("colour", p.colour)
            .put("icon", p.icon))
        device(ctx).edit().putString(ROSTER, arr.toString()).apply()
    }

    fun activeId(ctx: Context): String {
        val id = device(ctx).getString(ACTIVE, PRIMARY) ?: PRIMARY
        // a profile deleted while it was active falls back to the primary one
        return if (all(ctx).any { it.id == id }) id else PRIMARY
    }

    fun active(ctx: Context): Profile {
        val id = activeId(ctx)
        return all(ctx).firstOrNull { it.id == id } ?: all(ctx).first()
    }

    fun activeName(ctx: Context): String = runCatching { active(ctx).name }.getOrDefault("")

    fun switchTo(ctx: Context, id: String) {
        device(ctx).edit().putString(ACTIVE, id).apply()
        Diag.log("profile", "switched", mapOf("to" to runCatching { active(ctx).name }.getOrDefault(id)))
    }

    fun add(ctx: Context, name: String): Profile {
        val list = all(ctx).toMutableList()
        val clean = name.trim().ifEmpty { "Viewer" }
        val p = Profile(
            id = "p" + System.currentTimeMillis().toString(36),
            name = clean,
            colour = colourFor(clean),
            icon = Avatars.defaultFor(clean)
        )
        list.add(p)
        save(ctx, list)
        return p
    }

    fun rename(ctx: Context, id: String, name: String) {
        save(ctx, all(ctx).map {
            if (it.id != id) it else {
                val clean = name.trim().ifEmpty { it.name }
                // a renamed profile that never chose an icon takes the one
                // that suits its new name; a deliberate choice is left alone
                val icon = if (it.icon.isEmpty() || it.icon == Avatars.defaultFor(it.name))
                    Avatars.defaultFor(clean) else it.icon
                it.copy(name = clean, colour = colourFor(clean), icon = icon)
            }
        })
    }

    fun setIcon(ctx: Context, id: String, icon: String) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(icon = icon) else it })
    }

    fun recolour(ctx: Context, id: String) {
        save(ctx, all(ctx).map {
            if (it.id != id) it else {
                val i = COLOURS.indexOf(it.colour)
                it.copy(colour = COLOURS[(if (i < 0) 0 else i + 1) % COLOURS.size])
            }
        })
    }

    /** The primary profile stays. Removing another one drops its data with it. */
    fun remove(ctx: Context, id: String): Boolean {
        if (id == PRIMARY) return false
        save(ctx, all(ctx).filter { it.id != id })
        if (activeId(ctx) == id) switchTo(ctx, PRIMARY)
        runCatching {
            ctx.getSharedPreferences("u_$id", Context.MODE_PRIVATE).edit().clear().apply()
            ctx.getSharedPreferences("w_$id", Context.MODE_PRIVATE).edit().clear().apply()
        }
        return true
    }

    fun multiple(ctx: Context): Boolean = all(ctx).size > 1

    /** has anyone actually said who they are on this TV yet? */
    fun named(ctx: Context): Boolean = device(ctx).getBoolean("profile_named", false)

    /** Answer the one-time question: name the profile in use and move on. */
    fun nameThisTv(ctx: Context, name: String) {
        // rename already derives the colour from the name
        rename(ctx, activeId(ctx), name)
        device(ctx).edit().putBoolean("profile_named", true).apply()
    }

    // ------------------------------------------------------- personal storage

    /** My List, collections, kept Reel rows, player skin - per profile */
    fun userPrefs(ctx: Context): SharedPreferences {
        val id = activeId(ctx)
        return ctx.getSharedPreferences(if (id == PRIMARY) "settings" else "u_$id", Context.MODE_PRIVATE)
    }

    /** watch history and resume positions - per profile */
    fun watchPrefs(ctx: Context): SharedPreferences {
        val id = activeId(ctx)
        return ctx.getSharedPreferences(if (id == PRIMARY) "watch_state" else "w_$id", Context.MODE_PRIVATE)
    }

    /** the TorBox key, player engine, trailer mode - shared by everyone */
    fun devicePrefs(ctx: Context): SharedPreferences = device(ctx)
}
