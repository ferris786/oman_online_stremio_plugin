package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast

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
    private const val CURRENT = "glass"

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
            .setTitle("New player look: Liquid Glass")
            .setMessage(
                "The controls now come on a frosted pane, so the picture " +
                "carries on behind them instead of going dark.\n\n" +
                "It sits alongside Classic, Terminal and Ottoman. You can " +
                "switch whenever you like in Settings, under Player skin.")
            .setPositiveButton("Use it now") { _, _ ->
                PlayerSkin.save(activity, PlayerSkin.GLASS)
                Toast.makeText(activity,
                    "Liquid Glass is on. It shows the next time you play something.",
                    Toast.LENGTH_LONG).show()
                Diag.log("announce", "glass enabled from the notice")
            }
            .setNeutralButton("Show me Settings") { _, _ ->
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .setNegativeButton("Keep Classic", null)
            .show()
    }
}
