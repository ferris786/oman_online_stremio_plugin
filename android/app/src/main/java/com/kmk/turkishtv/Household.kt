package com.kmk.turkishtv

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A household: this install and the other person's, linked by a short code.
 *
 * One link carries three things:
 *   - an INBOX, so a title sent to the other person waits for them. This is
 *     the half that gets used, because being on the app at the same moment
 *     is rare.
 *   - PRESENCE, so you can see what they are watching right now.
 *   - a ROOM, so you can watch the same thing in step.
 *
 * The device identity is the id the app already generates for diagnostics,
 * so linking adds no new notion of who this box is. The household id and
 * member names are device-wide rather than per profile: the TV belongs to
 * the house, whoever happens to be sitting in front of it.
 *
 * Every call here blocks on the network and must be made off the main thread.
 */
object Household {

    private const val KEY_HOUSE = "house_id"
    private const val KEY_OTHERS = "house_others"

    private val http = Http.withTimeouts(8, 10, 10)
    private val JSON = "application/json".toMediaType()

    data class Sent(
        val sentId: String, val from: String, val note: String,
        val kind: String, val id: String, val name: String,
        val poster: String?, val background: String?, val year: String?
    )

    data class Watcher(
        val deviceId: String, val name: String, val kind: String,
        val videoId: String, val seriesId: String?, val title: String,
        val poster: String?, val background: String?,
        val posS: Int, val durS: Int, val playing: Boolean
    )

    data class Room(
        val host: String, val hostName: String, val kind: String,
        val videoId: String, val seriesId: String?, val title: String,
        val poster: String?, val background: String?,
        val sourceName: String, val hash: String, val fileIdx: Int,
        val epTag: String, val dn: String,
        val positionMs: Long, val paused: Boolean,
        val waiting: Boolean, val stalledBy: List<String>, val seq: Int
    )

    // ------------------------------------------------------------ identity

    private fun prefs(ctx: Context) = Profiles.devicePrefs(ctx)

    /** the same id the diagnostics use, so a box has exactly one identity */
    fun deviceId(ctx: Context): String =
        prefs(ctx).getString("diag_device", null) ?: "unpaired"

    fun houseId(ctx: Context): String? =
        prefs(ctx).getString(KEY_HOUSE, null)?.takeIf { it.isNotBlank() }

    fun linked(ctx: Context): Boolean = houseId(ctx) != null

