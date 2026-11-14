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

    /**
     * The app is split into sections so one screen is not thirty-five rows
     * long. Home is personal (My List, Continue Watching, recommendations);
     * the other three are whole libraries.
     */
    enum class Section(val label: String) {
        HOME("Home"), TURKISH("Turkish"), TV("TV"), MOVIES("Movies"), COLLECTIONS("Collections")
    }
    private var section = Section.HOME
    private var sectionLoadId = 0

    private var myListRow: Row? = null
    private var continueRow: Row? = null
    private var becauseRow: Row? = null
    private var newRow: Row? = null
    private var seriesRow: Row? = null
    private var becauseFor: String? = null

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
        data class E(val m: Api.EnMovie) : Spot()      // English series
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

        findViewById<View>(R.id.railHome).setOnClickListener { goTo(Section.HOME) }
        findViewById<View>(R.id.railTurkish).setOnClickListener { goTo(Section.TURKISH) }
        findViewById<View>(R.id.railTv).setOnClickListener { goTo(Section.TV) }
        findViewById<View>(R.id.railMovies).setOnClickListener { goTo(Section.MOVIES) }
        findViewById<View>(R.id.railCollections).setOnClickListener { goTo(Section.COLLECTIONS) }
        findViewById<View>(R.id.railSpin).setOnClickListener { startActivity(Intent(this, SpinActivity::class.java)) }
        findViewById<View>(R.id.railSearch).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        findViewById<View>(R.id.railSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        bbPrimary.setOnClickListener { onPrimary() }
        bbSecondary.setOnClickListener { onSecondary() }

        setupRail()
        setupSnapping()
        showSplash()
        UpdateChecker.check(this)
        load()
    }

    // ------------------------------------------------------------ rail

    private val railItems by lazy {
        listOf(R.id.railHome, R.id.railTurkish, R.id.railTv, R.id.railMovies, R.id.railCollections,
               R.id.railSpin, R.id.railSearch, R.id.railSettings)
    }
    private val railLabels by lazy {
        listOf(R.id.railHomeLabel, R.id.railTurkishLabel, R.id.railTvLabel, R.id.railMoviesLabel,
               R.id.railCollectionsLabel, R.id.railSpinLabel, R.id.railSearchLabel, R.id.railSettingsLabel)
    }

    /** labels appear only while the rail has focus, so it reads as icons at rest */
    private fun setupRail() {
        val show = { on: Boolean ->
            railLabels.forEach { findViewById<TextView>(it).visibility = if (on) View.VISIBLE else View.GONE }
        }
        railItems.forEach { id ->
            findViewById<View>(id).setOnFocusChangeListener { _, _ ->
                findViewById<View>(R.id.rail).post {
                    show(railItems.any { findViewById<View>(it).hasFocus() })
                }
            }
        }
        paintRail()
    }

    private fun paintRail() {
        val selected = when (section) {
            Section.HOME -> R.id.railHome
            Section.TURKISH -> R.id.railTurkish
            Section.TV -> R.id.railTv
            Section.MOVIES -> R.id.railMovies
            Section.COLLECTIONS -> R.id.railCollections
        }
        railItems.forEach { findViewById<View>(it).isSelected = it == selected }
    }

    // ------------------------------------------------------------ sections

    private fun goTo(s: Section) {
        if (s == section && rowsContainer.childCount > 0) { snapTo(0); focusFirstItem(); return }
        section = s
        paintRail()
        buildSection()
    }

    /** tear the rows down and rebuild them for the current section */
    private fun buildSection() {
        val id = ++sectionLoadId
        rowsContainer.removeAllViews()
        keptRows.clear()
        lastFocusedRow = null
        lastSnapY = Int.MIN_VALUE
        myListRow = null; continueRow = null; becauseRow = null; newRow = null; seriesRow = null
        becauseFor = null
        snapTo(0)

        when (section) {
            Section.HOME -> {
                myListRow = addRow("My List")
                continueRow = addRow("Continue Watching")
                becauseRow = addRow("Because you watched")
                newRow = addRow("New this week")
                keptHost = rowsContainer.childCount
                fillMyListRow(); fillContinueRow(); fillBecauseRow(); fillNewRow()
                // a taster from each library, so Home is still a place to browse
                seriesRow = addRow("Turkish Series")
                seriesRow?.let {
                    it.list.adapter = SeriesAdapter(catalog, false)
                    it.view.visibility = if (catalog.isEmpty()) View.GONE else View.VISIBLE
                }
                asyncRows(id, listOf(
                    RowSpec("Popular Series", tv = true) { Api.enSeriesCatalog("top", 0) },
                    RowSpec("Popular Movies") { Api.enCatalog("top", 0) }))
            }
            Section.TURKISH -> {
                newRow = addRow("New this week")
                seriesRow = addRow("All Turkish Series")
                keptHost = rowsContainer.childCount
                fillNewRow()
                seriesRow?.let {
                    it.list.adapter = SeriesAdapter(catalog, false)
                    it.view.visibility = if (catalog.isEmpty()) View.GONE else View.VISIBLE
                }
                // genre rows cost nothing: the catalogue already carries genres
                val genres = catalog.flatMap { it.genres }.distinct()
                    .map { g -> g to catalog.filter { it.genres.contains(g) } }
                    .filter { it.second.size >= 3 }
                    .sortedByDescending { it.second.size }
                for ((g, items) in genres) {
                    val row = addRow(g)
                    row.list.adapter = SeriesAdapter(items, false)
                    row.view.visibility = View.VISIBLE
                }
                focusFirstItemSoon()
            }
            Section.TV -> {
                keptHost = rowsContainer.childCount
                asyncRows(id, listOf(
                    RowSpec("Popular Series", tv = true) { Api.enSeriesCatalog("top", 0) },
                    RowSpec("New Series", tv = true) { Api.enSeriesCatalog("year", 0) },
                    RowSpec("Featured Series", tv = true) { Api.enSeriesCatalog("imdbRating", 0) }) +
                    listOf("Drama", "Comedy", "Crime", "Sci-Fi", "Thriller", "Mystery",
                           "Animation", "Documentary", "Action", "Fantasy", "Horror", "Family")
                        .map { g -> RowSpec(g, tv = true) { Api.enSeriesCatalog("top", 0, g) } })
            }
            Section.COLLECTIONS -> {
                keptHost = rowsContainer.childCount
                buildCollections(id)
            }
            Section.MOVIES -> {
                keptHost = rowsContainer.childCount
                asyncRows(id, listOf(
                    RowSpec("Just added") { Api.enLatestAdded() },
                    RowSpec("New releases") { Api.enCatalog("year", 0) },
                    RowSpec("Popular") { Api.enCatalog("top", 0) },
                    RowSpec("Featured") { Api.enCatalog("imdbRating", 0) }) +
                    listOf("Action", "Adventure", "Comedy", "Drama", "Sci-Fi", "Thriller",
                           "Horror", "Crime", "Mystery", "Romance", "Animation", "Family",
                           "Fantasy", "Documentary", "Biography", "History", "War", "Western",
                           "Music", "Sport")
                        .map { g -> RowSpec(g) { Api.enCatalog("top", 0, g) } })
            }
        }
    }

    private inner class RowSpec(val title: String, val tv: Boolean = false,
                                val fetch: () -> List<Api.EnMovie>)

    /**
     * Collections: the viewer's own named sets first, then anything kept
     * from The Reel, then the franchises. Each collection is one row, which
     * is the same shape as everything else in the app.
     */
    private fun buildCollections(loadId: Int) {
        val mine = Collections.all(this)
        for (c in mine) {
            val row = addRow(c.name + "   ·   ${c.items.size}")
            row.list.adapter = CollectionAdapter(c.items)
            row.view.visibility = if (c.items.isEmpty()) View.GONE else View.VISIBLE
            row.header.setOnClickListener { manageCollection(c.name) }
        }
        if (mine.isEmpty()) {
            val row = addRow("Your collections")
            row.view.visibility = View.VISIBLE
            row.header.text = "Your collections   ·   none yet. Open any film and press ＋ Collection."
        }

        // kept Reel lists
        for ((listId, title) in KeptLists.all(this)) {
            val row = addRow(title)
            row.header.setOnClickListener {
                startActivity(Intent(this, SpinActivity::class.java).putExtra("listId", listId))
            }
            Thread {
                val films = try { Api.movieList(listId).films } catch (e: Exception) { emptyList() }
                runOnUiThread {
                    if (isFinishing || loadId != sectionLoadId || films.isEmpty()) return@runOnUiThread
                    row.list.adapter = SuggestionAdapter(films)
                    row.view.visibility = View.VISIBLE
                }
            }.start()
        }

        // franchises, from the server
        Thread {
            val cat = try { Api.collectionCatalogue() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || loadId != sectionLoadId) return@runOnUiThread
                val slots = cat.map { addRow(it.title) }
                Thread {
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                    cat.forEachIndexed { i, c ->
                        pool.execute {
                            val films = try { Api.collection(c.id).films } catch (e: Exception) { emptyList() }
                            if (films.isEmpty()) return@execute
                            runOnUiThread {
                                if (isFinishing || loadId != sectionLoadId) return@runOnUiThread
                                slots[i].list.adapter = SuggestionAdapter(films)
                                slots[i].view.visibility = View.VISIBLE
                            }
                        }
                    }
                    pool.shutdown()
                }.start()
            }
        }.start()
        focusFirstItemSoon()
    }

    private fun manageCollection(name: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle(name)
            .setItems(arrayOf("Delete this collection")) { _, _ ->
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                    .setTitle("Delete \"$name\"?")
                    .setMessage("The collection is removed. Nothing you watched is affected.")
                    .setPositiveButton("Delete") { _, _ ->
                        Collections.delete(this, name)
                        buildSection()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** items in a hand-built collection; each opens the right page for its kind */
    private inner class CollectionAdapter(private val items: List<Collections.Item>) :
        RecyclerView.Adapter<CollectionAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val chip: TextView = v.findViewById(R.id.chip)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            Ui.loadImage(holder.poster, e.poster, 4f, R.drawable.placeholder_poster)
            holder.chip.text = when (e.kind) { "movie" -> "FILM"; "enseries" -> "SERIES"; else -> "TÜRK" }
            holder.chip.setBackgroundResource(R.drawable.chip_gold)
            holder.chip.setTextColor(resources.getColor(R.color.accent_dark))
            holder.chip.visibility = View.VISIBLE
            val asMovie = Api.EnMovie(id = e.id, name = e.name, poster = e.poster, year = e.year,
                background = e.background)
            Ui.focusLift(holder.itemView)
            holder.itemView.setOnClickListener {
                when (e.kind) {
                    "movie" -> openMovie(asMovie, autoplay = false)
                    "enseries" -> openEnSeries(asMovie)
                    else -> openSeries(seriesById[e.id] ?: Api.Series(e.id, e.name, e.poster, null, e.background))
                }
            }
        }
    }

    /**
     * Placeholders first so the order is fixed, then fill them from a small
     * pool - twenty-odd sequential round trips left the lower rows empty for
     * far too long.
     */
    private fun asyncRows(loadId: Int, specs: List<RowSpec>) {
        val slots = specs.map { addRow(it.title) }
        Thread {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
            specs.forEachIndexed { i, spec ->
                pool.execute {
                    val items = try { spec.fetch() } catch (e: Exception) { emptyList() }
                    if (items.isEmpty()) return@execute
                    runOnUiThread {
                        if (isFinishing || loadId != sectionLoadId) return@runOnUiThread
                        slots[i].list.adapter = if (spec.tv) EnSeriesAdapter(items) else MovieAdapter(items)
                        slots[i].view.visibility = View.VISIBLE
                        if (i == 0) focusFirstItemSoon()
                    }
                }
            }
            pool.shutdown()
        }.start()
    }

    private fun focusFirstItemSoon() {
        rowsContainer.post { if (currentFocus == null || isInside(currentFocus, rowsContainer)) focusFirstItem() }
    }

    /**
     * BACK on the home screen used to drop straight out to the TV launcher,
     * which is easy to do by accident when walking back up from the player.
     * Now it asks, and offers Settings as a way to stay in the app.
     */
    override fun onBackPressed() {
        if (section != Section.HOME) { goTo(Section.HOME); return }
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
        if (catalog.isNotEmpty() && section == Section.HOME) {
            refreshKeptRows()
            fillMyListRow()
            fillContinueRow()
            fillBecauseRow()
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
        list.layoutManager = RowLayoutManager(this)
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
                    buildSection()
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
        }.start()
    }

    private fun fillContinueRow() {
        val row = continueRow ?: return
        val entries = WatchState.history(this).filter { !it.isFinished || it.isMovie }.take(10)
        row.list.adapter = ContinueAdapter(entries)
        row.view.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun fillMyListRow() {
        val row = myListRow ?: return
        val entries = MyList.all(this)
        row.list.adapter = MyListAdapter(entries)
        row.view.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    /** "Because you watched X": recommendations from the last film finished */
    private fun fillBecauseRow() {
        val row = becauseRow ?: return
        val last = WatchState.history(this).firstOrNull { it.isMovie && it.isFinished } ?: return
        val imdbId = last.videoId.removePrefix("movie:")
        if (becauseFor == imdbId) return
        becauseFor = imdbId
        Thread {
            val (title, films) = try { Api.similar(imdbId) } catch (e: Exception) { Pair(null, emptyList()) }
            runOnUiThread {
                if (isFinishing || films.isEmpty() || becauseRow !== row) return@runOnUiThread
                row.header.text = "Because you watched ${title ?: last.seriesName}"
                row.list.adapter = SuggestionAdapter(films)
                row.view.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun fillNewRow() {
        val row = newRow ?: return
        val fresh = catalog.filter { it.airedWithinDays(Ui.NEW_WINDOW_DAYS) }
            .sortedByDescending { it.lastAired ?: "" }
        row.list.adapter = SeriesAdapter(fresh, true)
        row.view.visibility = if (fresh.isEmpty()) View.GONE else View.VISIBLE
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
    private fun focusFirstItem(attempt: Int = 0) {
        val row = (0 until rowsContainer.childCount)
            .map { rowsContainer.getChildAt(it) }
            .firstOrNull { it.visibility == View.VISIBLE } ?: return
        val list = row.findViewById<RecyclerView>(R.id.rowList) ?: return
        list.post {
            val child = list.getChildAt(0)
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
            // Only react when the focused ROW changes. Moving along a row
            // fires this on every poster, and re-issuing the same vertical
            // smooth-scroll each time is what made fast scrolling lurch.
            if (row != null) {
                if (row === lastFocusedRow) return@addOnGlobalFocusChangeListener
                lastFocusedRow = row
                val idx = rowsContainer.indexOfChild(row)
                val firstVisible = (0 until rowsContainer.childCount).firstOrNull { rowsContainer.getChildAt(it).visibility == View.VISIBLE }
                if (idx == firstVisible) snapTo(0)
                else snapTo(rowsScroll.paddingTop + row.top - Ui.dp(this, 30))
            } else if (isInside(newFocus, billboard) || isInside(newFocus, findViewById(R.id.rail))) {
                lastFocusedRow = null
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

    private var lastFocusedRow: View? = null
    private var lastSnapY = Int.MIN_VALUE

    private fun snapTo(y: Int) {
        if (y == lastSnapY) return
        lastSnapY = y
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
                if (h.isEnSeries) chip = "CONTINUE WATCHING"
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
            is Spot.E -> {
                val m = s.m
                title = m.name
                bg = m.background ?: m.poster
                synopsis = m.description
                meta = listOfNotNull("Series", m.year, m.rating?.let { "★ $it" },
                    m.genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() }).joinToString("  ·  ")
                val last = WatchState.lastForSeries(this, EnSeriesActivity.PREFIX + m.id)
                primary = when {
                    last == null -> "▶  Start watching"
                    last.isFinished -> "▶  Play next episode"
                    else -> "▶  Resume  ·  S${last.season} E${last.episode}"
                }
                secondary = "ⓘ  Episodes"
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
            is Spot.C -> when {
                s.h.isMovie -> openMovieFromHistory(s.h, autoplay = true)
                s.h.isEnSeries -> openEnSeriesFromHistory(s.h, resume = true)
                else -> playHistory(s.h)
            }
            is Spot.M -> openMovie(s.m, autoplay = true)
            is Spot.E -> {
                val last = WatchState.lastForSeries(this, EnSeriesActivity.PREFIX + s.m.id)
                openEnSeries(s.m, autoplayId = last?.takeIf { !it.isFinished }?.videoId?.removePrefix(EnSeriesActivity.PREFIX))
            }
            null -> {}
        }
    }

    private fun onSecondary() {
        when (val s = spot) {
            is Spot.S -> openSeries(s.s)
            is Spot.C -> when {
                s.h.isMovie -> openMovieFromHistory(s.h, autoplay = false)
                s.h.isEnSeries -> openEnSeriesFromHistory(s.h, resume = false)
                else -> openSeries(seriesById[s.h.seriesId] ?: Api.Series(s.h.seriesId, s.h.seriesName, s.h.poster, null))
            }
            is Spot.M -> openMovie(s.m, autoplay = false)
            is Spot.E -> openEnSeries(s.m)
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

    private fun openEnSeries(m: Api.EnMovie, autoplayId: String? = null) {
        startActivity(Intent(this, EnSeriesActivity::class.java).apply {
            putExtra("imdbId", m.id)
            putExtra("name", m.name)
            putExtra("poster", m.poster)
            putExtra("background", m.background)
            autoplayId?.let { putExtra("autoplayId", it) }
        })
    }

    private fun openEnSeriesFromHistory(h: WatchState.HistoryEntry, resume: Boolean) {
        startActivity(Intent(this, EnSeriesActivity::class.java).apply {
            putExtra("imdbId", h.seriesId.removePrefix(EnSeriesActivity.PREFIX))
            putExtra("name", h.seriesName)
            putExtra("poster", h.poster)
            putExtra("background", h.backdrop)
            if (resume) putExtra("autoplayId", h.videoId.removePrefix(EnSeriesActivity.PREFIX))
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

    private inner class EnSeriesAdapter(private val items: List<Api.EnMovie>) :
        RecyclerView.Adapter<EnSeriesAdapter.VH>() {
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
                if (f) show(Spot.E(m))
            }
            holder.itemView.setOnClickListener { openEnSeries(m) }
        }
    }

    /** My List mixes Turkish series, English movies and English series */
    private inner class MyListAdapter(private val items: List<MyList.Entry>) :
        RecyclerView.Adapter<MyListAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val chip: TextView = v.findViewById(R.id.chip)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            Ui.loadImage(holder.poster, e.poster, 4f, R.drawable.placeholder_poster)
            holder.chip.text = when (e.kind) { "movie" -> "FILM"; "enseries" -> "SERIES"; else -> "TÜRK" }
            holder.chip.setBackgroundResource(R.drawable.chip_gold)
            holder.chip.setTextColor(resources.getColor(R.color.accent_dark))
            holder.chip.visibility = View.VISIBLE
            val asMovie = Api.EnMovie(id = e.id, name = e.name, poster = e.poster, year = e.year,
                background = e.background, description = e.description)
            holder.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start()
                v.translationZ = if (f) 14f else 0f
                if (f) when (e.kind) {
                    "movie" -> show(Spot.M(asMovie))
                    "enseries" -> show(Spot.E(asMovie))
                    else -> show(Spot.S(seriesById[e.id] ?: Api.Series(e.id, e.name, e.poster, e.description, e.background)))
                }
            }
            holder.itemView.setOnClickListener {
                when (e.kind) {
                    "movie" -> openMovie(asMovie, autoplay = false)
                    "enseries" -> openEnSeries(asMovie)
                    else -> openSeries(seriesById[e.id] ?: Api.Series(e.id, e.name, e.poster, e.description, e.background))
                }
            }
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
            holder.label.text = e.seriesName
            holder.progress.progress = e.progressPct
            holder.progress.visibility = if (e.durationMs > 0) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start()
                v.translationZ = if (f) 14f else 0f
                if (f) show(Spot.C(e))
            }
            holder.itemView.setOnClickListener {
                when {
                    e.isMovie -> openMovieFromHistory(e, autoplay = true)
                    e.isEnSeries -> openEnSeriesFromHistory(e, resume = true)
                    else -> playHistory(e)
                }
            }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@SeriesActivity, android.R.style.Theme_Material_Dialog)
                    .setTitle("Remove from Continue Watching?")
                    .setMessage(e.seriesName + (if (e.isMovie) "" else "  ·  S${e.season} E${e.episode}") +
                        "\n\nThis forgets where you were. It does not touch My List.")
                    .setPositiveButton("Remove") { _, _ ->
                        WatchState.removeHistory(this@SeriesActivity, e.videoId)
                        WatchState.clearPosition(this@SeriesActivity, e.videoId)
                        fillContinueRow()
                        focusFirstItem()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
                true
            }
        }
    }
}
