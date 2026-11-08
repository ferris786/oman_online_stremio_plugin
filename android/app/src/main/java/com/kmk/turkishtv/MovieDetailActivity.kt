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
                val title = meta?.optString("name") ?: movie?.name ?: ""
                val year = meta?.optString("year") ?: (movie?.year ?: "")

                // Parallel discovery: YTS (exact IMDb), TPB from the device,
                // and the addon's merged list - all at once. Lazy model: we
                // list sources after ONE batched cached-check and only
                // resolve the link for the source actually chosen (Comet-style).
                var yts: List<Api.Torrent> = emptyList()
                var tpb: List<Api.Torrent> = emptyList()
                var addon: List<Api.Torrent> = emptyList()
                val t1 = Thread { yts = Api.ytsByImdb(imdbId) }
                val t2 = Thread { if (title.isNotEmpty()) tpb = Api.apibaySearch(title, year) }
                val t3 = Thread { addon = try { Api.enTorrents(imdbId) } catch (e: Exception) { emptyList() } }
                t1.start(); t2.start(); t3.start(); t1.join(); t2.join(); t3.join()

                val candidates = (yts + tpb + addon)
                    .distinctBy { it.hash }
                    .sortedByDescending { t ->
                        minOf(t.seeders, 200) +
                        (if (t.quality.startsWith("1080p")) 120 else if (t.quality.startsWith("720p")) 60 else 0) +
                        (if (t.sizeBytes in 1500000000L..9000000000L) 150 else 0)
                    }
                if (candidates.isEmpty()) throw Exception("no torrent sources found for this movie")

                // one batched availability check -> instant chip list
                val cached = Torbox.cachedHashes(key, candidates.map { it.hash })
                val playable = candidates.filter { cached.contains(it.hash.lowercase()) }
                if (playable.isEmpty()) {
                    runOnUiThread {
                        dlg.dismiss()
                        Toast.makeText(this,
                            "None of the ${candidates.size} sources are cached on TorBox yet",
                            Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                runOnUiThread {
                    dlg.dismiss()
                    val arr = org.json.JSONArray()
                    for (t in playable.take(8)) {
                        val gb = t.sizeBytes / 1e9
                        arr.put(JSONObject()
                            .put("name", t.quality)
                            .put("title", "${t.quality} · ${String.format("%.1f", gb)}GB · ${t.seeders} seeds")
                            .put("url", "")
                            .put("hash", t.hash)
                            .put("dn", t.name))
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
