package com.kmk.turkishtv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
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

/**
 * Player with a custom controller (title, time remaining, gold scrubber,
 * key hints), top pills for what is playing and which source, and a
 * next-episode card that pre-resolves the next episode's streams and plays
 * it when this one ends (OK plays it now, BACK keeps watching).
 */
class PlayerActivity : Activity() {

    companion object {
        const val SEEK_STEP_MS = 10_000L
        /** auto-fallback stops after this many failures; the user picks from the source list */
        const val MAX_AUTO_FALLBACK = 5
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var sourceLabel: TextView     // top-left pill: what is playing
    private lateinit var sourcePill: TextView      // top-right pill: source browser
    private lateinit var nextCard: LinearLayout
    private lateinit var nextThumb: ImageView
    private lateinit var nextTitle: TextView
    private lateinit var nextHint: TextView
    private var ctlTitle: TextView? = null
    private var ctlSub: TextView? = null

    data class PStream(val name: String, val title: String, val url: String,
                        val hash: String = "", val dn: String = "",
                        val cached: Boolean = true, val encode: String = "")
    private var streams: List<PStream> = emptyList()
    private var streamIndex = 0
    private var videoId: String = ""
    private var videoTitle: String = ""
    private var seriesId: String? = null

    // Set once the first frame renders - after that, errors trigger fallback
    private var playbackStarted = false

    // next-episode handoff
    private var nextEpisode: Api.Episode? = null
    private var allEpisodes: List<Api.Episode> = emptyList()
    private var nextPrefetched = false
    private var nextCancelled = false
    private var nextShown = false
    private var ended = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        sourceLabel = findViewById(R.id.sourceLabel)
        sourcePill = findViewById(R.id.sourceChips)
        nextCard = findViewById(R.id.nextCard)
        nextThumb = findViewById(R.id.nextThumb)
        nextTitle = findViewById(R.id.nextTitle)
        nextHint = findViewById(R.id.nextHint)
        ctlTitle = playerView.findViewById(R.id.ctlTitle)
        ctlSub = playerView.findViewById(R.id.ctlSub)
        // D-pad scrubbing on the time bar defaults to duration/20 per press
        // (~7 min on a 2 h episode). Fix it at 10 s.
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            ?.setKeyTimeIncrement(SEEK_STEP_MS)

        videoId = intent.getStringExtra("videoId") ?: run { finish(); return }
        videoTitle = intent.getStringExtra("videoTitle") ?: ""
        seriesId = intent.getStringExtra("seriesId")

        player = buildPlayer()
        playerView.player = player
        setupOverlayVisibility()
        applyTitles()
        nextCard.setOnClickListener { playNext() }

        // Pre-resolved streams (English movies resolved through TorBox) are
        // passed directly instead of hitting the addon /stream endpoint.
        val preResolved = intent.getStringExtra("streamsJson")
        if (preResolved != null) {
            val arr = org.json.JSONArray(preResolved)
            val list = ArrayList<PStream>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(PStream(o.optString("name"), o.optString("title"),
                    o.optString("url"), o.optString("hash", ""), o.optString("dn", ""),
                    o.optBoolean("cached", true), o.optString("encode", "")))
            }
            streams = list
            val startIdx = intent.getIntExtra("startIndex", 0)
            streamIndex = if (startIdx in list.indices) startIdx else 0
            buildSourceChips()
            playCurrent()
            return
        }

