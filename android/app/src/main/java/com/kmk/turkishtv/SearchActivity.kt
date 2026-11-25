package com.kmk.turkishtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

/**
 * Search, rebuilt around the remote.
 *
 * The old version was one text field and one grid holding everything at
 * once. Three things were wrong with it, and all three are addressed here.
 *
 * Typing. It used the system keyboard, which on a television covers half
 * the screen and takes a D-pad press per letter anyway. There is now an
 * on-screen grid of keys instead: fixed positions, no overlay, and the
 * results update underneath as the query grows. Voice is still there and
 * is still the fastest route when the TV supports it.
 *
 * Reading the results. Turkish series, films, shows and actors were mixed
 * into one wall of posters distinguishable only by a small chip. They are
 * now the same labelled rows the rest of the app uses, so each kind can be
 * skimmed or skipped, and row navigation behaves exactly as it does on the
 * home screen.
 *
 * Before you type. The screen used to be blank. It now offers what you
 * searched for before, which on a device this awkward to type on is the
 * single most useful thing to show.
 */
class SearchActivity : Activity() {

    companion object {
        /** long enough that a query is not fired off per keystroke */
        private const val DEBOUNCE_MS = 450L
        private const val VOICE_REQUEST = 7001
        private const val RECENTS_KEY = "search_recents"
        private const val MAX_RECENTS = 12
        private const val KEYS_PER_ROW = 6
        /** sized so six columns and seven rows clear a 540dp screen with overscan */
        private const val KEY_W = 46
        private const val KEY_H = 40
        private const val KEY_GAP = 4
    }

    private lateinit var input: TextView
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var keyboard: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var searchSeq = 0
    private var query = ""

    private sealed class Hit {
        data class S(val s: Api.Series) : Hit()
        data class M(val m: Api.Suggestion) : Hit()
        data class P(val p: Api.Person) : Hit()
        data class T(val t: Api.EnMovie) : Hit()   // English series
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.searchInput)
        status = findViewById(R.id.searchStatus)
        rows = findViewById(R.id.resultsRows)
        scroll = findViewById(R.id.resultsScroll)
        keyboard = findViewById(R.id.keyboard)

        findViewById<TextView>(R.id.voiceBtn).setOnClickListener { startVoice() }
        buildKeyboard()

