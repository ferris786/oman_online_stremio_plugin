package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent

/**
 * Telling the viewer something new exists.
 *
 * Features kept arriving in Settings where nobody went looking: the named
 * lists sat unused for weeks, and the photo-from-phone upload may as well
 * not have shipped. A thing nobody can find was not really delivered, so
 * each release that adds something worth knowing says so once, on the home
 * screen, and offers to turn it on there and then.
 *
 * Shown once per announcement id, device-wide rather than per profile:
 * it is news about the app, not about whoever is watching.
 */
object Announce {

    private const val KEY = "announced"

    /**
     * Bump this when there is something new to say, and update the text
     * below. An id that has already been seen never shows again.
     */
    private const val CURRENT = "v71"

    fun pending(activity: Activity): Boolean =
        Profiles.devicePrefs(activity).getString(KEY, "") != CURRENT

    private fun markSeen(activity: Activity) {
        Profiles.devicePrefs(activity).edit().putString(KEY, CURRENT).apply()
    }

    /** Shows the notice if it has not been seen. Safe to call every launch. */
    fun maybeShow(activity: Activity) {
        if (!pending(activity)) return
        if (activity.isFinishing) return
        markSeen(activity)   // seen once, whatever they choose

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Five new things")
            .setMessage(
                "Tonight — the first row now suggests what to watch, with a " +
                "reason for each pick.\n\n" +
                "All episodes — on a series page, see every episode at once " +
                "instead of scrolling a strip. Kurulus Osman has 194.\n\n" +
                "Colour — the home screen now takes its tint from whatever " +
                "artwork is showing.\n\n" +
                "Pages open rather than cut, and Wahab TV can be your TV's " +
                "screensaver: pick it in your TV's own Settings, under " +
                "Display, Screensaver.")
            .setPositiveButton("Have a look", null)
            .setNeutralButton("Open Settings") { _, _ ->
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .show()
    }
}
