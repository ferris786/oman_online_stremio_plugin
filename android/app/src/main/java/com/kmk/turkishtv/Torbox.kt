package com.kmk.turkishtv

import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TorBox client - runs IN THE APP so each device uses its own API key
 * (Settings).
 *
 * TorBox plans allow a handful of ACTIVE downloads. Once those slots are
 * taken (typically by stalled pre-warm downloads of movies that were only
 * browsed), every new torrent - cached ones included - is silently QUEUED:
 * createtorrent answers with a queued_id instead of a torrent_id and the
 * torrent never shows up in mylist, which is where the old "Torrent never
 * appeared in your TorBox list" came from. v5.0.3 detects that, prunes
 * stale unfinished downloads to free a slot, and waits for the queued
 * torrent to start.
 */
object Torbox {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://api.torbox.app/v1/api"
    private const val CACHE_CHECK_BATCH = 100
    private val JSON = "application/json".toMediaType()

    /** unfinished downloads older than this are considered abandoned pre-warms */
    private const val STALE_DOWNLOAD_MIN = 15L

    fun apiKey(ctx: android.content.Context): String? {
        val k = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("torbox_key", null)
        return if (k.isNullOrBlank()) null else k
    }

    fun saveApiKey(ctx: android.content.Context, key: String) {
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("torbox_key", key.trim()).apply()
    }

    /**
     * TorBox allows 300 requests/minute (x-ratelimit-limit) and answers 429
     * with a Retry-After once that is spent. The old client parsed every
     * response body as JSON, so a 429 (or any HTML error page) threw, the
     * caller swallowed it as "transient", retried, and finally reported
     * "Torrent never appeared in your TorBox list" - which is what made
     * EVERY English source fail at once. Now a 429 is recorded, every
     * in-flight caller fails fast until it expires, and the reason is
     * reported honestly.
     */
    @Volatile private var rateLimitedUntilMs = 0L

    fun rateLimitSecondsLeft(): Long {
        val left = rateLimitedUntilMs - System.currentTimeMillis()
        return if (left > 0) (left + 999) / 1000 else 0
    }

