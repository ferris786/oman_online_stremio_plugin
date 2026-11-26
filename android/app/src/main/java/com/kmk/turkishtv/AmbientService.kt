package com.kmk.turkishtv

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Wahab TV when the television is idle.
 *
 * Android gives any app a screensaver slot, and nothing else in this app
 * is seen without someone deliberately opening it. Artwork from what is
 * actually being watched, with the title and where you got to, turns the
 * idle TV into a reminder of the thing rather than a clock on a black
 * background.
 *
 * Deliberately quiet: one image at a time, a slow cross-fade, a slow
 * drift. A screensaver that moves quickly is worse than none, and burn-in
 * on an OLED is a real consideration, so nothing sits in a fixed position
 * for long.
 */
class AmbientService : DreamService() {

    companion object {
        private const val HOLD_MS = 14_000L
        private const val FADE_MS = 1_600L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var art: ImageView
    private lateinit var caption: LinearLayout
    private lateinit var title: TextView
    private lateinit var sub: TextView

    private var slides: List<Slide> = emptyList()
    private var at = 0
    /** DreamService exposes no "still dreaming" flag, so keep one */
    private var dreaming = false

    private data class Slide(val image: String?, val title: String, val sub: String)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false      // an idle TV should not be at full brightness

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }

        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        root.addView(art, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // the app's own scrim, so a caption over bright artwork still reads
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.scrim_bottom)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
            val pad = Ui.dp(this@AmbientService, 64)
            setPadding(pad, pad, pad, pad)
        }
        title = TextView(this).apply {
            setTextColor(resources.getColor(R.color.title_cream))
            textSize = 34f
            typeface = resources.getFont(R.font.cinzel_bold)
            maxLines = 2
            setShadowLayer(18f, 0f, 2f, 0xCC000000.toInt())
        }
        sub = TextView(this).apply {
            setTextColor(resources.getColor(R.color.text_soft))
            textSize = 15f
            setShadowLayer(12f, 0f, 2f, 0xCC000000.toInt())
        }
        caption.addView(title)
        caption.addView(sub)
        root.addView(caption, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START))

        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        Thread {
            val built = build()
            runOnUi {
                slides = built
                if (slides.isEmpty()) {
                    title.text = "Wahab TV"
                    sub.text = ""
                    caption.animate().alpha(1f).setDuration(FADE_MS).start()
                } else {
                    show()
                }
            }
        }.start()
    }

    /**
     * What to show, in order of how much it means to whoever walks past:
     * what is part-watched, then the list, then the Turkish library.
     */
    private fun build(): List<Slide> {
        val out = ArrayList<Slide>()
        runCatching {
            for (h in WatchState.history(this).take(8)) {
                val img = h.backdrop ?: h.thumb ?: h.poster ?: continue
                out.add(Slide(img, h.seriesName,
                    if (h.isMovie) "Carry on watching"
                    else "Season ${h.season}, episode ${h.episode}"))
            }
        }
        runCatching {
            for (e in MyList.all(this).take(8)) {
                val img = e.background ?: e.poster ?: continue
                out.add(Slide(img, e.name, "On your list"))
            }
        }
        runCatching {
            for (s in Api.catalog().shuffled().take(10)) {
                val img = s.background ?: s.poster ?: continue
                out.add(Slide(img, s.name, s.genres.take(2).joinToString("  ·  ")))
            }
        }
        return out.distinctBy { it.title }.shuffled()
    }

    private fun show() {
        if (slides.isEmpty()) return
        val s = slides[at % slides.size]
        at++

        art.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction {
            Ui.loadImage(art, s.image, 0f, R.drawable.placeholder_poster, crossfadeMs = 0)
            title.text = s.title
            sub.text = s.sub
            art.scaleX = 1.06f; art.scaleY = 1.06f
            art.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f)
                .setDuration(HOLD_MS + FADE_MS).start()
            caption.animate().alpha(1f).setDuration(FADE_MS).start()
        }.start()

        handler.postDelayed({ show() }, HOLD_MS)
    }

    private fun runOnUi(block: () -> Unit) = handler.post { if (dreaming) block() }

    override fun onDreamingStopped() {
        dreaming = false
        handler.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }
}
