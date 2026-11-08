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
        val url = Config.BASE_URL + "/en/catalog/movie/" + catalog +
            (if (skip > 0) "/$skip" else "")
        val metas = JSONObject(get(url)).getJSONArray("metas")
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

    private fun encode(s: String): String {
        return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
