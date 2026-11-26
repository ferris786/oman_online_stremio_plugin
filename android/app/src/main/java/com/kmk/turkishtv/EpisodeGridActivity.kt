package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Every episode of a series, at once.
 *
 * The episode strip on the series page shows four or five at a time. That
 * is fine for a twelve-part drama and useless for Kurulus Osman, which has
 * 194 episodes across six seasons; reaching episode 147 means holding the
 * D-pad even with the season jump. This grid shows about fifty at a time,
 * keeps the season breaks in view, and has a number pad for going straight
 * to one.
 *
 * No thumbnails, deliberately. Fifty thumbnails is fifty image loads on a
 * box already tight for memory, and the number is what anyone scanning for
 * an episode is actually reading.
 */
class EpisodeGridActivity : Activity() {

    companion object {
        /** 84dp tiles plus margins across a 960dp screen */
        private const val COLUMNS = 9
    }

    private lateinit var list: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var subView: TextView

    private var seriesId = ""
    private var seriesName = ""
    private var rows: List<Row> = emptyList()
    private var history: Map<String, WatchState.HistoryEntry> = emptyMap()

    /** the grid holds both season labels and episodes */
    private sealed class Row {
        data class Season(val number: Int, val count: Int) : Row()
        data class Ep(val e: Api.Episode) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_episode_grid)

        seriesId = intent.getStringExtra("seriesId") ?: run { finish(); return }
        seriesName = intent.getStringExtra("seriesName") ?: "Episodes"

        titleView = findViewById(R.id.gridTitle)
        subView = findViewById(R.id.gridSub)
        list = findViewById(R.id.gridList)

        titleView.text = seriesName
        subView.text = "Loading episodes…"

        val lm = GridLayoutManager(this, COLUMNS)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            // a season label takes the whole row; an episode takes one cell
            override fun getSpanSize(position: Int): Int =
                if (rows.getOrNull(position) is Row.Season) COLUMNS else 1
        }
        list.layoutManager = lm
        list.setHasFixedSize(true)
        list.itemAnimator = null

        findViewById<TextView>(R.id.gridJump).setOnClickListener { askEpisodeNumber() }

        load()
    }

    private fun load() {
        Thread {
            val meta = try { Api.seriesMeta(seriesId) } catch (e: Exception) { null }
            val hist = WatchState.history(this).associateBy { it.videoId }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (meta == null || meta.videos.isEmpty()) {
                    subView.text = "Could not load the episode list"
                    return@runOnUiThread
                }
                history = hist
                build(meta.videos)
            }
        }.start()
    }

    private fun build(videos: List<Api.Episode>) {
        val out = ArrayList<Row>()
        for ((season, eps) in videos.groupBy { it.season }.toSortedMap()) {
            out.add(Row.Season(season, eps.size))
            eps.sortedBy { it.episode }.forEach { out.add(Row.Ep(it)) }
        }
        rows = out
        list.adapter = GridAdapter()

        val seasons = videos.map { it.season }.distinct().size
        subView.text = "${videos.size} episodes   ·   $seasons seasons   ·   " +
            "hold ◀ ▶ to move quickly"

        // open where the viewer actually is, not at episode one
        val resumeAt = videos.indexOfFirst { history[it.id]?.let { h -> !h.isFinished } == true }
        val firstUnwatched = videos.indexOfFirst { history[it.id] == null }
        val land = when {
            resumeAt >= 0 -> videos[resumeAt]
            firstUnwatched > 0 -> videos[firstUnwatched]
            else -> null
        }
        val pos = if (land != null) rows.indexOfFirst { it is Row.Ep && it.e.id == land.id } else 1
        focusAt(if (pos >= 0) pos else 1)
    }

    /** scroll the position into view and put focus on it, once it exists */
    private fun focusAt(pos: Int, attempt: Int = 0) {
        if (pos < 0 || pos >= rows.size) return
        (list.layoutManager as? GridLayoutManager)
            ?.scrollToPositionWithOffset(pos, Ui.dp(this, 80))
        list.post {
            val v = list.findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) v.requestFocus()
            else if (attempt < 6) focusAt(pos, attempt + 1)
        }
    }

    /**
     * Straight to a number. With 194 episodes this is the difference
     * between one entry and a long scroll, and the digits are already the
     * fastest thing to enter on a remote.
     */
    private fun askEpisodeNumber() {
        val input = android.widget.EditText(this).apply {
            hint = "Episode number"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            val p = Ui.dp(this@EpisodeGridActivity, 16)
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Jump to episode")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().trim().toIntOrNull() ?: return@setPositiveButton
                // match the number the sites use, which is what the viewer sees
                val pos = rows.indexOfFirst { it is Row.Ep && it.e.absNumber == n }
                val byEp = rows.indexOfFirst { it is Row.Ep && it.e.episode == n }
                when {
                    pos >= 0 -> focusAt(pos)
                    byEp >= 0 -> focusAt(byEp)
                    else -> Toast.makeText(this, "No episode $n in this series",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    /** held LEFT/RIGHT paced by RowNav, and stopped at the end of a line */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        if (RowNav.handle(this, event)) true else super.dispatchKeyEvent(event)

    private fun play(e: Api.Episode) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtras(intent)
            putExtra("videoId", e.id)
            putExtra("videoTitle", "$seriesName · S${e.season}E${e.episode}")
            putExtra("season", e.season)
            putExtra("episode", e.episode)
        })
        finish()
    }

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_SEASON = 0
        private val TYPE_EP = 1

        inner class SeasonVH(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.seasonLabel)
        }
        inner class EpVH(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.tileNum)
            val tick: TextView = v.findViewById(R.id.tileTick)
            val progress: ProgressBar = v.findViewById(R.id.tileProgress)
        }

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Season) TYPE_SEASON else TYPE_EP

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_SEASON)
                SeasonVH(layoutInflater.inflate(R.layout.item_episode_season, parent, false))
            else EpVH(layoutInflater.inflate(R.layout.item_episode_tile, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Season -> (holder as SeasonVH).label.text =
                    "SEASON ${row.number}   ·   ${row.count} EPISODES"
                is Row.Ep -> {
                    val h = holder as EpVH
                    val e = row.e
                    h.num.text = e.absNumber.toString()
                    val seen = history[e.id]
                    // a finished episode gets a tick; a part-watched one
                    // keeps its progress, which is the thing worth seeing
                    // at a glance across two hundred of them
                    h.tick.visibility = if (seen?.isFinished == true) View.VISIBLE else View.GONE
                    val pct = if (seen != null && !seen.isFinished) seen.progressPct else 0
                    h.progress.progress = pct
                    h.progress.visibility = if (pct > 0) View.VISIBLE else View.GONE
                    h.itemView.alpha = if (e.isUnaired) 0.45f else 1f
                    Ui.focusLift(h.itemView, 1.12f)
                    h.itemView.setOnClickListener {
                        if (e.isUnaired) Toast.makeText(this@EpisodeGridActivity,
                            "Episode ${e.absNumber} has not aired yet", Toast.LENGTH_SHORT).show()
                        else play(e)
                    }
                }
            }
        }
    }
}
