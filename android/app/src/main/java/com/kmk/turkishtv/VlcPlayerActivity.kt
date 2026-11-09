package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Embedded VLC player for English movies (chosen in Settings). libVLC decodes
 * virtually everything in software where the device decoder bails - it exists
 * precisely for the HEVC/Dolby-Vision files ExoPlayer struggles with on
 * some TVs. Turkish playback always stays on ExoPlayer.
 *
 * Controls: OK pause/play, LEFT/RIGHT seek 10s, MENU source browser,
 * Channel +/- cycles sources, BACK saves position and exits.
 */
class VlcPlayerActivity : Activity() {

    data class VStream(val name: String, val title: String, val url: String,
                       val hash: String, val dn: String, val cached: Boolean)

    private lateinit var libVlc: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var status: TextView

    private var streams: List<VStream> = emptyList()
    private var streamIndex = 0
    private var videoId = ""
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vlc_player)

        videoId = intent.getStringExtra("videoId") ?: run { finish(); return }
        val streamsJson = intent.getStringExtra("streamsJson") ?: run { finish(); return }
        status = findViewById(R.id.vlcStatus)
        videoLayout = findViewById(R.id.vlcVideoLayout)

        val arr = org.json.JSONArray(streamsJson)
        val list = ArrayList<VStream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(VStream(o.optString("name"), o.optString("title"), o.optString("url"),
                o.optString("hash", ""), o.optString("dn", ""), o.optBoolean("cached", true)))
        }
        streams = list
        streamIndex = intent.getIntExtra("startIndex", 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))

        libVlc = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        mp = MediaPlayer(libVlc)
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                // MediaPlayer.EncounteredError == 516 in libVLC 3.x
                if (event.type == 516) {
                    runOnUiThread {
                        Toast.makeText(this@VlcPlayerActivity, "Playback error - switching source", Toast.LENGTH_SHORT).show()
                        nextSource()
                    }
                }
            }
        })

        findViewById<TextView>(R.id.vlcHint).postDelayed({ findViewById<TextView>(R.id.vlcHint).visibility = View.GONE }, 6000)
        playCurrent()
        status.postDelayed(saveLoop, 10_000)
    }

    private fun playCurrent(resume: Boolean = true) {
        if (streams.isEmpty()) { finish(); return }
        val s = streams[streamIndex]
        status.text = "${streamIndex + 1}/${streams.size} · ${s.name} · VLC"
        started = false

        when {
            s.url.startsWith("http") -> startMedia(s.url, resume)
            s.hash.isNotEmpty() -> resolveThenPlay(s, resume)
            else -> nextSource()
        }
    }

    private fun startMedia(url: String, resume: Boolean) {
        val pos = if (resume) WatchState.getPosition(this, videoId) else 0L
        val media = Media(libVlc, Uri.parse(url))
        mp.media = media
        media.release()
        mp.play()
        if (pos > Config.RESUME_THRESHOLD_MS) {
            mp.setTime(pos, true)
            Toast.makeText(this, "Resuming", Toast.LENGTH_SHORT).show()
        }
        started = true
    }

    private fun resolveThenPlay(s: VStream, resume: Boolean) {
        val key = Torbox.apiKey(this)
        if (key == null) {
            Toast.makeText(this, "Add your TorBox key in Settings first", Toast.LENGTH_LONG).show()
            finish(); return
        }
        status.text = "Resolving ${s.name}…"
        Thread {
            val result = if (s.cached) Torbox.resolveStream(key, s.hash, s.dn.ifEmpty { s.name })
            else resolveUncachedBlocking(key, s)
            runOnUiThread {
                if (result.startsWith("http")) {
                    streams = streams.toMutableList().also { it[streamIndex] = s.copy(url = result) }
                    startMedia(result, resume)
                } else {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    nextSource()
                }
            }
        }.start()
    }

    /** download-on-demand for ⏳ sources (same flow as the Exo player) */
    private fun resolveUncachedBlocking(key: String, s: VStream): String {
        val torrentId = Torbox.startDownload(key, s.hash, s.dn.ifEmpty { s.name })
        if (torrentId <= 0) return "Could not start download (plan limit reached?)"
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 30 * 60 * 1000) {
            val p = Torbox.downloadProgress(key, torrentId)
            if (p.ready) return Torbox.resolveStream(key, s.hash, s.dn.ifEmpty { s.name })
            runOnUiThread { status.text = "⏳ ${p.percent}% · ${String.format("%.1f", p.speedMbps)} MB/s" }
            Thread.sleep(2500)
        }
        return "Download timed out"
    }

    private fun nextSource() {
        if (streams.isEmpty()) return
        streamIndex = (streamIndex + 1) % streams.size
        mp.stop()
        playCurrent(resume = false)
    }

    private val saveLoop = object : Runnable {
        override fun run() {
            if (started && mp.isPlaying) {
                WatchState.savePosition(this@VlcPlayerActivity, videoId, mp.getTime())
                WatchState.updateHistoryPosition(this@VlcPlayerActivity, videoId, mp.getTime(), mp.getLength())
            }
            status.postDelayed(this, 10_000)
        }
    }

    private fun showSourceBrowser() {
        if (streams.isEmpty()) return
        val names = streams.mapIndexed { i, s ->
            (if (i == streamIndex) "▶ " else "   ") +
                (if (s.cached) "⚡ " else "⏳ ") + s.title
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Sources (${streams.size})")
            .setItems(names) { d, which ->
                d.dismiss()
                if (which != streamIndex) {
                    streamIndex = which
                    mp.stop()
                    playCurrent(resume = false)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (mp.isPlaying) mp.pause() else mp.play(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                mp.setTime((mp.getTime() + 10_000).coerceAtMost(mp.getLength() - 1000), true); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                mp.setTime((mp.getTime() - 10_000).coerceAtLeast(0), true); return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { nextSource(); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                streamIndex = (streamIndex - 1 + streams.size) % streams.size
                mp.stop(); playCurrent(resume = false); return true
            }
            KeyEvent.KEYCODE_MENU -> { showSourceBrowser(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        if (started) {
            WatchState.savePosition(this, videoId, mp.getTime())
            WatchState.updateHistoryPosition(this, videoId, mp.getTime(), mp.getLength())
        }
        status.removeCallbacks(saveLoop)
        super.onStop()
    }

    override fun onDestroy() {
        mp.stop()
        mp.detachViews()
        mp.release()
        libVlc.release()
        super.onDestroy()
    }
}
