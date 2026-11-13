package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Series page: backdrop + synopsis, Resume button that knows where you are,
 * a season menu, and a row of episode cards with stills, runtime, overview
 * and watched / in-progress / new / unaired states. Opens on the season of
 * the next episode to watch and focuses it.
 */
class EpisodesActivity : Activity() {

    private lateinit var backdrop: ImageView
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var metaLine: TextView
    private lateinit var synopsis: TextView
    private lateinit var btnResume: TextView
    private lateinit var btnSeason: TextView
    private lateinit var rowHeader: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: EpisodeAdapter

    private var seriesId: String = ""
    private var seriesName: String = ""
    private var seriesPoster: String? = null
    private var seriesBackdrop: String? = null
    private var meta: Api.SeriesMeta? = null
    private var episodes: List<Api.Episode> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var currentSeason = 1
    private var target: Api.Episode? = null
    private var targetIsResume = false
    private var historyByVideo: Map<String, WatchState.HistoryEntry> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_episodes)

        seriesId = intent.getStringExtra("seriesId") ?: run { finish(); return }
        seriesName = intent.getStringExtra("seriesName") ?: "Episodes"
        seriesPoster = intent.getStringExtra("seriesPoster")
        seriesBackdrop = intent.getStringExtra("seriesBackdrop")

        backdrop = findViewById(R.id.epBackdrop)
        logo = findViewById(R.id.epLogo)
        title = findViewById(R.id.seriesName)
        metaLine = findViewById(R.id.epMeta)
        synopsis = findViewById(R.id.epSynopsis)
        btnResume = findViewById(R.id.btnResume)
        btnSeason = findViewById(R.id.btnSeason)
        rowHeader = findViewById(R.id.epRowHeader)
        list = findViewById(R.id.episodeList)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.itemAnimator = null
        adapter = EpisodeAdapter()
        list.adapter = adapter

        title.text = seriesName
        Ui.loadBackdrop(backdrop, seriesBackdrop ?: seriesPoster)
        intent.getStringExtra("seriesLogo")?.let { showLogo(it) }
        btnResume.text = "▶  Play"
        rowHeader.text = "Loading episodes…"

        btnResume.setOnClickListener { target?.let { play(it) } }
        btnSeason.setOnClickListener { showSeasonMenu() }

        Thread {
            try {
                val m = Api.seriesMeta(seriesId)
                runOnUiThread { if (!isFinishing) render(m) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        rowHeader.text = "Could not load episodes"
                        Toast.makeText(this, "Could not load episodes: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (episodes.isNotEmpty()) {
            refreshHistory()
            computeTarget()
            adapter.notifyDataSetChanged()
            updateHeader()
        }
    }

    private fun showLogo(url: String) {
        logo.visibility = View.VISIBLE
        title.visibility = View.GONE
        Ui.loadImage(logo, url, 0f, android.R.color.transparent, 150)
    }

    private fun refreshHistory() {
        historyByVideo = WatchState.history(this).associateBy { it.videoId }
    }

    private fun render(m: Api.SeriesMeta) {
        meta = m
        episodes = m.videos
        seasons = episodes.map { it.season }.distinct().sorted()
        if (m.name.isNotEmpty()) { seriesName = m.name; title.text = m.name }
        m.background?.let { seriesBackdrop = it; Ui.loadBackdrop(backdrop, it) }
        m.logo?.let { showLogo(it) }
        metaLine.text = listOfNotNull(
            m.year,
            "${seasons.size} season${if (seasons.size != 1) "s" else ""}",
            "${episodes.size} episodes",
            m.genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() }
        ).joinToString("  ·  ")
        synopsis.text = m.description ?: ""

        m.trailer?.let { yt ->
            findViewById<TextView>(R.id.btnTrailer).apply {
                visibility = View.VISIBLE
                setOnClickListener { Extras.playTrailer(this@EpisodesActivity, yt, seriesName) }
            }
        }

        refreshHistory()
        computeTarget()
        selectSeason(target?.season ?: seasons.lastOrNull() ?: 1, focusId = target?.id)
    }

    /** where "Play" goes: resume the unfinished episode, else the one after the last finished, else episode 1 */
    private fun computeTarget() {
        val last = WatchState.lastForSeries(this, seriesId)
        val idx = if (last != null) episodes.indexOfFirst { it.id == last.videoId } else -1
        target = when {
            idx < 0 -> episodes.firstOrNull()
            last != null && !last.isFinished -> episodes[idx]
            else -> episodes.getOrNull(idx + 1) ?: episodes[idx]
        }
        targetIsResume = last != null && idx >= 0 && !last.isFinished
        val t = target
        btnResume.text = when {
            t == null -> "▶  Play"
            targetIsResume -> "▶  Resume  ·  Episode ${t.absNumber}" +
                (last?.takeIf { it.durationMs > 0 }?.let { "  ·  ${it.minutesLeft} min left" } ?: "")
            idx >= 0 -> "▶  Play next  ·  Episode ${t.absNumber}"
            else -> "▶  Play  ·  Episode ${t.absNumber}"
        }
    }

    private fun showSeasonMenu() {
        if (seasons.isEmpty()) return
        val labels = seasons.map { s -> "Season $s   ·   ${episodes.count { it.season == s }} episodes" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle(seriesName)
            .setSingleChoiceItems(labels, seasons.indexOf(currentSeason)) { d, which ->
                d.dismiss()
                selectSeason(seasons[which], focusId = null)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun selectSeason(season: Int, focusId: String?) {
        currentSeason = season
        btnSeason.text = "Season $season  ▾"
        adapter.submit(episodes.filter { it.season == season })
        updateHeader()
        val items = adapter.items
        val idx = if (focusId != null) items.indexOfFirst { it.id == focusId } else 0
        val pos = if (idx >= 0) idx else 0
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, Ui.dp(this, 6))
        focusCard(pos)
    }

    /** the holder may not exist until the next layout pass; retry a few frames */
    private fun focusCard(pos: Int, attempt: Int = 0) {
        list.post {
            val v = list.findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) v.requestFocus()
            else if (attempt < 6) focusCard(pos, attempt + 1)
        }
    }

    private fun updateHeader() {
        val inSeason = episodes.filter { it.season == currentSeason }
        val watched = inSeason.count { isFinished(it) }
        rowHeader.text = "Season $currentSeason   ·   ${inSeason.size} episodes" +
            (if (watched > 0) "   ·   $watched watched" else "")
    }

    private fun isFinished(e: Api.Episode): Boolean {
        historyByVideo[e.id]?.let { if (it.durationMs > 0) return it.isFinished }
        val pos = WatchState.getPosition(this, e.id)
        return e.runtimeMin > 0 && pos >= e.runtimeMin * 60_000L * 93 / 100
    }

    private fun progressOf(e: Api.Episode): Int {
        historyByVideo[e.id]?.let { if (it.durationMs > 0) return it.progressPct }
        val pos = WatchState.getPosition(this, e.id)
        if (pos <= Config.RESUME_THRESHOLD_MS) return 0
        return if (e.runtimeMin > 0) ((pos * 100) / (e.runtimeMin * 60_000L)).toInt().coerceIn(1, 100) else 0
    }

    private fun play(e: Api.Episode) {
        WatchState.upsertHistory(this, WatchState.HistoryEntry(
            seriesId, seriesName, seriesPoster, e.id, e.title, e.season, e.episode,
            WatchState.getPosition(this, e.id), 0, System.currentTimeMillis(),
            thumb = e.thumbnail, backdrop = seriesBackdrop
        ))
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("videoId", e.id)
            putExtra("videoTitle", "$seriesName · S${e.season}E${e.episode}")
            putExtra("seriesId", seriesId)
            putExtra("seriesName", seriesName)
            putExtra("seriesPoster", seriesPoster)
            putExtra("seriesBackdrop", seriesBackdrop)
            putExtra("season", e.season)
            putExtra("episode", e.episode)
        })
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        var items: List<Api.Episode> = emptyList()
            private set

        fun submit(items: List<Api.Episode>) {
            this.items = items
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val still: ImageView = v.findViewById(R.id.epStill)
            val num: TextView = v.findViewById(R.id.epNum)
            val state: TextView = v.findViewById(R.id.epState)
            val progress: ProgressBar = v.findViewById(R.id.epProgress)
            val name: TextView = v.findViewById(R.id.epName)
            val dur: TextView = v.findViewById(R.id.epDur)
            val overview: TextView = v.findViewById(R.id.epOverview)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_episode_card, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            Ui.loadImage(holder.still, e.thumbnail ?: seriesBackdrop ?: seriesPoster, 4f)
            holder.num.text = e.absNumber.toString()
            holder.name.text = "Episode ${e.absNumber}"
            val unaired = e.isUnaired
            val finished = isFinished(e)
            val pct = progressOf(e)
            holder.dur.text = when {
                unaired -> Ui.airedLabel(e.released) ?: "Not yet aired"
                pct in 1..92 && !finished && e.runtimeMin > 0 -> "${(e.runtimeMin * (100 - pct)) / 100} min left"
                e.runtimeMin > 0 -> Ui.minutesLabel(e.runtimeMin)
                else -> ""
            }
            holder.overview.text = e.overview ?: if (unaired) "Not yet aired." else ""
            holder.itemView.alpha = if (unaired) 0.55f else 1f
            when {
                finished -> {
                    holder.state.text = "✓  WATCHED"
                    holder.state.setBackgroundResource(R.drawable.chip_gold)
                    holder.state.setTextColor(resources.getColor(R.color.accent_dark))
                    holder.state.visibility = View.VISIBLE
                }
                !unaired && (Ui.daysSince(e.released)?.let { it in 0..Ui.NEW_WINDOW_DAYS } == true) -> {
                    holder.state.text = "NEW"
                    holder.state.setBackgroundResource(R.drawable.chip_new)
                    holder.state.setTextColor(resources.getColor(R.color.white))
                    holder.state.visibility = View.VISIBLE
                }
                else -> holder.state.visibility = View.GONE
            }
            if (!finished && pct > 0) {
                holder.progress.progress = pct
                holder.progress.visibility = View.VISIBLE
            } else holder.progress.visibility = View.GONE

            // ring on the still only (duplicateParentState); the card lifts as a whole
            Ui.focusLift(holder.itemView, 1.06f)
            holder.itemView.setOnClickListener { play(e) }
        }
    }
}
