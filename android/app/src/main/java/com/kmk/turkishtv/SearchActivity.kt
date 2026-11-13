package com.kmk.turkishtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Search across everything: Turkish series, English movies (TMDB
 * type-ahead, popularity-ranked) and actors.
 *
 * Typing on a TV remote is the real problem, so the microphone is the
 * primary route and the keyboard is the fallback. Results update as you
 * type, debounced, and an actor row leads to that actor's films.
 */
class SearchActivity : Activity() {

    companion object {
        private const val DEBOUNCE_MS = 350L
        private const val VOICE_REQUEST = 7001
    }

    private lateinit var grid: RecyclerView
    private lateinit var query: EditText
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var searchSeq = 0

    private sealed class Hit {
        data class S(val s: Api.Series) : Hit()
        data class M(val m: Api.Suggestion) : Hit()
        data class P(val p: Api.Person) : Hit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        query = findViewById(R.id.searchInput)
        grid = findViewById(R.id.searchGrid)
        status = findViewById(R.id.searchStatus)
        grid.layoutManager = GridLayoutManager(this, 6)

        findViewById<TextView>(R.id.searchBtn).setOnClickListener { runSearch(now = true) }
        findViewById<TextView>(R.id.voiceBtn).setOnClickListener { startVoice() }

        // the actor's films, when we arrived from an actor row
        val personId = intent.getIntExtra("personId", 0)
        if (personId > 0) {
            val name = intent.getStringExtra("personName") ?: "Actor"
            query.setText(name)
            showPersonFilms(personId, name)
        }

        query.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = runSearch(now = false)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(now = true); true } else false
        }
    }

    // ---------------------------------------------------------- voice

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title or an actor")
        }
        try {
            startActivityForResult(i, VOICE_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "No voice input on this TV - type instead", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) {
                query.setText(said)
                query.setSelection(said.length)
                runSearch(now = true)
            }
        }
    }

    // ---------------------------------------------------------- search

    private fun runSearch(now: Boolean) {
        pending?.let { handler.removeCallbacks(it) }
        val q = query.text.toString().trim()
        if (q.length < 2) {
            status.text = "Type or press the microphone"
            grid.adapter = null
            return
        }
        val go = Runnable { search(q) }
        pending = go
        handler.postDelayed(go, if (now) 0L else DEBOUNCE_MS)
    }

    private fun search(q: String) {
        val seq = ++searchSeq
        status.text = "Searching…"
        Thread {
            var series: List<Api.Series> = emptyList()
            var movies: List<Api.Suggestion> = emptyList()
            var people: List<Api.Person> = emptyList()
            val t1 = Thread { series = try { Api.searchSeries(q) } catch (e: Exception) { emptyList() } }
            val t2 = Thread { movies = try { Api.suggestMovies(q) } catch (e: Exception) { emptyList() } }
            val t3 = Thread { people = try { Api.searchPeople(q) } catch (e: Exception) { emptyList() } }
            t1.start(); t2.start(); t3.start(); t1.join(); t2.join(); t3.join()

            // an exact-ish person match is usually what someone means when they
            // say a name, so those lead; otherwise titles come first
            val nameHit = people.any { it.name.equals(q, true) }
            val hits = if (nameHit)
                people.map { Hit.P(it) } + series.map { Hit.S(it) } + movies.map { Hit.M(it) }
            else
                series.map { Hit.S(it) } + movies.map { Hit.M(it) } + people.take(4).map { Hit.P(it) }

            runOnUiThread {
                if (isFinishing || seq != searchSeq) return@runOnUiThread
                grid.adapter = HitAdapter(hits)
                status.text = if (hits.isEmpty()) "No results for \"$q\"" else listOfNotNull(
                    series.size.takeIf { it > 0 }?.let { "$it series" },
                    movies.size.takeIf { it > 0 }?.let { "$it movies" },
                    people.size.takeIf { it > 0 }?.let { "$it people" }
                ).joinToString("  ·  ")
            }
        }.start()
    }

    private fun showPersonFilms(personId: Int, name: String) {
        status.text = "Loading $name…"
        Thread {
            val films = try { Api.personMovies(personId) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                grid.adapter = HitAdapter(films.map { Hit.M(it) })
                status.text = if (films.isEmpty()) "No films found for $name"
                else "$name  ·  ${films.size} films"
                grid.post { grid.getChildAt(0)?.requestFocus() }
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

    private inner class HitAdapter(private val items: List<Hit>) : RecyclerView.Adapter<HitAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val poster: ImageView = v.findViewById(R.id.poster)
            val chip: TextView = v.findViewById(R.id.chip)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val hit = items[position]
            holder.name.visibility = View.VISIBLE
            holder.chip.visibility = View.GONE
            Ui.focusLift(holder.itemView)
            when (hit) {
                is Hit.S -> {
                    holder.name.text = hit.s.name
                    Ui.loadImage(holder.poster, hit.s.poster, 4f, R.drawable.placeholder_poster)
                    holder.chip.text = "SERIES"
                    holder.chip.setBackgroundResource(R.drawable.chip_gold)
                    holder.chip.setTextColor(resources.getColor(R.color.accent_dark))
                    holder.chip.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@SearchActivity, EpisodesActivity::class.java).apply {
                            putExtra("seriesId", hit.s.id)
                            putExtra("seriesName", hit.s.name)
                            putExtra("seriesPoster", hit.s.poster)
                            putExtra("seriesBackdrop", hit.s.background)
                            putExtra("seriesLogo", hit.s.logo)
                        })
                    }
                }
                is Hit.M -> {
                    holder.name.text = if (hit.m.year != null) "${hit.m.name} (${hit.m.year})" else hit.m.name
                    Ui.loadImage(holder.poster, hit.m.poster, 4f, R.drawable.placeholder_poster)
                    holder.itemView.setOnClickListener { openSuggestion(hit.m) }
                }
                is Hit.P -> {
                    holder.name.text = hit.p.name
                    Ui.loadImage(holder.poster, hit.p.photo, 4f, R.drawable.placeholder_poster)
                    holder.chip.text = "ACTOR"
                    holder.chip.setBackgroundResource(R.drawable.chip_new)
                    holder.chip.setTextColor(resources.getColor(R.color.white))
                    holder.chip.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@SearchActivity, SearchActivity::class.java).apply {
                            putExtra("personId", hit.p.personId)
                            putExtra("personName", hit.p.name)
                        })
                    }
                }
            }
        }
    }
}