        Thread {
            try {
                val apiStreams = Api.streams(videoId)
                val s = apiStreams.map { PStream(it.name, it.title, it.url) }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
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
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, "Could not load streams: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()

        loadNextEpisode()
    }

    private fun applyTitles() {
        val series = intent.getStringExtra("seriesName")
        val season = intent.getIntExtra("season", 0)
        val episode = intent.getIntExtra("episode", 0)
        sourceLabel.text = videoTitle.ifEmpty { series ?: "" }
        if (series != null && season > 0) {
            ctlTitle?.text = "Episode ${videoId.substringAfterLast("episode-", "$episode")}"
            ctlSub?.text = "$series  ·  Season $season"
        } else {
            ctlTitle?.text = videoTitle
            ctlSub?.text = ""
        }
    }

    private fun buildPlayer(): ExoPlayer {
        // Generous buffers for flaky streaming CDNs: keep 30s minimum / 2 min
        // maximum buffered, start after 3s (5s after a stall).
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 3_000, 5_000)
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
                        if (state == Player.STATE_ENDED && !ended) {
                            ended = true
                            onEnded()
                        }
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

    /** saved position, unless the episode was already finished (then start over) */
    private fun resumePosition(): Long {
        val h = WatchState.history(this).firstOrNull { it.videoId == videoId }
        if (h != null && h.isFinished) return 0L
        return WatchState.getPosition(this, videoId)
    }

    private fun playCurrent(resumeMs: Long = -1L) {
        if (streamIndex >= streams.size) {
            Toast.makeText(this, "All sources failed", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val s = streams[streamIndex]
        playbackStarted = false
        updateSourcePill()
        if (isYouTube(s.url)) {
            launchYouTube(s.url)
            playbackStarted = true // don't let auto-fallback fire the intent
            return
        }
        if (s.url.isEmpty() && s.hash.isNotEmpty()) {
            if (s.cached) resolveLazy(s, -1L) else resolveUncached(s)
            return
        }
        player.setMediaItem(MediaItem.fromUri(s.url))
        player.prepare()
        val pos = if (resumeMs >= 0) resumeMs else resumePosition()
        if (pos > Config.RESUME_THRESHOLD_MS && pos < 4 * 60 * 60 * 1000) {
            player.seekTo(pos)
            Toast.makeText(this, "Resuming", Toast.LENGTH_SHORT).show()
        }
        player.playWhenReady = true
    }

    private val failedSources = mutableSetOf<Int>()

    private fun tryNextSource(reason: String) {
        failedSources.add(streamIndex)

        // Never cascade through a long picker while TorBox is rate-limiting:
        // each attempt costs several API calls, so auto-fallback would spend
        // the whole 300/min budget and make every source fail.
        val waitS = Torbox.rateLimitSecondsLeft()
        if (waitS > 0) {
            Toast.makeText(this, "TorBox is rate-limiting this TV - wait ${waitS}s, then pick a source",
                Toast.LENGTH_LONG).show()
            return
        }
        // Give up after a handful of failures rather than hammering the API
        if (failedSources.size >= MAX_AUTO_FALLBACK) {
            Toast.makeText(this, "$reason - $MAX_AUTO_FALLBACK sources failed; pick one from ◧ sources",
                Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "$reason - trying next source…", Toast.LENGTH_SHORT).show()

        // Codec-aware fallback: prefer the next UNTRIED source with a
        // different encode (HEVC/DV files often fail on some TVs while
        // x264 plays everywhere); any untried beats a repeat; skip YouTube.
        val failedEncode = streams[streamIndex].encode
        val candidates = streams.indices.filter { it !in failedSources && !isYouTube(streams[it].url) }
        val next = candidates.firstOrNull { streams[it].encode != failedEncode && streams[it].encode.isNotEmpty() }
            ?: candidates.firstOrNull()

        if (next == null) {
            Toast.makeText(this, "All sources failed", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        streamIndex = next
        updateSourcePill()
        playCurrent()
    }

    /**
     * Comet-style lazy resolve: turn a cached torrent hash into a direct
     * TorBox link only when the user actually picks it.
     */
    private fun resolveLazy(s: PStream, seekTo: Long) {
        val key = Torbox.apiKey(this)
        if (key == null) {
            Toast.makeText(this, "Add your TorBox key in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Resolving ${s.name}…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = Torbox.resolveStream(key, s.hash, s.dn.ifEmpty { s.name })
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (result.startsWith("http")) {
                    val pos = if (seekTo >= 0) seekTo else resumePosition()
                    playbackStarted = false
                    player.setMediaItem(MediaItem.fromUri(result))
                    player.prepare()
                    if (pos > Config.RESUME_THRESHOLD_MS) player.seekTo(pos)
                    player.playWhenReady = true
                } else {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    failedSources.add(streamIndex)
                    tryNextSource(result)
                }
            }
        }.start()
    }

    /**
     * Uncached source: ask TorBox to download it, show TV-friendly progress
     * (percent + speed + cancel), then resolve and play when ready.
     */
    private fun resolveUncached(s: PStream) {
        val key = Torbox.apiKey(this)
        if (key == null) {
            Toast.makeText(this, "Add your TorBox key in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Starting download: ${s.name}…", Toast.LENGTH_SHORT).show()

        Thread {
            val torrentId = Torbox.startDownload(key, s.hash, s.dn.ifEmpty { s.name })
            if (torrentId <= 0) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, "Could not start download (plan limit reached?)", Toast.LENGTH_LONG).show()
                    failedSources.add(streamIndex)
                    tryNextSource("download start failed")
                }
                return@Thread
            }

            // progress dialog (updated from the poll loop)
            val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle("Downloading ${s.name}")
                .setMessage("Connecting…")
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .setCancelable(true)
                .create()
            var cancelled = false
            dialog.setOnDismissListener { cancelled = true }
            runOnUiThread { if (!isFinishing) dialog.show() }

            val start = System.currentTimeMillis()
            var earlyAttempts = 0
            while (!cancelled && !isFinishing && System.currentTimeMillis() - start < 30 * 60 * 1000) {
                val p = Torbox.downloadProgress(key, torrentId)
                // After the first ~10s, try resolving early every ~12s even
                // before completion - TorBox often serves the file while it
                // finishes, which makes well-seeded sources feel instant.
                val tryEarly = !p.ready &&
                    System.currentTimeMillis() - start > 10000 && earlyAttempts < 5
                if (p.ready || tryEarly) {
                    if (tryEarly) earlyAttempts++
                    val link = Torbox.resolveStream(key, s.hash, s.dn.ifEmpty { s.name })
                    if (link.startsWith("http")) {
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            dialog.dismiss()
                            playbackStarted = false
                            player.setMediaItem(MediaItem.fromUri(link))
                            player.prepare()
                            player.playWhenReady = true
                        }
                        return@Thread
                    }
                    if (p.ready) {
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            dialog.dismiss()
                            Toast.makeText(this, link, Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                }
                runOnUiThread {
                    if (dialog.isShowing) {
                        dialog.setMessage(
                            "${p.percent}%  ·  ${String.format("%.1f", p.speedMbps)} MB/s\n" +
                            "status: ${p.status}\n\nWell-seeded sources usually start within a minute.")
                    }
                }
                Thread.sleep(2000)
            }
            runOnUiThread { if (dialog.isShowing) dialog.dismiss() }
        }.start()
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
        updateSourcePill()
        if (isYouTube(s.url)) {
            launchYouTube(s.url)
            playbackStarted = true
            return
        }
        if (s.url.isEmpty() && s.hash.isNotEmpty()) {
            if (s.cached) resolveLazy(s, player.currentPosition) else resolveUncached(s)
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
     * The top pills show and hide together with the player controls. While
     * the source pill has focus it stays visible even if the controls time
     * out underneath it, and hides once focus leaves.
     */
    private fun setupOverlayVisibility() {
        sourcePill.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !playerView.isControllerFullyVisible) {
                sourcePill.visibility = View.GONE
                sourceLabel.visibility = View.GONE
            }
        }
        playerView.setControllerVisibilityListener(
            object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    if (visibility == View.VISIBLE) {
                        sourcePill.visibility = View.VISIBLE
                        sourceLabel.visibility = if (sourceLabel.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                    } else if (!sourcePill.hasFocus()) {
                        sourcePill.visibility = View.GONE
                        sourceLabel.visibility = View.GONE
                    }
                }
            }
        )
    }

    private fun buildSourceChips() {
        updateSourcePill()
        sourcePill.setOnClickListener { showSourceBrowser() }
        sourcePill.setOnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            sourcePill.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            sourcePill.translationZ = if (hasFocus) 10f else 0f
            if (!hasFocus && !playerView.isControllerFullyVisible) {
                sourcePill.visibility = View.GONE
                sourceLabel.visibility = View.GONE
            }
        }
    }

    private fun updateSourcePill() {
        if (streams.isEmpty()) return
        val cur = streams[streamIndex]
        val badge = when {
            isYouTube(cur.url) -> "▶"
            cur.cached -> "⚡"
            else -> "⏳"
        }
        sourcePill.text = "◧  ${streamIndex + 1}/${streams.size}  ·  ${cur.name}  $badge"
    }

    /**
     * Full source browser over the video: every source as a large row
     * (same style as the pre-play picker) with the playing one marked.
     */
    private fun showSourceBrowser() {
        if (streams.isEmpty()) return
        val density = resources.displayMetrics.density
        val pad = (density * 14).toInt()

        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        streams.forEachIndexed { i, st ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(if (i == streamIndex) R.drawable.card_bg_focused else R.drawable.row_selector)
                setPadding(pad, pad, pad, pad)
                val m = (density * 6).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = m; leftMargin = m; rightMargin = m }

                val failed = i in failedSources
                val badge = TextView(this@PlayerActivity).apply {
                    text = when {
                        i == streamIndex -> "▶"
                        failed -> "✗"
                        isYouTube(st.url) -> "▶"
                        st.cached -> "⚡"
                        else -> "⏳"
                    }
                    textSize = 20f
                    setTextColor(resources.getColor(
                        if (failed) android.graphics.Color.parseColor("#e05555")
                        else if (st.cached || i == streamIndex) R.color.accent else R.color.text_dim))
                }
                addView(badge)

                val texts = LinearLayout(this@PlayerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val mm = (density * 14).toInt()
                    setPadding(mm, 0, 0, 0)
                    addView(TextView(this@PlayerActivity).apply {
                        text = st.name + if (i == streamIndex) "   (playing)" else ""
                        textSize = 18f
                        setTextColor(resources.getColor(R.color.text))
                        paint.isFakeBoldText = true
                    })
                    addView(TextView(this@PlayerActivity).apply {
                        text = st.title.substringAfter("·", st.title).trim()
                        textSize = 13f
                        setTextColor(resources.getColor(R.color.text_dim))
                    })
                }
                addView(texts)

                setOnClickListener { dialog?.dismiss(); switchToSource(i) }
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, hasFocus ->
                    val scale = if (hasFocus) 1.02f else 1f
                    animate().scaleX(scale).scaleY(scale).setDuration(110).start()
                }
            }
            list.addView(row)
        }

        dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Sources (${streams.size})")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .show()
    }

