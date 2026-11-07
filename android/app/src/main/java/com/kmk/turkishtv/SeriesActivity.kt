package com.kmk.turkishtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import coil.request.ImageRequest

class SeriesActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var continueBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        grid = findViewById(R.id.grid)
        continueBtn = findViewById(R.id.continueBtn)
        grid.layoutManager = GridLayoutManager(this, 5)

        WatchState.last(this)?.let { last ->
            continueBtn.text = "Continue: ${last.seriesName} · S${last.season}E${last.episode}"
            continueBtn.visibility = View.VISIBLE
            continueBtn.setOnClickListener {
                startActivity(Intent(this, EpisodesActivity::class.java).apply {
                    putExtra("seriesId", last.seriesId)
                    putExtra("seriesName", last.seriesName)
                })
            }
        }

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

    private inner class SeriesAdapter(private val items: List<Api.Series>) :
        RecyclerView.Adapter<SeriesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: android.widget.TextView = v.findViewById(R.id.name)
            val poster: android.widget.ImageView = v.findViewById(R.id.poster)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_series, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.name.text = s.name
            val req = ImageRequest.Builder(this@SeriesActivity)
                .data(s.poster)
                .crossfade(true)
                .target(holder.poster)
                .build()
            Coil.imageLoader(this@SeriesActivity).enqueue(req)
            holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                holder.itemView.background = getDrawable(
                    if (hasFocus) R.drawable.card_bg_focused else R.drawable.card_bg)
            }
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@SeriesActivity, EpisodesActivity::class.java).apply {
                    putExtra("seriesId", s.id)
                    putExtra("seriesName", s.name)
                })
            }
        }
    }
}
