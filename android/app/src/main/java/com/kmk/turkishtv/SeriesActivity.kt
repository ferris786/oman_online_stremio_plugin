package com.kmk.turkishtv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import coil.request.ImageRequest

class SeriesActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var continueRow: RecyclerView
    private lateinit var continueHeader: TextView
    private lateinit var continueBar: TextView
    private lateinit var splash: View
    private var continueCollapsed = false

    private lateinit var turkishPane: View
    private lateinit var englishPane: View
    private lateinit var tabTurkish: TextView
    private lateinit var tabEnglish: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)

        grid = findViewById(R.id.grid)
        continueRow = findViewById(R.id.continueRow)
        continueHeader = findViewById(R.id.continueHeader)
        continueBar = findViewById(R.id.continueBar)
        splash = findViewById(R.id.splash)

        turkishPane = findViewById(R.id.turkishPane)
        englishPane = findViewById(R.id.englishPane)
        tabTurkish = findViewById(R.id.tabTurkish)
        tabEnglish = findViewById(R.id.tabEnglish)
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        tabTurkish.setOnClickListener { switchPane(true) }
        tabEnglish.setOnClickListener { switchPane(false) }
        findViewById<View>(R.id.enSearchBtn).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        // English movies grid (kept fully separate from Turkish)
        val enGrid = findViewById<RecyclerView>(R.id.enGrid)
        enGrid.layoutManager = GridLayoutManager(this, 6)

        grid.layoutManager = GridLayoutManager(this, 5)
        continueRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        showSplash()
        loadCatalog()
        showHistory()
        setupCollapseBehaviour()
        UpdateChecker.check(this)
        loadEnglishMovies(enGrid)
    }

    private fun switchPane(turkish: Boolean) {
        turkishPane.visibility = if (turkish) View.VISIBLE else View.GONE
        englishPane.visibility = if (turkish) View.GONE else View.VISIBLE
        tabTurkish.setTextColor(resources.getColor(if (turkish) R.color.accent else R.color.text_dim))
        tabEnglish.setTextColor(resources.getColor(if (turkish) R.color.text_dim else R.color.accent))
    }

    private fun loadEnglishMovies(enGrid: RecyclerView) {
        Thread {
            try {
                // first two catalog pages for a decent initial browse
                val movies = Api.enCatalog("top", 0) + Api.enCatalog("top", 100)
                runOnUiThread { enGrid.adapter = MovieGridAdapter(movies) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load movies: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private inner class MovieGridAdapter(private val items: List<Api.EnMovie>) :
        RecyclerView.Adapter<MovieGridAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val poster: android.widget.ImageView = v.findViewById(R.id.poster)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_series, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            holder.name.text = if (m.year != null) "${m.name} (${m.year})" else m.name
            loadPoster(holder.poster, m.poster)
            applyFocusFx(holder.itemView)
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@SeriesActivity, MovieDetailActivity::class.java).apply {
                    putExtra("imdbId", m.id)
                    putExtra("name", m.name)
                    putExtra("poster", m.poster)
                    putExtra("year", m.year)
                })
            }
        }
    }

    /**
     * Netflix-style: the Continue Watching row collapses to a slim bar as soon
     * as focus enters the series grid, and expands again when the bar is
     * focused. One UP press from the grid brings the bar into reach.
     */
    private fun setupCollapseBehaviour() {
        val root = findViewById<View>(R.id.splash).parent as View
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val inGrid = isInside(newFocus, grid)
            val atBar = newFocus === continueBar || isInside(newFocus, continueRow)
            if (inGrid && !continueCollapsed) collapseContinue()
            if (atBar && continueCollapsed) expandContinue()
        }
        continueBar.setOnClickListener { expandContinue() }
    }

    private fun isInside(view: View?, container: View): Boolean {
        var v = view
        while (v != null) {
            if (v === container) return true
            if (v.parent is View) v = v.parent as View else return false
        }
        return false
    }

    private fun collapseContinue() {
        continueCollapsed = true
        continueHeader.visibility = View.GONE
        continueRow.visibility = View.GONE
        continueBar.visibility = View.VISIBLE
    }

    private fun expandContinue() {
        continueCollapsed = false
        continueHeader.visibility = View.VISIBLE
        continueRow.visibility = View.VISIBLE
        continueBar.visibility = View.GONE
        if (continueRow.childCount > 0) continueRow.getChildAt(0).requestFocus()
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
        continueRow.adapter = ContinueAdapter(entries.take(6))
        if (!continueCollapsed && entries.isNotEmpty()) {
            continueHeader.visibility = View.VISIBLE
            continueRow.visibility = View.VISIBLE
            continueBar.visibility = View.GONE
        }
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