    private var dialog: android.app.AlertDialog? = null

    // ------------------------------------------------------------ next episode

    private fun loadNextEpisode() {
        val sid = seriesId ?: return
        if (videoId.startsWith("movie:")) return
        Thread {
            try {
                val eps = Api.episodes(sid)
                val idx = eps.indexOfFirst { it.id == videoId }
                val next = if (idx >= 0) eps.getOrNull(idx + 1) else null
                runOnUiThread {
                    allEpisodes = eps
                    nextEpisode = next?.takeIf { !it.isUnaired }
                }
            } catch (e: Exception) { /* no handoff this time */ }
        }.start()
    }

    private fun showNextCard() {
        val n = nextEpisode ?: return
        nextShown = true
        nextTitle.text = "Episode ${n.absNumber}"
        Ui.loadImage(nextThumb, n.thumbnail ?: intent.getStringExtra("seriesBackdrop"), 3f)
        nextCard.visibility = View.VISIBLE
        nextCard.alpha = 0f
        nextCard.animate().alpha(1f).setDuration(250).start()
        // the card owns OK while it is up (the PlayerView would otherwise
        // swallow DPAD_CENTER to show its controller)
        nextCard.requestFocus()
    }

    private fun hideNextCard() {
        nextCard.visibility = View.GONE
    }

