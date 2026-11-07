package com.kmk.turkishtv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class EpisodesActivity : Activity() {

    private lateinit var seasonRow: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var adapter: EpisodeAdapter

    private var episodes: List<Api.Episode> = emptyList()
    private var seasons: List<Int> = emptyList()

    private var seriesId: String? = null
    private var seriesName: String? = null
    private var seriesPoster: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_episodes)

        seriesId = intent.getStringExtra("seriesId")
        seriesName = intent.getStringExtra("seriesName") ?: "Episodes"
        seriesPoster = intent.getStringExtra("seriesPoster")
        findViewById<TextView>(R.id.seriesName).text = seriesName
        seasonRow = findViewById(R.id.seasonRow)
        list = findViewById(R.id.episodeList)
        list.layoutManager = LinearLayoutManager(this)

        adapter = EpisodeAdapter()
        list.adapter = adapter

        Thread {
            try {
                val eps = Api.episodes(seriesId ?: return@Thread)
                val seasons = eps.map { it.season }.distinct().sorted()
                runOnUiThread {
                    this.episodes = eps
                    this.seasons = seasons
                    buildSeasonTabs()
                    selectSeason(seasons.lastOrNull() ?: 1)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load episodes: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun focusFx(view: View) {
        view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1f
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, "scaleX", scale),
                    ObjectAnimator.ofFloat(view, "scaleY", scale)
                )
                duration = 130
                interpolator = DecelerateInterpolator()
                start()
            }
            view.translationZ = if (hasFocus) 10f else 0f
        }
    }

    private fun buildSeasonTabs() {
        seasonRow.removeAllViews()
        val pad = (resources.displayMetrics.density * 18).toInt()
        for (s in seasons) {
            val tab = TextView(this).apply {
                text = "Season $s"
                textSize = 16f
                setTextColor(resources.getColor(R.color.text))
                gravity = Gravity.CENTER
                setPadding(pad, pad / 2, pad, pad / 2)
                background = getDrawable(R.drawable.row_selector)
                val m = (resources.displayMetrics.density * 10).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = m }
                setOnClickListener { selectSeason(s) }
                focusFx(this)
            }
            seasonRow.addView(tab)
        }
    }

    private fun selectSeason(season: Int) {
        adapter.submit(episodes.filter { it.season == season })
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        private var items: List<Api.Episode> = emptyList()

        fun submit(items: List<Api.Episode>) {
            this.items = items
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.epLabel)
            val title: TextView = v.findViewById(R.id.epTitle)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_episode, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.label.text = "S${e.season}E${e.episode}"
            holder.title.text = if (WatchState.getPosition(this@EpisodesActivity, e.id) > Config.RESUME_THRESHOLD_MS)
                "${e.title}  ✓" else e.title
            focusFx(holder.itemView)
            holder.itemView.setOnClickListener {
                val sid = seriesId ?: return@setOnClickListener
                val sname = seriesName ?: ""
                // record in Continue Watching
                WatchState.upsertHistory(this@EpisodesActivity, WatchState.HistoryEntry(
                    sid, sname, seriesPoster, e.id, e.title, e.season, e.episode,
                    WatchState.getPosition(this@EpisodesActivity, e.id), 0, System.currentTimeMillis()
                ))
                startActivity(Intent(this@EpisodesActivity, PlayerActivity::class.java).apply {
                    putExtra("videoId", e.id)
                    putExtra("videoTitle", "$sname · S${e.season}E${e.episode}")
                    putExtra("seriesId", sid)
                    putExtra("seriesName", sname)
                    putExtra("season", e.season)
                    putExtra("episode", e.episode)
                })
            }
        }
    }
}
