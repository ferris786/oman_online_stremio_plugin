package com.kmk.turkishtv

import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TorBox client - runs IN THE APP so each device uses its own API key
 * (Settings). Instant-only: we only offer torrents already cached on
 * TorBox, so playback starts immediately.
 */
object Torbox {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://api.torbox.app/v1/api"

    fun apiKey(ctx: android.content.Context): String? {
        val k = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("torbox_key", null)
        return if (k.isNullOrBlank()) null else k
    }

    fun saveApiKey(ctx: android.content.Context, key: String) {
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("torbox_key", key.trim()).apply()
    }

    private fun call(url: String, key: String, post: FormBody? = null): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .apply { if (post != null) post(post) else get() }
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            return JSONObject(body)
        }
    }

    /** Lightweight key validation. */
    fun testKey(key: String): String {
        return try {
            val j = call("$BASE/torrents/mylist?limit=1", key)
            if (j.optBoolean("success")) "ok" else (j.optString("error", "invalid key"))
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    /**
     * Which of the given info-hashes are already cached (instant) on TorBox?
     * Returns the set of cached hashes.
     *
     * The API takes hash as an ARRAY query param (FastAPI style): repeated
     * ?hash= keys, NOT comma-joined. Response format=list gives a plain
     * array of hashes; format=object gives objects - we parse both.
     */
    fun cachedHashes(key: String, hashes: List<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        return try {
            val sb = StringBuilder("$BASE/torrents/checkcached?format=list")
            for (h in hashes) sb.append("&hash=").append(h.lowercase())
            val j = call(sb.toString(), key)
            if (!j.optBoolean("success")) return emptySet()
            val out = HashSet<String>()
            val data = j.opt("data") ?: return emptySet()
            when (data) {
                is org.json.JSONArray -> for (i in 0 until data.length()) {
                    val el = data.opt(i) ?: continue
                    when (el) {
                        is String -> if (el.isNotBlank()) out.add(el.lowercase())
                        is org.json.JSONObject -> {
                            val h = el.optString("hash", "")
                            if (h.isNotBlank()) out.add(h.lowercase())
                        }
                    }
                }
                is org.json.JSONObject -> for (k in el_keys(data)) out.add(k.lowercase())
            }
            out
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun el_keys(o: org.json.JSONObject): List<String> {
        val out = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    /**
     * Resolve one cached torrent to a direct streaming URL:
     * create (idempotent) -> find in mylist by hash -> pick the largest
     * video file -> request a direct link.
     */
    fun resolveStream(key: String, hash: String, name: String): String? {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=${java.net.URLEncoder.encode(name, "UTF-8")}" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"

        // 1. create (or re-use) the torrent in the account; the response may
        // carry the torrent_id directly, which saves a mylist scan
        var knownTorrentId = -1L
        try {
            val create = call("$BASE/torrents/createtorrent", key,
                FormBody.Builder().add("magnet", magnet).add("seed", "2").build())
            val d = create.optJSONObject("data")
            if (d != null) {
                knownTorrentId = d.optLong("torrent_id", -1L)
            }
        } catch (e: Exception) { /* may already exist */ }

        // 2. find it in the list (a couple of retries for propagation)
        repeat(4) { attempt ->
            try {
                val list = call("$BASE/torrents/mylist?limit=1000", key)
                val data = list.optJSONArray("data") ?: return null
                for (i in 0 until data.length()) {
                    val t = data.optJSONObject(i) ?: continue
                    if (!t.optString("hash", "").lowercase().contentEquals(hash.lowercase())) continue

                    val files = t.optJSONArray("files") ?: continue
                    var bestId = -1L
                    var bestSize = -1L
                    for (f in 0 until files.length()) {
                        val file = files.optJSONObject(f) ?: continue
                        val fname = file.optString("name", "").lowercase()
                        if (!(fname.endsWith(".mp4") || fname.endsWith(".mkv"))) continue
                        val size = file.optLong("size", 0)
                        if (size > bestSize) {
                            bestSize = size
                            bestId = file.optLong("id")
                        }
                    }
                    if (bestId > 0) {
                        // 3. direct link
                        val dl = call("$BASE/torrents/requestdl?torrent_id=${t.optLong("id", knownTorrentId)}&file_id=$bestId", key)
                        val link = dl.optString("data")
                        if (link.startsWith("http")) return link
                        return null
                    }
                    return null
                }
            } catch (e: Exception) { /* retry */ }
            if (attempt < 3) Thread.sleep(1200)
        }
        return null
    }
}