    private fun onEnded() {
        WatchState.savePosition(this, videoId, player.duration.coerceAtLeast(player.currentPosition))
        WatchState.updateHistoryPosition(this, videoId, player.duration, player.duration)
        if (nextEpisode != null && !nextCancelled) playNext() else finish()
    }

    private fun playNext() {
        val n = nextEpisode ?: return
        val series = intent.getStringExtra("seriesName") ?: ""
        if (player.duration > 0) {
            WatchState.savePosition(this, videoId, player.currentPosition)
            WatchState.updateHistoryPosition(this, videoId, player.currentPosition, player.duration)
        }
        WatchState.upsertHistory(this, WatchState.HistoryEntry(
            seriesId ?: "", series, intent.getStringExtra("seriesPoster"), n.id, n.title, n.season, n.episode,
            0, 0, System.currentTimeMillis(), thumb = n.thumbnail, backdrop = intent.getStringExtra("seriesBackdrop")))
        val i = android.content.Intent(this, PlayerActivity::class.java)
            .putExtras(intent)
            .putExtra("videoId", n.id)
            .putExtra("videoTitle", "$series · S${n.season}E${n.episode}")
            .putExtra("season", n.season)
            .putExtra("episode", n.episode)
        ended = true
        startActivity(i)
        finish()
    }

