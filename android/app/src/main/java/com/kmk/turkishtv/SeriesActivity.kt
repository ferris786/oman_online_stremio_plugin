package com.kmk.turkishtv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import coil.request.ImageRequest

class SeriesActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var continueRow: RecyclerView
    private lateinit var continueHeader: TextView
    private lateinit var splash: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)

        grid = findViewById(R.id.grid)
        continueRow = findViewById(R.id.continueRow)
        continueHeader = findViewById(R.id.continueHeader)
        splash = findViewById(R.id.splash)

        grid.layoutManager = GridLayoutManager(this, 5)
        continueRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        showSplash()
        loadCatalog()
        showHistory()
    }

    private fun showSplash() {
        val img = findViewById<View>(R.id.splashImg)
        val caption = findViewById<View>(R.id.splashText)

        splash.visibility = View.VISIBLE
        splash.alpha = 1f

        // artwork fades in, then slow cinematic zoom (Ken Burns)
        img.animate().alpha(1f).setDuration(600).start()
        img.postDelayed({
            img.animate()
                .scaleX(1.09f).scaleY(1.09f)
                .setDuration(4600)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }, 250)

        // caption slides into the open sky a beat later
        caption.translationY = -26f
        caption.postDelayed({
            caption.animate()
                .alpha(1f).translationY(0f)
                .setDuration(500)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }, 950)

        splash.postDelayed({ fadeSplash() }, 3700)
        splash.setOnClickListener { fadeSplash() }
    }

    private fun fadeSplash() {
        if (splash.visibility != View.VISIBLE) return
        splash.animate().alpha(0f).setDuration(600).withEndAction {
            splash.visibility = View.GONE
        }.start()
    }

    private fun showHistory() {
        val entries = WatchState.history(this)
        if (entries.isEmpty()) return
        continueHeader.visibility = View.VISIBLE
        continueRow.visibility = View.VISIBLE
        continueRow.adapter = ContinueAdapter(entries)
    }

    override fun onResume() {
        super.onResume()
        showHistory() // refresh progress after coming back from the player
    }

    private fun loadCatalog() {
        Thread {
            try {
                val series = Api.catalog()
                runOnUiThread {
                    grid.adapter = SeriesAdapter(series)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load series: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Shared focus treatment: scale up + the accent-border selector background */
    private fun applyFocusFx(view: View) {
        view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) 1.06f else 1f
            val sx = ObjectAnimator.ofFloat(view, "scaleX", scale)
            val sy = ObjectAnimator.ofFloat(view, "scaleY", scale)
            AnimatorSet().apply {
                playTogether(sx, sy)
                duration = 140
                interpolator = DecelerateInterpolator()
                start()
            }
            view.translationZ = if (hasFocus) 12f else 0f
        }
    }

    private fun loadPoster(target: ImageView, url: String?) {
        val req = ImageRequest.Builder(this)
            .data(url)
            .placeholder(R.drawable.placeholder_poster)
            .error(R.drawable.placeholder_poster)
            .crossfade(true)
            .target(target)
            .build()
        Coil.imageLoader(this).enqueue(req)
    }

    // ---------------- Continue Watching ----------------

    private inner class ContinueAdapter(private val items: List<WatchState.HistoryEntry>) :
        RecyclerView.Adapter<ContinueAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.cPoster)
            val title: TextView = v.findViewById(R.id.cTitle)
            val sub: TextView = v.findViewById(R.id.cSub)
            val progress: android.widget.ProgressBar = v.findViewById(R.id.cProgress)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_continue, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            loadPoster(holder.poster, e.poster)
            holder.title.text = e.seriesName
            val pct = if (e.durationMs > 0)
                ((e.positionMs * 100) / e.durationMs).toInt().coerceIn(0, 100) else 0
            val remaining = if (e.durationMs > e.positionMs)
                "${(e.durationMs - e.positionMs) / 60000} min left" else ""
            holder.sub.text = "S${e.season}E${e.episode} · $remaining"
            holder.progress.progress = pct
            applyFocusFx(holder.itemView)
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@SeriesActivity, PlayerActivity::class.java).apply {
                    putExtra("videoId", e.videoId)
                    putExtra("videoTitle", "${e.seriesName} · S${e.season}E${e.episode}")
                    putExtra("seriesId", e.seriesId)
                    putExtra("seriesName", e.seriesName)
                    putExtra("season", e.season)
                    putExtra("episode", e.episode)
                })
            }
        }
    }

    // ---------------- Series grid ----------------

    private inner class SeriesAdapter(private val items: List<Api.Series>) :
        RecyclerView.Adapter<SeriesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val poster: ImageView = v.findViewById(R.id.poster)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            return VH(layoutInflater.inflate(R.layout.item_series, parent, false))
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.name.text = s.name
            loadPoster(holder.poster, s.poster)
            applyFocusFx(holder.itemView)
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@SeriesActivity, EpisodesActivity::class.java).apply {
                    putExtra("seriesId", s.id)
                    putExtra("seriesName", s.name)
                    putExtra("seriesPoster", s.poster)
                })
            }
        }
    }
}
