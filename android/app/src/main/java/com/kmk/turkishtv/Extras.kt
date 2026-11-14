package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Trailers and reviews - shared by the movie page and the series page.
 *
 * Trailers play in the YouTube app, which every Android TV has and which
 * handles its own streaming, so nothing has to be scraped or proxied.
 */
object Extras {

    /**
     * Play a trailer. In-app by default (YouTube's embedded player in a
     * WebView): handing it to the YouTube TV app lands on the account
     * picker on a cold start and loses the video, so you pick a profile and
     * never reach the trailer. Settings can switch back to the app.
     */
    fun playTrailer(activity: Activity, ytId: String, title: String) {
        val useApp = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("trailer_mode", "inapp") == "youtube"
        if (!useApp) {
            activity.startActivity(Intent(activity, TrailerActivity::class.java)
                .putExtra("ytId", ytId).putExtra("title", title))
            return
        }
        openInYouTube(activity, ytId, title)
    }

    /** Explicit packages first - a chooser or an implicit match is what lands on the profile screen. */
    fun openInYouTube(activity: Activity, ytId: String, title: String) {
        val url = "https://www.youtube.com/watch?v=$ytId"
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube.tv"),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube"),
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$ytId")),
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        )
        for (i in attempts) {
            try {
                activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(activity, "Trailer: $title", Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) { /* try the next one */ }
        }
        Toast.makeText(activity, "No app on this TV can play the trailer", Toast.LENGTH_LONG).show()
    }

    /**
     * Reviews, shown as a scrollable list. IMDb has no public review API, so
     * these are TMDB user reviews - the dialog says so rather than implying
     * they came from IMDb.
     */
    fun showReviews(activity: Activity, title: String, imdbRating: String?, reviews: List<Api.Review>) {
        if (activity.isFinishing) return
        val d = activity.resources.displayMetrics.density
        val pad = (d * 16).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        root.addView(TextView(activity).apply {
            text = listOfNotNull(
                imdbRating?.let { "IMDb $it / 10" },
                "${reviews.size} reader reviews"
            ).joinToString("   ·   ")
            setTextColor(activity.resources.getColor(R.color.accent))
            textSize = 14f
            paint.isFakeBoldText = true
        })
        root.addView(TextView(activity).apply {
            text = "Review text from The Movie Database. IMDb does not publish a review API."
            setTextColor(activity.resources.getColor(R.color.text_dim))
            textSize = 11f
            setPadding(0, (d * 3).toInt(), 0, (d * 10).toInt())
        })

        if (reviews.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "No written reviews for this title yet."
                setTextColor(activity.resources.getColor(R.color.text_dim))
                textSize = 14f
            })
        }

        for (r in reviews) {
            root.addView(TextView(activity).apply {
                text = listOfNotNull(
                    r.author,
                    r.rating.takeIf { it > 0 }?.let { "★ $it/10" },
                    r.createdAt
                ).joinToString("   ·   ")
                setTextColor(activity.resources.getColor(R.color.title_cream))
                textSize = 13f
                paint.isFakeBoldText = true
                setPadding(0, (d * 12).toInt(), 0, (d * 3).toInt())
            })
            root.addView(TextView(activity).apply {
                // reviewers paste markdown and html; strip the worst of it
                text = r.content
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("_{2,}|\\*{2,}"), "")
                    .replace(Regex("[ \\t]{2,}"), " ")
                    .trim()
                setTextColor(activity.resources.getColor(R.color.text_soft))
                textSize = 13f
                setLineSpacing(d * 3, 1f)
            })
        }

        val scroll = ScrollView(activity).apply {
            isFocusable = true
            addView(root)
        }

        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("Close", null)
            .show()
        scroll.post { scroll.requestFocus() }
    }

    /** Fetch reviews off the UI thread, then show them. */
    fun loadReviews(activity: Activity, imdbId: String, title: String, imdbRating: String?, onDone: () -> Unit) {
        Thread {
            val list = try { Api.reviews(imdbId) } catch (e: Exception) { emptyList() }
            activity.runOnUiThread {
                onDone()
                if (!activity.isFinishing) showReviews(activity, title, imdbRating, list)
            }
        }.start()
    }
}
