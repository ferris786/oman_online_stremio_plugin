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
                if (result.cachedCount == 0) {
                    runOnUiThread {
                        dlg.dismiss()
                        Toast.makeText(this,
                            "Nothing cached yet - ${result.uncachedCount} downloadable (⏳) sources listed",
                            Toast.LENGTH_LONG).show()
                    }
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
                runOnUiThread {
                    dlg.dismiss()
                    startActivity(Intent(this, SourcePickerActivity::class.java).apply {
                        putExtra("movieTitle", movie?.name)
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
