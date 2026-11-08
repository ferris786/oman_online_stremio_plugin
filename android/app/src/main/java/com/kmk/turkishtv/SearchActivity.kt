package com.kmk.turkishtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import coil.request.ImageRequest

class SearchActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var query: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        query = findViewById(R.id.searchInput)
        grid = findViewById(R.id.searchGrid)
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
        Thread {
            try {
                val results = Api.enSearch(q)
                runOnUiThread {
                    grid.adapter = MovieAdapter(results)
                    if (results.isEmpty()) Toast.makeText(this, "No results", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Search failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private inner class MovieAdapter(private val items: List<Api.EnMovie>) :
        RecyclerView.Adapter<MovieAdapter.VH>() {

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
            Coil.imageLoader(this@SearchActivity).enqueue(
                ImageRequest.Builder(this@SearchActivity).data(m.poster)
                    .placeholder(R.drawable.placeholder_poster)
                    .error(R.drawable.placeholder_poster)
                    .crossfade(true).target(holder.poster).build())
            (holder.itemView as? View)?.apply {
                onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    val scale = if (hasFocus) 1.06f else 1f
                    animate().scaleX(scale).scaleY(scale).setDuration(140).start()
                    translationZ = if (hasFocus) 12f else 0f
                }
                setOnClickListener {
                    startActivity(Intent(this@SearchActivity, MovieDetailActivity::class.java).apply {
                        putExtra("imdbId", m.id)
                        putExtra("name", m.name)
                        putExtra("poster", m.poster)
                        putExtra("year", m.year)
                    })
                }
            }
        }
    }
}