    /** cached, so the home screen can label a row without a round trip */
    fun others(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_OTHERS, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    fun otherName(ctx: Context): String = others(ctx).firstOrNull() ?: "them"

    private fun remember(ctx: Context, house: String?, others: List<String>) {
        val a = JSONArray()
        for (o in others) a.put(o)
        prefs(ctx).edit()
            .putString(KEY_HOUSE, house ?: "")
            .putString(KEY_OTHERS, a.toString())
            .apply()
    }

    /** how this box is labelled in the other person's app */
    private fun myName(ctx: Context): String = Profiles.activeName(ctx).ifBlank { "Viewer" }

    // ------------------------------------------------------------ transport

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(Config.BASE_URL + path)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun get(path: String): JSONObject {
        val req = Request.Builder().url(Config.BASE_URL + path).build()
        http.newCall(req).execute().use { resp ->
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun JSONObject.err(): String? = optString("error").takeIf { it.isNotEmpty() }

    private fun JSONObject.strOrNull(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

    private fun JSONObject.names(k: String): List<String> {
        val a = optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------ the link

    /** Show a code on THIS TV for the other one to type. */
    fun start(ctx: Context): Pair<String?, String?> {
        val j = try {
            post("/house/start", JSONObject()
                .put("deviceId", deviceId(ctx))
                .put("name", myName(ctx)))
        } catch (e: Exception) {
            return Pair(null, "Could not reach the server")
        }
        j.err()?.let { return Pair(null, it) }
        remember(ctx, j.optString("houseId"), others(ctx))
        return Pair(j.optString("code"), null)
    }

    /** Type the code shown on the other TV. Returns an error, or null on success. */
    fun join(ctx: Context, code: String): String? {
        val j = try {
            post("/house/join", JSONObject()
                .put("code", code.trim().uppercase())
                .put("deviceId", deviceId(ctx))
                .put("name", myName(ctx)))
        } catch (e: Exception) {
            return "Could not reach the server"
        }
        j.err()?.let { return it }
        val mine = myName(ctx)
        remember(ctx, j.optString("houseId"), j.names("members").filter { it != mine })
        return null
    }

    /** Refresh the member list, and drop a link the server no longer knows. */
    fun refresh(ctx: Context): List<String> {
        val h = houseId(ctx) ?: return emptyList()
        val j = try { get("/house/$h?deviceId=" + deviceId(ctx)) } catch (e: Exception) { return others(ctx) }
        if (j.err() != null) {
            remember(ctx, null, emptyList())
            return emptyList()
        }
        val names = j.names("others")
        remember(ctx, h, names)
        return names
    }

    fun leave(ctx: Context) {
        val h = houseId(ctx) ?: return
        runCatching { post("/house/$h/leave", JSONObject().put("deviceId", deviceId(ctx))) }
        remember(ctx, null, emptyList())
    }

    // ------------------------------------------------------------ the inbox

    /** Send a title to everyone else in the house. Returns an error, or null. */
    fun send(ctx: Context, kind: String, id: String, name: String,
             poster: String?, background: String?, year: String?, note: String = ""): String? {
        val h = houseId(ctx) ?: return "This TV is not linked to another one yet"
        val j = try {
            post("/house/$h/send", JSONObject()
                .put("deviceId", deviceId(ctx))
                .put("item", JSONObject()
                    .put("kind", kind).put("id", id).put("name", name)
                    .put("poster", poster ?: "").put("background", background ?: "")
                    .put("year", year ?: "").put("note", note)))
        } catch (e: Exception) {
            return "Could not reach the server"
        }
        j.err()?.let { return it }
        // remember who is actually in the house while we have the answer
        val to = j.names("to")
        if (to.isNotEmpty()) remember(ctx, h, to)
        if (j.optInt("delivered", 0) <= 0) return "Nobody else has joined this household yet"
        return null
    }

    fun inbox(ctx: Context): List<Sent> {
        val h = houseId(ctx) ?: return emptyList()
        val j = try { get("/house/$h/inbox?deviceId=" + deviceId(ctx)) } catch (e: Exception) { return emptyList() }
        val arr: JSONArray = j.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Sent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Sent(
                o.optString("sentId"), o.optString("from"), o.optString("note"),
                o.optString("kind"), o.optString("id"), o.optString("name"),
                o.strOrNull("poster"), o.strOrNull("background"), o.strOrNull("year")))
        }
        return out
    }

    fun dismiss(ctx: Context, sentId: String) {
        val h = houseId(ctx) ?: return
        runCatching {
            post("/house/$h/inbox/dismiss", JSONObject()
                .put("deviceId", deviceId(ctx)).put("sentId", sentId))
        }
    }

    // ------------------------------------------------------------ presence

    /** What this box is playing. Call it while watching; it overwrites. */
    fun beat(ctx: Context, kind: String, videoId: String, seriesId: String?, title: String,
             poster: String?, background: String?, posS: Int, durS: Int, playing: Boolean) {
        val h = houseId(ctx) ?: return
        runCatching {
            post("/house/$h/presence", JSONObject()
                .put("deviceId", deviceId(ctx))
                .put("kind", kind).put("videoId", videoId)
                .put("seriesId", seriesId ?: "").put("title", title)
                .put("poster", poster ?: "").put("background", background ?: "")
                .put("posS", posS).put("durS", durS).put("playing", playing))
        }
    }

    /** Tell the house this box has stopped watching. */
    fun beatStopped(ctx: Context) {
        val h = houseId(ctx) ?: return
        runCatching {
            post("/house/$h/presence", JSONObject()
                .put("deviceId", deviceId(ctx)).put("watching", false))
        }
    }

    fun watching(ctx: Context): List<Watcher> {
        val h = houseId(ctx) ?: return emptyList()
        val j = try { get("/house/$h/watching?deviceId=" + deviceId(ctx)) } catch (e: Exception) { return emptyList() }
        val arr = j.optJSONArray("watching") ?: return emptyList()
        val out = ArrayList<Watcher>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Watcher(
                o.optString("deviceId"), o.optString("name"), o.optString("kind"),
                o.optString("videoId"), o.strOrNull("seriesId"), o.optString("title"),
                o.strOrNull("poster"), o.strOrNull("background"),
                o.optInt("posS"), o.optInt("durS"), o.optBoolean("playing")))
        }
        return out
    }

    // -------------------------------------------------------- profile pictures

    /**
     * Pictures dropped into data/avatars on the server. Shared by every TV,
     * so a picture added once is offered to everyone.
     */
    fun avatarPictures(ctx: Context): List<Pair<String, String>> {
        val j = try { get("/avatars") } catch (e: Exception) { return emptyList() }
        val arr = j.optJSONArray("avatars") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isNotBlank()) out.add(Pair(o.optString("name"), url))
        }
        return out
    }

    /**
     * Start a phone upload. The TV shows the QR and the code; the phone
     * squares the photo off and sends it. Returns the code, or null.
     */
    fun avatarUploadStart(): String? {
        val j = try { post("/avatar/upload/start", JSONObject()) } catch (e: Exception) { return null }
        return j.optString("code").takeIf { it.isNotBlank() }
    }

    /** "waiting" until the phone sends something, then the picture's URL. */
    fun avatarUploadCheck(code: String): Pair<String, String?> {
        val j = try { get("/avatar/upload/$code/check") } catch (e: Exception) {
            return Pair("waiting", null)
        }
        return Pair(j.optString("status", "waiting"), j.strOrNull("url"))
    }

    fun avatarQrUrl(code: String): String = Config.BASE_URL + "/avatar/qr/" + code

    // ------------------------------------------------------------ the room

    private fun parseRoom(j: JSONObject): Room? {
        val r = j.optJSONObject("room") ?: return null
        val src = r.optJSONObject("source") ?: JSONObject()
        return Room(
            r.optString("host"), r.optString("hostName"), r.optString("kind"),
            r.optString("videoId"), r.strOrNull("seriesId"), r.optString("title"),
            r.strOrNull("poster"), r.strOrNull("background"),
            src.optString("name"), src.optString("hash"), src.optInt("fileIdx", -1),
            src.optString("epTag"), src.optString("dn"),
            r.optLong("positionMs"), r.optBoolean("paused"),
            r.optBoolean("waiting"), r.names("stalledBy"), r.optInt("seq"))
    }

    /**
     * Open a room on what this box is about to play. The source is pinned by
     * hash and file index rather than by URL, because the other TV has to
     * resolve its own playable link through its own TorBox account.
     */
    fun roomOpen(ctx: Context, kind: String, videoId: String, seriesId: String?, title: String,
                 poster: String?, background: String?, positionMs: Long,
                 sourceName: String, hash: String, fileIdx: Int, epTag: String, dn: String): Room? {
        val h = houseId(ctx) ?: return null
        val j = try {
            post("/house/$h/room/open", JSONObject()
                .put("deviceId", deviceId(ctx))
                .put("kind", kind).put("videoId", videoId)
                .put("seriesId", seriesId ?: "").put("title", title)
                .put("poster", poster ?: "").put("background", background ?: "")
                .put("positionMs", positionMs).put("paused", false)
                .put("source", JSONObject()
                    .put("name", sourceName).put("hash", hash)
                    .put("fileIdx", fileIdx).put("epTag", epTag).put("dn", dn)))
        } catch (e: Exception) { return null }
        return parseRoom(j)
    }

    /**
     * One call both ways: publish this box's state, read the room's back.
     *
     * `control` marks a deliberate act, meaning a pause, a resume or a seek.
     * Only a control action or the host moves the room's clock; without that
     * rule two boxes fight over a position that is always slightly behind.
     */
    fun roomSync(ctx: Context, positionMs: Long, paused: Boolean,
                 stalled: Boolean, control: Boolean): Room? {
        val h = houseId(ctx) ?: return null
        val body = JSONObject()
            .put("deviceId", deviceId(ctx))
            .put("stalled", stalled)
            .put("positionMs", positionMs)
        if (control) body.put("control", true).put("paused", paused)
        val j = try { post("/house/$h/room/sync", body) } catch (e: Exception) { return null }
        return parseRoom(j)
    }

    fun roomGet(ctx: Context): Room? {
        val h = houseId(ctx) ?: return null
        return try { parseRoom(get("/house/$h/room")) } catch (e: Exception) { null }
    }

    fun roomClose(ctx: Context) {
        val h = houseId(ctx) ?: return
        runCatching { post("/house/$h/room/close", JSONObject().put("deviceId", deviceId(ctx))) }
    }
}
