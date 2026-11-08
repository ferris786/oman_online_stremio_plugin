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

    private fun call(url: String, key: String, post: okhttp3.RequestBody? = null): JSONObject {
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
     * Uncached flow: start a TorBox download for a torrent and expose its id
     * so the app can poll progress. Returns the torrent id or an error reason
     * (prefixed "err:").
     */
    fun startDownload(key: String, hash: String, name: String): Long {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=${java.net.URLEncoder.encode(name, "UTF-8")}" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
        return try {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("magnet", magnet)
                .addFormDataPart("seed", "2")
                .build()
            val create = call("$BASE/torrents/createtorrent", key, body)
            val d = create.optJSONObject("data")
            val id = d?.optLong("torrent_id", -1L) ?: -1L
            if (id > 0) return id
            // maybe already in the account - find by hash
            val list = call("$BASE/torrents/mylist?limit=1000&bypass_cache=true", key)
            val data = list.optJSONArray("data") ?: return -1L
            for (i in 0 until data.length()) {
                val t = data.optJSONObject(i) ?: continue
                if (t.optString("hash", "").lowercase().contentEquals(hash.lowercase())) {
                    return t.optLong("id", -1L)
                }
            }
            -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Progress of a torrent download. Returns
     * (progressPercent, downloadSpeedMbps, statusString, readyBoolean).
     */
    fun downloadProgress(key: String, torrentId: Long): DownloadProgress {
        return try {
            val list = call("$BASE/torrents/mylist?limit=1000&bypass_cache=true", key)
            val data = list.optJSONArray("data")
                ?: return DownloadProgress(0, 0.0, "list unreadable", false)
            for (i in 0 until data.length()) {
                val t = data.optJSONObject(i) ?: continue
                if (t.optLong("id", -1L) != torrentId) continue
                val progress = t.optDouble("progress", 0.0)
                val speed = t.optDouble("download_speed", 0.0) / 1_000_000.0
                val status = t.optString("status", "unknown")
                val ready = t.optBoolean("cached") ||
                    status.contains("completed", ignoreCase = true) ||
                    (progress >= 100.0) ||
                    (t.optString("download_state", "") == "completed")
                return DownloadProgress(progress.toInt(), speed, status, ready)
            }
            DownloadProgress(0, 0.0, "not found", false)
        } catch (e: Exception) {
            DownloadProgress(0, 0.0, "error: ${e.message}", false)
        }
    }

    data class DownloadProgress(val percent: Int, val speedMbps: Double, val status: String, val ready: Boolean)

    /**
     * Resolve one cached torrent to a direct streaming URL.
     * Returns the http link on success, or a human-readable error reason.
     *
     * Per the TorBox OpenAPI spec:
     *  - createtorrent expects multipart/form-data
     *  - mylist is served from a server-side cache; after creating, poll
     *    with bypass_cache=true or the new torrent never appears
     *  - requestdl REQUIRES the API token as a ?token= query parameter
     */
    fun resolveStream(key: String, hash: String, name: String): String {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=${java.net.URLEncoder.encode(name, "UTF-8")}" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"

        // 1. create (or re-use) the torrent in the account
        var knownTorrentId = -1L
        try {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("magnet", magnet)
                .addFormDataPart("seed", "2")
                .build()
            val create = call("$BASE/torrents/createtorrent", key, body)
            val d = create.optJSONObject("data")
            if (d != null) knownTorrentId = d.optLong("torrent_id", -1L)
        } catch (e: Exception) {
            // usually "already exists" - continue to the list scan
        }

        // 2. find it in mylist (bypass_cache so freshly added torrents show)
        repeat(5) { attempt ->
            try {
                val list = call("$BASE/torrents/mylist?limit=1000&bypass_cache=true", key)
                val data = list.optJSONArray("data")
                    ?: return "TorBox list unreadable: ${list.optString("error", "?")}"
                for (i in 0 until data.length()) {
                    val t = data.optJSONObject(i) ?: continue
                    if (!t.optString("hash", "").lowercase().contentEquals(hash.lowercase())) continue

                    val files = t.optJSONArray("files")
                        ?: return "Torrent has no files yet (not fully cached)"
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
                    if (bestId <= 0) return "No video file in this torrent"

                    // 3. direct link (token query param is required by the API)
                    val torrentId = if (t.optLong("id", -1) > 0) t.optLong("id") else knownTorrentId
                    val dl = call("$BASE/torrents/requestdl?token=$key&torrent_id=$torrentId&file_id=$bestId", key)
                    val link = dl.optString("data")
                    if (link.startsWith("http")) return link
                    return "TorBox refused download: ${dl.optString("error", dl.optString("detail", "?"))}"
                }
            } catch (e: Exception) {
                // transient - retry
            }
            Thread.sleep(1000)
        }
        return "Torrent never appeared in your TorBox list (plan limit reached?)"
    }
}
