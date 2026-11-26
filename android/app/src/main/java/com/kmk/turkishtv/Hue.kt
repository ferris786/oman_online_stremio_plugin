package com.kmk.turkishtv

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * The colour of whatever is on screen.
 *
 * Every screen in the app is the same gold on the same charcoal, whatever
 * you happen to be looking at. Taking a colour from the artwork and
 * washing it under the billboard makes the app feel like it is responding
 * to the content rather than framing it.
 *
 * androidx.palette is not in the offline Gradle cache and is not needed:
 * a 16x16 downscale is 256 pixels, which is plenty to find a dominant
 * colour, and costs microseconds rather than the real work Palette does.
 *
 * The result is always darkened and desaturated towards the app's own
 * ground. A poster's actual colour at full strength would fight the white
 * text sitting on it, and a wash that changes brightness as focus moves
 * is far more distracting than one that only changes hue.
 */
object Hue {

    private const val SAMPLE = 16
    private val cache = HashMap<String, Int>()

    /** the app's charcoal, used when nothing better can be found */
    val DEFAULT = 0xFF171722.toInt()

    /**
     * A dark, muted version of the artwork's dominant colour, ready to sit
     * behind text. Cached per key, because focus moves back and forth
     * across a row constantly.
     */
    fun of(key: String?, drawable: Drawable?): Int {
        if (key == null) return DEFAULT
        cache[key]?.let { return it }
        val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return DEFAULT
        val c = runCatching { extract(bmp) }.getOrDefault(DEFAULT)
        cache[key] = c
        return c
    }

    private fun extract(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, SAMPLE, SAMPLE, true)
        var bestScore = -1.0
        var bestR = 0; var bestG = 0; var bestB = 0
        var sumR = 0L; var sumG = 0L; var sumB = 0L; var n = 0

        val hsv = FloatArray(3)
        for (y in 0 until SAMPLE) {
            for (x in 0 until SAMPLE) {
                val p = small.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                sumR += r; sumG += g; sumB += b; n++
                Color.colorToHSV(p, hsv)
                // prefer a pixel with some colour in it and some light:
                // near-black and near-white tell you nothing about a poster
                val sat = hsv[1]; val value = hsv[2]
                if (value < 0.12f || value > 0.95f) continue
                val score = sat.toDouble() * (1.0 - Math.abs(value - 0.6))
                if (score > bestScore) { bestScore = score; bestR = r; bestG = g; bestB = b }
            }
        }
        if (small != src) small.recycle()

        // nothing had any colour in it: fall back to the average, muted
        if (bestScore <= 0.04 && n > 0) {
            bestR = (sumR / n).toInt(); bestG = (sumG / n).toInt(); bestB = (sumB / n).toInt()
        }
        return mute(Color.rgb(bestR, bestG, bestB))
    }

    /** push a colour down into the range that white text survives on */
    private fun mute(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 0.62f).coerceIn(0f, 0.55f)   // calmer
        hsv[2] = (hsv[2] * 0.34f).coerceIn(0.07f, 0.24f) // dark
        return Color.HSVToColor(hsv)
    }

    /**
     * Cross-fade a view's tint to a new colour. Focus moves constantly, so
     * this has to be a fade; snapping between colours looks broken.
     */
    fun washTo(view: android.view.View?, colour: Int, durationMs: Long = 420) {
        val v = view ?: return
        val from = (v.getTag(R.id.hue_tag) as? Int) ?: DEFAULT
        if (from == colour) return
        v.setTag(R.id.hue_tag, colour)
        android.animation.ValueAnimator.ofObject(
            android.animation.ArgbEvaluator(), from, colour
        ).apply {
            duration = durationMs
            addUpdateListener { a ->
                val c = a.animatedValue as Int
                v.background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(c, c and 0x00FFFFFF or (0xB0 shl 24), 0x00000000)
                )
            }
            start()
        }
    }
}
