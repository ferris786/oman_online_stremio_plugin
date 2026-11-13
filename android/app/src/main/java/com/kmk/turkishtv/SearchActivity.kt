package com.kmk.turkishtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Search across both libraries: Turkish series (addon catalog search) and
 * English movies (Cinemeta). Series come first.
 */
class SearchActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var query: EditText
    private lateinit var status: TextView

    private sealed class Hit {
        data class S(val s: Api.Series) : Hit()
        data class M(val m: Api.EnMovie) : Hit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        query = findViewById(R.id.searchInput)
        grid = findViewById(R.id.searchGrid)
        status = findViewById(R.id.searchStatus)
        grid.layoutManager = GridLayoutManager(this, 6)

        findViewById<TextView>(R.id.searchBtn).setOnClickListener { doSearch() }
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
    }

    private fun doSearch() {
        val q = query.text.toString().trim()
        if (q.length < 2) return
        grid.adapter = null
        status.text = "Searching…"
        Thread {
            var series: List<Api.Series> = emptyList()
            var movies: List<Api.EnMovie> = emptyList()
            var error: String? = null
            val t1 = Thread { series = try { Api.searchSeries(q) } catch (e: Exception) { emptyList() } }
            val t2 = Thread { movies = try { Api.enSearch(q) } catch (e: Exception) { error = e.message; emptyList() } }
            t1.start(); t2.start(); t1.join(); t2.join()
            val hits = series.map { Hit.S(it) } + movies.map { Hit.M(it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                grid.adapter = HitAdapter(hits)
                status.text = when {
                    hits.isEmpty() && error != null -> "Search failed: $error"
                    hits.isEmpty() -> "No results for \"$q\""
                    else -> listOfNotNull(
                        series.size.takeIf { it > 0 }?.let { "$it series" },
                        movies.size.takeIf { it > 0 }?.let { "$it movies" }).joinToString("  ·  ")
                }
                grid.post { grid.getChildAt(0)?.requestFocus() }
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
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@SearchActivity, MovieDetailActivity::class.java).apply {
                            putExtra("imdbId", hit.m.id)
                            putExtra("name", hit.m.name)
                            putExtra("poster", hit.m.poster)
                            putExtra("year", hit.m.year)
                            putExtra("background", hit.m.background)
                        })
                    }
                }
            }
        }
    }
}
