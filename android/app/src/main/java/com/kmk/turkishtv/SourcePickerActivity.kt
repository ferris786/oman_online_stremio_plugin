package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Full-screen source picker (Comet-style): shows every muxed source as a
 * large, D-pad-friendly row with cached badge, quality, size, seeders and
 * provider. Picking one launches the player on that source (the rest remain
 * available as chips in the player).
 */
class SourcePickerActivity : Activity() {

    data class Row(val name: String, val title: String, val hash: String,
                   val dn: String, val cached: Boolean)

    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_picker)

        val movieTitle = intent.getStringExtra("movieTitle") ?: ""
        val streamsJson = intent.getStringExtra("streamsJson") ?: run { finish(); return }

        findViewById<TextView>(R.id.pickerMovie).text = movieTitle

        val arr = org.json.JSONArray(streamsJson)
        val list = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Row(o.optString("name"), o.optString("title"),
                o.optString("hash", ""), o.optString("dn", ""),
                o.optBoolean("cached", true)))
        }
        rows = list
        val cached = list.count { it.cached }
        val providers = intent.getStringArrayListExtra("providers")?.joinToString(" + ") ?: ""
        findViewById<TextView>(R.id.pickerSub).text =
            "${list.size} sources · $cached instant ⚡ · ${list.size - cached} download ⏳" +
            (if (providers.isNotEmpty()) "\n$providers" else "")

        val recycler = findViewById<RecyclerView>(R.id.pickerList)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = PickerAdapter()
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

        if (engine == "exo") {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("videoId", intent.getStringExtra("videoId"))
                putExtra("videoTitle", intent.getStringExtra("videoTitle"))
                putExtra("streamsJson", streamsJson)
                putExtra("startIndex", position)
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
            val link = Torbox.resolveStream(key, st.optString("hash", ""), st.optString("dn", ""))
            runOnUiThread {
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

    private inner class PickerAdapter : RecyclerView.Adapter<PickerAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val badge: TextView = v.findViewById(R.id.sBadge)
            val quality: TextView = v.findViewById(R.id.sQuality)
            val details: TextView = v.findViewById(R.id.sDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_source_row, parent, false) as LinearLayout
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = rows[position]
            holder.badge.text = if (r.cached) "⚡" else "⏳"
            holder.badge.setTextColor(resources.getColor(if (r.cached) R.color.accent else R.color.text_dim))
            holder.quality.text = r.name
            holder.details.text = r.title.substringAfter("·", r.title).trim()
            holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.03f else 1f
                holder.itemView.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                holder.itemView.translationZ = if (hasFocus) 12f else 0f
            }
            holder.itemView.setOnClickListener { launchWithEngine(position) }
        }
    }
}
