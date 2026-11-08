package com.kmk.turkishtv

import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the Stremio-addon HTTP interface:
 *   /catalog/series/osmanonline_catalog.json -> { metas: [...] }
 *   /meta/series/{id}.json                  -> { meta: { videos: [...] } }
 *   /stream/series/{videoId}.json           -> { streams: [...] }
 */
object Api {

    data class Series(val id: String, val name: String, val poster: String?, val description: String?)
    data class Episode(val id: String, val title: String, val season: Int, val episode: Int)
    data class Stream(val name: String, val title: String, val url: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(Config.HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Config.HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private fun get(path: String): String {
        val request = okhttp3.Request.Builder().url(Config.BASE_URL + path).build()
        val resp = http.newCall(request).execute()
        resp.use {
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $path")
            return resp.body?.string() ?: throw RuntimeException("empty body for $path")
        }
    }

    fun catalog(): List<Series> {
        val metas = JSONObject(get("/catalog/series/osmanonline_catalog.json")).getJSONArray("metas")
        val out = ArrayList<Series>(metas.length())
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            out.add(Series(m.getString("id"), m.getString("name"),
                if (m.isNull("poster")) null else m.optString("poster"),
                if (m.isNull("description")) null else m.optString("description")))
        }
        return out
    }

    fun episodes(seriesId: String): List<Episode> {
        val meta = JSONObject(get("/meta/series/" + encode(seriesId) + ".json")).getJSONObject("meta")
        val videos = meta.getJSONArray("videos")
        val out = ArrayList<Episode>(videos.length())
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            out.add(Episode(v.getString("id"), v.optString("title", ""),
                v.optInt("season", 1), v.optInt("episode", 1)))
        }
        return out
    }

