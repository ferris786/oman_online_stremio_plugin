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
import org.json.JSONObject

/**
 * Movie detail + play flow:
 * candidates (addon) -> TorBox availability (in-app key) -> resolve cached
 * ones to direct links -> PlayerActivity with pre-resolved streams.
 */
class MovieDetailActivity : Activity() {

    private var movie: Api.EnMovie? = null
    private var meta: JSONObject? = null

    // streams built for the player: name/title/url like Api.Stream
    private data class PlayStream(val name: String, val title: String, val url: String)

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
                val candidates = Api.enTorrents(imdbId)
                if (candidates.isEmpty()) throw Exception("no torrents found for this movie")

                val cached = Torbox.cachedHashes(key, candidates.map { it.hash })
                val playable = candidates.filter { cached.contains(it.hash.lowercase()) }
                if (playable.isEmpty()) {
                    runOnUiThread {
                        dlg.dismiss()
                        Toast.makeText(this,
                            "Not cached on TorBox yet - try again later or another title",
                            Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                // resolve the best (up to 4) to direct links
                val streams = ArrayList<PlayStream>()
                for (t in playable.take(4)) {
                    val url = Torbox.resolveStream(key, t.hash, t.name)
                    if (url != null) {
                        val gb = t.sizeBytes / 1e9
                        streams.add(PlayStream(
                            name = "${t.quality}",
                            title = "${t.quality} · ${String.format("%.1f", gb)}GB · ${t.seeders} seeds",
                            url = url))
                    }
                }
                if (streams.isEmpty()) throw Exception("could not resolve any link")

                runOnUiThread {
                    dlg.dismiss()
                    val arr = org.json.JSONArray()
                    streams.forEach {
                        arr.put(JSONObject().put("name", it.name).put("title", it.title).put("url", it.url))
                    }
                    startActivity(Intent(this, PlayerActivity::class.java).apply {
                        putExtra("videoId", "movie:$imdbId")
                        putExtra("videoTitle", movie?.name)
                        putExtra("streamsJson", arr.toString())
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
}
