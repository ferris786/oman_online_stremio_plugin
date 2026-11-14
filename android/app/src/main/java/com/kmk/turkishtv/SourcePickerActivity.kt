package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Source sheet: every muxed source grouped into Instant ⚡ and Download ⏳,
 * best pick first and preselected. Picking one launches the chosen player
 * engine on that source (the rest stay available inside the player).
 */
class SourcePickerActivity : Activity() {

    data class Row(val index: Int, val name: String, val title: String, val hash: String,
                   val dn: String, val cached: Boolean)

    private sealed class Item {
        data class Header(val text: String) : Item()
        data class Source(val row: Row, val best: Boolean) : Item()
    }

    private var items: List<Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_picker)

        val movieTitle = intent.getStringExtra("movieTitle") ?: ""
        val streamsJson = intent.getStringExtra("streamsJson") ?: run { finish(); return }
        Ui.loadBackdrop(findViewById(R.id.pkBackdrop), intent.getStringExtra("background") ?: intent.getStringExtra("poster"))

        findViewById<TextView>(R.id.pickerMovie).text = movieTitle

        val arr = org.json.JSONArray(streamsJson)
        val rows = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            rows.add(Row(i, o.optString("name"), o.optString("title"),
                o.optString("hash", ""), o.optString("dn", ""), o.optBoolean("cached", true)))
        }
        val cached = rows.filter { it.cached }
        val uncached = rows.filter { !it.cached }
        val providers = intent.getStringArrayListExtra("providers")?.joinToString(" + ") ?: ""
        findViewById<TextView>(R.id.pickerSub).text =
            "${rows.size} sources  ·  ${cached.size} instant ⚡  ·  ${uncached.size} download ⏳" +
            (if (providers.isNotEmpty()) "\n$providers" else "")

        val built = ArrayList<Item>()
        if (cached.isNotEmpty()) {
            built.add(Item.Header("Instant  ⚡"))
            cached.forEachIndexed { i, r -> built.add(Item.Source(r, i == 0)) }
        }
        if (uncached.isNotEmpty()) {
            built.add(Item.Header("Download first  ⏳"))
            uncached.forEach { built.add(Item.Source(it, false)) }
        }
        items = built

        val recycler = findViewById<RecyclerView>(R.id.pickerList)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = PickerAdapter()
        val first = items.indexOfFirst { it is Item.Source }
        if (first >= 0) focusRow(recycler, first)
    }

    private fun focusRow(recycler: RecyclerView, pos: Int, attempt: Int = 0) {
        recycler.post {
            val v = recycler.findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) v.requestFocus()
            else if (attempt < 6) focusRow(recycler, pos, attempt + 1)
        }
    }

    /**
     * Player routing (English movies only; Turkish always uses ExoPlayer):
     * exo -> PlayerActivity, vlc -> embedded VlcPlayerActivity,
     * external -> resolve in place and hand the URL to any installed player
     * (MX Player / Just Player / VLC app...), Stremio-style.
     */
    private fun launchWithEngine(position: Int) {
        val engine = getSharedPreferences("settings", MODE_PRIVATE).getString("player_engine", "exo") ?: "exo"
        val streamsJson = intent.getStringExtra("streamsJson") ?: return
        recordHistory()

        if (engine == "exo") {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("videoId", intent.getStringExtra("videoId"))
                putExtra("videoTitle", intent.getStringExtra("videoTitle"))
                putExtra("streamsJson", streamsJson)
                putExtra("startIndex", position)
                // English series: the player needs these for next-episode handoff
                for (k in listOf("seriesId", "seriesName", "seriesBackdrop", "seriesPoster"))
                    intent.getStringExtra(k)?.let { putExtra(k, it) }
                if (intent.hasExtra("season")) putExtra("season", intent.getIntExtra("season", 0))
                if (intent.hasExtra("episode")) putExtra("episode", intent.getIntExtra("episode", 0))
            })
            return
        }
        if (engine == "vlc") {
            startActivity(Intent(this, VlcPlayerActivity::class.java).apply {
                putExtra("videoId", intent.getStringExtra("videoId"))
                putExtra("streamsJson", streamsJson)
                putExtra("startIndex", position)
            })
            return
        }

        // external player: resolve the chosen source here, then hand off
        val arr = org.json.JSONArray(streamsJson)
        val st = arr.optJSONObject(position) ?: return
        val key = Torbox.apiKey(this)
        if (key == null) {
            Toast.makeText(this, "Add your TorBox key in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        if (!st.optBoolean("cached", true)) {
            Toast.makeText(this, "External player needs an instant (⚡) source\nor switch the engine in Settings", Toast.LENGTH_LONG).show()
            return
        }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setMessage("Resolving…").setCancelable(false).show()
        Thread {
            val link = Torbox.resolveStream(key, st.optString("hash", ""), st.optString("dn", ""),
                st.optInt("fileIdx", -1), st.optString("epTag", ""))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                dlg.dismiss()
                if (link.startsWith("http")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                            setDataAndType(Uri.parse(link), "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (e: Exception) {
                        Toast.makeText(this, "No external player installed (MX Player / Just Player / VLC)", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, link, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun recordHistory() {
        if (intent.hasExtra("seriesId")) return   // the series page already recorded the episode
        val videoId = intent.getStringExtra("videoId") ?: return
        val name = intent.getStringExtra("movieTitle") ?: return
        val bg = intent.getStringExtra("background")
        WatchState.upsertHistory(this, WatchState.HistoryEntry(
            videoId, name, intent.getStringExtra("poster"), videoId, name, 0, 0,
            WatchState.getPosition(this, videoId), 0, System.currentTimeMillis(), thumb = bg, backdrop = bg))
    }

    /** "1080p · BluRay · x264 · HDR · 12.1GB · 1048↑ · TOR ⚡" -> details / sub lines */
    private fun splitTitle(r: Row): Pair<String, String> {
        val parts = r.title.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        val details = parts.filter { !it.endsWith("GB") && !it.endsWith("MB") && !it.contains("↑") && it != r.name && it != "—" }
            .filter { !Regex("^(TOR|YTS|TPB|SRV)\\s*[⚡⏳]?$").matches(it) }
        val size = parts.firstOrNull { it.endsWith("GB") || it.endsWith("MB") }
        val seeds = parts.firstOrNull { it.contains("↑") }?.replace("↑", " seeds")
        val provider = parts.firstOrNull { Regex("^(TOR|YTS|TPB|SRV)\\s*[⚡⏳]?$").matches(it) }
            ?.replace(Regex("[⚡⏳]"), "")?.trim()?.let {
                when (it) { "TOR" -> "Torrentio"; "SRV" -> "Addon"; else -> it }
            }
        return Pair(details.joinToString("  ·  "), listOfNotNull(size, seeds, provider).joinToString("  ·  "))
    }

    private inner class PickerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v)
        inner class SourceVH(v: View) : RecyclerView.ViewHolder(v) {
            val quality: TextView = v.findViewById(R.id.sQuality)
            val details: TextView = v.findViewById(R.id.sDetails)
            val sub: TextView = v.findViewById(R.id.sSub)
            val badge: TextView = v.findViewById(R.id.sBadge)
        }

        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) HeaderVH(layoutInflater.inflate(R.layout.item_source_header, parent, false))
            else SourceVH(layoutInflater.inflate(R.layout.item_source_row, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder.itemView as TextView).text = item.text
                is Item.Source -> {
                    val h = holder as SourceVH
                    val r = item.row
                    h.quality.text = when (r.name) { "2160p" -> "4K"; else -> r.name }
                    val (details, sub) = splitTitle(r)
                    h.details.text = details
                    h.sub.text = sub
                    h.badge.text = when {
                        item.best -> "BEST PICK"
                        r.cached -> "⚡"
                        else -> "⏳"
                    }
                    h.badge.setTextColor(resources.getColor(if (r.cached) R.color.accent else R.color.text_dim))
                    h.itemView.setOnFocusChangeListener { v, f ->
                        v.translationZ = if (f) 8f else 0f
                        h.badge.setTextColor(resources.getColor(
                            if (f) R.color.accent_dark else if (r.cached) R.color.accent else R.color.text_dim))
                    }
                    h.itemView.setOnClickListener { launchWithEngine(r.index) }
                }
            }
        }
    }
}
