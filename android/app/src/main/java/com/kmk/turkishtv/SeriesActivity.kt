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
        HOME("Home"), TURKISH("Turkish"), TV("TV"), MOVIES("Movies"), COLLECTIONS("My Lists")
    }
    /** how far from the end of a row the next page is asked for */
    private val NEXT_PAGE_LEAD = 12
    /** a row stops growing here; thirty rows of artwork already sit in memory */
    private val MAX_ROW_ITEMS = 500

    /** sidebar widths in dp: icons only, and open with labels */
    private val RAIL_W_REST = 84
    private val RAIL_W_OPEN = 246

    private var section = Section.HOME
    private var sectionLoadId = 0

    /** which profile the rows on screen belong to, so a switch rebuilds them */
    private var shownProfile: String? = null

    private var watchingRow: Row? = null
    private var sentRow: Row? = null
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
        findViewById<View>(R.id.railProfile).setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        bbPrimary.setOnClickListener { onPrimary() }
        bbSecondary.setOnClickListener { onSecondary() }

        setupRail()
        setupSnapping()
        showSplash()
        UpdateChecker.check(this)
        shownProfile = Profiles.activeId(this)
        Diag.log("home", "opened", mapOf("profile" to Profiles.activeName(this)))

        // Something new worth knowing about, once, with a way to turn it on
        // right here. It waits for the rows so it does not land on a blank
        // screen, and stands aside for the profile questions below.
        if (Announce.pending(this) && Profiles.named(this) &&
            !(Profiles.multiple(this) && !ProfilesActivity.askedThisLaunch)) {
            rowsContainer.postDelayed({ if (!isFinishing) Announce.maybeShow(this) }, 2500)
        }

        // There is no sign-in, so the app asks once who is watching on this
        // TV. The answer is what the other TV in the household sees on its
        // "watching now" and "sent by" rows, so it is worth asking properly
        // rather than defaulting to a name nobody chose.
        if (!Profiles.named(this)) {
            startActivity(Intent(this, ProfilesActivity::class.java).putExtra("setup", true))
        } else if (Profiles.multiple(this) && !ProfilesActivity.askedThisLaunch) {
            // More than one person uses the box: ask which, once per launch.
            ProfilesActivity.askedThisLaunch = true
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        load()
    }

    // ------------------------------------------------------------ rail

    private val railItems by lazy {
        listOf(R.id.railProfile, R.id.railHome, R.id.railTurkish, R.id.railTv, R.id.railMovies,
               R.id.railCollections, R.id.railSpin, R.id.railSearch, R.id.railSettings)
    }
    private val railLabels by lazy {
        listOf(R.id.railProfileText, R.id.railHomeLabel, R.id.railTurkishLabel, R.id.railTvLabel,
               R.id.railMoviesLabel, R.id.railCollectionsLabel, R.id.railSpinLabel,
               R.id.railSearchLabel, R.id.railSettingsLabel)
    }
    /** the sections, for the selected-state paint - the profile chip is not one */
    private val railSections by lazy { railItems.drop(1) }

    private var railOpen = false

    /**
     * The sidebar is a narrow column of icons at rest and a labelled panel
     * while it has focus. Both widths are real layout widths rather than a
     * wrap_content guess, so the icons sit in the same place in both states
     * and the panel does not jump as labels appear.
     */
    private fun setRailOpen(open: Boolean) {
        if (open == railOpen) return
        railOpen = open
        val rail = findViewById<View>(R.id.rail)
        val from = rail.layoutParams.width.takeIf { it > 0 } ?: Ui.dp(this, RAIL_W_REST)
        val to = Ui.dp(this, if (open) RAIL_W_OPEN else RAIL_W_REST)
        // labels go in before the widening so they are measured, and out
        // after the narrowing so they are never clipped mid-animation
        if (open) railLabels.forEach { findViewById<View>(it).visibility = View.VISIBLE }
        android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = 170
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                rail.layoutParams.width = a.animatedValue as Int
                rail.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!railOpen) railLabels.forEach { findViewById<View>(it).visibility = View.GONE }
                }
            })
            start()
        }
    }

    private fun setupRail() {
        railItems.forEach { id ->
            findViewById<View>(id).setOnFocusChangeListener { _, _ ->
                // run after the focus change settles: on a move WITHIN the
                // rail, one view loses focus before the next gains it
                findViewById<View>(R.id.rail).post {
                    setRailOpen(railItems.any { findViewById<View>(it).hasFocus() })
                }
            }
        }
        paintProfile()
        paintRail()
    }

    /** the avatar carries the profile's own mark and colour, so a glance tells you who */
    private fun paintProfile() {
        val p = Profiles.active(this)
        Avatars.paint(this, findViewById(R.id.railAvatar), findViewById(R.id.railAvatarImg), p)
        findViewById<TextView>(R.id.railProfileName).text = p.name
        findViewById<TextView>(R.id.railProfileSub).text =
            if (Profiles.multiple(this)) "Switch profile" else "Add a profile"
    }

    private fun paintRail() {
        val selected = when (section) {
            Section.HOME -> R.id.railHome
            Section.TURKISH -> R.id.railTurkish
            Section.TV -> R.id.railTv
            Section.MOVIES -> R.id.railMovies
            Section.COLLECTIONS -> R.id.railCollections
        }
        railSections.forEach { findViewById<View>(it).isSelected = it == selected }
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
        rowFetchPool?.shutdownNow(); rowFetchPool = null; pendingRows.clear()
        watchingRow = null; sentRow = null
        myListRow = null; continueRow = null; becauseRow = null; newRow = null; seriesRow = null
        becauseFor = null
        snapTo(0)

        when (section) {
            Section.HOME -> {
                // the household rows go first: one is time-sensitive, the
                // other is somebody asking you to watch something
                watchingRow = addRow("Watching now")
                sentRow = addRow("Sent to you")
                myListRow = addRow("My List")
                continueRow = addRow("Continue Watching")
                becauseRow = addRow("Because you watched")
                newRow = addRow("New this week")
                keptHost = rowsContainer.childCount
                fillMyListRow(); fillContinueRow(); fillBecauseRow(); fillNewRow()
                fillHouseRows()
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
                // every one of these pages: the row keeps growing as you
                // run along it, until the catalogue itself is spent
                asyncRows(id, listOf(
                    RowSpec("Popular Series", tv = true,
                        page = { k -> Api.enSeriesCatalog("top", k) }) { Api.enSeriesCatalog("top", 0) },
                    RowSpec("New Series", tv = true,
                        page = { k -> Api.enSeriesCatalog("year", k) }) { Api.enSeriesCatalog("year", 0) },
                    RowSpec("Featured Series", tv = true,
                        page = { k -> Api.enSeriesCatalog("imdbRating", k) }) { Api.enSeriesCatalog("imdbRating", 0) }) +
                    listOf("Drama", "Comedy", "Crime", "Sci-Fi", "Thriller", "Mystery",
                           "Animation", "Documentary", "Action", "Fantasy", "Horror", "Family")
                        .map { g -> RowSpec(g, tv = true,
                            page = { k -> Api.enSeriesCatalog("top", k, g) }) { Api.enSeriesCatalog("top", 0, g) } })
            }
            Section.COLLECTIONS -> {
                keptHost = rowsContainer.childCount
                buildCollections(id)
            }
            Section.MOVIES -> {
                keptHost = rowsContainer.childCount
                // "Just added" is a dated snapshot and has a real end, so it
                // is the one row here that does not page
                asyncRows(id, listOf(
                    RowSpec("Just added") { Api.enLatestAdded() },
                    RowSpec("New releases",
                        page = { k -> Api.enCatalog("year", k) }) { Api.enCatalog("year", 0) },
                    RowSpec("Popular",
                        page = { k -> Api.enCatalog("top", k) }) { Api.enCatalog("top", 0) },
                    RowSpec("Featured",
                        page = { k -> Api.enCatalog("imdbRating", k) }) { Api.enCatalog("imdbRating", 0) }) +
                    listOf("Action", "Adventure", "Comedy", "Drama", "Sci-Fi", "Thriller",
                           "Horror", "Crime", "Mystery", "Romance", "Animation", "Family",
                           "Fantasy", "Documentary", "Biography", "History", "War", "Western",
                           "Music", "Sport")
                        .map { g -> RowSpec(g,
                            page = { k -> Api.enCatalog("top", k, g) }) { Api.enCatalog("top", 0, g) } })
            }
        }
    }

    private inner class RowSpec(val title: String, val tv: Boolean = false,
                                /** fetches the page starting at skip; null for a fixed row */
                                val page: ((Int) -> List<Api.EnMovie>)? = null,
                                val fetch: () -> List<Api.EnMovie>)

    /**
     * An endless row.
     *
     * Cinemeta hands back about fifty titles a page, so a row used to stop
     * there. The next page is fetched while binding is still a screenful
     * from the end, which means the row keeps flowing rather than halting
     * while something loads.
     *
     * Pages can overlap, so new titles are de-duplicated by id. A page that
     * adds nothing new means the catalogue is spent and the row is done
     * growing, which is when it finally has a real end to stop at.
     *
     * The cap matters on this box: thirty rows of artwork already sit in
     * memory, and an unbounded row would quietly undo that work.
     */
    private inner class Pager(
        private val row: Row,
        private val items: MutableList<Api.EnMovie>,
        private val loadId: Int,
        private val fetch: (Int) -> List<Api.EnMovie>
    ) {
        private var nextSkip = items.size
        private var loading = false
        private var exhausted = false

        fun needMore() {
            if (loading || exhausted || items.size >= MAX_ROW_ITEMS) return
            loading = true
            val skip = nextSkip
            Thread {
                val more = try { fetch(skip) } catch (e: Exception) { emptyList() }
                runOnUiThread {
                    loading = false
                    if (isFinishing || loadId != sectionLoadId) return@runOnUiThread
                    if (more.isEmpty()) { exhausted = true; return@runOnUiThread }
                    val seen = items.mapTo(HashSet()) { it.id }
                    val fresh = more.filter { seen.add(it.id) }
                    nextSkip = skip + more.size
                    if (fresh.isEmpty()) { exhausted = true; return@runOnUiThread }
                    val start = items.size
                    items.addAll(fresh)
                    row.list.adapter?.notifyItemRangeInserted(start, fresh.size)
                }
            }.start()
        }
    }

    /**
     * Collections: the viewer's own named sets first, then anything kept
     * from The Reel, then the franchises. Each collection is one row, which
     * is the same shape as everything else in the app.
     */
    private fun buildCollections(loadId: Int) {
        // --- yours first, because they are the reason to open this tab ---
        addSectionLabel("Your lists   ·   hold OK on a title to remove it or manage the list")

        val myList = MyList.all(this)
        val mineRow = addRow("My List   ·   ${myList.size}")
        mineRow.list.adapter = MyListAdapter(myList)
        mineRow.view.visibility = if (myList.isEmpty()) View.GONE else View.VISIBLE

        val mine = Collections.all(this)
        for (c in mine) {
            val row = addRow(c.name + "   ·   ${c.items.size}")
            row.list.adapter = CollectionAdapter(c.items, c.name)
            row.view.visibility = if (c.items.isEmpty()) View.GONE else View.VISIBLE
            row.header.setOnClickListener { manageCollection(c.name) }
        }
        if (mine.isEmpty() && myList.isEmpty()) {
            val row = addRow("")
            row.view.visibility = View.VISIBLE
            row.header.text = "Nothing saved yet. Open any film or series and press ＋ Save to…"
        }

        // --- then anything kept from The Reel ---
        if (KeptLists.all(this).isNotEmpty()) addSectionLabel("Kept from The Reel")
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

        // --- franchises last: a browse feature, not something you made ---
        val fLabel = addSectionLabel("Film franchises")
        fLabel.view.visibility = View.GONE
        Thread {
            val cat = try { Api.collectionCatalogue() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || loadId != sectionLoadId) return@runOnUiThread
                if (cat.isNotEmpty()) fLabel.view.visibility = View.VISIBLE
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

    /**
     * Managing a list.
     *
     * This used to hang off a click on the row's header, and the header
     * has no focusable attribute — so with a remote there was no way to
     * reach it at all and a list could never be deleted. It is reached by
     * holding OK on any title in the list now, which is the gesture
     * Continue Watching and the sent-to-you row already use.
     *
     * @param item the title that was held, when there was one
     */
    private fun manageCollection(name: String, item: Collections.Item? = null) {
        val options = ArrayList<String>()
        if (item != null) options.add("Remove \"${item.name.take(28)}\" from this list")
        options.add("Rename this list")
        options.add("Empty this list")
        options.add("Delete this list")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Remove \"${item?.name?.take(28)}\" from this list" -> {
                        Collections.toggle(this, name, item!!)
                        Toast.makeText(this, "Removed from $name", Toast.LENGTH_SHORT).show()
                        buildSection()
                    }
                    "Rename this list" -> renameCollection(name)
                    "Empty this list" -> confirmCollection(
                        "Empty \"$name\"?",
                        "Every title is taken out. The list itself stays.") {
                        for (i in Collections.all(this).firstOrNull { it.name == name }?.items.orEmpty()) {
                            Collections.toggle(this, name, i)
                        }
                    }
                    "Delete this list" -> confirmCollection(
                        "Delete \"$name\"?",
                        "The list is removed. Nothing you watched is affected.") {
                        Collections.delete(this, name)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmCollection(title: String, message: String, act: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> act(); buildSection() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameCollection(name: String) {
        val input = android.widget.EditText(this).apply {
            setText(name)
            setSelection(name.length)
            setSingleLine()
            val pad = Ui.dp(this@SeriesActivity, 16)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename list")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val to = input.text.toString().trim()
                if (to.isEmpty() || to == name) return@setPositiveButton
                // create the new one, move everything across, drop the old
                val items = Collections.all(this).firstOrNull { it.name == name }?.items.orEmpty()
                Collections.create(this, to)
                for (i in items.reversed()) Collections.toggle(this, to, i)
                Collections.delete(this, name)
                buildSection()
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    /** items in a hand-built collection; each opens the right page for its kind */
    private inner class CollectionAdapter(private val items: List<Collections.Item>,
                                          private val listName: String? = null) :
        RecyclerView.Adapter<CollectionAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val chip: TextView = v.findViewById(R.id.chip)
            val score: TextView = v.findViewById(R.id.score)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            Ui.loadImage(holder.poster, e.poster, 4f, R.drawable.placeholder_poster)
            // a saved entry carries no score of its own; the Turkish ones can
            // borrow it from the catalogue already in memory
            Ui.showScore(holder.score, seriesById[e.id]?.rating)
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
            // the only way to reach list management with a remote: the row
            // header this used to hang off was never focusable
            holder.itemView.setOnLongClickListener {
                listName?.let { manageCollection(it, e) }
                listName != null
            }
        }
    }

    /**
     * Placeholders first so the order is fixed, then fill them from a small
     * pool - twenty-odd sequential round trips left the lower rows empty for
     * far too long.
     */
    /**
     * Build the rows, but only fetch the ones anywhere near the screen.
     *
     * Movies asks for twenty-four rows and TV for twenty. Firing all of
     * them on open meant five waves through the thread pool before the
     * screen settled, for the sake of maybe four rows anyone could see.
     * The rest are fetched as focus walks down towards them, a few ahead
     * so a row is already filling by the time it is reached.
     */
    private fun asyncRows(loadId: Int, specs: List<RowSpec>) {
        val slots = specs.map { addRow(it.title) }
        pendingRows.clear()
        specs.forEachIndexed { i, spec -> pendingRows[slots[i].view] = Pending(spec, slots[i], loadId) }
        rowFetchPool?.shutdownNow()
        rowFetchPool = java.util.concurrent.Executors.newFixedThreadPool(4)
        // the opening screenful, plus a little in hand
        fetchRowsUpTo(FIRST_ROWS - 1)
    }

    /** a row that exists on screen but has not been asked for yet */
    private inner class Pending(val spec: RowSpec, val row: Row, val loadId: Int) {
        var started = false
    }

    private val pendingRows = LinkedHashMap<View, Pending>()
    private var rowFetchPool: java.util.concurrent.ExecutorService? = null

    /** how many rows are fetched before anyone scrolls */
    private val FIRST_ROWS = 5
    /** how far ahead of the focused row to keep fetching */
    private val ROW_LOOKAHEAD = 4

    private fun fetchRowsUpTo(index: Int) {
        val views = pendingRows.keys.toList()
        for (i in 0..index.coerceAtMost(views.size - 1)) {
            val p = pendingRows[views[i]] ?: continue
            if (p.started) continue
            p.started = true
            val pool = rowFetchPool ?: continue
            if (pool.isShutdown) continue
            pool.execute {
                val items = try { p.spec.fetch() } catch (e: Exception) { emptyList() }
                if (items.isEmpty()) {
                    // An empty row stays hidden, so focus can never land in
                    // it to pull the next one in. Advance the frontier by
                    // hand, or a bad first few rows would strand the rest.
                    runOnUiThread {
                        if (!isFinishing && p.loadId == sectionLoadId) fetchRowsUpTo(i + 1)
                    }
                    return@execute
                }
                runOnUiThread {
                    if (isFinishing || p.loadId != sectionLoadId) return@runOnUiThread
                    val live = items.toMutableList()
                    val pager = p.spec.page?.let { Pager(p.row, live, p.loadId, it) }
                    p.row.list.adapter =
                        if (p.spec.tv) EnSeriesAdapter(live) { pager?.needMore() }
                        else MovieAdapter(live) { pager?.needMore() }
                    p.row.view.visibility = View.VISIBLE
                    if (i == 0) focusFirstItemSoon()
                }
            }
        }
    }

    /** focus moved to this row: make sure the next few are on their way */
    private fun ensureRowsAround(rowView: View) {
        val views = pendingRows.keys.toList()
        val idx = views.indexOf(rowView)
        if (idx < 0) return
        fetchRowsUpTo(idx + ROW_LOOKAHEAD)
    }

    /**
     * A held LEFT or RIGHT is paced by RowNav rather than by the remote's
     * own repeat rate, and is swallowed at the end of a row so focus can
     * never jump to the row above or below.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        if (RowNav.handle(this, event)) true else super.dispatchKeyEvent(event)

    private fun focusFirstItemSoon() {
        rowsContainer.post { if (currentFocus == null || isInside(currentFocus, rowsContainer)) focusFirstItem() }
    }

    /**
     * BACK unwinds towards the way out, one step at a time.
     *
     *   anywhere in the rows or the billboard -> the sidebar
     *   the sidebar, in a section             -> Home
     *   the sidebar, on Home                  -> ask before leaving
     *
     * It used to be two states: a section went Home, and Home showed the
     * exit prompt. So being forty posters deep in a row and pressing BACK
     * asked whether you wanted to quit, which is not what BACK means.
     */
    override fun onBackPressed() {
        val rail = findViewById<View>(R.id.rail)
        if (!isInside(currentFocus, rail)) { focusRail(); return }
        if (section != Section.HOME) { goTo(Section.HOME); focusRail(); return }
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

    /**
     * While the player is in front, the home screen is still alive behind it
     * holding up to thirty rows of artwork. Hand the bitmaps back so the
     * system has no reason to kill the player to reclaim memory.
     */
    override fun onStop() {
        super.onStop()
        runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Came back from the picker as somebody else: the rows on screen are
        // the previous viewer's, so paint the chip and rebuild from scratch.
        val now = Profiles.activeId(this)
        // Repaint unconditionally: the one-time setup changes the NAME
        // without changing the profile id, so comparing ids alone would
        // leave a stale label on the rail.
        paintProfile()
        if (shownProfile != null && shownProfile != now) {
            shownProfile = now
            section = Section.HOME
            paintRail()
            buildSection()
            return
        }
        if (catalog.isNotEmpty() && section == Section.HOME) {
            refreshKeptRows()
            fillMyListRow()
            fillContinueRow()
            fillBecauseRow()
            fillHouseRows()
            // progress may have changed while watching
            (spot as? Spot.C)?.let { c ->
                WatchState.history(this).firstOrNull { it.videoId == c.h.videoId }?.let { show(Spot.C(it)) }
            }
            (spot as? Spot.S)?.let { show(it) }
        }
    }

    // ------------------------------------------------------------ rows

    /**
     * A header with no row under it, used to label a group.
     *
     * The Collections tab was a flat pile of three unrelated things with
     * nothing saying which was which: your own lists, lists kept from The
     * Reel, and thirty-four film franchises, all just rows with names.
     */
    private fun addSectionLabel(text: String): Row {
        val r = addRow(text.uppercase())
        r.list.visibility = View.GONE
        r.header.setTextColor(resources.getColor(R.color.accent))
        r.header.textSize = 12f
        r.header.letterSpacing = 0.22f
        r.view.visibility = View.VISIBLE
        return r
    }

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
            val score: TextView = v.findViewById(R.id.score)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            Ui.loadImage(holder.poster, s.poster, 4f, R.drawable.placeholder_poster)
            Ui.showScore(holder.score, s.rating)
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

    /**
     * The two household rows.
     *
     * Both are network-backed and both are allowed to come back empty, so
     * they stay hidden until there is something real to show rather than
     * leaving two blank headers on the home screen.
     */
    private fun fillHouseRows() {
        val wRow = watchingRow
        val sRow = sentRow
        if (!Household.linked(this)) {
            wRow?.view?.visibility = View.GONE
            sRow?.view?.visibility = View.GONE
            return
        }
        Thread {
            val watchers = Household.watching(this)
            val sent = Household.inbox(this)
            runOnUiThread {
                if (isFinishing || watchingRow !== wRow || sentRow !== sRow) return@runOnUiThread
                wRow?.let {
                    it.list.adapter = WatchingAdapter(watchers)
                    it.view.visibility = if (watchers.isEmpty()) View.GONE else View.VISIBLE
                }
                sRow?.let {
                    val who = sent.map { x -> x.from }.distinct()
                    it.header.text = if (who.size == 1) "Sent by ${who[0]}" else "Sent to you"
                    it.list.adapter = SentAdapter(sent)
                    it.view.visibility = if (sent.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }.start()
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
                ensureRowsAround(row)
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

    /** put focus on the sidebar entry for wherever we currently are */
    private fun focusRail() {
        val id = when (section) {
            Section.HOME -> R.id.railHome
            Section.TURKISH -> R.id.railTurkish
            Section.TV -> R.id.railTv
            Section.MOVIES -> R.id.railMovies
            Section.COLLECTIONS -> R.id.railCollections
        }
        findViewById<View>(id)?.requestFocus()
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
            val score: TextView = v.findViewById(R.id.score)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            Ui.loadImage(holder.poster, s.poster, 4f, R.drawable.placeholder_poster)
            Ui.showScore(holder.score, s.rating)
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

    private inner class EnSeriesAdapter(private val items: List<Api.EnMovie>,
                                        private val onNeedMore: (() -> Unit)? = null) :
        RecyclerView.Adapter<EnSeriesAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val score: TextView = v.findViewById(R.id.score)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            if (position >= items.size - NEXT_PAGE_LEAD) onNeedMore?.invoke()
            Ui.loadImage(holder.poster, m.poster, 4f, R.drawable.placeholder_poster)
            Ui.showScore(holder.score, m.rating)
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
    /** What the other person is playing right now, and a way to fall in step. */
    private inner class WatchingAdapter(private val items: List<Household.Watcher>) :
        RecyclerView.Adapter<WatchingAdapter.VH>() {
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
            val w = items[position]
            val series = seriesById[w.seriesId ?: ""]
            Ui.loadImage(holder.still, w.background ?: series?.background ?: w.poster, 4f)
            holder.label.text = w.title.ifBlank { "Watching" }
            val mins = w.posS / 60
            holder.sub.text = "${w.name}  ·  " +
                (if (w.playing) "${mins} min in" else "paused at ${mins} min") +
                "  ·  OK to join"
            holder.progress.progress = if (w.durS > 0) (w.posS * 100 / w.durS) else 0
            holder.progress.visibility = if (w.durS > 0) View.VISIBLE else View.INVISIBLE
            Ui.focusLift(holder.itemView)
            holder.itemView.setOnClickListener { joinWatcher(w) }
        }
    }

    /**
     * Titles the other person sent over. Opening one is the point; a long
     * press takes it off the row, the same gesture Continue Watching uses.
     */
    private inner class SentAdapter(private val items: List<Household.Sent>) :
        RecyclerView.Adapter<SentAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val chip: TextView = v.findViewById(R.id.chip)
            val name: TextView = v.findViewById(R.id.name)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            Ui.loadImage(holder.poster, e.poster, 4f, R.drawable.placeholder_poster)
            holder.chip.text = e.from.take(10).uppercase()
            holder.chip.setBackgroundResource(R.drawable.chip_gold)
            holder.chip.setTextColor(resources.getColor(R.color.accent_dark))
            holder.chip.visibility = View.VISIBLE
            holder.name.visibility = View.VISIBLE
            holder.name.text = if (e.note.isNotBlank()) e.note else e.name
            val asMovie = Api.EnMovie(id = e.id, name = e.name, poster = e.poster,
                year = e.year, background = e.background)
            holder.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start()
                v.translationZ = if (f) 14f else 0f
                if (f) when (e.kind) {
                    "movie" -> show(Spot.M(asMovie))
                    "enseries" -> show(Spot.E(asMovie))
                    else -> show(Spot.S(seriesById[e.id]
                        ?: Api.Series(e.id, e.name, e.poster, null, e.background)))
                }
            }
            holder.itemView.setOnClickListener {
                when (e.kind) {
                    "movie" -> openMovie(asMovie, autoplay = false)
                    "enseries" -> openEnSeries(asMovie)
                    else -> openSeries(seriesById[e.id]
                        ?: Api.Series(e.id, e.name, e.poster, null, e.background))
                }
            }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@SeriesActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Remove \"${e.name}\"?")
                    .setMessage("It came from ${e.from}. Removing it only clears it from this row.")
                    .setPositiveButton("Remove") { _, _ ->
                        Thread {
                            Household.dismiss(this@SeriesActivity, e.sentId)
                            runOnUiThread { if (!isFinishing) fillHouseRows() }
                        }.start()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
                true
            }
        }
    }

    /**
     * Fall in step with the other person.
     *
     * Turkish episodes go straight to the player, which resolves its own
     * streams from the addon. English titles go through their own page
     * instead, because those sources have to be resolved through this TV's
     * own TorBox account before anything can play.
     */
    private fun joinWatcher(w: Household.Watcher) {
        Toast.makeText(this, "Joining ${w.name}…", Toast.LENGTH_SHORT).show()
        when (w.kind) {
            "movie" -> startActivity(Intent(this, MovieDetailActivity::class.java)
                .putExtra("imdbId", w.videoId.removePrefix("movie:"))
                .putExtra("name", w.title)
                .putExtra("poster", w.poster)
                .putExtra("background", w.background)
                .putExtra("autoplay", true)
                .putExtra("together", true))
            "enseries" -> startActivity(Intent(this, EnSeriesActivity::class.java)
                .putExtra("imdbId", (w.seriesId ?: "").removePrefix(EnSeriesActivity.PREFIX))
                .putExtra("name", w.title)
                .putExtra("background", w.background)
                .putExtra("autoplayId", w.videoId)
                .putExtra("together", true))
            else -> startActivity(Intent(this, PlayerActivity::class.java)
                .putExtra("videoId", w.videoId)
                .putExtra("videoTitle", w.title)
                .putExtra("seriesId", w.seriesId)
                .putExtra("seriesName", seriesById[w.seriesId ?: ""]?.name)
                .putExtra("seriesPoster", w.poster)
                .putExtra("seriesBackdrop", w.background)
                .putExtra("together", true)
                .putExtra("togetherPositionMs", w.posS * 1000L))
        }
    }

    private inner class MyListAdapter(private val items: List<MyList.Entry>) :
        RecyclerView.Adapter<MyListAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val chip: TextView = v.findViewById(R.id.chip)
            val score: TextView = v.findViewById(R.id.score)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            Ui.loadImage(holder.poster, e.poster, 4f, R.drawable.placeholder_poster)
            // a saved entry carries no score of its own; the Turkish ones can
            // borrow it from the catalogue already in memory
            Ui.showScore(holder.score, seriesById[e.id]?.rating)
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
            // same gesture as every other removable row in the app
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@SeriesActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Remove \"${e.name}\" from My List?")
                    .setMessage("It stays in any other list it is in, and nothing you watched changes.")
                    .setPositiveButton("Remove") { _, _ ->
                        MyList.toggle(this@SeriesActivity,
                            MyList.Entry(e.kind, e.id, e.name, e.poster, e.background, e.year, e.description))
                        buildSection()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
                true
            }
        }
    }

    private inner class MovieAdapter(private val items: List<Api.EnMovie>,
                                     private val onNeedMore: (() -> Unit)? = null) :
        RecyclerView.Adapter<MovieAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val score: TextView = v.findViewById(R.id.score)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            if (position >= items.size - NEXT_PAGE_LEAD) onNeedMore?.invoke()
            Ui.loadImage(holder.poster, m.poster, 4f, R.drawable.placeholder_poster)
            Ui.showScore(holder.score, m.rating)
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
