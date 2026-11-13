package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Home: billboard + rows.
 *
 * The billboard always describes whatever has focus in the rows (title,
 * meta, synopsis, backdrop crossfade), the way Netflix does it. Rows snap
 * into place as focus moves down and slide back under the billboard when
 * focus returns to the first row.
 */
class SeriesActivity : Activity() {

    private lateinit var backdrop: ImageView
    private lateinit var billboard: View
    private lateinit var bbChip: TextView
    private lateinit var bbLogo: ImageView
    private lateinit var bbTitle: TextView
    private lateinit var bbMeta: TextView
    private lateinit var bbSynopsis: TextView
    private lateinit var bbPrimary: TextView
    private lateinit var bbSecondary: TextView
    private lateinit var rowsScroll: SnapScrollView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var splash: View

    private var catalog: List<Api.Series> = emptyList()
    private var seriesById: Map<String, Api.Series> = emptyMap()

    private lateinit var continueRow: Row
    private lateinit var newRow: Row
    private lateinit var seriesRow: Row

    /** where kept-list rows are inserted, and which ones are already shown */
    private var keptHost = 0
    private val keptRows = LinkedHashMap<String, Row>()

    private val handler = Handler(Looper.getMainLooper())
    private var pendingBackdrop: Runnable? = null
    private var billboardHidden = false

    /** what the billboard is describing */
    private sealed class Spot {
        data class S(val s: Api.Series) : Spot()
        data class M(val m: Api.EnMovie) : Spot()
        data class C(val h: WatchState.HistoryEntry) : Spot()
    }
    private var spot: Spot? = null

    private inner class Row(val view: View, val header: TextView, val list: RecyclerView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)

        backdrop = findViewById(R.id.backdrop)
        billboard = findViewById(R.id.billboard)
        bbChip = findViewById(R.id.bbChip)
        bbLogo = findViewById(R.id.bbLogo)
        bbTitle = findViewById(R.id.bbTitle)
        bbMeta = findViewById(R.id.bbMeta)
        bbSynopsis = findViewById(R.id.bbSynopsis)
        bbPrimary = findViewById(R.id.bbPrimary)
        bbSecondary = findViewById(R.id.bbSecondary)
        rowsScroll = findViewById(R.id.rowsScroll)
        rowsContainer = findViewById(R.id.rowsContainer)
        splash = findViewById(R.id.splash)

        findViewById<View>(R.id.railHome).setOnClickListener { snapTo(0); focusFirstItem() }
        findViewById<View>(R.id.railSpin).setOnClickListener { startActivity(Intent(this, SpinActivity::class.java)) }
        findViewById<View>(R.id.railSearch).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        findViewById<View>(R.id.railSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        bbPrimary.setOnClickListener { onPrimary() }
        bbSecondary.setOnClickListener { onSecondary() }

        // rows in display order; empty ones stay hidden
        continueRow = addRow("Continue Watching")
        newRow = addRow("New this week")
        seriesRow = addRow("Turkish Series")
        keptHost = rowsContainer.childCount   // kept lists are inserted here

        setupSnapping()
        showSplash()
        UpdateChecker.check(this)
        load()
    }

