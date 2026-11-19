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
 * Three kinds, all stored in the profile as one string:
 *
 *   - "art:crescent" — the painted set: cast-gold emblems on deep coloured
 *     grounds. These are the default, because they look like something a
 *     person made rather than something a program drew.
 *   - "av:crescent" — the flat vector marks, kept as the simple alternative
 *     and as the fallback for anything the painted set does not cover.
 *     Generated from computed geometry by scripts/gen_avatars.py.
 *   - a full URL — any picture, from the server's avatar folder or sent
 *     straight from a phone.
 *
 * The painted set is resolved BY NAME at runtime rather than from a
 * hardcoded list of resource ids, so it can grow or shrink with whatever
 * artwork ships without this file changing.
 *
 * An empty value falls back to the first letter of the name.
 */
object Avatars {

    /** flat vector marks: key to drawable. Order is the picker's order. */
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

    /** cached: it costs one resource lookup per key the first time */
    private var artCache: List<Pair<String, Int>>? = null

    /**
     * The painted set actually present in this build, in the vector set's
     * order so the two read the same way in the picker.
     */
    fun art(ctx: Context): List<Pair<String, Int>> {
        artCache?.let { return it }
        val found = BUILT_IN.mapNotNull { (key, _) ->
            val id = ctx.resources.getIdentifier("art_$key", "drawable", ctx.packageName)
            if (id != 0) key to id else null
        }
        artCache = found
        return found
    }

    private fun artId(ctx: Context, key: String): Int? =
        art(ctx).firstOrNull { it.first == key }?.second

    /** a starting avatar per known name, so nobody begins as a bare letter */
    private val DEFAULTS = mapOf(
        "Wahab" to "crescent", "Farhana" to "tulip", "Baboo" to "tea",
        "Khalid" to "swords", "Zeenat" to "lantern", "Laila" to "comet",
        "Aaria" to "owl", "Wasim" to "ship"
    )

    fun defaultFor(ctx: Context, name: String): String {
        val key = DEFAULTS.entries.firstOrNull { it.key.equals(name.trim(), true) }?.value
            // someone not on the list still gets a stable mark rather than a letter
            ?: BUILT_IN[Math.abs(name.trim().lowercase().hashCode()) % BUILT_IN.size].first
        return if (artId(ctx, key) != null) "art:$key" else "av:$key"
    }

    fun isArt(icon: String?) = icon != null && icon.startsWith("art:")
    fun isBuiltIn(icon: String?) = icon != null && icon.startsWith("av:")
    fun isPicture(icon: String?) = icon != null && icon.startsWith("http")

    fun drawableFor(icon: String?): Int? =
        if (isBuiltIn(icon)) byKey[icon!!.removePrefix("av:")] else null

    /**
     * Paint a profile onto the pair of views every avatar uses: a tinted
     * disc carrying the initial, and an image over it.
     *
     * The kinds are drawn differently on purpose. A flat vector mark is a
     * silhouette, so it is tinted dark and inset to leave the profile's
     * colour showing around it. Painted artwork and photographs carry their
     * own colour and fill the disc edge to edge, circle-cropped. In every
     * case the initial stays underneath as the fallback, so an image that
     * fails to load leaves something readable rather than an empty disc.
     */
    fun paint(ctx: Context, letter: TextView, image: ImageView?, p: Profiles.Profile) {
        letter.backgroundTintList = android.content.res.ColorStateList.valueOf(p.colour)
        val vector = drawableFor(p.icon)
        val painted = if (isArt(p.icon)) artId(ctx, p.icon.removePrefix("art:")) else null
        when {
            vector != null && image != null -> {
                letter.text = ""
                image.imageTintList =
                    android.content.res.ColorStateList.valueOf(0xFF1A1408.toInt())
                // a drawn mark needs air inside the disc; a picture does not
                val inset = (letter.layoutParams?.width ?: 0).let {
                    if (it > 0) it / 4 else Ui.dp(ctx, 10)
                }
                image.setPadding(inset, inset, inset, inset)
                image.setImageResource(vector)
                image.visibility = android.view.View.VISIBLE
            }
            painted != null && image != null -> fill(ctx, letter, image, painted, p)
            isPicture(p.icon) && image != null -> fill(ctx, letter, image, p.icon, p)
            else -> {
                letter.text = p.initial
                image?.visibility = android.view.View.GONE
            }
        }
    }

    /** edge to edge in the disc, circle-cropped, the letter left as fallback */
    private fun fill(ctx: Context, letter: TextView, image: ImageView, data: Any,
                     p: Profiles.Profile) {
        letter.text = ""
        image.imageTintList = null
        image.setPadding(0, 0, 0, 0)
        image.visibility = android.view.View.VISIBLE
        Coil.imageLoader(ctx).enqueue(
            ImageRequest.Builder(ctx)
                .data(data)
                .transformations(CircleCropTransformation())
                .target(image)
                .listener(onError = { _, _ ->
                    image.visibility = android.view.View.GONE
                    letter.text = p.initial
                })
                .build())
    }
}