    private fun call(url: String, key: String, post: okhttp3.RequestBody? = null): JSONObject {
        val waitS = rateLimitSecondsLeft()
        if (waitS > 0) return JSONObject().put("success", false)
            .put("error", "RATE_LIMITED").put("detail", "TorBox rate limit - retry in ${waitS}s")

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .apply { if (post != null) post(post) else get() }
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 429) {
                val retryAfter = resp.header("retry-after")?.toLongOrNull() ?: 61L
                rateLimitedUntilMs = System.currentTimeMillis() + retryAfter * 1000
                return JSONObject().put("success", false)
                    .put("error", "RATE_LIMITED").put("detail", "TorBox rate limit - retry in ${retryAfter}s")
            }
            val body = resp.body?.string() ?: "{}"
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                JSONObject().put("success", false).put("error", "HTTP ${resp.code}")
                    .put("detail", body.take(120).replace(Regex("<[^>]*>"), " ").trim())
            }
        }
    }

    private fun isRateLimited(j: JSONObject) = j.optString("error") == "RATE_LIMITED"

    private fun postJson(path: String, key: String, body: JSONObject): JSONObject =
        call("$BASE$path", key, body.toString().toRequestBody(JSON))

    /** Lightweight key validation. */
    fun testKey(key: String): String {
        return try {
            val j = call("$BASE/torrents/mylist?limit=1", key)
            if (j.optBoolean("success")) "ok" else (j.optString("error", "invalid key"))
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    // ------------------------------------------------------------ cache check

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
        // AIOStreams-style: instant-playable = cached on TorBox OR already in
        // the user's account (library). Both checked in parallel.
        //
        // The hashes travel as repeated ?hash= query params and TorBox answers
        // 414 URI Too Long past ~10 KB (about 220 hashes). Popular titles pull
        // 300+ Torrentio results, which silently zeroed the cached set and
        // emptied the source list for anyone with "show uncached" off. Check
        // in batches of 100 (~4.6 KB each), in parallel.
        var cached: Set<String> = emptySet()
        var library: Set<String> = emptySet()
        val t1 = Thread {
            val out = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val batches = hashes.map { it.lowercase() }.distinct().chunked(CACHE_CHECK_BATCH)
            val workers = batches.map { batch ->
                Thread {
                    try {
                        val sb = StringBuilder("$BASE/torrents/checkcached?format=list")
                        for (h in batch) sb.append("&hash=").append(h)
                        out.addAll(parseHashSet(call(sb.toString(), key)))
                    } catch (e: Exception) { /* this batch counts as uncached */ }
                }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }
            cached = out
        }
        val t2 = Thread {
            library = try {
                val out = HashSet<String>()
                for (t in fullList(key)) {
                    val h = t.optString("hash", "")
                    // only torrents that actually finished count as instant
                    if (h.isNotBlank() && isFinished(t)) out.add(h.lowercase())
                }
                out
            } catch (e: Exception) { emptySet() }
        }
        t1.start(); t2.start(); t1.join(); t2.join()
        return cached + library
    }

    private fun parseHashSet(j: JSONObject): Set<String> {
        if (!j.optBoolean("success")) return emptySet()
        val out = HashSet<String>()
        val data = j.opt("data") ?: return emptySet()
        when (data) {
            is JSONArray -> for (i in 0 until data.length()) {
                val el = data.opt(i) ?: continue
                when (el) {
                    is String -> if (el.isNotBlank()) out.add(el.lowercase())
                    is JSONObject -> {
                        val h = el.optString("hash", "")
                        if (h.isNotBlank()) out.add(h.lowercase())
                    }
                }
            }
            is JSONObject -> for (k in keysOf(data)) out.add(k.lowercase())
        }
        return out
    }

    private fun keysOf(o: JSONObject): List<String> {
        val out = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    // ------------------------------------------------------------ account list

    /**
     * every torrent in the account (mylist); data may be an array or, with
     * ?id=, a single object.
     *
     * The full list is ~430 KB on a well-used account (121 torrents with
     * file lists); fetching one torrent by id is ~2 KB. Resolve and progress
     * always pass an id, and the one place that needs the whole list caches
     * it briefly, so a stream of retries can no longer spend the rate limit.
     */
    private fun listTorrents(key: String, bypassCache: Boolean, id: Long = -1L): List<JSONObject> {
        val url = StringBuilder("$BASE/torrents/mylist?limit=1000")
        if (bypassCache) url.append("&bypass_cache=true")
        if (id > 0) url.append("&id=").append(id)
        val j = call(url.toString(), key)
        if (!j.optBoolean("success")) {
            throw RuntimeException(j.optString("detail", "").ifEmpty { j.optString("error", "list unreadable") })
        }
        val out = ArrayList<JSONObject>()
        when (val data = j.opt("data")) {
            is JSONArray -> for (i in 0 until data.length()) data.optJSONObject(i)?.let { out.add(it) }
            is JSONObject -> out.add(data)
        }
        return out
    }

    private var fullListCache: List<JSONObject> = emptyList()
    private var fullListCacheAt = 0L
    private const val FULL_LIST_TTL_MS = 60_000L

    @Synchronized
    private fun fullList(key: String): List<JSONObject> {
        if (System.currentTimeMillis() - fullListCacheAt < FULL_LIST_TTL_MS && fullListCache.isNotEmpty()) return fullListCache
        val list = listTorrents(key, bypassCache = true)
        fullListCache = list
        fullListCacheAt = System.currentTimeMillis()
        return list
    }

    private fun invalidateList() { fullListCacheAt = 0L }

    private fun isFinished(t: JSONObject): Boolean =
        t.optBoolean("download_finished") || t.optBoolean("cached") || t.optDouble("progress", 0.0) >= 1.0 ||
            t.optString("download_state", "").contains("completed", ignoreCase = true)

    /** minutes since created_at ("2026-11-13T14:06:51.123456+00:00" or with a space); huge when unknown */
    private fun ageMinutes(t: JSONObject): Long {
        val created = t.optString("created_at", "").replace(" ", "T")
        if (created.length < 19) return Long.MAX_VALUE
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val ms = f.parse(created.substring(0, 19))?.time ?: return Long.MAX_VALUE
            (System.currentTimeMillis() - ms) / 60_000
        } catch (e: Exception) { Long.MAX_VALUE }
    }

    data class AccountState(val total: Int, val downloading: Int, val stale: Int)

    /** how busy the account is: unfinished downloads hold the plan's active slots */
    fun accountState(key: String): AccountState {
        return try {
            val list = fullList(key)
            val unfinished = list.filter { !isFinished(it) }
            AccountState(list.size, unfinished.size, unfinished.count { ageMinutes(it) >= STALE_DOWNLOAD_MIN })
        } catch (e: Exception) { AccountState(-1, -1, -1) }
    }

    private fun deleteTorrent(key: String, id: Long): Boolean = try {
        postJson("/torrents/controltorrent", key, JSONObject().put("torrent_id", id).put("operation", "delete")).optBoolean("success")
    } catch (e: Exception) { false }

    private fun deleteQueued(key: String, queuedId: Long): Boolean = try {
        postJson("/queued/controlqueued", key, JSONObject().put("queued_id", queuedId).put("operation", "delete")).optBoolean("success")
    } catch (e: Exception) { false }

    /** Delete every torrent in the account (the ops-doc cleanup, one tap). Returns a human message. */
    fun deleteAll(key: String): String = try {
        val j = postJson("/torrents/controltorrent", key, JSONObject().put("operation", "delete").put("all", true))
        invalidateList()
        if (j.optBoolean("success")) "TorBox cleaned up" else "TorBox refused: ${j.optString("detail", j.optString("error", "?"))}"
    } catch (e: Exception) { "error: ${e.message}" }

    /**
     * Free active slots by deleting unfinished downloads older than
     * STALE_DOWNLOAD_MIN (abandoned pre-warms). Returns how many were removed.
     */
    fun pruneStale(key: String, keepHash: String = ""): Int {
        return try {
            var n = 0
            invalidateList()
            for (t in fullList(key)) {
                if (isFinished(t)) continue
                if (t.optString("hash", "").equals(keepHash, ignoreCase = true)) continue
                if (ageMinutes(t) < STALE_DOWNLOAD_MIN) continue
                if (deleteTorrent(key, t.optLong("id", -1L))) n++
            }
            n
        } catch (e: Exception) { 0 }
    }

    // ------------------------------------------------------------ create

    private data class Created(val torrentId: Long, val queuedId: Long, val error: String?)

    private fun magnetFor(hash: String, name: String) =
        "magnet:?xt=urn:btih:$hash&dn=${java.net.URLEncoder.encode(name, "UTF-8")}" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"

    private fun createTorrent(key: String, hash: String, name: String): Created {
        return try {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("magnet", magnetFor(hash, name))
                .addFormDataPart("seed", "2")
                .build()
            val create = call("$BASE/torrents/createtorrent", key, body)
            if (isRateLimited(create)) return Created(-1L, -1L, create.optString("detail"))
            invalidateList()
            val d = create.optJSONObject("data")
            val torrentId = d?.optLong("torrent_id", -1L) ?: -1L
            val queuedId = d?.optLong("queued_id", -1L) ?: -1L
            val detail = create.optString("detail", "")
            val error = if (!create.optBoolean("success")) create.optString("error", detail).ifEmpty { "create failed" } else null
            // some API versions report queueing only in the detail text
            val queuedByText = torrentId <= 0 && queuedId <= 0 && error == null && detail.contains("queue", ignoreCase = true)
            Created(torrentId, if (queuedByText) 0L else queuedId, error)
        } catch (e: Exception) {
            Created(-1L, -1L, null) // usually "already exists" - the list scan finds it
        }
    }

    private fun findByHash(key: String, hash: String, torrentId: Long): JSONObject? {
        val list = if (torrentId > 0) listTorrents(key, bypassCache = true, id = torrentId) else listTorrents(key, bypassCache = true)
        return list.firstOrNull { it.optString("hash", "").equals(hash, ignoreCase = true) }
            ?: list.firstOrNull { torrentId > 0 && it.optLong("id", -1L) == torrentId }
    }

    /**
     * Create (or re-use) the torrent and wait until it is listed in the
     * account. Handles the queued case by pruning stale downloads so a slot
     * frees up. Returns the torrent's list entry, or null with a reason.
     */
    private fun ensureListed(key: String, hash: String, name: String, needFiles: Boolean): Pair<JSONObject?, String> {
        rateLimitSecondsLeft().let { if (it > 0) return Pair(null, "TorBox is rate-limiting this TV - wait ${it}s and try again") }

        val created = createTorrent(key, hash, name)
        val queuedId = created.queuedId
        var pruned = -1
        if (created.torrentId <= 0 && queuedId >= 0) {
            // At the plan's active-download limit: TorBox queued us. Free slots.
            pruned = pruneStale(key, keepHash = hash)
        }

        val attempts = if (queuedId >= 0) 14 else 8
        repeat(attempts) {
            rateLimitSecondsLeft().let { if (it > 0) return Pair(null, "TorBox is rate-limiting this TV - wait ${it}s and try again") }
            try {
                val t = findByHash(key, hash, created.torrentId)
                if (t != null) {
                    val files = t.optJSONArray("files")
                    if (!needFiles || (files != null && files.length() > 0)) return Pair(t, "")
                }
            } catch (e: Exception) {
                return Pair(null, "TorBox: ${e.message}")
            }
            Thread.sleep(1500)
        }

        if (queuedId >= 0) {
            val st = accountState(key)
            if (queuedId > 0) deleteQueued(key, queuedId)
            return Pair(null, "TorBox is full: ${st.downloading} downloads hold all your plan's slots" +
                (if (pruned > 0) " ($pruned stale removed, still busy)" else "") +
                ". Settings → Clean up TorBox, then try again.")
        }
        return Pair(null, created.error?.let { "TorBox: $it" }
            ?: "This source has no metadata on TorBox yet - try another one")
    }

    /**
     * Uncached flow: start a TorBox download for a torrent and expose its id
     * so the app can poll progress. Returns the torrent id or -1.
     */
    fun startDownload(key: String, hash: String, name: String): Long {
        val (t, _) = ensureListed(key, hash, name, needFiles = false)
        return t?.optLong("id", -1L) ?: -1L
    }

    /**
     * Progress of a torrent download. Returns
     * (progressPercent, downloadSpeedMbps, statusString, readyBoolean).
     */
    fun downloadProgress(key: String, torrentId: Long): DownloadProgress {
        return try {
            val t = listTorrents(key, bypassCache = true, id = torrentId).firstOrNull { it.optLong("id", -1L) == torrentId }
                ?: return DownloadProgress(0, 0.0, "not found", false)
            val progress = t.optDouble("progress", 0.0)
            val speed = t.optDouble("download_speed", 0.0) / 1_000_000.0
            val status = t.optString("download_state", t.optString("status", "unknown"))
            val ready = isFinished(t) || progress >= 1.0
            DownloadProgress((progress * 100).toInt().coerceIn(0, 100), speed, status, ready)
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
    fun resolveStream(key: String, hash: String, name: String, fileIdx: Int = -1, epTag: String = ""): String {
        val (t, why) = ensureListed(key, hash, name, needFiles = true)
        if (t == null) return why
        val files = t.optJSONArray("files") ?: return "No video file in this torrent"

        // Pick the real movie file. TorBox sometimes reports all
        // file sizes as 0 - picking "largest by size" then lands
        // on junk (samples/trailers) and playback fails on every
        // retry. Strategy: real videos with sane sizes first;
        // fall back to name heuristics when sizes are unknown.
        //
        // Episodes inside a season pack: Torrentio's fileIdx names the file
        // outright; failing that, the episode tag (S01E05, also 1x05) in the
        // filename decides. Without either, a pack would resolve to its
        // biggest file, i.e. some other episode.
        data class Pick(val id: Long, val size: Long, val score: Int)
        var best: Pick? = null
        var hasArchive = false
        val releaseName = t.optString("name", "").lowercase()
        val epAlt = if (epTag.length == 6) "${epTag.substring(1, 3).toInt()}x${epTag.substring(4)}" else ""
        for (f in 0 until files.length()) {
            val file = files.optJSONObject(f) ?: continue
            val fname = file.optString("name", "").lowercase()
            if (fname.isEmpty()) continue
            val isVideo = fname.endsWith(".mp4") || fname.endsWith(".mkv") ||
                fname.endsWith(".avi") || fname.endsWith(".ts") ||
                fname.endsWith(".m2ts") || fname.endsWith(".wmv") ||
                fname.endsWith(".mov") || fname.endsWith(".mpg") ||
                fname.endsWith(".mpeg") || fname.endsWith(".flv") ||
                fname.endsWith(".m4v") || fname.endsWith(".divx") ||
                fname.endsWith(".vob")
            val isIso = fname.endsWith(".iso")
            if (fname.endsWith(".rar") || fname.endsWith(".zip") || fname.endsWith(".7z")) {
                hasArchive = true
                continue
            }
            if (!isVideo && !isIso) continue
            val junk = fname.contains("sample") || fname.contains("trailer") ||
                fname.contains("proof") || fname.contains("extra") ||
                fname.contains("featurette")
            var score = 0
            if (junk) score -= 100
            if (isIso) score -= 40          // only VLC plays ISOs; last resort
            val size = file.optLong("size", 0)
            if (size > 100_000_000L) score += 50       // definitely a real movie file
            if (size == 0L) score -= 10                 // size unknown
            // Episode selection inside a pack trumps everything else. The
            // FILENAME tag decides, not Torrentio's fileIdx: verified on a
            // real cached Breaking Bad S01 pack that TorBox numbers files in
            // its own order (ids 2,1,5,3,6,4,0 for E01..E07), so fileIdx 4
            // pointed at E06 when E05 was wanted. fileIdx is only a weak
            // tiebreaker for packs whose filenames carry no tag at all.
            if (epTag.isNotEmpty()) {
                val up = fname.uppercase()
                if (up.contains(epTag) || (epAlt.isNotEmpty() && up.contains(epAlt.uppercase()))) score += 1000
                else if (Regex("""S\d{2}E\d{2}|\b\d{1,2}X\d{2}\b""").containsMatchIn(up)) score -= 300 // a different episode
            }
            if (fileIdx >= 0 && file.optLong("id", -1L) == fileIdx.toLong()) score += 300
            val norm = { x: String -> x.replace(Regex("[^a-z0-9]"), "") }
            val fn = norm(fname); val rn = norm(releaseName)
            if (rn.length > 8 && fn.length > 8 && (fn.contains(rn.substring(0, 12)) || rn.contains(fn.substring(0, 12)))) score += 20
            val candidate = Pick(file.optLong("id", 0), size, score)
            val better = best == null ||
                candidate.score > best!!.score ||
                (candidate.score == best!!.score && candidate.size > best!!.size)
            if (better) best = candidate
        }
        // IMPORTANT: TorBox file ids are 0-indexed on many torrents
        // (single-file releases are id=0) - "bestId > 0" wrongly
        // rejected them, which is what flooded "No video file".
        if (best != null) {
            val bestId = best!!.id
            val torrentId = t.optLong("id", -1L)
            val dl = call("$BASE/torrents/requestdl?token=$key&torrent_id=$torrentId&file_id=$bestId", key)
            val link = dl.optString("data")
            if (link.startsWith("http")) return link
            return "TorBox refused download: ${dl.optString("error", dl.optString("detail", "?"))}"
        }
        // files listed but nothing playable: RAR/ISO-only pack or
        // a non-video bundle.
        return if (hasArchive) "Archive torrent (RAR) - skipped" else "No video file in this torrent"
    }
}
