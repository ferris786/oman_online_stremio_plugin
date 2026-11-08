package com.kmk.turkishtv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var sourceLabel: TextView
    private lateinit var sourceChipRow: LinearLayout

    private var streams: List<Api.Stream> = emptyList()
    private var streamIndex = 0
    private var videoId: String = ""

    // Set once the first frame renders - after that, errors trigger fallback
    private var playbackStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        sourceLabel = findViewById(R.id.sourceLabel)
        sourceChipRow = findViewById(R.id.sourceChipRow)
        videoId = intent.getStringExtra("videoId") ?: run { finish(); return }

        player = buildPlayer()
        playerView.player = player
        setupSourceBarVisibility()

        // Pre-resolved streams (English movies resolved through TorBox) are
        // passed directly instead of hitting the addon /stream endpoint.
        val preResolved = intent.getStringExtra("streamsJson")
        if (preResolved != null) {
            val arr = org.json.JSONArray(preResolved)
            val list = ArrayList<Api.Stream>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Api.Stream(o.optString("name"), o.optString("title"), o.optString("url")))
            }
            streams = list
            streamIndex = 0
            buildSourceChips()
            playCurrent()
            return
        }

        Thread {
            try {
                val s = Api.streams(videoId)
                runOnUiThread {
                    if (s.isEmpty()) {
                        Toast.makeText(this, "No streams available for this episode", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        streams = s
                        streamIndex = 0
                        buildSourceChips()
                        playCurrent()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load streams: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun buildPlayer(): ExoPlayer {
        // Generous buffers for flaky streaming CDNs: keep 30s minimum / 2 min
        // maximum buffered, start after 3s (5s after a stall).
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,          // minBufferMs
                120_000,         // maxBufferMs
                3_000,           // bufferForPlaybackMs
                5_000            // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        return ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .setTrackSelector(DefaultTrackSelector(this))
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) playbackStarted = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // While nothing has played yet, hop to the next source
                        if (!playbackStarted) {
                            tryNextSource("Source failed (${error.errorCodeName})")
                        } else {
                            Toast.makeText(this@PlayerActivity,
                                "Playback error: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
    }

    private fun isYouTube(url: String): Boolean =
        url.contains("youtube.com") || url.contains("youtu.be")

    private fun launchYouTube(url: String) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, "Opening in YouTube…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "YouTube app required for this episode", Toast.LENGTH_LONG).show()
        }
    }

    private fun playCurrent(resumeMs: Long = -1L) {
        if (streamIndex >= streams.size) {
            Toast.makeText(this, "All sources failed", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val s = streams[streamIndex]
        playbackStarted = false
        sourceLabel.text = "${streamIndex + 1}/${streams.size} · ${s.name}"
        if (isYouTube(s.url)) {
            launchYouTube(s.url)
            playbackStarted = true // don't let auto-fallback fire the intent
            return
        }
        player.setMediaItem(MediaItem.fromUri(s.url))
        player.prepare()
        val pos = if (resumeMs >= 0) resumeMs else WatchState.getPosition(this, videoId)
        if (pos > Config.RESUME_THRESHOLD_MS && pos < 4 * 60 * 60 * 1000) {
            player.seekTo(pos)
            Toast.makeText(this, "Resuming", Toast.LENGTH_SHORT).show()
        }
        player.playWhenReady = true
    }

    private fun tryNextSource(reason: String) {
        Toast.makeText(this, "$reason - trying next source…", Toast.LENGTH_SHORT).show()
        // skip YouTube entries during auto-fallback: opening another app
        // should be a deliberate choice (chip or number key), not a surprise
        while (streamIndex + 1 < streams.size && isYouTube(streams[streamIndex + 1].url)) {
            streamIndex++
        }
        streamIndex++
        highlightChips()
        playCurrent()
    }

    private fun cycleSource(delta: Int) {
        if (streams.isEmpty()) return
        switchToSource((streamIndex + delta + streams.size) % streams.size)
    }

    private fun switchToSource(index: Int) {
        if (streams.isEmpty() || index < 0 || index >= streams.size) return
        streamIndex = index
        playbackStarted = false
        val s = streams[streamIndex]
        sourceLabel.text = "${streamIndex + 1}/${streams.size} · ${s.name}"
        highlightChips()
        if (isYouTube(s.url)) {
            launchYouTube(s.url)
            playbackStarted = true
            return
        }
        val pos = player.currentPosition
        player.setMediaItem(MediaItem.fromUri(s.url))
        player.prepare()
        if (pos > Config.RESUME_THRESHOLD_MS) player.seekTo(pos)
        player.playWhenReady = true
        Toast.makeText(this, "Source: ${s.name}", Toast.LENGTH_SHORT).show()
    }

    /**
     * The source bar shows/hides together with the player controls so it never
     * distracts from viewing. While a chip has focus it stays visible even if
     * the controls time out underneath it, and hides once focus leaves.
     */
    private fun setupSourceBarVisibility() {
        val bar = findViewById<View>(R.id.sourceChips)
        bar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !playerView.isControllerFullyVisible) {
                bar.visibility = View.GONE
            }
        }
        playerView.setControllerVisibilityListener(
            object : androidx.media3.ui.PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    if (visibility == View.VISIBLE) {
                        bar.visibility = View.VISIBLE
                    } else if (!bar.hasFocus()) {
                        bar.visibility = View.GONE
                    }
                }
            }
        )
    }

    private fun buildSourceChips() {
        sourceChipRow.removeAllViews()
        val density = resources.displayMetrics.density
        streams.forEachIndexed { i, stream ->
            val chip = TextView(this).apply {
                text = "${i + 1} · ${stream.name}"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text))
                background = getDrawable(R.drawable.row_selector)
                val h = (density * 8).toInt()
                setPadding(h, h / 2, h, h / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (density * 6).toInt() }
                setOnClickListener { switchToSource(i) }
                setOnFocusChangeListener { _, hasFocus ->
                    val scale = if (hasFocus) 1.1f else 1f
                    animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                    translationZ = if (hasFocus) 10f else 0f
                }
            }
            sourceChipRow.addView(chip)
        }
        highlightChips()
    }

    private fun highlightChips() {
        for (i in 0 until sourceChipRow.childCount) {
            val chip = sourceChipRow.getChildAt(i) as TextView
            chip.setTextColor(resources.getColor(
                if (i == streamIndex) R.color.accent else R.color.text))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Number keys 1-9 jump straight to a source
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val idx = keyCode - KeyEvent.KEYCODE_1
            if (idx < streams.size) {
                switchToSource(idx)
                return true
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { cycleSource(1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { cycleSource(-1); true }
            KeyEvent.KEYCODE_MENU -> { cycleSource(1); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private val saveRunnable = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                WatchState.savePosition(this@PlayerActivity, videoId, player.currentPosition)
                WatchState.updateHistoryPosition(this@PlayerActivity, videoId,
                    player.currentPosition, player.duration)
            }
            playerView.postDelayed(this, 10_000)
        }
    }

    override fun onStart() {
        super.onStart()
        playerView.postDelayed(saveRunnable, 10_000)
    }

    override fun onStop() {
        WatchState.savePosition(this, videoId, player.currentPosition)
        WatchState.updateHistoryPosition(this, videoId, player.currentPosition, player.duration)
        playerView.removeCallbacks(saveRunnable)
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
