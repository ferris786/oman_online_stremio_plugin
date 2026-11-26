package com.kmk.turkishtv

import android.content.Context
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import coil.Coil
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Shared presentation helpers: the one focus treatment every card uses
 * (lift + white ring drawn by the card's foreground), image loading, and
 * small formatters. Keeping them here is what makes the screens read as one
 * system instead of five.
 */
object Ui {

    /** Netflix-style focus: scale up, lift above neighbours. The ring itself is the view's foreground selector. */
    fun focusLift(view: View, scale: Float = 1.08f) {
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
            v.translationZ = if (hasFocus) 14f else 0f
        }
    }

    /**
     * The score badge on a card.
     *
     * One helper rather than the same five lines in every adapter, because
     * the rule is the same everywhere: show it only when a score is
     * actually known, and never show a bare zero. A title with no rating
     * yet is not a nought-out-of-ten.
     *
     * Ratings arrive as strings from two sources, IMDb through Cinemeta and
     * TMDB's own average, so they are parsed rather than trusted.
     */
    fun showScore(badge: android.widget.TextView?, rating: String?) {
        val v = badge ?: return
        val n = rating?.trim()?.toFloatOrNull()
        if (n == null || n <= 0f) {
            v.visibility = View.GONE
            return
        }
        v.text = "★ " + (if (n >= 10f) "10" else String.format(java.util.Locale.US, "%.1f", n))
        // green for the ones worth stopping on, gold for the rest, so a row
        // can be skimmed without reading a single number
        v.setTextColor(if (n >= 7.5f) 0xFF8CC47C.toInt() else 0xFFE0B34D.toInt())
        v.visibility = View.VISIBLE
    }

    fun loadImage(target: ImageView, url: String?, radiusDp: Float = 4f, placeholder: Int = R.drawable.still_bg, crossfadeMs: Int = 200) {
        val ctx = target.context
        val px = radiusDp * ctx.resources.displayMetrics.density
        val req = ImageRequest.Builder(ctx)
            .data(url)
            .placeholder(placeholder)
            .error(placeholder)
            .crossfade(crossfadeMs)
            .apply { if (radiusDp > 0f) transformations(RoundedCornersTransformation(px)) }
            .target(target)
            .build()
        Coil.imageLoader(ctx).enqueue(req)
    }

    /**
     * Full-bleed backdrop with a slow crossfade (no rounding, no
     * placeholder flash).
     *
     * @param onLoaded handed the finished drawable, so the caller can take
     *        a colour off the artwork without decoding it a second time
     */
    fun loadBackdrop(target: ImageView, url: String?,
                     onLoaded: ((String, android.graphics.drawable.Drawable) -> Unit)? = null) {
        if (url.isNullOrEmpty()) return
        if (target.tag == url) return
        target.tag = url
        val ctx = target.context
        Coil.imageLoader(ctx).enqueue(
            ImageRequest.Builder(ctx).data(url).crossfade(450).target(target)
                .listener(onSuccess = { _, result ->
                    onLoaded?.invoke(url, result.drawable)
                })
                .build())
    }

    /** days since a yyyy-mm-dd date (negative = in the future); null when unparsable */
    fun daysSince(date: String?): Int? {
        if (date.isNullOrEmpty() || date.length < 10) return null
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val t = f.parse(date.substring(0, 10))?.time ?: return null
            ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
        } catch (e: Exception) { null }
    }

    fun minutesLabel(min: Int): String = when {
        min <= 0 -> ""
        min < 60 -> "$min min"
        else -> "${min / 60} h ${min % 60} m"
    }

    fun msLabel(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** "New" when aired within the last 10 days */
    const val NEW_WINDOW_DAYS = 10

    fun airedLabel(date: String?): String? {
        val d = daysSince(date) ?: return null
        return when {
            d < 0 -> "Airs ${date!!.substring(0, 10)}"
            d == 0 -> "Today"
            d == 1 -> "Yesterday"
            d < 7 -> "$d days ago"
            d < 30 -> "${d / 7} week${if (d / 7 > 1) "s" else ""} ago"
            else -> date!!.substring(0, 10)
        }
    }
}