    fun streams(videoId: String): List<Stream> {
        val arr = JSONObject(get("/stream/series/" + encode(videoId) + ".json")).getJSONArray("streams")
        val out = ArrayList<Stream>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val url = s.optString("url")
            if (url.isNotEmpty()) out.add(Stream(s.optString("name"), s.optString("title"), url))
        }
        return out
    }

    // ---------- English Movies (Cinemeta + TPB via our addon) ----------

    data class EnMovie(val id: String, val name: String, val poster: String?, val year: String?)
    data class Torrent(val name: String, val hash: String, val seeders: Int, val sizeBytes: Long, val quality: String)

    fun enCatalog(catalog: String, skip: Int = 0): List<EnMovie> {
        // get() prepends BASE_URL itself - pass only the path
        val path = "/en/catalog/movie/" + catalog + (if (skip > 0) "/$skip" else "")
        val metas = JSONObject(get(path)).getJSONArray("metas")
        val out = ArrayList<EnMovie>(metas.length())
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            out.add(EnMovie(m.getString("id"), m.getString("name"),
                if (m.isNull("poster")) null else m.optString("poster"),
                if (m.isNull("year")) null else m.optString("year")))
        }
        return out
    }

    fun enSearch(query: String): List<EnMovie> {
        val metas = JSONObject(get("/en/search/movie/" + encode(query))).getJSONArray("metas")
        val out = ArrayList<EnMovie>(metas.length())
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            out.add(EnMovie(m.getString("id"), m.getString("name"),
                if (m.isNull("poster")) null else m.optString("poster"),
                if (m.isNull("year")) null else m.optString("year")))
        }
        return out
    }

    fun enMeta(imdbId: String): JSONObject {
        return JSONObject(get("/en/meta/movie/" + encode(imdbId))).getJSONObject("meta")
    }

    fun enTorrents(imdbId: String): List<Torrent> {
        val arr = JSONObject(get("/en/torrents/movie/" + encode(imdbId))).getJSONArray("torrents")
        val out = ArrayList<Torrent>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            out.add(Torrent(t.getString("name"), t.getString("hash"),
                t.optInt("seeders", 0), t.optLong("sizeBytes", 0), t.optString("quality", "")))
        }
        return out
    }

    /**
     * Direct YTS lookup by IMDb id from the DEVICE - exact movie match with
     * every quality tier (720p/1080p/2160p). Used in the fallback path when
     * the addon's server IP is blocked by indexes.
     */
    fun ytsByImdb(imdbId: String): List<Torrent> {
        val out = ArrayList<Torrent>()
        try {
            val body = rawGet("https://yts.lt/api/v2/list_movies.json?query_term=" + encode(imdbId))
            val movie = JSONObject(body).getJSONObject("data")
                .optJSONArray("movies")?.optJSONObject(0) ?: return out
            val title = movie.optString("title", "")
            val torrents = movie.optJSONArray("torrents") ?: return out
            for (i in 0 until torrents.length()) {
                val t = torrents.getJSONObject(i)
                val hash = t.optString("hash", "").lowercase()
                val size = t.optLong("size_bytes", 0)
                if (hash.length < 20 || size < 300000000L || size > 70000000000L) continue
                val quality = t.optString("quality", "")
                val type = if (t.optString("type") == "web") "WEB" else t.optString("type", "BluRay")
                out.add(Torrent("$title $quality $type YTS", hash,
                    t.optInt("seeds", 0), size, "$quality ${type.replaceFirstChar { it.uppercase() }}"))
            }
        } catch (e: Exception) { /* yts unreachable */ }
        return out
    }

    /**
     * Direct TPB (apibay) search from the DEVICE - fallback for when the
     * addon's server IP gets nothing back (some indexers block datacenter
     * IPs). Residential connections (the TV itself) are usually fine.
     */
    fun apibaySearch(title: String, year: String): List<Torrent> {
        val clean = title.replace(Regex("[^\\w\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val urls = listOf(
            "https://apibay.org/q.php?q=" + encode("$clean $year") + "&cat=207",
            "https://apibay.org/q.php?q=" + encode(clean) + "&cat=202"
        )
        val out = ArrayList<Torrent>()
        for (u in urls) {
            try {
                val body = rawGet(u)
                val arr = org.json.JSONArray(body)
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val name = r.optString("name")
                    if (name == "No results returned") continue
                    val hash = r.optString("info_hash")
                    val seeders = r.optInt("seeders", 0)
                    val size = r.optLong("size", 0)
                    if (hash.length < 20 || seeders < 3 || size < 400000000L || size > 70000000000L) continue
                    val lname = name.lowercase()
                    if (Regex("\\b(cam|ts|screener|hdcam|hc)\\b").containsMatchIn(lname)) continue
                    if (year.isNotEmpty() && !lname.contains(year)) {
                        val years = Regex("(19|20)\\d{2}").findAll(lname).map { it.value }.toList()
                        if (years.isNotEmpty() && !years.contains(year)) continue
                    }
                    val res = Regex("(2160|1080|720|480)p").find(lname)?.value ?: "SD"
                    val kind = when {
                        lname.contains("remux") -> "Remux"
                        lname.contains("blu") -> "BluRay"
                        lname.contains("web") -> "WEB-DL"
                        else -> "WEB"
                    }
                    out.add(Torrent(name, hash, seeders, size, "$res $kind"))
                }
            } catch (e: Exception) { /* indexer unreachable */ }
        }
        // dedupe by hash, best (seeders + sane size) first
        return out.distinctBy { it.hash }.sortedByDescending {
            minOf(it.seeders, 200) + (if (it.sizeBytes >= 1500000000L && it.sizeBytes <= 9000000000L) 150 else 0) +
                (if (it.quality.startsWith("1080p")) 120 else if (it.quality.startsWith("720p")) 60 else 0) +
                (if (it.sizeBytes > 1500000000L && it.sizeBytes < 9000000000L) 150 else 0)
        }.take(12)
    }

    // ---------- phone pairing ----------
    // Returns "code" on success (throws on failure)
    fun pairStart(): String {
        val req = okhttp3.Request.Builder()
            .url(Config.BASE_URL + "/pair/start")
            .method("POST", okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            return JSONObject(body).getString("code")
        }
    }

    /** status: waiting | ready (with key) | expired */
    fun pairCheck(code: String): Pair<String, String?> {
        val body = rawGet(Config.BASE_URL + "/pair/" + encode(code) + "/check")
        val j = JSONObject(body)
        return Pair(j.optString("status"), if (j.has("key")) j.optString("key") else null)
    }

    private fun rawGet(url: String): String {
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            return resp.body?.string() ?: "[]"
        }
    }

    private fun encode(s: String): String {
        return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