    /**
     * BACK on the home screen used to drop straight out to the TV launcher,
     * which is easy to do by accident when walking back up from the player.
     * Now it asks, and offers Settings as a way to stay in the app.
     */
    override fun onBackPressed() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Leave Wahab TV?")
            .setMessage("You are on the home screen. Exit the app, or stay here?")
            .setPositiveButton("Stay") { d, _ -> d.dismiss() }
            .setNeutralButton("Settings") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Exit") { _, _ -> super.onBackPressed() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (catalog.isNotEmpty()) {
            refreshKeptRows()
            fillContinueRow()
            // progress may have changed while watching
            (spot as? Spot.C)?.let { c ->
                WatchState.history(this).firstOrNull { it.videoId == c.h.videoId }?.let { show(Spot.C(it)) }
            }
            (spot as? Spot.S)?.let { show(it) }
        }
    }

    // ------------------------------------------------------------ rows

    private fun addRow(title: String, at: Int = -1): Row {
        val v = layoutInflater.inflate(R.layout.row_section, rowsContainer, false)
        val header = v.findViewById<TextView>(R.id.rowHeader)
        val list = v.findViewById<RecyclerView>(R.id.rowList)
        header.text = title
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.setHasFixedSize(true)
        list.itemAnimator = null
        v.visibility = View.GONE
        if (at >= 0) rowsContainer.addView(v, at) else rowsContainer.addView(v)
        return Row(v, header, list)
    }

    /**
     * Rows the viewer kept from The Reel. They sit above the stock movie
     * rows, because a list someone chose deserves better placement than one
     * we picked for them.
     */
    private fun refreshKeptRows() {
        val kept = KeptLists.all(this)
        // drop rows that were un-kept while we were away
        keptRows.keys.toList().filter { id -> kept.none { it.first == id } }.forEach { id ->
            keptRows.remove(id)?.let { rowsContainer.removeView(it.view) }
        }
        kept.forEachIndexed { i, (id, title) ->
            if (keptRows.containsKey(id)) return@forEachIndexed
            val row = addRow(title, keptHost + i)
            keptRows[id] = row
            row.header.setOnClickListener {
                startActivity(Intent(this, SpinActivity::class.java).putExtra("listId", id))
            }
            Thread {
                val films = try { Api.movieList(id).films } catch (e: Exception) { emptyList() }
                runOnUiThread {
                    if (isFinishing || films.isEmpty()) return@runOnUiThread
                    row.list.adapter = SuggestionAdapter(films)
                    row.view.visibility = View.VISIBLE
                }
            }.start()
        }
    }

    /** films from a kept list: TMDB ids, resolved to IMDb only when opened */
    private inner class SuggestionAdapter(private val items: List<Api.Suggestion>) :
        RecyclerView.Adapter<SuggestionAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            Ui.loadImage(holder.poster, s.poster, 4f, R.drawable.placeholder_poster)
            holder.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start()
                v.translationZ = if (f) 14f else 0f
                if (f) show(Spot.M(Api.EnMovie(
                    id = "", name = s.name, poster = s.poster, year = s.year,
                    background = s.background, description = s.description, rating = s.rating)))
            }
            holder.itemView.setOnClickListener { openSuggestion(s) }
        }
    }

    private fun openSuggestion(s: Api.Suggestion) {
        Thread {
            val imdbId = try { Api.resolveSuggestion(s.tmdbId) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (imdbId == null) {
                    Toast.makeText(this, "${s.name} has no match in the movie database", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                startActivity(Intent(this, MovieDetailActivity::class.java).apply {
                    putExtra("imdbId", imdbId)
                    putExtra("name", s.name)
                    putExtra("poster", s.poster)
                    putExtra("year", s.year)
                    putExtra("background", s.background)
                })
            }
        }.start()
    }

    /**
     * One-time introduction to The Reel. Shown once, after the splash, so it
     * does not land on top of a cold start.
     */
    private fun maybeShowWizard() {
        if (KeptLists.wizardSeen(this)) return
        KeptLists.markWizardSeen(this)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("New: The Reel")
            .setMessage(
                "Looking for something different to watch?\n\n" +
                "Press ✸ in the left bar and the reel spins through sixty curated lists " +
                "before landing on one: heists of the seventies, films caught in a time loop, " +
                "Turkish cinema, the class of 1999, hidden gems almost nobody has rated.\n\n" +
                "Spin again if it is not for tonight, or keep a list and it becomes a permanent " +
                "row on this screen.")
            .setPositiveButton("Spin now") { _, _ ->
                startActivity(Intent(this, SpinActivity::class.java))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun load() {
        Thread {
            try {
                val series = Api.catalog()
                runOnUiThread {
                    catalog = series
                    seriesById = series.associateBy { it.id }
                    refreshKeptRows()
                    fillContinueRow()
                    fillNewRow()
                    seriesRow.list.adapter = SeriesAdapter(series, false)
                    seriesRow.view.visibility = View.VISIBLE
                    initialBillboard()
                    // the splash's end action focuses the first item if it is still up
                    if (splash.visibility != View.VISIBLE) rowsContainer.post { focusFirstItem() }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load series: ${e.message}", Toast.LENGTH_LONG).show()
                    fadeSplash()
                }
            }
            loadMovieRows()
        }.start()
    }

    private fun fillContinueRow() {
        val entries = WatchState.history(this).filter { !it.isFinished || it.isMovie }.take(10)
        continueRow.list.adapter = ContinueAdapter(entries)
        continueRow.view.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun fillNewRow() {
        val fresh = catalog.filter { it.airedWithinDays(Ui.NEW_WINDOW_DAYS) }
            .sortedByDescending { it.lastAired ?: "" }
        newRow.list.adapter = SeriesAdapter(fresh, true)
        newRow.view.visibility = if (fresh.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Movie rows arrive one by one as the addon answers; each becomes a row.
     *
     * "Just added" is built from upload timestamps, so everything in it can
     * actually be played tonight. "New releases" is Cinemeta's New catalog,
     * which is recognisable titles but may run ahead of what is downloadable.
     * Genre rows are filtered server-side, because Cinemeta's own genre
     * filter let a fifth of the row through wrongly.
     */
    private fun loadMovieRows() {
        data class Spec(val title: String, val fetch: () -> List<Api.EnMovie>)
        fun genre(g: String) = Spec(g) { Api.enCatalog("top", 0, g) }

        val wanted = listOf(
            Spec("Just added") { Api.enLatestAdded() },
            Spec("New releases") { Api.enCatalog("year", 0) },
            Spec("Popular Movies") { Api.enCatalog("top", 0) },
            Spec("Featured") { Api.enCatalog("imdbRating", 0) },
            Spec("Highly rated this year") { Api.enCatalog("imdbRating", 0, "Drama") },
            genre("Action"), genre("Adventure"), genre("Comedy"), genre("Drama"),
            genre("Sci-Fi"), genre("Thriller"), genre("Horror"), genre("Crime"),
            genre("Mystery"), genre("Romance"), genre("Animation"), genre("Family"),
            genre("Fantasy"), genre("Documentary"), genre("Biography"),
            genre("History"), genre("War"), genre("Western"), genre("Music"), genre("Sport")
        )

        // Placeholders first so the rows keep their order no matter which
        // request finishes first, then fill them from a small thread pool -
        // 25 sequential round trips made the lower rows take far too long.
        val slots = ArrayList<Row>(wanted.size)
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            wanted.forEach { slots.add(addRow(it.title)) }
            latch.countDown()
        }
        latch.await()

        val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
        wanted.forEachIndexed { i, spec ->
            pool.execute {
                val movies = try { spec.fetch() } catch (e: Exception) { emptyList() }
                if (movies.isEmpty()) return@execute
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    slots[i].list.adapter = MovieAdapter(movies)
                    slots[i].view.visibility = View.VISIBLE
                }
            }
        }
        pool.shutdown()
    }

    private fun focusFirstItem(attempt: Int = 0) {
        val row = listOf(continueRow, newRow, seriesRow).firstOrNull { it.view.visibility == View.VISIBLE } ?: return
        row.list.post {
            val child = row.list.getChildAt(0)
            if (child != null) child.requestFocus()
            else if (attempt < 5) focusFirstItem(attempt + 1)
        }
    }

    // ------------------------------------------------------------ snapping

    private fun setupSnapping() {
        val root = findViewById<View>(R.id.splash).parent as View
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null) return@addOnGlobalFocusChangeListener
            val row = rowOf(newFocus)
            if (row != null) {
                val idx = rowsContainer.indexOfChild(row)
                val firstVisible = (0 until rowsContainer.childCount).firstOrNull { rowsContainer.getChildAt(it).visibility == View.VISIBLE }
                if (idx == firstVisible) snapTo(0)
                else snapTo(rowsScroll.paddingTop + row.top - Ui.dp(this, 30))
            } else if (isInside(newFocus, billboard) || isInside(newFocus, findViewById(R.id.rail))) {
                snapTo(0)
            }
        }
    }

    private fun rowOf(view: View): View? {
        var v: View? = view
        while (v != null) {
            val p = v.parent as? View ?: return null
            if (p === rowsContainer) return v
            v = p
        }
        return null
    }

    private fun isInside(view: View?, container: View): Boolean {
        var v = view
        while (v != null) {
            if (v === container) return true
            v = v.parent as? View
        }
        return false
    }

    private fun snapTo(y: Int) {
        if (rowsScroll.scrollY != y) rowsScroll.smoothScrollTo(0, y)
        val hide = y > 0
        if (hide != billboardHidden) {
            billboardHidden = hide
            billboard.animate().alpha(if (hide) 0f else 1f).setDuration(220).start()
        }
    }

    // ------------------------------------------------------------ billboard

    private fun initialBillboard() {
        val last = WatchState.history(this).firstOrNull { !it.isFinished || it.isMovie }
        when {
            last != null -> show(Spot.C(last))
            catalog.isNotEmpty() -> show(Spot.S(catalog.maxByOrNull { it.lastAired ?: "" } ?: catalog[0]))
        }
    }

    private fun show(s: Spot) {
        spot = s
        var chip: String? = null
        var chipNew = true
        var logo: String? = null
        val title: String
        val meta: String
        val synopsis: String?
        val primary: String
        val secondary: String
        val bg: String?

        when (s) {
            is Spot.S -> {
                val x = s.s
                title = x.name
                logo = x.logo
                bg = x.background ?: x.poster
                synopsis = x.description
                meta = listOfNotNull(
                    x.year,
                    x.seasonCount.takeIf { it > 0 }?.let { "$it season${if (it > 1) "s" else ""}" },
                    x.episodeCount.takeIf { it > 0 }?.let { "$it episodes" },
                    x.genres.take(2).joinToString(", ").takeIf { it.isNotEmpty() }
                ).joinToString("  ·  ")
                if (x.airedWithinDays(Ui.NEW_WINDOW_DAYS)) chip = "NEW EPISODE  ·  ${Ui.airedLabel(x.lastAired)?.uppercase()}"
                else if (x.nextAir != null && (Ui.daysSince(x.nextAir) ?: 1) < 0) { chip = "NEXT EPISODE  ·  ${x.nextAir}"; chipNew = false }
                val last = WatchState.lastForSeries(this, x.id)
                primary = when {
                    last == null -> "▶  Start watching"
                    last.isFinished -> "▶  Play next episode"
                    else -> "▶  Resume  ·  S${last.season} E${last.episode}"
                }
                secondary = "ⓘ  Episodes"
            }
            is Spot.C -> {
                val h = s.h
                val series = seriesById[h.seriesId]
                title = h.seriesName
                logo = series?.logo
                bg = h.backdrop ?: series?.background ?: h.thumb ?: h.poster
                synopsis = series?.description
                meta = if (h.isMovie) listOfNotNull("Movie", "${h.minutesLeft} min left".takeIf { h.durationMs > 0 }).joinToString("  ·  ")
                else listOfNotNull("Season ${h.season}  ·  Episode ${h.episode}",
                    "${h.minutesLeft} min left".takeIf { h.durationMs > 0 && !h.isFinished },
                    "${h.progressPct}% watched".takeIf { h.durationMs > 0 }).joinToString("  ·  ")
                chip = "CONTINUE WATCHING"; chipNew = false
                primary = if (h.isMovie && h.isFinished) "▶  Play again" else "▶  Resume"
                secondary = if (h.isMovie) "ⓘ  Details" else "ⓘ  Episodes"
            }
            is Spot.M -> {
                val m = s.m
                title = m.name
                bg = m.background ?: m.poster
                synopsis = m.description
                meta = listOfNotNull(m.year, m.runtime, m.rating?.let { "★ $it" },
                    m.genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() }).joinToString("  ·  ")
                primary = "▶  Play"
                secondary = "ⓘ  Details"
            }
        }

        if (chip != null) {
            bbChip.text = chip
            bbChip.setBackgroundResource(if (chipNew) R.drawable.chip_new else R.drawable.chip_gold)
            bbChip.setTextColor(resources.getColor(if (chipNew) R.color.white else R.color.accent_dark))
            bbChip.visibility = View.VISIBLE
        } else bbChip.visibility = View.GONE

        if (logo != null) {
            bbLogo.visibility = View.VISIBLE
            bbTitle.visibility = View.GONE
            Ui.loadImage(bbLogo, logo, 0f, android.R.color.transparent, 150)
        } else {
            bbLogo.visibility = View.GONE
            bbTitle.visibility = View.VISIBLE
            bbTitle.text = title
        }
        bbMeta.text = meta
        bbMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        bbSynopsis.text = synopsis ?: ""
        bbPrimary.text = primary
        bbSecondary.text = secondary

        // debounce the backdrop so fast scrolling doesn't thrash the network
        pendingBackdrop?.let { handler.removeCallbacks(it) }
        val r = Runnable { Ui.loadBackdrop(backdrop, bg) }
        pendingBackdrop = r
        handler.postDelayed(r, 160)
    }

    private fun onPrimary() {
        when (val s = spot) {
            is Spot.S -> {
                val last = WatchState.lastForSeries(this, s.s.id)
                if (last != null && !last.isFinished) playHistory(last) else openSeries(s.s)
            }
            is Spot.C -> if (s.h.isMovie) openMovieFromHistory(s.h, autoplay = true) else playHistory(s.h)
            is Spot.M -> openMovie(s.m, autoplay = true)
            null -> {}
        }
    }

    private fun onSecondary() {
        when (val s = spot) {
            is Spot.S -> openSeries(s.s)
            is Spot.C -> if (s.h.isMovie) openMovieFromHistory(s.h, autoplay = false)
                else openSeries(seriesById[s.h.seriesId] ?: Api.Series(s.h.seriesId, s.h.seriesName, s.h.poster, null))
            is Spot.M -> openMovie(s.m, autoplay = false)
            null -> {}
        }
    }

    // ------------------------------------------------------------ navigation

    private fun openSeries(s: Api.Series) {
        startActivity(Intent(this, EpisodesActivity::class.java).apply {
            putExtra("seriesId", s.id)
            putExtra("seriesName", s.name)
            putExtra("seriesPoster", s.poster)
            putExtra("seriesBackdrop", s.background)
            putExtra("seriesLogo", s.logo)
        })
    }

    private fun playHistory(e: WatchState.HistoryEntry) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("videoId", e.videoId)
            putExtra("videoTitle", "${e.seriesName} · S${e.season}E${e.episode}")
            putExtra("seriesId", e.seriesId)
            putExtra("seriesName", e.seriesName)
            putExtra("seriesPoster", e.poster)
            putExtra("seriesBackdrop", e.backdrop ?: seriesById[e.seriesId]?.background)
            putExtra("season", e.season)
            putExtra("episode", e.episode)
        })
    }

    private fun openMovie(m: Api.EnMovie, autoplay: Boolean) {
        startActivity(Intent(this, MovieDetailActivity::class.java).apply {
            putExtra("imdbId", m.id)
            putExtra("name", m.name)
            putExtra("poster", m.poster)
            putExtra("year", m.year)
            putExtra("background", m.background)
            putExtra("autoplay", autoplay)
        })
    }

    private fun openMovieFromHistory(h: WatchState.HistoryEntry, autoplay: Boolean) {
        startActivity(Intent(this, MovieDetailActivity::class.java).apply {
            putExtra("imdbId", h.videoId.removePrefix("movie:"))
            putExtra("name", h.seriesName)
            putExtra("poster", h.poster)
            putExtra("background", h.backdrop)
            putExtra("autoplay", autoplay)
        })
    }

    // ------------------------------------------------------------ splash

    private fun showSplash() {
        val img = findViewById<View>(R.id.splashImg)
        val caption = findViewById<View>(R.id.splashText)
        splash.visibility = View.VISIBLE
        splash.alpha = 1f
        img.animate().alpha(1f).setDuration(600).start()
        img.postDelayed({
            img.animate().scaleX(1.09f).scaleY(1.09f).setDuration(4600)
                .setInterpolator(DecelerateInterpolator()).start()
        }, 250)
        caption.translationY = -26f
        caption.postDelayed({
            caption.animate().alpha(1f).translationY(0f).setDuration(500)
                .setInterpolator(DecelerateInterpolator()).start()
        }, 950)
        splash.postDelayed({ fadeSplash() }, 3400)
        splash.setOnClickListener { fadeSplash() }
    }

    private fun fadeSplash() {
        if (splash.visibility != View.VISIBLE) return
        splash.animate().alpha(0f).setDuration(700).withEndAction {
            splash.visibility = View.GONE
            focusFirstItem()
            maybeShowWizard()
        }.start()
    }

    // ------------------------------------------------------------ adapters

    private inner class SeriesAdapter(private val items: List<Api.Series>, private val showNew: Boolean) :
        RecyclerView.Adapter<SeriesAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val chip: TextView = v.findViewById(R.id.chip)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            Ui.loadImage(holder.poster, s.poster, 4f, R.drawable.placeholder_poster)
            holder.chip.visibility = if (showNew || s.airedWithinDays(Ui.NEW_WINDOW_DAYS)) View.VISIBLE else View.GONE
            holder.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start()
                v.translationZ = if (f) 14f else 0f
                if (f) show(Spot.S(s))
            }
            holder.itemView.setOnClickListener { openSeries(s) }
        }
    }

    private inner class MovieAdapter(private val items: List<Api.EnMovie>) :
        RecyclerView.Adapter<MovieAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            Ui.loadImage(holder.poster, m.poster, 4f, R.drawable.placeholder_poster)
            holder.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start()
                v.translationZ = if (f) 14f else 0f
                if (f) show(Spot.M(m))
            }
            holder.itemView.setOnClickListener { openMovie(m, autoplay = false) }
        }
    }

    private inner class ContinueAdapter(private val items: List<WatchState.HistoryEntry>) :
        RecyclerView.Adapter<ContinueAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val still: ImageView = v.findViewById(R.id.still)
            val label: TextView = v.findViewById(R.id.label)
            val sub: TextView = v.findViewById(R.id.sub)
            val progress: ProgressBar = v.findViewById(R.id.progress)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_still, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            val series = seriesById[e.seriesId]
            Ui.loadImage(holder.still, e.thumb ?: e.backdrop ?: series?.background ?: e.poster, 4f)
            holder.label.text = e.seriesName
            holder.sub.text = if (e.isMovie) (if (e.durationMs > 0) "${e.minutesLeft} min left" else "Movie")
                else "S${e.season} E${e.episode}" + (if (e.durationMs > 0) "  ·  ${e.minutesLeft} min left" else "")
            holder.progress.progress = e.progressPct
            holder.progress.visibility = if (e.durationMs > 0) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start()
                v.translationZ = if (f) 14f else 0f
                if (f) show(Spot.C(e))
            }
            holder.itemView.setOnClickListener {
                if (e.isMovie) openMovieFromHistory(e, autoplay = true) else playHistory(e)
            }
        }
    }
}
