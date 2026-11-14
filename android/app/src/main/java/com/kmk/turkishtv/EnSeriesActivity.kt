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
import com.kmk.turkishtv.mux.MuxConfig
import com.kmk.turkishtv.mux.MuxEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * English TV series page. Same layout and episode cards as the Turkish
 * series page, different plumbing: episodes are Stremio ids (tt:s:e),
 * sources come from the mux engine in TV mode (Torrentio marks the
 * episode's file inside season packs), and playback resolves through
 * TorBox exactly like a movie.
 *
 * Ids used everywhere else:
 *   seriesId  = "enseries:tt0903747"
 *   videoId   = "enseries:tt0903747:1:5"
 */
class EnSeriesActivity : Activity() {

    companion object {
        const val PREFIX = "enseries:"
    }

    private lateinit var backdrop: ImageView
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var metaLine: TextView
    private lateinit var synopsis: TextView
    private lateinit var btnResume: TextView
    private lateinit var btnSeason: TextView
    private lateinit var btnMyList: TextView
    private lateinit var rowHeader: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: EpisodeAdapter

    private var imdbId = ""
    private var seriesId = ""
    private var seriesName = ""
    private var meta: Api.EnSeriesMeta? = null
    private var episodes: List<Api.EnEpisode> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var currentSeason = 1
    private var target: Api.EnEpisode? = null
    private var historyByVideo: Map<String, WatchState.HistoryEntry> = emptyMap()
    private var busy = false
    private var pendingAutoplay: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_episodes)

        imdbId = intent.getStringExtra("imdbId") ?: run { finish(); return }
        seriesId = PREFIX + imdbId
        seriesName = intent.getStringExtra("name") ?: ""
        pendingAutoplay = intent.getStringExtra("autoplayId")

        backdrop = findViewById(R.id.epBackdrop)
        logo = findViewById(R.id.epLogo)
        title = findViewById(R.id.seriesName)
        metaLine = findViewById(R.id.epMeta)
        synopsis = findViewById(R.id.epSynopsis)
        btnResume = findViewById(R.id.btnResume)
        btnSeason = findViewById(R.id.btnSeason)
        btnMyList = findViewById(R.id.btnMyList)
        rowHeader = findViewById(R.id.epRowHeader)
        list = findViewById(R.id.episodeList)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.itemAnimator = null
        adapter = EpisodeAdapter()
        list.adapter = adapter

        title.text = seriesName
        Ui.loadBackdrop(backdrop, intent.getStringExtra("background") ?: intent.getStringExtra("poster"))
        rowHeader.text = "Loading episodes…"
        btnResume.text = "▶  Play"
        btnResume.setOnClickListener { target?.let { play(it) } }
        btnSeason.setOnClickListener { showSeasonMenu() }
        findViewById<TextView>(R.id.btnCollection).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                val m = meta
                CollectionPicker.show(this@EnSeriesActivity, Collections.Item("enseries", imdbId,
                    m?.name ?: seriesName, m?.poster ?: intent.getStringExtra("poster"),
                    m?.background ?: intent.getStringExtra("background"), m?.year))
            }
        }

        btnMyList.visibility = View.VISIBLE
        paintMyList()
        btnMyList.setOnClickListener {
            val m = meta
            val nowIn = MyList.toggle(this, MyList.Entry("enseries", imdbId, m?.name ?: seriesName,
                m?.poster ?: intent.getStringExtra("poster"), m?.background ?: intent.getStringExtra("background"),
                m?.year, m?.description))
            paintMyList()
            Toast.makeText(this, if (nowIn) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                val m = Api.enSeriesMeta(imdbId)
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

    /** next-episode handoff from the player re-enters here with a new autoplay id */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getStringExtra("autoplayId") ?: return
        val ep = episodes.firstOrNull { it.id == id }
        if (ep != null) play(ep) else pendingAutoplay = id
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

    private fun paintMyList() {
        btnMyList.text = MyList.label(MyList.has(this, "enseries", imdbId))
    }

    private fun refreshHistory() {
        historyByVideo = WatchState.history(this).associateBy { it.videoId }
    }

    private fun render(m: Api.EnSeriesMeta) {
        meta = m
        episodes = m.episodes
        seasons = episodes.map { it.season }.distinct().sorted()
        if (m.name.isNotEmpty()) { seriesName = m.name; title.text = m.name }
        m.background?.let { Ui.loadBackdrop(backdrop, it) }
        m.logo?.let {
            logo.visibility = View.VISIBLE
            title.visibility = View.GONE
            Ui.loadImage(logo, it, 0f, android.R.color.transparent, 150)
        }
        metaLine.text = listOfNotNull(
            m.year,
            "${seasons.size} season${if (seasons.size != 1) "s" else ""}",
            "${episodes.size} episodes",
            m.rating?.let { "★ $it" },
            m.genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() }
        ).joinToString("  ·  ")
        synopsis.text = m.description ?: ""
        m.trailer?.let { yt ->
            findViewById<TextView>(R.id.btnTrailer).apply {
                visibility = View.VISIBLE
                setOnClickListener { Extras.playTrailer(this@EnSeriesActivity, yt, seriesName) }
            }
        }

        refreshHistory()
        computeTarget()
        selectSeason(target?.season ?: seasons.firstOrNull() ?: 1, focusId = target?.id)

        pendingAutoplay?.let { id ->
            pendingAutoplay = null
            episodes.firstOrNull { it.id == id }?.let { play(it) }
        }
    }

    private fun computeTarget() {
        val last = WatchState.lastForSeries(this, seriesId)
        val idx = if (last != null) episodes.indexOfFirst { it.id == last.videoId } else -1
        target = when {
            idx < 0 -> episodes.firstOrNull { !it.isUnaired }
            last != null && !last.isFinished -> episodes[idx]
            else -> episodes.drop(idx + 1).firstOrNull { !it.isUnaired } ?: episodes[idx]
        }
        val t = target
        btnResume.text = when {
            t == null -> "▶  Play"
            last != null && idx >= 0 && !last.isFinished -> "▶  Resume  ·  ${t.tag}" +
                (last.takeIf { it.durationMs > 0 }?.let { "  ·  ${it.minutesLeft} min left" } ?: "")
            idx >= 0 -> "▶  Play next  ·  ${t.tag}"
            else -> "▶  Play  ·  ${t.tag}"
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

    private fun isFinished(e: Api.EnEpisode): Boolean =
        historyByVideo[PREFIX + e.id]?.let { it.durationMs > 0 && it.isFinished } ?: false

    private fun progressOf(e: Api.EnEpisode): Int =
        historyByVideo[PREFIX + e.id]?.let { if (it.durationMs > 0) it.progressPct else 0 } ?: 0

    // ---------------------------------------------------------- playback

    /**
     * Resolve sources for the episode through the mux engine (TV mode),
     * then either play the best instant one or open the picker.
     */
    private fun play(e: Api.EnEpisode) {
        if (busy) return
        val key = Torbox.apiKey(this)
        if (key == null) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle("TorBox key needed")
                .setMessage("English series stream through your TorBox account.\nAdd your API key in Settings first.")
                .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        busy = true
        rowHeader.text = "Finding sources for ${e.tag}…"
        val m = meta
        Thread {
            try {
                val result = MuxEngine.fetch(imdbId, m?.name ?: seriesName, m?.year ?: "", key,
                    MuxConfig.load(this), e.season, e.episode)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    busy = false
                    updateHeader()
                    if (result.streams.isEmpty()) {
                        Toast.makeText(this, "No sources found for ${e.tag}", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val arr = JSONArray()
                    for (t in result.streams) {
                        arr.put(JSONObject()
                            .put("name", t.attrs.resolutionLabel + if (t.isPack) " · pack" else "")
                            .put("title", t.formattedTitle())
                            .put("url", "")
                            .put("hash", t.hash)
                            .put("dn", t.name)
                            .put("encode", t.attrs.encode)
                            .put("cached", t.cached)
                            .put("fileIdx", t.fileIdx)
                            .put("epTag", e.tag))
                    }
                    recordHistory(e)
                    val best = result.streams.indexOfFirst { it.cached }
                    if (best >= 0) launchPlayer(e, arr, best)
                    else startActivity(Intent(this, SourcePickerActivity::class.java).apply {
                        putExtra("movieTitle", "$seriesName · ${e.tag}")
                        putExtra("videoId", PREFIX + e.id)
                        putExtra("videoTitle", "$seriesName · ${e.tag}")
                        putExtra("streamsJson", arr.toString())
                        putExtra("background", m?.background)
                        putExtra("poster", m?.poster)
                        putExtra("seriesId", seriesId)
                        putExtra("seriesName", seriesName)
                        putExtra("seriesBackdrop", m?.background)
                        putExtra("season", e.season)
                        putExtra("episode", e.episode)
                        putStringArrayListExtra("providers", ArrayList(result.providers))
                    })
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    busy = false
                    updateHeader()
                    Toast.makeText(this, "Could not find sources: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun recordHistory(e: Api.EnEpisode) {
        val m = meta
        WatchState.upsertHistory(this, WatchState.HistoryEntry(
            seriesId, seriesName, m?.poster ?: intent.getStringExtra("poster"),
            PREFIX + e.id, e.title, e.season, e.episode,
            WatchState.getPosition(this, PREFIX + e.id), 0, System.currentTimeMillis(),
            thumb = e.thumbnail, backdrop = m?.background
        ))
    }

    private fun launchPlayer(e: Api.EnEpisode, arr: JSONArray, startIndex: Int) {
        val m = meta
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("videoId", PREFIX + e.id)
            putExtra("videoTitle", "$seriesName · ${e.tag}")
            putExtra("streamsJson", arr.toString())
            putExtra("startIndex", startIndex)
            putExtra("seriesId", seriesId)
            putExtra("seriesName", seriesName)
            putExtra("seriesPoster", m?.poster)
            putExtra("seriesBackdrop", m?.background)
            putExtra("season", e.season)
            putExtra("episode", e.episode)
        })
    }

    // ---------------------------------------------------------- cards

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        var items: List<Api.EnEpisode> = emptyList()
            private set

        fun submit(items: List<Api.EnEpisode>) {
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
            Ui.loadImage(holder.still, e.thumbnail ?: meta?.background ?: meta?.poster, 4f)
            holder.num.text = e.episode.toString()
            holder.name.text = e.title.ifEmpty { "Episode ${e.episode}" }
            val unaired = e.isUnaired
            val finished = isFinished(e)
            val pct = progressOf(e)
            holder.dur.text = when {
                unaired -> Ui.airedLabel(e.released) ?: "Not yet aired"
                else -> Ui.airedLabel(e.released) ?: ""
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
            Ui.focusLift(holder.itemView, 1.06f)
            holder.itemView.setOnClickListener { play(e) }
        }
    }
}
