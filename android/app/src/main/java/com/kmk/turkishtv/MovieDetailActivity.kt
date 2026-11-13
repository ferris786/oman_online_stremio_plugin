package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.kmk.turkishtv.mux.MuxConfig
import com.kmk.turkishtv.mux.MuxEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Movie page: backdrop, title, rating / year / runtime / genres, synopsis.
 * Play takes the best instant source straight to the player; Choose source
 * opens the grouped source sheet. Candidates come from the mux engine
 * (parallel providers -> batched TorBox cache check -> profile sort).
 */
class MovieDetailActivity : Activity() {

    private var movie: Api.EnMovie? = null
    private var meta: JSONObject? = null
    private var background: String? = null
    private var busy = false
    private var trailerId: String? = null

    private lateinit var status: TextView
    private lateinit var playBtn: TextView
    private lateinit var sourcesBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie)

        val imdbId = intent.getStringExtra("imdbId") ?: run { finish(); return }
        movie = Api.EnMovie(
            imdbId,
            intent.getStringExtra("name") ?: "",
            intent.getStringExtra("poster"),
            intent.getStringExtra("year")
        )
        background = intent.getStringExtra("background")

        status = findViewById(R.id.mStatus)
        playBtn = findViewById(R.id.mPlay)
        sourcesBtn = findViewById(R.id.mSources)
        findViewById<TextView>(R.id.mTitle).text = movie?.name
        findViewById<TextView>(R.id.mYear).text = movie?.year ?: ""
        Ui.loadBackdrop(findViewById(R.id.mBackdrop), background ?: movie?.poster)

        playBtn.setOnClickListener { findSources(pickBest = true) }
        sourcesBtn.setOnClickListener { findSources(pickBest = false) }
        playBtn.requestFocus()

        findViewById<TextView>(R.id.mReviews).setOnClickListener {
            status.text = "Loading reviews…"
            Extras.loadReviews(this, imdbId, movie?.name ?: "", meta?.optString("imdbRating")) {
                status.text = ""
            }
        }
        findViewById<TextView>(R.id.mTrailer).setOnClickListener {
            trailerId?.let { Extras.playTrailer(this, it, movie?.name ?: "") }
        }

        val autoplay = intent.getBooleanExtra("autoplay", false)

        Thread {
            try {
                val m = Api.enMeta(imdbId)
                meta = m
                runOnUiThread { if (!isFinishing) renderMeta(m) }
            } catch (e: Exception) { /* detail text stays as passed in */ }

            // Cinemeta usually carries trailerStreams already; ask the server
            // (TMDB) only when it does not.
            val fromMeta = meta?.optJSONArray("trailerStreams")?.optJSONObject(0)?.optString("ytId")
                ?: meta?.optJSONArray("trailers")?.optJSONObject(0)?.optString("source")
            val yt = fromMeta?.takeIf { it.isNotBlank() }
                ?: try { Api.trailerId(imdbId) } catch (e: Exception) { null }
            if (yt != null) runOnUiThread {
                if (isFinishing) return@runOnUiThread
                trailerId = yt
                findViewById<TextView>(R.id.mTrailer).visibility = View.VISIBLE
            }

            if (autoplay) runOnUiThread { if (!isFinishing) findSources(pickBest = true) }

            // AIOStreams preloadStreams trick: while the user reads the
            // synopsis, send the best candidate to TorBox so it's cached
            // (or nearly) by the time PLAY is pressed. Adds ONE torrent
            // per browsed movie when nothing is cached.
            // Only when the account has slots to spare: every pre-warm is a
            // real download, and a pile of them is what fills the plan's
            // active limit and gets every later torrent queued.
            val key = Torbox.apiKey(this)
            if (!autoplay && key != null && MuxConfig.load(this).prewarm &&
                Torbox.accountState(key).downloading in 0..1) {
                try {
                    val m = meta
                    val title = m?.optString("name", movie?.name ?: "") ?: movie?.name ?: ""
                    val year = m?.optString("year", movie?.year ?: "") ?: movie?.year ?: ""
                    val result = MuxEngine.fetch(imdbId, title, year, key, MuxConfig.load(this))
                    val best = result.streams.firstOrNull { !it.cached }
                    if (best != null) Torbox.startDownload(key, best.hash, best.name)
                } catch (e: Exception) { /* silent - prewarm is best-effort */ }
            }
        }.start()
    }

    private fun renderMeta(m: JSONObject) {
        findViewById<TextView>(R.id.mDesc).text = m.optString("description", "")
        val rating = m.optString("imdbRating", "")
        findViewById<TextView>(R.id.mRating).apply {
            text = "★ $rating"
            visibility = if (rating.isNotEmpty()) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.mYear).text = m.optString("year", movie?.year ?: "")
        findViewById<TextView>(R.id.mRuntime).text = m.optString("runtime", "")
        val genres = m.optJSONArray("genres") ?: m.optJSONArray("genre")
        val gl = ArrayList<String>()
        if (genres != null) for (i in 0 until minOf(3, genres.length())) gl.add(genres.optString(i))
        findViewById<TextView>(R.id.mGenres).text = gl.joinToString("  ·  ")
        val cast = m.optJSONArray("cast")
        if (cast != null && cast.length() > 0) {
            val names = ArrayList<String>()
            for (i in 0 until minOf(4, cast.length())) names.add(cast.optString(i))
            findViewById<TextView>(R.id.mCast).text = "Cast  ·  " + names.joinToString(", ")
        }
        m.optString("background").takeIf { it.isNotEmpty() }?.let {
            background = it
            Ui.loadBackdrop(findViewById(R.id.mBackdrop), it)
        }
        m.optString("logo").takeIf { it.isNotEmpty() }?.let {
            val logo = findViewById<ImageView>(R.id.mLogo)
            logo.visibility = View.VISIBLE
            findViewById<TextView>(R.id.mTitle).visibility = View.GONE
            Ui.loadImage(logo, it, 0f, android.R.color.transparent, 150)
        }
    }

    private fun setBusy(b: Boolean, text: String) {
        busy = b
        status.text = text
        playBtn.alpha = if (b) 0.5f else 1f
        sourcesBtn.alpha = if (b) 0.5f else 1f
    }

    private fun findSources(pickBest: Boolean) {
        if (busy) return
        val key = Torbox.apiKey(this)
        if (key == null) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle("TorBox key needed")
                .setMessage("English movies stream through your TorBox account.\nAdd your API key in Settings first.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val imdbId = movie?.id ?: return
        setBusy(true, "Finding sources…")

        Thread {
            try {
                val title = meta?.optString("name") ?: movie?.name ?: ""
                val year = meta?.optString("year") ?: (movie?.year ?: "")
                val config = MuxConfig.load(this)
                val result = MuxEngine.fetch(imdbId, title, year, key, config)

                if (result.streams.isEmpty()) {
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        setBusy(false, "No sources found for this movie")
                    }
                    return@Thread
                }
                val arr = JSONArray()
                for (t in result.streams) {
                    arr.put(JSONObject()
                        .put("name", t.attrs.resolutionLabel)
                        .put("title", t.formattedTitle())
                        .put("url", "")
                        .put("hash", t.hash)
                        .put("dn", t.name)
                        .put("encode", t.attrs.encode)
                        .put("cached", t.cached))
                }

                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    setBusy(false, "${result.streams.size} sources  ·  ${result.cachedCount} instant ⚡")
                    when {
                        pickBest && result.cachedCount > 0 -> {
                            val best = result.streams.indexOfFirst { it.cached }
                            launchPlayer(arr, best, imdbId)
                        }
                        pickBest -> {
                            // TorBox's cache is thinner than Real-Debrid's: when NOTHING is
                            // instant, offering a one-tap download of the best source beats
                            // a wall of identical rows.
                            val best1080 = result.streams.indexOfFirst { it.attrs.resolution == 1080 }
                            val best4k = result.streams.indexOfFirst { it.attrs.resolution == 2160 }
                            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                                .setTitle("Nothing instant on TorBox yet")
                                .setMessage("Nothing cached for this movie. Download the best source now?\n" +
                                    "(download plays automatically when ready)")
                                .setPositiveButton("Best 4K") { _, _ ->
                                    launchPlayer(arr, if (best4k >= 0) best4k else 0, imdbId)
                                }
                                .setNeutralButton("Best 1080p") { _, _ ->
                                    launchPlayer(arr, if (best1080 >= 0) best1080 else 0, imdbId)
                                }
                                .setNegativeButton("Browse all") { _, _ ->
                                    launchPicker(arr, imdbId, result)
                                }
                                .show()
                        }
                        else -> launchPicker(arr, imdbId, result)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    setBusy(false, "Could not find sources: ${e.message}")
                    Toast.makeText(this, "Could not play: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun recordHistory(imdbId: String) {
        val m = movie ?: return
        WatchState.upsertHistory(this, WatchState.HistoryEntry(
            "movie:$imdbId", m.name, m.poster, "movie:$imdbId", m.name, 0, 0,
            WatchState.getPosition(this, "movie:$imdbId"), 0, System.currentTimeMillis(),
            thumb = background, backdrop = background))
    }

    private fun launchPicker(arr: JSONArray, imdbId: String, result: MuxEngine.Result) {
        startActivity(Intent(this, SourcePickerActivity::class.java).apply {
            putExtra("movieTitle", movie?.name)
            putExtra("videoId", "movie:$imdbId")
            putExtra("videoTitle", movie?.name)
            putExtra("streamsJson", arr.toString())
            putExtra("background", background)
            putExtra("imdbId", imdbId)
            putExtra("poster", movie?.poster)
            putStringArrayListExtra("providers", ArrayList(result.providers))
        })
    }

    private fun launchPlayer(arr: JSONArray, startIndex: Int, imdbId: String) {
        recordHistory(imdbId)
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("videoId", "movie:$imdbId")
            putExtra("videoTitle", movie?.name)
            putExtra("streamsJson", arr.toString())
            putExtra("startIndex", startIndex)
        })
    }
}
