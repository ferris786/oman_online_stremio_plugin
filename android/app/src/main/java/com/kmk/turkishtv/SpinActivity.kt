package com.kmk.turkishtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The Reel: spins through the names of about sixty curated lists, slows
 * down, and lands on one. The films appear underneath, and the list can be
 * kept as a permanent row on the home screen.
 *
 * Every list is a named TMDB query held on the server (src/lists.js), so
 * the set can be tuned without an app release. Recently seen lists are
 * skipped so consecutive spins feel different.
 */
class SpinActivity : Activity() {

    companion object {
        private const val RECENT_KEY = "reel_recent"
        private const val RECENT_MEMORY = 12
        private const val SPIN_STEPS = 26
    }

    private lateinit var stage: View
    private lateinit var result: View
    private lateinit var spinName: TextView
    private lateinit var spinSub: TextView
    private lateinit var resultTitle: TextView
    private lateinit var resultSub: TextView
    private lateinit var grid: RecyclerView
    private lateinit var keepBtn: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var catalogue: List<Api.MovieList> = emptyList()
    private var landed: Api.MovieList? = null
    private var spinning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spin)

        stage = findViewById(R.id.spinStage)
        result = findViewById(R.id.resultStage)
        spinName = findViewById(R.id.spinName)
        spinSub = findViewById(R.id.spinSub)
        resultTitle = findViewById(R.id.resultTitle)
        resultSub = findViewById(R.id.resultSub)
        grid = findViewById(R.id.resultGrid)
        keepBtn = findViewById(R.id.keepList)
        grid.layoutManager = GridLayoutManager(this, 6)

        findViewById<TextView>(R.id.spinAgain).setOnClickListener { spin() }
        keepBtn.setOnClickListener { toggleKeep() }

        spinName.text = "Loading the reel…"
        Thread {
            val c = try { Api.listCatalogue() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (c.isEmpty()) {
                    Toast.makeText(this, "Could not load the lists", Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                catalogue = c
                // a specific list was asked for (a kept row), so skip the spin
                val direct = intent.getStringExtra("listId")
                if (direct != null) load(c.firstOrNull { it.id == direct } ?: c.random())
                else spin()
            }
        }.start()
    }

    // ---------------------------------------------------------- the spin

    private fun recent(): MutableList<String> {
        val raw = getSharedPreferences("settings", MODE_PRIVATE).getString(RECENT_KEY, "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }.toMutableList()
    }

    private fun remember(id: String) {
        val r = recent()
        r.remove(id)
        r.add(0, id)
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString(RECENT_KEY, r.take(RECENT_MEMORY).joinToString(",")).apply()
    }

    private fun spin() {
        if (spinning || catalogue.isEmpty()) return
        spinning = true
        result.visibility = View.GONE
        stage.visibility = View.VISIBLE
        spinSub.text = ""

        val seen = recent().toSet()
        val fresh = catalogue.filter { it.id !in seen }.ifEmpty { catalogue }
        val target = fresh.random()

        // names flick past, decelerating into the chosen one
        var step = 0
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing) return
                if (step >= SPIN_STEPS) {
                    spinName.text = target.title
                    spinSub.text = target.subtitle ?: ""
                    spinName.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160)
                        .withEndAction { spinName.animate().scaleX(1f).scaleY(1f).setDuration(220).start() }
                        .start()
                    handler.postDelayed({ if (!isFinishing) load(target) }, 520)
                    return
                }
                spinName.text = catalogue.random().title
                step++
                // ease out: 28ms at the start, ~190ms at the end
                handler.postDelayed(this, (28 + (step * step * 0.24)).toLong())
            }
        }
        handler.post(tick)
    }

    private fun load(list: Api.MovieList) {
        spinning = false
        remember(list.id)
        resultTitle.text = list.title
        resultSub.text = list.subtitle ?: ""
        paintKeep(list.id)
        Thread {
            val full = try { Api.movieList(list.id) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (full == null || full.films.isEmpty()) {
                    Toast.makeText(this, "That list came back empty - spinning again", Toast.LENGTH_SHORT).show()
                    spin()
                    return@runOnUiThread
                }
                landed = full
                resultTitle.text = full.title
                resultSub.text = listOfNotNull(full.subtitle, "${full.films.size} films").joinToString("   ·   ")
                grid.adapter = FilmAdapter(full.films)
                stage.visibility = View.GONE
                result.visibility = View.VISIBLE
                result.alpha = 0f
                result.animate().alpha(1f).setDuration(260).start()
                full.films.firstOrNull()?.background?.let {
                    Ui.loadBackdrop(findViewById(R.id.spinBackdrop), it)
                }
                grid.post { grid.getChildAt(0)?.requestFocus() }
            }
        }.start()
    }

    // ---------------------------------------------------------- keeping

    private fun paintKeep(id: String) {
        keepBtn.text = if (KeptLists.has(this, id)) "✓  On your home screen" else "＋  Keep on home"
    }

    private fun toggleKeep() {
        val l = landed ?: return
        if (KeptLists.has(this, l.id)) {
            KeptLists.remove(this, l.id)
            Toast.makeText(this, "Removed from your home screen", Toast.LENGTH_SHORT).show()
        } else {
            KeptLists.add(this, l.id, l.title)
            Toast.makeText(this, "\"${l.title}\" added to your home screen", Toast.LENGTH_LONG).show()
        }
        paintKeep(l.id)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------- films

    /** a TMDB suggestion carries no IMDb id, so resolve it before opening */
    private fun open(s: Api.Suggestion) {
        Toast.makeText(this, "Opening ${s.name}…", Toast.LENGTH_SHORT).show()
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

    private inner class FilmAdapter(private val items: List<Api.Suggestion>) :
        RecyclerView.Adapter<FilmAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val name: TextView = v.findViewById(R.id.name)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_poster, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.name.visibility = View.VISIBLE
            holder.name.text = if (s.year != null) "${s.name} (${s.year})" else s.name
            Ui.loadImage(holder.poster, s.poster, 4f, R.drawable.placeholder_poster)
            Ui.focusLift(holder.itemView)
            holder.itemView.setOnClickListener { open(s) }
        }
    }
}
