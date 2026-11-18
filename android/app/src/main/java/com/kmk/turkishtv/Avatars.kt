package com.kmk.turkishtv

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import coil.Coil
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation

/**
 * Profile avatars.
 *
 * Two kinds, both stored in the profile as a single string:
 *   - a built-in mark, "av:crescent", drawn from this set of original flat
 *     icons. They are generated from computed geometry by
 *     scripts/gen_avatars.py rather than traced, so the whole set shares a
 *     grid and a weight.
 *   - any picture, stored as a full URL. Drop image files into
 *     data/avatars on the server and they appear in the picker on every TV
 *     in the household, which is how you get artwork of your own in here
 *     without it being baked into a release.
 *
 * An empty value falls back to the first letter of the name, which is what
 * every profile starts as.
 */
object Avatars {

    /** key to drawable. Order is the order the picker shows them in. */
    val BUILT_IN: List<Pair<String, Int>> = listOf(
        // the Ottoman set, for the Turkish half of the library
        "crescent" to R.drawable.av_crescent,
        "tulip" to R.drawable.av_tulip,
        "fez" to R.drawable.av_fez,
        "tea" to R.drawable.av_tea,
        "lantern" to R.drawable.av_lantern,
        "swords" to R.drawable.av_swords,
        "shield" to R.drawable.av_shield,
        "dagger" to R.drawable.av_dagger,
        "crown" to R.drawable.av_crown,
        "fortress" to R.drawable.av_fortress,
        "ship" to R.drawable.av_ship,
        "compass" to R.drawable.av_compass,
        // cinema
        "clapper" to R.drawable.av_clapper,
        "reel" to R.drawable.av_reel,
        "masks" to R.drawable.av_masks,
        "vinyl" to R.drawable.av_vinyl,
        // the rest
        "key" to R.drawable.av_key,
        "hourglass" to R.drawable.av_hourglass,
        "lighthouse" to R.drawable.av_lighthouse,
        "comet" to R.drawable.av_comet,
        "owl" to R.drawable.av_owl,
        "mountains" to R.drawable.av_mountains
    )

    private val byKey: Map<String, Int> = BUILT_IN.toMap()

    /** a pleasing starting icon per known name, so nobody starts blank */
    private val DEFAULTS = mapOf(
        "Wahab" to "crescent", "Farhana" to "tulip", "Baboo" to "tea",
        "Khalid" to "swords", "Zeenat" to "lantern", "Laila" to "comet",
        "Aaria" to "owl", "Wasim" to "ship"
    )

    fun defaultFor(name: String): String {
        val k = DEFAULTS.entries.firstOrNull { it.key.equals(name.trim(), true) }?.value
        if (k != null) return "av:$k"
        // someone not on the list still gets a stable mark rather than a letter
        val i = Math.abs(name.trim().lowercase().hashCode()) % BUILT_IN.size
        return "av:" + BUILT_IN[i].first
    }

    fun isBuiltIn(icon: String?) = icon != null && icon.startsWith("av:")
    fun isPicture(icon: String?) = icon != null && icon.startsWith("http")

    fun drawableFor(icon: String?): Int? =
        if (isBuiltIn(icon)) byKey[icon!!.removePrefix("av:")] else null

    /**
     * Paint a profile onto the pair of views every avatar uses: a tinted
     * circle carrying the initial, and an image on top for the icon. The
     * initial stays as the fallback, so a picture that fails to load still
     * leaves something readable rather than an empty disc.
     */
    fun paint(ctx: Context, letter: TextView, image: ImageView?, p: Profiles.Profile) {
        letter.backgroundTintList = android.content.res.ColorStateList.valueOf(p.colour)
        val res = drawableFor(p.icon)
        when {
            res != null -> {
                letter.text = ""
                image?.apply {
                    setImageResource(res)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFF1A1408.toInt())
                    // a drawn mark needs air inside the disc; a photo does not
                    val inset = (letter.layoutParams?.width ?: 0).let {
                        if (it > 0) it / 4 else Ui.dp(ctx, 10)
                    }
                    setPadding(inset, inset, inset, inset)
                    visibility = android.view.View.VISIBLE
                }
            }
            isPicture(p.icon) && image != null -> {
                letter.text = ""
                image.imageTintList = null
                image.setPadding(0, 0, 0, 0)
                image.visibility = android.view.View.VISIBLE
                Coil.imageLoader(ctx).enqueue(
                    ImageRequest.Builder(ctx)
                        .data(p.icon)
                        .transformations(CircleCropTransformation())
                        .target(image)
                        .listener(onError = { _, _ ->
                            // fall back to the letter rather than an empty disc
                            image.visibility = android.view.View.GONE
                            letter.text = p.initial
                        })
                        .build())
            }
            else -> {
                letter.text = p.initial
                image?.visibility = android.view.View.GONE
            }
        }
    }
}