        // arrived from an actor: show their films straight away
        val personId = intent.getIntExtra("personId", 0)
        if (personId > 0) {
            val name = intent.getStringExtra("personName") ?: "Actor"
            setQuery(name, run = false)
            showPersonFilms(personId, name)
        } else {
            showRecents()
        }
    }

    // ------------------------------------------------------- the keyboard

    /**
     * A fixed grid of keys. Letters first because that is what people
     * reach for, then digits, then the three actions. Nothing here moves
     * as you type, which is the whole point: your thumb learns where the
     * letters are and stops looking.
     */
    private fun buildKeyboard() {
        keyboard.removeAllViews()
        val letters = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() }
        letters.chunked(KEYS_PER_ROW).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { ch -> line.addView(key(ch, KEY_H) { type(ch) }) }
            keyboard.addView(line)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(key("SPACE", KEY_H, width = 146) { type(" ") })
        actions.addView(key("DEL", KEY_H, width = 72) { backspace() })
        actions.addView(key("CLEAR", KEY_H, width = 82) { setQuery("", run = true) })
        keyboard.addView(actions)
    }

    private fun key(label: String, heightDp: Int, width: Int = KEY_W, onPress: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.key_bg)
            setTextColor(resources.getColorStateList(R.color.key_text, null))
            textSize = if (label.length > 1) 11f else 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@SearchActivity, width),
                Ui.dp(this@SearchActivity, heightDp)).apply {
                marginEnd = Ui.dp(this@SearchActivity, KEY_GAP)
                bottomMargin = Ui.dp(this@SearchActivity, KEY_GAP)
            }
            setOnClickListener { onPress() }
        }

    private fun type(s: String) = setQuery(query + s, run = true)
    private fun backspace() = setQuery(query.dropLast(1), run = true)

    private fun setQuery(q: String, run: Boolean) {
        query = q
        input.text = q
        if (!run) return
        if (q.isBlank()) { showRecents(); return }
        schedule(q)
    }

    // ------------------------------------------------------------ recents

    private fun recents(): List<String> =
        (Profiles.userPrefs(this).getString(RECENTS_KEY, "") ?: "")
            .split("\n").filter { it.isNotBlank() }

    private fun remember(q: String) {
        val list = (listOf(q) + recents().filterNot { it.equals(q, true) }).take(MAX_RECENTS)
        Profiles.userPrefs(this).edit().putString(RECENTS_KEY, list.joinToString("\n")).apply()
    }

    /**
     * What to show before anything is typed. On a device this awkward to
     * type on, the thing you looked for last week is worth more than an
     * empty screen telling you to use the microphone.
     */
    private fun showRecents() {
        rows.removeAllViews()
        val past = recents()
        if (past.isEmpty()) {
            status.text = "Pick letters on the left, or press Speak"
            return
        }
        status.text = "Recent searches"
        val row = addRow("Searched before")
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (q in past) {
            strip.addView(TextView(this).apply {
                text = q
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.key_bg)
                setTextColor(resources.getColorStateList(R.color.key_text, null))
                textSize = 14f
                val p = Ui.dp(this@SearchActivity, 14)
                setPadding(p, p / 2, p, p / 2)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Ui.dp(this@SearchActivity, 8) }
                setOnClickListener { setQuery(q, run = true) }
                setOnLongClickListener {
                    Profiles.userPrefs(this@SearchActivity).edit()
                        .putString(RECENTS_KEY, recents().filterNot { it == q }.joinToString("\n"))
                        .apply()
                    showRecents(); true
                }
            })
        }
        row.list.visibility = View.GONE
        (row.view as ViewGroup).addView(strip)
        row.view.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------- search

    private fun schedule(q: String) {
        pending?.let { handler.removeCallbacks(it) }
        if (q.trim().length < 2) {
            status.text = "Keep going…"
            return
        }
        val go = Runnable { search(q.trim()) }
        pending = go
        handler.postDelayed(go, DEBOUNCE_MS)
    }

    private fun search(q: String) {
        val seq = ++searchSeq
        status.text = "Searching for \"$q\"…"
        Thread {
            var series: List<Api.Series> = emptyList()
            var movies: List<Api.Suggestion> = emptyList()
            var people: List<Api.Person> = emptyList()
            var shows: List<Api.EnMovie> = emptyList()
            val t1 = Thread { series = try { Api.searchSeries(q) } catch (e: Exception) { emptyList() } }
            val t2 = Thread { movies = try { Api.suggestMovies(q) } catch (e: Exception) { emptyList() } }
            val t3 = Thread { people = try { Api.searchPeople(q) } catch (e: Exception) { emptyList() } }
            val t4 = Thread { shows = try { Api.enSeriesSearch(q).take(16) } catch (e: Exception) { emptyList() } }
            listOf(t1, t2, t3, t4).forEach { it.start() }
            // a bounded wait: one slow provider should not hold the screen
            val deadline = System.currentTimeMillis() + 9000
            for (t in listOf(t1, t2, t3, t4)) {
                val left = deadline - System.currentTimeMillis()
                if (left > 0) t.join(left) else break
            }

            runOnUiThread {
                if (isFinishing || seq != searchSeq) return@runOnUiThread
                render(q, series, shows, movies, people)
            }
        }.start()
    }

    /**
     * One row per kind, in the order someone is most likely to want.
     *
     * An exact name match leads with the person, because typing a full
     * name almost always means "show me their films" rather than "find a
     * title that happens to share the name".
     */
    private fun render(q: String, series: List<Api.Series>, shows: List<Api.EnMovie>,
                       movies: List<Api.Suggestion>, people: List<Api.Person>) {
        rows.removeAllViews()
        val total = series.size + shows.size + movies.size + people.size
        if (total == 0) {
            status.text = "Nothing found for \"$q\""
            return
        }
        remember(q)
        status.text = listOfNotNull(
            series.size.takeIf { it > 0 }?.let { "$it Turkish" },
            movies.size.takeIf { it > 0 }?.let { "$it films" },
            shows.size.takeIf { it > 0 }?.let { "$it shows" },
            people.size.takeIf { it > 0 }?.let { "$it people" }
        ).joinToString("   ·   ")

        val nameHit = people.any { it.name.equals(q, true) }
        val blocks = mutableListOf<Pair<String, List<Hit>>>()
        if (nameHit) blocks.add("People" to people.map { Hit.P(it) })
        if (series.isNotEmpty()) blocks.add("Turkish series" to series.map { Hit.S(it) })
        if (movies.isNotEmpty()) blocks.add("Films" to movies.map { Hit.M(it) })
        if (shows.isNotEmpty()) blocks.add("TV shows" to shows.map { Hit.T(it) })
        if (!nameHit && people.isNotEmpty()) blocks.add("People" to people.take(8).map { Hit.P(it) })

        for ((title, hits) in blocks) {
            val row = addRow("$title   ·   ${hits.size}")
            row.list.adapter = HitAdapter(hits)
            row.view.visibility = View.VISIBLE
        }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private class Row(val view: View, val header: TextView, val list: RecyclerView)

    /** the same row furniture the home screen uses, so navigation matches */
    private fun addRow(title: String): Row {
        val v = layoutInflater.inflate(R.layout.row_section, rows, false)
        val header = v.findViewById<TextView>(R.id.rowHeader)
        val list = v.findViewById<RecyclerView>(R.id.rowList)
        header.text = title
        list.layoutManager = RowLayoutManager(this)
        list.setHasFixedSize(true)
        list.itemAnimator = null
        v.visibility = View.GONE
        rows.addView(v)
        return Row(v, header, list)
    }

    /** held LEFT/RIGHT paced by RowNav, and stopped at the end of a row */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        if (RowNav.handle(this, event)) true else super.dispatchKeyEvent(event)

    // -------------------------------------------------------------- voice

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title or an actor")
        }
        try {
            startActivityForResult(i, VOICE_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "No voice input on this TV - use the keys on the left",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) setQuery(said.uppercase(), run = true)
        }
    }

    // ------------------------------------------------------------ opening

    private fun showPersonFilms(personId: Int, name: String) {
        status.text = "Loading $name…"
        Thread {
            val films = try { Api.personMovies(personId) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                rows.removeAllViews()
                if (films.isEmpty()) { status.text = "No films found for $name"; return@runOnUiThread }
                status.text = "$name   ·   ${films.size} films"
                val row = addRow("Films with $name")
                row.list.adapter = HitAdapter(films.map { Hit.M(it) })
                row.view.visibility = View.VISIBLE
                row.list.post { row.list.getChildAt(0)?.requestFocus() }
            }
        }.start()
    }

    /** a TMDB suggestion carries no IMDb id, so resolve it before opening */
    private fun openSuggestion(s: Api.Suggestion) {
        status.text = "Opening ${s.name}…"
        Thread {
            val imdbId = try { Api.resolveSuggestion(s.tmdbId) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (imdbId == null) {
                    status.text = "${s.name} has no match in the movie database"
                    return@runOnUiThread
                }
                status.text = ""
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

    private inner class HitAdapter(private val items: List<Hit>) :
        RecyclerView.Adapter<HitAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val chip: TextView = v.findViewById(R.id.chip)
            val name: TextView = v.findViewById(R.id.name)
            val score: TextView = v.findViewById(R.id.score)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val hit = items[position]
            holder.name.visibility = View.VISIBLE
            // the row header already says what kind these are, so the chip
            // is only used where it adds something
            holder.chip.visibility = View.GONE
            Ui.focusLift(holder.itemView)
            Ui.showScore(holder.score, when (hit) {
                is Hit.S -> hit.s.rating
                is Hit.M -> hit.m.rating
                is Hit.T -> hit.t.rating
                is Hit.P -> null
            })

            when (hit) {
                is Hit.S -> {
                    holder.name.text = hit.s.name
                    Ui.loadImage(holder.poster, hit.s.poster, 4f, R.drawable.placeholder_poster)
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@SearchActivity, EpisodesActivity::class.java).apply {
                            putExtra("seriesId", hit.s.id)
                            putExtra("seriesName", hit.s.name)
                            putExtra("seriesPoster", hit.s.poster)
                            putExtra("seriesBackdrop", hit.s.background)
                        })
                    }
                }
                is Hit.M -> {
                    holder.name.text = if (hit.m.year != null) "${hit.m.name} (${hit.m.year})" else hit.m.name
                    Ui.loadImage(holder.poster, hit.m.poster, 4f, R.drawable.placeholder_poster)
                    holder.itemView.setOnClickListener { openSuggestion(hit.m) }
                }
                is Hit.T -> {
                    holder.name.text = hit.t.name
                    Ui.loadImage(holder.poster, hit.t.poster, 4f, R.drawable.placeholder_poster)
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@SearchActivity, EnSeriesActivity::class.java).apply {
                            putExtra("imdbId", hit.t.id)
                            putExtra("name", hit.t.name)
                            putExtra("poster", hit.t.poster)
                            putExtra("background", hit.t.background)
                        })
                    }
                }
                is Hit.P -> {
                    holder.name.text = hit.p.name
                    Ui.loadImage(holder.poster, hit.p.photo, 4f, R.drawable.placeholder_poster)
                    holder.chip.text = hit.p.role?.uppercase() ?: "PERSON"
                    holder.chip.setBackgroundResource(R.drawable.chip_gold)
                    holder.chip.setTextColor(resources.getColor(R.color.accent_dark))
                    holder.chip.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener { showPersonFilms(hit.p.personId, hit.p.name) }
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
