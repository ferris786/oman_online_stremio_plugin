package com.kmk.turkishtv

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the Stremio-addon HTTP interface:
 *   /catalog/series/osmanonline_catalog.json -> { metas: [...] }
 *   /meta/series/{id}.json                  -> { meta: { videos: [...] } }
 *   /stream/series/{videoId}.json           -> { streams: [...] }
 *
 * v5: catalog/meta carry TMDB enrichment (backdrops, logos, genres, air
 * dates, per-episode stills/overviews/runtimes). All new fields are optional
 * so the app keeps working against an older server.
 */
object Api {

    data class Series(
        val id: String, val name: String, val poster: String?, val description: String?,
        val background: String? = null, val logo: String? = null,
        val genres: List<String> = emptyList(), val year: String? = null,
        val lastAired: String? = null, val nextAir: String? = null,
        val episodeCount: Int = 0, val seasonCount: Int = 0,
        val trailer: String? = null
    ) {
        /** aired within the last N days (server date strings are yyyy-mm-dd) */
        fun airedWithinDays(days: Int): Boolean = Ui.daysSince(lastAired)?.let { it in 0..days } ?: false
    }

    data class Episode(
        val id: String, val title: String, val season: Int, val episode: Int,
        val thumbnail: String? = null, val overview: String? = null,
        val runtimeMin: Int = 0, val released: String? = null
    ) {
        /** absolute episode number as the sites count it (from the id) */
        val absNumber: Int get() = Regex("episode-(\\d+)").find(id)?.groupValues?.get(1)?.toIntOrNull() ?: episode
        val isUnaired: Boolean get() = Ui.daysSince(released)?.let { it < 0 } ?: false
    }

    data class SeriesMeta(
        val id: String, val name: String, val poster: String?, val background: String?,
        val logo: String?, val description: String?, val genres: List<String>,
        val year: String?, val runtimeMin: Int, val lastAired: String?, val nextAir: String?,
        val videos: List<Episode>, val trailer: String? = null
    )

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

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.strList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun parseSeries(m: JSONObject) = Series(
        m.getString("id"), m.getString("name"), m.str("poster"), m.str("description"),
        m.str("background"), m.str("logo"), m.strList("genres"), m.str("year"),
        m.str("lastAired"), m.str("nextAir"), m.optInt("episodeCount", 0), m.optInt("seasonCount", 0),
        m.str("trailer")
    )

    private fun parseSeriesList(body: String): List<Series> {
        val metas = JSONObject(body).getJSONArray("metas")
        val out = ArrayList<Series>(metas.length())
        for (i in 0 until metas.length()) out.add(parseSeries(metas.getJSONObject(i)))
        return out
    }

    fun catalog(): List<Series> = parseSeriesList(get("/catalog/series/osmanonline_catalog.json"))

    fun searchSeries(query: String): List<Series> =
        parseSeriesList(get("/catalog/series/osmanonline_catalog/search=" + encode(query) + ".json"))

    private fun parseEpisode(v: JSONObject) = Episode(
        v.getString("id"), v.optString("title", ""), v.optInt("season", 1), v.optInt("episode", 1),
        v.str("thumbnail"), v.str("overview"), v.optInt("runtime", 0), v.str("released")
    )

    fun seriesMeta(seriesId: String): SeriesMeta {
        val meta = JSONObject(get("/meta/series/" + encode(seriesId) + ".json")).getJSONObject("meta")
        val videos = meta.optJSONArray("videos") ?: JSONArray()
        val eps = ArrayList<Episode>(videos.length())
        for (i in 0 until videos.length()) eps.add(parseEpisode(videos.getJSONObject(i)))
        return SeriesMeta(
            meta.optString("id", seriesId), meta.optString("name", ""), meta.str("poster"),
            meta.str("background"), meta.str("logo"), meta.str("description"), meta.strList("genres"),
            meta.str("year"), meta.optInt("runtime", 0), meta.str("lastAired"), meta.str("nextAir"), eps,
            meta.str("trailer")
        )
    }

    fun episodes(seriesId: String): List<Episode> = seriesMeta(seriesId).videos

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

    data class EnMovie(
        val id: String, val name: String, val poster: String?, val year: String?,
        val background: String? = null, val description: String? = null,
        val rating: String? = null, val runtime: String? = null,
        val genres: List<String> = emptyList()
    )

    data class Torrent(val name: String, val hash: String, val seeders: Int, val sizeBytes: Long, val quality: String)