    /** one-second tick: time remaining in the controller, next-up card, prefetch */
    private val tick = object : Runnable {
        override fun run() {
            val dur = player.duration
            val pos = player.currentPosition
            if (dur > 0) {
                val remaining = dur - pos
                val series = intent.getStringExtra("seriesName")
                val season = intent.getIntExtra("season", 0)
                ctlSub?.text = listOfNotNull(series, season.takeIf { it > 0 }?.let { "Season $it" },
                    "${(remaining / 60000).coerceAtLeast(0)} min left").joinToString("  ·  ")
                if (nextEpisode != null && playbackStarted) {
                    if (!nextPrefetched && remaining <= 180_000) {
                        nextPrefetched = true
                        val id = nextEpisode!!.id
                        Thread { try { Api.streams(id) } catch (e: Exception) { } }.start()
                    }
                    if (!nextShown && !nextCancelled && remaining <= 60_000) showNextCard()
                    if (nextCard.visibility == View.VISIBLE) {
                        nextHint.text = "Starts in ${Ui.msLabel(remaining)}  ·  OK plays now  ·  BACK keeps watching"
                    }
                }
            }
            playerView.postDelayed(this, 1000)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val dur = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        player.seekTo(if (dur > 0) target.coerceAtMost(dur - 1000) else target)
        playerView.showController()
    }

    /**
     * ◀ ▶ seek 10 s immediately, even while the controls are hidden (the
     * PlayerView would otherwise swallow the first press just to show them);
     * the media FF/REW keys jump 30 s. Once the controls are up, the time bar
     * handles ◀ ▶ itself in the same 10 s steps.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && nextCard.visibility != View.VISIBLE && streams.isNotEmpty()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!playerView.isControllerFullyVisible) {
                        seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -SEEK_STEP_MS else SEEK_STEP_MS)
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-3 * SEEK_STEP_MS); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(3 * SEEK_STEP_MS); return true }
            }
        }
        return super.dispatchKeyEvent(event)
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
        if (nextCard.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { nextCancelled = true; hideNextCard(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { playNext(); return true }
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { cycleSource(1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { cycleSource(-1); true }
            KeyEvent.KEYCODE_MENU -> { showSourceBrowser(); true }
            // reached only when the controller is showing and nothing sits
            // above the focused control (a hidden controller consumes ▲ to
            // show itself first)
            KeyEvent.KEYCODE_DPAD_UP -> { showSourceBrowser(); true }
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
        playerView.postDelayed(tick, 1000)
    }

    override fun onStop() {
        if (!ended && player.currentPosition > 0) {
            WatchState.savePosition(this, videoId, player.currentPosition)
            WatchState.updateHistoryPosition(this, videoId, player.currentPosition, player.duration)
        }
        playerView.removeCallbacks(saveRunnable)
        playerView.removeCallbacks(tick)
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
