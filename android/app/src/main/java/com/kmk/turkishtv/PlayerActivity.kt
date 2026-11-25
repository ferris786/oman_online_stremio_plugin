package com.kmk.turkishtv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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

        /**
         * Held-key seek ramp. A tap moves 10 s; keep the key down and the
         * step grows, so crossing an hour does not mean forty presses.
         * Indexed by the key's repeat count.
         */
        fun seekStepFor(repeat: Int): Long = when {
            repeat < 3 -> 10_000L        // taps and the first moments
            repeat < 8 -> 30_000L
            repeat < 14 -> 60_000L
            repeat < 22 -> 180_000L
            else -> 300_000L             // 5 min a tick once properly held
        }
        /** rapid ◀ ▶ presses are merged; the seek fires this long after the last one */
        const val SEEK_COMMIT_MS = 450L
        /** while a key is held, seek at least this often so the picture keeps up */
        const val SEEK_KEEPUP_MS = 2_000L
        /** no buffering panel for this long after a seek - it would just flash */
        const val SEEK_SETTLE_MS = 1_500L
        /** brief stalls are normal on these CDNs; only show the panel if it persists */
        const val BUFFER_PANEL_DELAY_MS = 900L
        /** auto-fallback stops after this many failures; the user picks from the source list */
        const val MAX_AUTO_FALLBACK = 5
        /** how many unasked-for pauses to undo in a minute before standing down */
        const val MAX_PAUSE_UNDOS = 4
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
    private lateinit var bufferPanel: View
    private lateinit var bufferBar: ProgressBar
    private lateinit var bufferText: TextView
    private lateinit var diagPanel: TextView
    private lateinit var seekIndicator: TextView
    private lateinit var skin: PlayerSkin

    // Seeking. Each ◀ ▶ press adds to an offset and only the last one is
    // committed, so holding the key is one network seek instead of ten. The
    // buffering panel is suppressed while that is happening - it used to pop
    // up on every single press and swallow the next one.
    private var pendingSeekMs = 0L
    private var seekBaseMs = -1L
    private var lastSeekAt = 0L
    private var seekStartedAt = 0L

    // Watching together. The room's position and pause state are authority
    // while this is on; a local pause or seek is DETECTED by comparing state
    // between ticks rather than by hooking every input path, which is what
    // makes it work with the remote, the controller and all three skins
    // without touching a single layout.
    private var togetherMode = false
    private var togetherWith = ""
    private var holdForOther = false          // paused because they are buffering
    private var controlPending = false        // a local act to report next tick
    private var lastTickPos = -1L
    private var lastTickAt = 0L
    private var lastTickPlaying = false
    private var offeredRoomFor = ""           // so the invite is offered once

    /** set when onStop paused playback, so onStart knows to resume it */
    private var pausedByBackground = false

    /**
     * What the viewer actually asked for.
     *
     * True from the moment a stream starts until they deliberately pause.
     * Anything that clears playWhenReady while this is still true was not
     * them, and is put back. That is the guard against causes we have not
     * met yet, rather than against the one we just fixed.
     */
    private var wantPlaying = true

    /** the system's idea of what is playing: remote keys, and a second
     *  signal to the TV that it should not drop into a screensaver */
    private var mediaSession: android.media.session.MediaSession? = null

    /** undo attempts in the last minute, so a stubborn cause cannot loop */
    private var undoCount = 0
    private var undoWindowAt = 0L

    // diagnostics state
    private var decoderName = "-"
    private var stalls = 0
    private var droppedFrames = 0
    private var lastHeartbeatAt = 0L
    private var diagVisible = false

    // external subtitles (English movies): fetched in the background, and
    // attached to the media item only when one is actually picked, so people
    // who never want subtitles pay nothing for them.
    private var externalSubs: List<Api.Subtitle> = emptyList()
    private var activeSub: Api.Subtitle? = null

    data class PStream(val name: String, val title: String, val url: String,
                        val hash: String = "", val dn: String = "",
                        val cached: Boolean = true, val encode: String = "",
                        val fileIdx: Int = -1, val epTag: String = "")
    private var streams: List<PStream> = emptyList()
    private var streamIndex = 0
    private var videoId: String = ""
    private var videoTitle: String = ""
    private var seriesId: String? = null

    // Set once the first frame renders - after that, errors trigger fallback
    private var playbackStarted = false

    // next-episode handoff
    private var nextEpisode: Api.Episode? = null
    private var nextEn: Api.EnEpisode? = null
    private var allEpisodes: List<Api.Episode> = emptyList()
    private var nextPrefetched = false
    private var nextCancelled = false
    private var nextShown = false
    private var ended = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Classic / Terminal / Ottoman - chosen in Settings. Every skin's
        // layout exposes the same ids, so nothing below changes.
        // Keep the screen awake for real.
        //
        // The manifest carried android:keepScreenOn="true" on the <activity>
        // tag, which is a VIEW attribute and means nothing there, so the
        // television's screensaver was free to take over mid-episode. When it
        // did, the app went to the background and onStop paused playback.
        // That is the self-pausing: logged twice in one Supernatural episode,
        // each time as onStop with finishing=false while still playing.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        skin = PlayerSkin.load(this)
        setContentView(skin.layout)

        playerView = findViewById(R.id.playerView)
        sourceLabel = findViewById(R.id.sourceLabel)
        sourcePill = findViewById(R.id.sourceChips)
        nextCard = findViewById(R.id.nextCard)
        nextThumb = findViewById(R.id.nextThumb)
        nextTitle = findViewById(R.id.nextTitle)
        nextHint = findViewById(R.id.nextHint)
        bufferPanel = findViewById(R.id.bufferPanel)
        bufferBar = findViewById(R.id.bufferBar)
        bufferText = findViewById(R.id.bufferText)
        diagPanel = findViewById(R.id.diagPanel)
        seekIndicator = findViewById(R.id.seekIndicator)
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
        startMediaSession()
        setupOverlayVisibility()
        applyTitles()
        nextCard.setOnClickListener { playNext() }
        loadNextEpisode()
        loadSubtitles()

        // Pre-resolved streams (English movies resolved through TorBox) are
        // passed directly instead of hitting the addon /stream endpoint.
        // arrived from "Join" on the home screen
        if (intent.getBooleanExtra("together", false)) {
            val at = intent.getLongExtra("togetherPositionMs", -1L)
            playerView.postDelayed({
                if (!isFinishing) {
                    if (at > 0) player.seekTo(at)
                    startTogether(announce = true)
                }
            }, 2_500)
        }

        val preResolved = intent.getStringExtra("streamsJson")
        if (preResolved != null) {
            val arr = org.json.JSONArray(preResolved)
            val list = ArrayList<PStream>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(PStream(o.optString("name"), o.optString("title"),
                    o.optString("url"), o.optString("hash", ""), o.optString("dn", ""),
                    o.optBoolean("cached", true), o.optString("encode", ""),
                    o.optInt("fileIdx", -1), o.optString("epTag", "")))
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
    }

    /** Turkish episodes are hard-subbed, so only movies get a subtitle list. */
    private fun loadSubtitles() {
        val isEn = videoId.startsWith(EnSeriesActivity.PREFIX)
        val id = when {
            isEn -> videoId.removePrefix(EnSeriesActivity.PREFIX)
            videoId.startsWith("movie:") -> videoId.removePrefix("movie:")
            else -> return
        }
        if (!id.startsWith("tt")) return
        Thread {
            try {
                val subs = if (isEn) Api.subtitlesEpisode(id) else Api.subtitles(id)
                runOnUiThread { if (!isFinishing) externalSubs = subs }
            } catch (e: Exception) { /* sheet simply shows none */ }
        }.start()
    }

    /** Build the item for a url, carrying the chosen external subtitle if any. */
    private fun mediaItemFor(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        activeSub?.let { s ->
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(s.lang)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()))
        }
        return b.build()
    }

    /** Re-prepare the current stream with a different external subtitle. */
    private fun applyExternalSub(sub: Api.Subtitle?) {
        activeSub = sub
        val url = streams.getOrNull(streamIndex)?.url ?: return
        if (url.isEmpty()) return
        val pos = player.currentPosition
        playbackStarted = false
        player.setMediaItem(mediaItemFor(url))
        player.prepare()
        if (pos > Config.RESUME_THRESHOLD_MS) player.seekTo(pos)
        player.playWhenReady = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, sub == null).build()
        Toast.makeText(this, if (sub == null) "Subtitles off" else "Subtitles: ${sub.label}",
            Toast.LENGTH_SHORT).show()
    }

    /** Terminal and Ottoman skins set their text in caps */
    private fun skinCase(s: String) = if (skin.upper) s.uppercase() else s

    private fun applyTitles() {
        val series = intent.getStringExtra("seriesName")
        val season = intent.getIntExtra("season", 0)
        val episode = intent.getIntExtra("episode", 0)
        sourceLabel.text = skinCase(videoTitle.ifEmpty { series ?: "" })
        if (series != null && season > 0) {
            ctlTitle?.text = skinCase("Episode ${videoId.substringAfterLast("episode-", "$episode")}")
            ctlSub?.text = skinCase("$series  ·  Season $season")
        } else {
            ctlTitle?.text = skinCase(videoTitle)
            ctlSub?.text = ""
        }
    }

    /**
     * A platform media session.
     *
     * Two jobs. It makes the remote's play, pause and stop keys work
     * without the on-screen controller, and it tells Android something is
     * playing — which is an independent reason for the television not to
     * start its screensaver, alongside the window flag. Belt and braces on
     * the bug that caused the self-pausing.
     */
    /**
     * The glass skin's material.
     *
     * The first attempt called setRenderEffect on each panel, which was
     * simply wrong: that blurs the view AND ITS CHILDREN, so the title,
     * the scrubber and the hints were all smeared into the pane and
     * nothing could be read. There is no backdrop-blur API for an ordinary
     * view, so nothing is blurred at all now. The panes are a dark
     * translucent material with a light edge, which is what reads as glass
     * on a television anyway, and the type stays perfectly crisp over it.
     */

    private fun startMediaSession() {
        runCatching {
            mediaSession = android.media.session.MediaSession(this, "WahabTV").apply {
                setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPlay() { wantPlaying = true; player.play() }
                    override fun onPause() { wantPlaying = false; player.pause() }
                    override fun onStop() { wantPlaying = false; finish() }
                    override fun onSeekTo(pos: Long) { player.seekTo(pos) }
                })
                isActive = true
            }
        }
    }

    /** keep the system in step with what the player is doing */
    private fun publishPlaybackState() {
        val session = mediaSession ?: return
        runCatching {
            val state = when {
                player.playbackState == Player.STATE_BUFFERING ->
                    android.media.session.PlaybackState.STATE_BUFFERING
                player.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
                else -> android.media.session.PlaybackState.STATE_PAUSED
            }
            session.setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setActions(
                        android.media.session.PlaybackState.ACTION_PLAY or
                        android.media.session.PlaybackState.ACTION_PAUSE or
                        android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                        android.media.session.PlaybackState.ACTION_STOP or
                        android.media.session.PlaybackState.ACTION_SEEK_TO)
                    .setState(state, player.currentPosition.coerceAtLeast(0), 1f)
                    .build())
        }
    }

    private fun buildPlayer(): ExoPlayer {
        // Generous buffers for flaky streaming CDNs: keep 30s minimum / 2 min
        // maximum buffered, start after 3s (5s after a stall).
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 3_000, 5_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
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
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime, decoderName: String,
                        initializedTimestampMs: Long, initializationDurationMs: Long
                    ) {
                        this@PlayerActivity.decoderName = decoderName
                    }
                })
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long
                    ) {
                        droppedFrames += count
                    }
                    override fun onAudioUnderrun(
                        eventTime: AnalyticsListener.EventTime, bufferSize: Int,
                        bufferSizeMs: Long, elapsedSinceLastFeedMs: Long
                    ) {
                        Diag.warn("play", "audio underrun", mapOf(
                            "posS" to player.currentPosition / 1000,
                            "sinceFeedMs" to elapsedSinceLastFeedMs))
                    }
                })
                p.addListener(object : Player.Listener {
                    /**
                     * Every pause now says who asked for it.
                     *
                     * The self-pausing took weeks to pin down because a
                     * pause left no trace of its cause. Media3 hands us a
                     * reason, so it goes in the log; and when the reason is
                     * not the viewer, the pause is simply undone.
                     */
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        val why = when (reason) {
                            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus lost"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio became noisy"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of item"
                            else -> "reason $reason"
                        }
                        Diag.log("play", if (playWhenReady) "play ($why)" else "pause ($why)",
                            mapOf("posS" to player.currentPosition / 1000,
                                  "wantPlaying" to wantPlaying))
                        publishPlaybackState()
                        if (playWhenReady) return

                        // Not the viewer, not us going to the background,
                        // and not the end of the episode: put it back.
                        val theirs = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                        if (!theirs && wantPlaying && !ended && !pausedByBackground) {
                            // Something that keeps pausing us would otherwise
                            // be fought forever. Try a few times, then leave
                            // it alone and say so rather than spin.
                            val now = System.currentTimeMillis()
                            if (now - undoWindowAt > 60_000) { undoWindowAt = now; undoCount = 0 }
                            undoCount++
                            if (undoCount > MAX_PAUSE_UNDOS) {
                                Diag.error("play", "giving up undoing pauses", mapOf(
                                    "why" to why, "inLastMinute" to undoCount,
                                    "posS" to player.currentPosition / 1000))
                                return
                            }
                            Diag.warn("play", "undoing a pause nobody asked for",
                                mapOf("why" to why, "attempt" to undoCount,
                                      "posS" to player.currentPosition / 1000))
                            playerView.post { if (!isFinishing && wantPlaying) player.play() }
                        }
                    }

                    /**
                     * Suppression pauses the picture without clearing
                     * playWhenReady, so it would never show up above.
                     */
                    override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                        if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                            Diag.warn("play", "playback suppressed", mapOf(
                                "reason" to reason, "posS" to player.currentPosition / 1000))
                        }
                        publishPlaybackState()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        publishPlaybackState()
                        Diag.log("play", "state=" + stateName(state), mapOf(
                            "posS" to player.currentPosition / 1000,
                            "durS" to player.duration / 1000,
                            "bufAheadS" to player.totalBufferedDuration / 1000,
                            "stalls" to stalls))
                        if (state == Player.STATE_READY) playbackStarted = true
                        if (state == Player.STATE_BUFFERING) {
                            if (playbackStarted) stalls++
                            showBuffering(true)
                        } else {
                            showBuffering(false)
                        }
                        if (state == Player.STATE_ENDED && !ended) {
                            val dur = player.duration
                            val pos = player.currentPosition
                            // A source that dies mid-episode reports ENDED just
                            // like a finished one. Treating that as "finished"
                            // is what closed the player and dropped the viewer
                            // back on the home screen partway through.
                            if (dur > 0 && pos < dur * 92 / 100) {
                                Diag.error("play", "ENDED EARLY", mapOf(
                                    "posS" to pos / 1000, "durS" to dur / 1000,
                                    "pct" to (if (dur > 0) pos * 100 / dur else 0),
                                    "source" to currentSourceName(),
                                    "host" to Diag.host(streams.getOrNull(streamIndex)?.url),
                                    "stalls" to stalls, "dropped" to droppedFrames,
                                    "decoder" to decoderName) + Diag.memory())
                                Diag.flushSoon()
                                noteStop("ended early at ${pos / 1000}s of ${dur / 1000}s")
                                handleEarlyEnd()
                            } else {
                                Diag.log("play", "finished", mapOf(
                                    "posS" to pos / 1000, "durS" to dur / 1000))
                                ended = true
                                onEnded()
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Diag.error("play", "error ${error.errorCodeName}", mapOf(
                            "message" to error.message,
                            "cause" to (error.cause?.javaClass?.simpleName ?: "-"),
                            "causeMsg" to (error.cause?.message ?: "-"),
                            "posS" to player.currentPosition / 1000,
                            "durS" to player.duration / 1000,
                            "started" to playbackStarted,
                            "source" to currentSourceName(),
                            "host" to Diag.host(streams.getOrNull(streamIndex)?.url),
                            "decoder" to decoderName) + Diag.memory())
                        Diag.flushSoon()
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

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "?$state"
    }

    private fun currentSourceName(): String = streams.getOrNull(streamIndex)?.name ?: "-"

    /**
     * Every thirty seconds while something is playing. This is the line that
     * settles the stopping question: if the heap climbs run after run and
     * the log simply stops, Android killed us; if the figures are flat and
     * the last entry is an error or an early end, the stream died.
     */
    private fun heartbeat() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatAt < 30_000) return
        lastHeartbeatAt = now
        if (!playbackStarted) return
        Diag.log("beat", "playing", mapOf(
            "posS" to player.currentPosition / 1000,
            "durS" to player.duration / 1000,
            "bufAheadS" to player.totalBufferedDuration / 1000,
            "playing" to player.isPlaying,
            "stalls" to stalls,
            "dropped" to droppedFrames,
            "decoder" to decoderName,
            "source" to currentSourceName()) + Diag.memory())
    }

    // ------------------------------------------------------ watching together

    private fun houseKind(): String = when {
        videoId.startsWith(EnSeriesActivity.PREFIX) -> "enseries"
        videoId.startsWith("movie:") -> "movie"
        else -> "series"
    }

    /** presence: what this box is playing, for the other TV's home screen */
    private val presenceBeat = object : Runnable {
        override fun run() {
            if (Household.linked(this@PlayerActivity)) {
                val pos = (player.currentPosition / 1000).toInt()
                val dur = (player.duration / 1000).coerceAtLeast(0).toInt()
                val playing = player.isPlaying
                Thread {
                    Household.beat(this@PlayerActivity, houseKind(), videoId, seriesId,
                        videoTitle, intent.getStringExtra("seriesPoster"),
                        intent.getStringExtra("seriesBackdrop"), pos, dur, playing)
                }.start()
            }
            playerView.postDelayed(this, 10_000)
        }
    }

    /**
     * Not in a room yet: has the other person opened one on what I am
     * watching? If so, offer to fall in step rather than silently yanking
     * the picture around.
     */
    private val roomWatch = object : Runnable {
        override fun run() {
            if (!togetherMode && Household.linked(this@PlayerActivity) && playbackStarted) {
                Thread {
                    val room = Household.roomGet(this@PlayerActivity)
                    runOnUiThread {
                        if (isFinishing || togetherMode) return@runOnUiThread
                        if (room == null || room.videoId != videoId) return@runOnUiThread
                        if (room.host == Household.deviceId(this@PlayerActivity)) return@runOnUiThread
                        if (offeredRoomFor == room.videoId + room.host) return@runOnUiThread
                        offeredRoomFor = room.videoId + room.host
                        Toast.makeText(this@PlayerActivity,
                            "${room.hostName} is watching this too. Press OK to watch in step.",
                            Toast.LENGTH_LONG).show()
                        pendingRoomInvite = room.hostName
                        // an unanswered invite must not keep hold of OK
                        playerView.postDelayed({ pendingRoomInvite = null }, 20_000)
                    }
                }.start()
            }
            playerView.postDelayed(this, 10_000)
        }
    }
    private var pendingRoomInvite: String? = null

    /** Open a room on what is playing, or fall into the one already there. */
    private fun startTogether(announce: Boolean) {
        if (!Household.linked(this)) {
            Toast.makeText(this, "Link this TV to the other one in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        val s = streams.getOrNull(streamIndex)
        val pos = player.currentPosition
        Thread {
            val existing = Household.roomGet(this)
            val room = if (existing != null && existing.videoId == videoId) existing
            else Household.roomOpen(this, houseKind(), videoId, seriesId, videoTitle,
                intent.getStringExtra("seriesPoster"), intent.getStringExtra("seriesBackdrop"),
                pos, s?.name ?: "", s?.hash ?: "", s?.fileIdx ?: -1, s?.epTag ?: "", s?.dn ?: "")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (room == null) {
                    Toast.makeText(this, "Could not start watching together", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                togetherWith = room.hostName
                enterTogether(announce)
            }
        }.start()
    }

    private fun enterTogether(announce: Boolean) {
        if (togetherMode) return
        togetherMode = true
        pendingRoomInvite = null
        noteTick()
        playerView.removeCallbacks(roomTick)
        playerView.post(roomTick)
        if (announce) {
            Toast.makeText(this, "Watching with ${togetherWith.ifEmpty { "them" }}", Toast.LENGTH_LONG).show()
        }
        Diag.log("together", "joined", mapOf("with" to togetherWith, "video" to videoId))
    }

    private fun leaveTogether(closeRoom: Boolean) {
        if (!togetherMode) return
        togetherMode = false
        holdForOther = false
        playerView.removeCallbacks(roomTick)
        showBuffering(false)
        if (closeRoom) Thread { Household.roomClose(this) }.start()
        Toast.makeText(this, "Watching on your own again", Toast.LENGTH_SHORT).show()
        Diag.log("together", "left", mapOf("video" to videoId))
    }

    /** remember where we were, so the next tick can tell a local act apart */
    private fun noteTick() {
        lastTickPos = player.currentPosition
        lastTickAt = System.currentTimeMillis()
        lastTickPlaying = player.isPlaying
    }

    /**
     * Two seconds is plenty for a television, and it keeps the client simple
     * enough to reason about. One call publishes our state and returns the
     * room's.
     */
    private val roomTick = object : Runnable {
        override fun run() {
            if (!togetherMode) return
            val pos = player.currentPosition
            val playing = player.isPlaying
            val now = System.currentTimeMillis()
            val stalled = player.playbackState == Player.STATE_BUFFERING

            // Did the person in THIS room just do something? A pause or
            // resume we did not apply ourselves, or a jump the clock cannot
            // explain, is a deliberate act and owns the room.
            var control = controlPending
            if (lastTickPos >= 0 && !holdForOther) {
                val elapsed = if (lastTickPlaying) now - lastTickAt else 0L
                val expected = lastTickPos + elapsed
                if (Math.abs(pos - expected) > 4_000) control = true
                if (playing != lastTickPlaying) control = true
            }
            controlPending = false
            noteTick()

            Thread {
                val room = Household.roomSync(this@PlayerActivity, pos, !playing, stalled, control)
                runOnUiThread { if (!isFinishing && togetherMode) applyRoom(room) }
            }.start()
            playerView.postDelayed(this, 2_000)
        }
    }

    /**
     * The rule that decides whether this feels good: if ANYONE is stalled,
     * everyone waits. Hiding one person's buffering is what makes every
     * other watch-together feature drift apart.
     */
    private fun applyRoom(room: Household.Room?) {
        if (room == null) {
            // the room is gone, so stop pretending we are in one
            leaveTogether(closeRoom = false)
            return
        }
        togetherWith = room.hostName

        if (room.waiting) {
            if (player.isPlaying) {
                holdForOther = true
                player.pause()
            }
            val who = room.stalledBy.filter { it != Profiles.activeName(this) }
            bufferText.text = if (who.isEmpty()) skinCase("Waiting…")
                else skinCase("Waiting for ${who.joinToString(" and ")}…")
            bufferPanel.visibility = View.VISIBLE
            noteTick()
            return
        }

        if (holdForOther) {
            holdForOther = false
            showBuffering(false)
            if (!room.paused) player.play()
            noteTick()
        }

        if (room.paused && player.isPlaying) { player.pause(); noteTick() }
        else if (!room.paused && !player.isPlaying && playbackStarted) { player.play(); noteTick() }

        // drift correction, never while anyone is still catching up
        val drift = Math.abs(player.currentPosition - room.positionMs)
        if (drift > 2_500) {
            player.seekTo(room.positionMs)
            noteTick()
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
        wantPlaying = true
        Diag.log("play", "starting", mapOf(
            "video" to videoId,
            "source" to s.name,
            "host" to Diag.host(s.url),
            "encode" to s.encode,
            "attempt" to (failedSources.size + 1),
            "skin" to skin.key) + Diag.memory())
        player.setMediaItem(mediaItemFor(s.url))
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
        Diag.warn("play", "falling back", mapOf(
            "reason" to reason, "from" to currentSourceName(),
            "failed" to failedSources.size, "posS" to player.currentPosition / 1000))
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
        playCurrent(resumeMs = WatchState.getPosition(this, videoId))
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
            val result = Torbox.resolveStream(key, s.hash, s.dn.ifEmpty { s.name }, s.fileIdx, s.epTag)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (result.startsWith("http")) {
                    val pos = if (seekTo >= 0) seekTo else resumePosition()
                    playbackStarted = false
                    player.setMediaItem(mediaItemFor(result))
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
                    val link = Torbox.resolveStream(key, s.hash, s.dn.ifEmpty { s.name }, s.fileIdx, s.epTag)
                    if (link.startsWith("http")) {
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            dialog.dismiss()
                            playbackStarted = false
                            player.setMediaItem(mediaItemFor(link))
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
        player.setMediaItem(mediaItemFor(s.url))
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
        sourcePill.text = if (skin == PlayerSkin.TERMINAL)
            "[^] SRC ${streamIndex + 1}/${streams.size} ${cur.name.uppercase()}"
        else skinCase("◧  ${streamIndex + 1}/${streams.size}  ·  ${cur.name}  $badge")
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
        if (sid.startsWith(EnSeriesActivity.PREFIX)) {
            Thread {
                try {
                    val eps = Api.enSeriesMeta(sid.removePrefix(EnSeriesActivity.PREFIX)).episodes
                    val idx = eps.indexOfFirst { EnSeriesActivity.PREFIX + it.id == videoId }
                    val next = if (idx >= 0) eps.drop(idx + 1).firstOrNull { !it.isUnaired } else null
                    runOnUiThread { nextEn = next }
                } catch (e: Exception) { /* no handoff this time */ }
            }.start()
            return
        }
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
        val label: String
        val thumb: String?
        when {
            nextEpisode != null -> { label = "Episode ${nextEpisode!!.absNumber}"; thumb = nextEpisode!!.thumbnail }
            nextEn != null -> { label = "${nextEn!!.tag}  ·  ${nextEn!!.title}"; thumb = nextEn!!.thumbnail }
            else -> return
        }
        nextShown = true
        nextTitle.text = label
        Ui.loadImage(nextThumb, thumb ?: intent.getStringExtra("seriesBackdrop"), 3f)
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
        if ((nextEpisode != null || nextEn != null) && !nextCancelled) playNext() else finish()
    }

    private fun playNext() {
        nextEn?.let { en ->
            // English series: the series page resolves sources through TorBox
            // and starts the player itself (singleTop, so it is re-entered)
            if (player.duration > 0) {
                WatchState.savePosition(this, videoId, player.currentPosition)
                WatchState.updateHistoryPosition(this, videoId, player.currentPosition, player.duration)
            }
            val sid = seriesId ?: return
            ended = true
            startActivity(android.content.Intent(this, EnSeriesActivity::class.java)
                .putExtra("imdbId", sid.removePrefix(EnSeriesActivity.PREFIX))
                .putExtra("name", intent.getStringExtra("seriesName"))
                .putExtra("background", intent.getStringExtra("seriesBackdrop"))
                .putExtra("poster", intent.getStringExtra("seriesPoster"))
                .putExtra("autoplayId", en.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            return
        }
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
            updateBuffering()
            updateDiagnostics()
            heartbeat()
            val dur = player.duration
            val pos = player.currentPosition
            if (dur > 0) {
                val remaining = dur - pos
                val series = intent.getStringExtra("seriesName")
                val season = intent.getIntExtra("season", 0)
                ctlSub?.text = skinCase(listOfNotNull(series, season.takeIf { it > 0 }?.let { "Season $it" },
                    "${(remaining / 60000).coerceAtLeast(0)} min left").joinToString("  ·  "))
                if ((nextEpisode != null || nextEn != null) && playbackStarted) {
                    // Turkish episodes can be pre-resolved on the server; English
                    // ones go through TorBox at play time, so nothing to warm
                    if (!nextPrefetched && remaining <= 180_000 && nextEpisode != null) {
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

    // ------------------------------------------------------------ buffering

    private val showBufferPanel = Runnable {
        // still buffering, and not just settling after a seek?
        if (player.playbackState == Player.STATE_BUFFERING && !seekPending()) {
            bufferPanel.visibility = View.VISIBLE
        }
    }

    /** remembered across launches so a mystery stop can be explained afterwards */
    private fun noteStop(reason: String) {
        runCatching {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("last_stop_reason", reason)
                .putLong("last_stop_at", System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * The stream stopped well short of the end. Keep the position, drop this
     * source, and carry on with the next one rather than closing.
     */
    private fun handleEarlyEnd() {
        val pos = player.currentPosition
        WatchState.savePosition(this, videoId, pos)
        WatchState.updateHistoryPosition(this, videoId, pos, player.duration)
        playbackStarted = false
        val untried = streams.indices.count { it !in failedSources } - 1
        if (untried <= 0) {
            Toast.makeText(this, "The stream stopped early and no other source is left. " +
                "Press play again to resume from here.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Stream stopped early - switching source and resuming",
            Toast.LENGTH_LONG).show()
        tryNextSource("Stream ended early")
    }

    private fun showBuffering(on: Boolean) {
        playerView.removeCallbacks(showBufferPanel)
        if (!on) {
            bufferPanel.visibility = View.GONE
            return
        }
        // A seek always buffers briefly. Showing the panel for that made it
        // flash on every arrow press, which is what felt like interference.
        if (seekPending()) return
        playerView.postDelayed(showBufferPanel, BUFFER_PANEL_DELAY_MS)
    }

    /** % of the 30 s minimum buffer that is filled, plus the live download rate */
    private fun updateBuffering() {
        if (bufferPanel.visibility != View.VISIBLE) return
        val buffered = player.totalBufferedDuration
        bufferBar.progress = ((buffered * 100) / 30_000L).toInt().coerceIn(0, 100)
        val kbps = player.videoFormat?.bitrate?.takeIf { it > 0 }?.let { it / 1000 } ?: 0
        bufferText.text = listOfNotNull(
            "${buffered / 1000}s buffered",
            if (kbps > 0) "${"%.1f".format(kbps / 1000f)} Mbps stream" else null,
            if (stalls > 0) "$stalls stalls" else null
        ).joinToString(if (skin.upper) "  ·  " else " · ").let { if (skin.upper) it.uppercase() else it }
    }

    // ------------------------------------------------------------ diagnostics

    /**
     * Stream monitor (key 0). Every field comes from the player itself -
     * no new network calls - and it is the quickest way to tell whether a
     * stutter is the CDN, a starving buffer, or a codec the TV struggles with.
     */
    private fun updateDiagnostics() {
        if (!diagVisible) return
        val v: Format? = player.videoFormat
        val a: Format? = player.audioFormat
        val host = streams.getOrNull(streamIndex)?.url?.let {
            try { android.net.Uri.parse(it).host ?: "-" } catch (e: Exception) { "-" }
        } ?: "-"
        val dropped = player.videoDecoderCounters?.droppedBufferCount ?: 0
        val buffered = player.totalBufferedDuration / 1000
        val bars = (buffered.toInt() / 3).coerceIn(0, 10)
        val fps = v?.frameRate?.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "-"
        val rows = listOf(
            "SOURCE" to (streams.getOrNull(streamIndex)?.name ?: "-"),
            "HOST" to host.take(30),
            "VIDEO" to (v?.let { "${it.width}x${it.height} @ ${fps}fps" } ?: "-"),
            "CODEC" to ((v?.codecs ?: v?.sampleMimeType ?: "-") + " / " + (a?.sampleMimeType?.substringAfter('/') ?: "-")),
            "BITRATE" to (v?.bitrate?.takeIf { it > 0 }?.let { "%.2f Mbps".format(it / 1_000_000f) } ?: "-"),
            "BUFFER" to ("#".repeat(bars) + ".".repeat(10 - bars) + "  ${buffered}s"),
            "DROPPED" to "$dropped frames",
            "STALLS" to "$stalls",
            "DECODER" to decoderName.take(26),
            "LAST STOP" to (getSharedPreferences("settings", MODE_PRIVATE)
                .getString("last_stop_reason", "-") ?: "-").take(26)
        )
        diagPanel.text = rows.joinToString("\n") { (k, v2) -> k.padEnd(9) + v2 }
    }

    private fun toggleDiagnostics() {
        diagVisible = !diagVisible
        diagPanel.visibility = if (diagVisible) View.VISIBLE else View.GONE
        updateDiagnostics()
    }

    // ------------------------------------------------------------ tracks

    /**
     * Audio and subtitle track selection (D-pad down), styled per skin.
     * Subtitle rows appear once a stream or a side-loaded file provides them.
     */
    private fun showTrackSheet() {
        val tracks = player.currentTracks
        val audio = ArrayList<Triple<Tracks.Group, Int, Format>>()
        val subs = ArrayList<Triple<Tracks.Group, Int, Format>>()
        for (g in tracks.groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> audio.add(Triple(g, i, f))
                    C.TRACK_TYPE_TEXT -> subs.add(Triple(g, i, f))
                }
            }
        }
        if (audio.size <= 1 && subs.isEmpty() && externalSubs.isEmpty()) {
            Toast.makeText(this, "This source has a single audio track and no subtitles",
                Toast.LENGTH_SHORT).show()
            return
        }

        val accent = resources.getColor(skin.accentColor)
        val dim = resources.getColor(skin.dimColor)
        val fg = resources.getColor(skin.textColor)
        val face = skin.fontRes?.let { androidx.core.content.res.ResourcesCompat.getFont(this, it) }
        val d = resources.displayMetrics.density
        val pad = (d * 14).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(skin.panelBg)
        }
        fun header(t: String) = TextView(this).apply {
            text = if (skin.upper) t.uppercase() else t
            setTextColor(accent); textSize = 13f; typeface = face
            letterSpacing = 0.14f
            setPadding(0, (d * 10).toInt(), 0, (d * 4).toInt())
        }
        fun row(label: String, selected: Boolean, onPick: () -> Unit) = TextView(this).apply {
            text = (if (selected) "> " else "  ") + (if (skin.upper) label.uppercase() else label)
            setTextColor(if (selected) accent else fg)
            textSize = if (skin.fontRes == R.font.vt323) 19f else 14f
            typeface = face
            isFocusable = true; isClickable = true
            setPadding(pad / 2, (d * 7).toInt(), pad / 2, (d * 7).toInt())
            setBackgroundResource(R.drawable.row_item_bg)
            setOnClickListener { onPick(); dialog?.dismiss() }
        }

        if (audio.isNotEmpty()) {
            root.addView(header("Audio"))
            audio.forEachIndexed { n, (g, i, f) ->
                val lang = f.language?.takeIf { it.isNotBlank() } ?: "und"
                val ch = f.channelCount.takeIf { it > 0 }?.let { c -> if (c > 2) "$c.1" else "2.0" } ?: ""
                val br = f.bitrate.takeIf { it > 0 }?.let { "${it / 1000}k" } ?: ""
                val label = listOf("${n + 1}  $lang", f.sampleMimeType?.substringAfter('/')?.uppercase() ?: "", ch, br)
                    .filter { it.isNotBlank() }.joinToString("   ")
                root.addView(row(label, g.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                })
            }
        }

        root.addView(header("Subtitles"))
        val embeddedOn = subs.any { (g, i, _) -> g.isTrackSelected(i) }
        root.addView(row("0  Off", activeSub == null && !embeddedOn) {
            if (activeSub != null) applyExternalSub(null)
            else player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        })
        var n = 0
        // tracks carried inside the stream
        subs.forEach { (g, i, f) ->
            n++
            val lang = f.language?.takeIf { it.isNotBlank() } ?: "und"
            root.addView(row("$n  $lang   in stream", g.isTrackSelected(i)) {
                activeSub = null
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
            })
        }
        // side-loaded files from OpenSubtitles (movies). Picking one re-prepares
        // the stream at the same position, so nobody pays for subtitles they
        // never asked for.
        externalSubs.forEach { s ->
            n++
            root.addView(row("$n  ${s.label}", activeSub?.url == s.url) { applyExternalSub(s) })
        }
        if (n == 0) {
            root.addView(TextView(this).apply {
                text = if (skin.upper) "NONE FOUND FOR THIS TITLE" else "None found for this title"
                setTextColor(dim); textSize = 12f; typeface = face
                setPadding(pad / 2, (d * 4).toInt(), 0, 0)
            })
        }

        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setView(scroll)
            .show()
        root.post { root.getChildAt(1)?.requestFocus() }
    }

    /**
     * Accumulate a seek instead of firing one per key press. The indicator
     * shows where you will land; the actual seek happens once you stop
     * pressing, which is both faster and stops every press triggering a
     * fresh buffering round.
     */
    private fun seekBy(deltaMs: Long) {
        val dur = player.duration
        if (seekBaseMs < 0) {
            seekBaseMs = player.currentPosition
            seekStartedAt = 0
        }
        val maxPos = if (dur > 0) dur - 1000 else Long.MAX_VALUE
        pendingSeekMs = (seekBaseMs + pendingSeekMs + deltaMs).coerceIn(0, maxPos) - seekBaseMs
        lastSeekAt = System.currentTimeMillis()

        val target = seekBaseMs + pendingSeekMs
        val sign = if (pendingSeekMs >= 0) "+" else "-"
        val off = Math.abs(pendingSeekMs)
        val offLabel = if (off >= 60_000) "${off / 60_000}m ${(off % 60_000) / 1000}s" else "${off / 1000}s"
        val rate = if (deltaMs >= 60_000) "   ▸ ${deltaMs / 60_000} min a step" else ""
        seekIndicator.text = skinCase("${Ui.msLabel(target)}   ($sign$offLabel)$rate")
        seekIndicator.visibility = View.VISIBLE
        showBuffering(false)
        playerView.showController()

        // A held key keeps pushing the commit back, so on a long hold the
        // picture would stay frozen until release. Commit every couple of
        // seconds as well, so it keeps up without seeking on every tick.
        val now = System.currentTimeMillis()
        if (seekStartedAt <= 0) seekStartedAt = now
        playerView.removeCallbacks(commitSeek)
        if (now - seekStartedAt >= SEEK_KEEPUP_MS) {
            seekStartedAt = now
            playerView.post(commitSeek)
        } else {
            playerView.postDelayed(commitSeek, SEEK_COMMIT_MS)
        }
    }

    private val commitSeek = Runnable {
        if (seekBaseMs >= 0) {
            player.seekTo((seekBaseMs + pendingSeekMs).coerceAtLeast(0))
            player.playWhenReady = true
        }
        pendingSeekMs = 0
        seekBaseMs = -1
        seekIndicator.visibility = View.GONE
    }

    private fun seekPending() = seekBaseMs >= 0 ||
        System.currentTimeMillis() - lastSeekAt < SEEK_SETTLE_MS

    /**
     * ◀ ▶ seek 10 s immediately, even while the controls are hidden (the
     * PlayerView would otherwise swallow the first press just to show them);
     * the media FF/REW keys jump 30 s. Once the controls are up, the time bar
     * handles ◀ ▶ itself in the same 10 s steps.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && nextCard.visibility != View.VISIBLE && streams.isNotEmpty()) {
            val hidden = !playerView.isControllerFullyVisible
            when (event.keyCode) {
                // The activity owns ◀ ▶ in both states now. The time bar used
                // to also scrub when the controller was showing, so the two
                // mechanisms fought over the same press.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val step = seekStepFor(event.repeatCount)
                    seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                    return true
                }
                // ▲ sources / ▼ tracks work straight from a hidden controller;
                // once it is showing, normal focus navigation takes over.
                KeyEvent.KEYCODE_DPAD_UP -> if (hidden) { showSourceBrowser(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (hidden) { showTrackSheet(); return true }
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { toggleDiagnostics(); return true }
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
        // OK on the controller toggles play/pause; record that as intent so
        // the watchdog does not fight the viewer
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (nextCard.visibility != View.VISIBLE && pendingRoomInvite == null && playbackStarted) {
                playerView.postDelayed({ if (!isFinishing) wantPlaying = player.playWhenReady }, 120)
            }
        }
        if (nextCard.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { nextCancelled = true; hideNextCard(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { playNext(); return true }
            }
        }
        // An invite owns OK for its short window, the same way the
        // next-episode card does, so accepting is the obvious press.
        if (pendingRoomInvite != null && !togetherMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    startTogether(announce = true); return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    pendingRoomInvite = null
                    Toast.makeText(this, "Carrying on separately", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        return when (keyCode) {
            // a coloured button where the remote has one, for turning
            // together mode on or off without waiting to be invited
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_BOOKMARK -> {
                if (togetherMode) leaveTogether(closeRoom = true) else startTogether(announce = true)
                true
            }
            // the viewer deliberately pausing is the one pause we keep
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { wantPlaying = false; player.pause(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { wantPlaying = true; player.play(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                wantPlaying = !player.isPlaying
                if (wantPlaying) player.play() else player.pause()
                true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { cycleSource(1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { cycleSource(-1); true }
            KeyEvent.KEYCODE_MENU -> { showSourceBrowser(); true }
            // reached only when the controller is showing and nothing sits
            // above/below the focused control
            KeyEvent.KEYCODE_DPAD_UP -> { showSourceBrowser(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { showTrackSheet(); true }
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
        runCatching { mediaSession?.isActive = true }
        // Whatever took the foreground has gone. If the pause was ours
        // rather than a deliberate one, carry on where we left off instead
        // of leaving a frozen picture nobody asked for.
        if (pausedByBackground) {
            pausedByBackground = false
            if (!ended) {
                wantPlaying = true
                player.play()
                Diag.log("life", "resumed after background", mapOf(
                    "posS" to player.currentPosition / 1000))
            }
        }
        playerView.postDelayed(saveRunnable, 10_000)
        playerView.postDelayed(tick, 1000)
        if (Household.linked(this)) {
            playerView.postDelayed(presenceBeat, 4_000)
            playerView.postDelayed(roomWatch, 12_000)
        }
    }

    override fun onStop() {
        // A stop that nobody asked for is the signature of the system taking
        // the activity away, so the reason it was leaving is recorded here.
        Diag.log("life", "onStop", mapOf(
            "posS" to player.currentPosition / 1000,
            "durS" to player.duration / 1000,
            "ended" to ended,
            "finishing" to isFinishing,
            "playing" to player.isPlaying) + Diag.memory())
        Diag.flushSoon()
        if (!ended && player.currentPosition > 0) {
            WatchState.savePosition(this, videoId, player.currentPosition)
            WatchState.updateHistoryPosition(this, videoId, player.currentPosition, player.duration)
        }
        playerView.removeCallbacks(saveRunnable)
        playerView.removeCallbacks(tick)
        playerView.removeCallbacks(commitSeek)
        playerView.removeCallbacks(showBufferPanel)
        playerView.removeCallbacks(presenceBeat)
        playerView.removeCallbacks(roomWatch)
        playerView.removeCallbacks(roomTick)
        // stop claiming to be watching the moment we are not
        if (Household.linked(this)) Thread { Household.beatStopped(this) }.start()
        if (togetherMode) Thread { Household.roomClose(this) }.start()
        // Remember that the pause was ours, not the viewer's.
        //
        // playWhenReady, not isPlaying: isPlaying is false while buffering,
        // and going to the background often coincides with a stall, so
        // reading the state rather than the intent would pause mid-buffer
        // and then refuse to resume.
        pausedByBackground = player.playWhenReady
        player.pause()
        runCatching { mediaSession?.isActive = false }
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { mediaSession?.isActive = false; mediaSession?.release() }
        mediaSession = null
        Diag.log("life", "onDestroy", mapOf(
            "posS" to player.currentPosition / 1000, "finishing" to isFinishing))
        Diag.flushSoon()
        player.release()
        super.onDestroy()
    }
}