    private fun parseMovies(body: String): List<EnMovie> {
        val metas = JSONObject(body).getJSONArray("metas")
        val out = ArrayList<EnMovie>(metas.length())
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            out.add(EnMovie(
                m.getString("id"), m.getString("name"), m.str("poster"), m.str("year"),
                m.str("background"), m.str("description"), m.str("imdbRating"), m.str("runtime"),
                m.strList("genres").ifEmpty { m.strList("genre") }
            ))
        }
        return out
    }

    fun enCatalog(catalog: String, skip: Int = 0, genre: String? = null): List<EnMovie> {
        // get() prepends BASE_URL itself - pass only the path
        val path = "/en/catalog/movie/" + catalog +
            (if (genre != null) "/genre/" + encode(genre) else if (skip > 0) "/$skip" else "")
        return parseMovies(get(path))
    }

    fun enSearch(query: String): List<EnMovie> = parseMovies(get("/en/search/movie/" + encode(query)))

    /** "Just added": releases that became watchable in the last day or two */
    fun enLatestAdded(): List<EnMovie> = parseMovies(get("/en/catalog/movie/latest"))

    // ---------- Type-ahead search, actors, subtitles ----------

    /** A TMDB suggestion. It has no IMDb id yet - resolveSuggestion() gets one. */
    data class Suggestion(
        val tmdbId: Int, val name: String, val year: String?, val poster: String?,
        val background: String?, val description: String?, val rating: String?
    )

    data class Person(
        val personId: Int, val name: String, val role: String?, val photo: String?,
        val knownFor: List<String>
    )

    data class Subtitle(val id: String, val lang: String, val label: String, val url: String)

    private fun parseSuggestions(body: String): List<Suggestion> {
        val arr = JSONObject(body).optJSONArray("suggestions") ?: return emptyList()
        val out = ArrayList<Suggestion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Suggestion(o.optInt("tmdbId"), o.optString("name"), o.str("year"),
                o.str("poster"), o.str("background"), o.str("description"), o.str("rating")))
        }
        return out
    }

    fun suggestMovies(query: String): List<Suggestion> =
        parseSuggestions(get("/en/suggest/movie/" + encode(query)))

    /** a picked suggestion -> its IMDb id (and Cinemeta meta when it has one) */
    fun resolveSuggestion(tmdbId: Int): String? {
        val j = JSONObject(get("/en/tmdb/movie/$tmdbId"))
        return j.str("imdbId")
    }

    fun searchPeople(query: String): List<Person> {
        val arr = JSONObject(get("/en/person/" + encode(query))).optJSONArray("people") ?: return emptyList()
        val out = ArrayList<Person>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Person(o.optInt("personId"), o.optString("name"), o.str("role"), o.str("photo"),
                o.strList("knownFor")))
        }
        return out
    }

    fun personMovies(personId: Int): List<Suggestion> =
        parseSuggestions(get("/en/person/$personId/movies"))

    data class Review(val author: String, val rating: Int, val createdAt: String?, val content: String)

    /**
     * Reviews. IMDb publishes no review API and scraping it breaks their
     * terms, so these come from TMDB's user reviews. The IMDb rating itself
     * still comes from the Cinemeta meta.
     */
    fun reviews(imdbId: String): List<Review> {
        val arr = JSONObject(get("/en/reviews/movie/" + encode(imdbId))).optJSONArray("reviews")
            ?: return emptyList()
        val out = ArrayList<Review>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Review(o.optString("author", "anonymous"), o.optInt("rating", 0),
                o.str("createdAt"), o.optString("content", "")))
        }
        return out
    }

    /** trailer fallback when the Cinemeta meta carries no trailerStreams */
    fun trailerId(imdbId: String): String? =
        JSONObject(get("/en/trailer/movie/" + encode(imdbId))).str("ytId")

    /** external subtitles for a movie (OpenSubtitles via the addon) */
    fun subtitles(imdbId: String): List<Subtitle> {
        val arr = JSONObject(get("/en/subtitles/movie/" + encode(imdbId))).optJSONArray("subtitles")
            ?: return emptyList()
        val out = ArrayList<Subtitle>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Subtitle(o.optString("id"), o.optString("lang"),
                o.optString("label"), o.optString("url")))
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
     * Torrentio as a provider (the AIOStreams approach): its public stream
     * endpoint aggregates dozens of indexers. We harvest infoHashes +
     * release names + seeds/size, then run our own TorBox cache check on
     * them. Title format: "ReleaseName\n👤 seeds 💾 size ⚙️ indexer".
     */
    fun torrentioMovie(imdbId: String): List<Torrent> {
        val out = ArrayList<Torrent>()
        try {
            val body = rawGet("https://torrentio.strem.fun/stream/movie/" + encode(imdbId) + ".json")
            val streams = JSONObject(body).optJSONArray("streams") ?: return out
            for (i in 0 until streams.length()) {
                val st = streams.optJSONObject(i) ?: continue
                val hash = st.optString("infoHash", "").lowercase()
                if (hash.length < 20) continue
                val title = st.optString("title", "")
                val name = title.substringBefore("\n").trim()
                if (name.isEmpty()) continue
                val seeds = Regex("👤\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val sizeStr = Regex("💾\\s*([\\d.]+)\\s*(GB|MB)").find(title)
                val size = when (sizeStr?.groupValues?.get(2)) {
                    "GB" -> (sizeStr.groupValues[1].toDouble() * 1e9).toLong()
                    "MB" -> (sizeStr.groupValues[1].toDouble() * 1e6).toLong()
                    else -> 0L
                }
                out.add(Torrent(name, hash, seeds, size, ""))
            }
        } catch (e: Exception) { /* torrentio unreachable */ }
        return out
    }

    /**
     * Direct YTS lookup by IMDb id from the DEVICE - exact movie match with
     * every quality tier (720p/1080p/2160p). Used in the fallback path when
     * the addon's server IP is blocked by indexes.
     */
    // yts.lt now redirects to yts.gg with an "API base is moving" notice
    // pointing at movies-api.accel.li; try the new base first, old one after.
    private val YTS_BASES = listOf("https://movies-api.accel.li/api/v2", "https://yts.lt/api/v2")

    fun ytsByImdb(imdbId: String): List<Torrent> {
        for (base in YTS_BASES) {
            val list = ytsFrom(base, imdbId)
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private fun ytsFrom(base: String, imdbId: String): List<Torrent> {
        val out = ArrayList<Torrent>()
        try {
            val body = rawGet("$base/list_movies.json?query_term=" + encode(imdbId))
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
