package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import coil.request.ImageRequest
import com.kmk.turkishtv.mux.MuxConfig
import com.kmk.turkishtv.mux.MuxEngine
import com.kmk.turkishtv.mux.QualityProfile
import org.json.JSONObject

/**
 * Movie detail + play flow:
 * candidates (addon) -> TorBox availability (in-app key) -> resolve cached
 * ones to direct links -> PlayerActivity with pre-resolved streams.
 */
class MovieDetailActivity : Activity() {

    private var movie: Api.EnMovie? = null
    private var meta: JSONObject? = null

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

        findViewById<TextView>(R.id.mTitle).text = movie?.name
        findViewById<TextView>(R.id.mYear).text = movie?.year ?: ""
        movie?.poster?.let { load(it, findViewById(R.id.mPoster)) }

        Thread {
            try {
                val m = Api.enMeta(imdbId)
                meta = m
                runOnUiThread {
                    findViewById<TextView>(R.id.mDesc).text = m.optString("description", "")
                    findViewById<TextView>(R.id.mRuntime).text =
                        if (m.optString("runtime", "").isNotEmpty()) m.optString("runtime") + " min" else ""
                    m.optString("background").takeIf { it.isNotEmpty() }?.let {
                        load(it, findViewById(R.id.mBackdrop))
                    }
                }

                // AIOStreams preloadStreams trick: while the user reads the
                // synopsis, send the best candidate to TorBox so it's cached
                // (or nearly) by the time PLAY is pressed. Adds ONE torrent
                // per browsed movie when nothing is cached.
                val key = Torbox.apiKey(this)
                if (key != null && MuxConfig.load(this).prewarm) {
                    try {
                        val title = m.optString("name", movie?.name ?: "")
                        val year = m.optString("year", movie?.year ?: "")
                        val result = MuxEngine.fetch(imdbId, title, year, key, MuxConfig.load(this))
                        val best = result.streams.firstOrNull { !it.cached }
                        if (best != null) Torbox.startDownload(key, best.hash, best.name)
                    } catch (e: Exception) { /* silent - prewarm is best-effort */ }
                }
            } catch (e: Exception) { /* detail text stays empty */ }
        }.start()

        findViewById<View>(R.id.mPlay).setOnClickListener { play() }
    }

    private fun load(url: String, view: ImageView) {
        Coil.imageLoader(this).enqueue(
            ImageRequest.Builder(this).data(url)
                .placeholder(R.drawable.placeholder_poster)
                .error(R.drawable.placeholder_poster)
                .crossfade(true).target(view).build())
    }

    private fun play() {
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
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setMessage("Finding sources…")
            .setCancelable(false)
            .show()

        Thread {
            try {
                val title = meta?.optString("name") ?: movie?.name ?: ""
                val year = meta?.optString("year") ?: (movie?.year ?: "")

                // AIOStreams-style mux: parallel providers -> parse -> dedupe ->
                // batched cache check -> profile filter/sort -> uniform chips
                val config = MuxConfig.load(this)
                val result = MuxEngine.fetch(imdbId, title, year, key, config)

                if (result.streams.isEmpty()) {
                    runOnUiThread {
                        dlg.dismiss()
                        Toast.makeText(this, "No sources found for this movie", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val arr = org.json.JSONArray()
                for (t in result.streams) {
                    arr.put(JSONObject()
                        .put("name", t.attrs.resolutionLabel)
                        .put("title", t.formattedTitle())
                        .put("url", "")
                        .put("hash", t.hash)
                        .put("dn", t.name)
                        .put("cached", t.cached))
                }
                // TorBox's cache is thinner than Real-Debrid's: when NOTHING is
                // instant, offering a one-tap download of the best source beats
                // a wall of identical rows.
                if (result.cachedCount == 0 && result.uncachedCount > 0) {
                    val best1080 = result.streams.indexOfFirst { it.attrs.resolution == 1080 }
                    val best4k = result.streams.indexOfFirst { it.attrs.resolution == 2160 }
                    runOnUiThread {
                        dlg.dismiss()
                        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
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
                    return@Thread
                }

                runOnUiThread {
                    dlg.dismiss()
                    startActivity(Intent(this, SourcePickerActivity::class.java).apply {
                        putExtra("movieTitle", movie?.name)
                        putExtra("videoId", "movie:$imdbId")
                        putExtra("videoTitle", movie?.name)
                        putExtra("streamsJson", arr.toString())
                        putStringArrayListExtra("providers", ArrayList(result.providers))
                    })
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, "Could not play: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchPicker(arr: org.json.JSONArray, imdbId: String, result: MuxEngine.Result) {
        startActivity(Intent(this, SourcePickerActivity::class.java).apply {
            putExtra("movieTitle", movie?.name)
            putExtra("videoId", "movie:$imdbId")
            putExtra("videoTitle", movie?.name)
            putExtra("streamsJson", arr.toString())
            putStringArrayListExtra("providers", ArrayList(result.providers))
        })
    }

    private fun launchPlayer(arr: org.json.JSONArray, startIndex: Int, imdbId: String) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("videoId", "movie:$imdbId")
            putExtra("videoTitle", movie?.name)
            putExtra("streamsJson", arr.toString())
            putExtra("startIndex", startIndex)
        })
    }
}
