package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Self-update: the addon serves /version.json and /apk. On launch we compare
 * versionCode and offer a one-click download + install.
 *
 * The update APK is downloaded into the app's PRIVATE external storage
 * (getExternalFilesDir) - public Downloads is not readable back via file
 * paths on modern Android (scoped storage), which makes FileProvider URIs
 * unreadable to the package installer and breaks the install step.
 *
 * v5.3 fixes a deadlock that made the updater stick on "downloading"
 * forever, which no amount of restarting could clear:
 *
 *  - A leftover update file that was partial or corrupt read back as
 *    versionCode -1. The old stale-file check only deleted files whose
 *    code was 0..installed, so -1 matched neither branch and the file
 *    survived every launch.
 *  - DownloadManager will not overwrite an existing destination; it
 *    silently writes "wahabtv-update-1.apk" instead. Install always opened
 *    the original name, i.e. the broken file, so the installer refused.
 *
 * Now: leftovers are always cleared before a download, the real downloaded
 * file is resolved from DownloadManager rather than assumed, every
 * candidate APK is parsed before being offered, and failures report the
 * reason instead of going quiet.
 */
object UpdateChecker {

    private const val PREFS = "settings"
    private const val KEY_DOWNLOAD_ID = "update_download_id"
    private const val FILE_NAME = "wahabtv-update.apk"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context): File? = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    private fun dm(ctx: Context) = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** every file a previous attempt may have left behind, including -1/-2 variants */
    private fun leftovers(ctx: Context): List<File> =
        dir(ctx)?.listFiles { f -> f.name.startsWith("wahabtv-update") && f.name.endsWith(".apk") }
            ?.toList() ?: emptyList()

    private fun clearLeftovers(ctx: Context) {
        leftovers(ctx).forEach { runCatching { it.delete() } }
        prefs(ctx).edit().remove(KEY_DOWNLOAD_ID).apply()
    }

    fun currentVersionCode(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    /** versionCode inside an APK file, or -1 when it cannot be parsed (partial/corrupt) */
    private fun archiveVersion(ctx: Context, f: File): Int {
        if (!f.isFile || f.length() < 1_000_000L) return -1
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0) }.getOrNull()
            ?: return -1
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    /** a usable, newer-than-installed APK already on disk, else null (deleting junk on the way) */
    private fun readyUpdate(ctx: Context): Pair<File, Int>? {
        val installed = currentVersionCode(ctx)
        var found: Pair<File, Int>? = null
        for (f in leftovers(ctx)) {
            val code = archiveVersion(ctx, f)
            if (code > installed) {
                // keep the highest; drop the rest
                if (found == null || code > found!!.second) {
                    found?.first?.let { runCatching { it.delete() } }
                    found = f to code
                } else runCatching { f.delete() }
            } else {
                // stale OR unparsable (code -1) - both are junk, and leaving an
                // unparsable one behind is what used to wedge the updater
                runCatching { f.delete() }
            }
        }
        return found
    }

    /**
     * Called on launch. Order matters: finish an interrupted download first,
     * then offer anything already on disk, then ask the server.
     */
    fun check(activity: Activity) {
        Thread {
            try {
                val installed = currentVersionCode(activity)

                // 1. a download this app started earlier
                val id = prefs(activity).getLong(KEY_DOWNLOAD_ID, -1L)
                if (id > 0) {
                    when (val st = downloadStatus(activity, id)) {
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_PAUSED -> return@Thread   // still going; leave it alone
                        DownloadManager.STATUS_FAILED -> {
                            val reason = downloadReason(activity, id)
                            dm(activity).remove(id)
                            clearLeftovers(activity)
                            activity.runOnUiThread {
                                if (!activity.isFinishing) Toast.makeText(activity,
                                    "Last update download failed (code $reason). It will retry.",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> prefs(activity).edit().remove(KEY_DOWNLOAD_ID).apply()
                        else -> if (st == -1) prefs(activity).edit().remove(KEY_DOWNLOAD_ID).apply()
                    }
                }

                // 2. a finished APK sitting on disk
                readyUpdate(activity)?.let { (file, code) ->
                    activity.runOnUiThread {
                        if (!activity.isFinishing) promptInstall(activity, file, code)
                    }
                    return@Thread
                }

                // 3. ask the server
                val raw = java.net.URL(Config.BASE_URL + "/version.json")
                    .openStream().bufferedReader().use { it.readText() }
                val j = JSONObject(raw)
                val remote = j.optInt("versionCode", -1)
                if (remote > installed) {
                    val name = j.optString("versionName", "?")
                    activity.runOnUiThread {
                        if (!activity.isFinishing) prompt(activity, name, remote)
                    }
                }
            } catch (e: Exception) {
                // no update info available - stay quiet
            }
        }.start()
    }

    /** Settings -> Check for updates. Reports every outcome, unlike the silent launch check. */
    fun checkNow(activity: Activity, report: (String) -> Unit) {
        Thread {
            try {
                val installed = currentVersionCode(activity)
                val raw = java.net.URL(Config.BASE_URL + "/version.json")
                    .openStream().bufferedReader().use { it.readText() }
                val j = JSONObject(raw)
                val remote = j.optInt("versionCode", -1)
                val name = j.optString("versionName", "?")
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    when {
                        remote <= 0 -> report("Server gave no version info")
                        remote > installed -> {
                            report("v$name available (you have $installed)")
                            prompt(activity, name, remote)
                        }
                        else -> report("Up to date (v$name, code $installed)")
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (!activity.isFinishing) report("Could not reach the server: ${e.message}")
                }
            }
        }.start()
    }

    /** force a fresh download even when a (possibly bad) file is already present */
    fun reinstall(activity: Activity, report: (String) -> Unit) {
        clearLeftovers(activity)
        report("Cleared old downloads - fetching the latest build…")
        download(activity, -1)
    }

    private fun downloadStatus(ctx: Context, id: Long): Int =
        dm(ctx).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c != null && c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
        }

    private fun downloadReason(ctx: Context, id: Long): Int =
        dm(ctx).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c != null && c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) else -1
        }

    /** where DownloadManager actually put the file (it renames on collision) */
    private fun downloadedFile(ctx: Context, id: Long): File? =
        dm(ctx).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c == null || !c.moveToFirst()) return null
            val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: return null
            runCatching { Uri.parse(uri).path?.let { File(it) } }.getOrNull()
        }

    private fun prompt(activity: Activity, versionName: String, expectedCode: Int) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("Update available")
            .setMessage("Wahab TV $versionName is ready.\nDownload and install now? (about 48 MB)")
            .setPositiveButton("Update now") { _, _ -> download(activity, expectedCode) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun promptInstall(activity: Activity, file: File, code: Int) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("Update downloaded")
            .setMessage("Version code $code is ready to install.\nInstall now?")
            .setPositiveButton("Install") { _, _ ->
                if (canInstall(activity)) install(activity, file) else requestInstallPermission(activity)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun canInstall(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission(activity: Activity) {
        Toast.makeText(activity,
            "Allow installs for Wahab TV once, then press Install again",
            Toast.LENGTH_LONG).show()
        activity.startActivity(Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")))
    }

    private fun download(activity: Activity, expectedCode: Int) {
        if (!canInstall(activity)) {
            requestInstallPermission(activity)
            return
        }

        // DownloadManager refuses to overwrite and would quietly write
        // "wahabtv-update-1.apk" instead, which install() never looked at.
        clearLeftovers(activity)

        val req = DownloadManager.Request(Uri.parse(Config.BASE_URL + "/apk"))
            .setTitle("Wahab TV update")
            .setDescription("Downloading the new version")
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = dm(activity).enqueue(req)
        prefs(activity).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()

        // Register on the APPLICATION context so the completion broadcast is
        // received even if the activity is destroyed while downloading. If the
        // whole process dies, the launch check picks the file up instead.
        val appCtx = activity.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { appCtx.unregisterReceiver(this) }
                prefs(appCtx).edit().remove(KEY_DOWNLOAD_ID).apply()

                if (downloadStatus(appCtx, id) != DownloadManager.STATUS_SUCCESSFUL) {
                    val reason = downloadReason(appCtx, id)
                    clearLeftovers(appCtx)
                    Toast.makeText(appCtx, "Update download failed (code $reason) - try again from Settings",
                        Toast.LENGTH_LONG).show()
                    return
                }

                // Use the file DownloadManager really wrote, and make sure it
                // parses before handing it to the installer.
                val file = downloadedFile(appCtx, id) ?: File(dir(appCtx), FILE_NAME)
                val code = archiveVersion(appCtx, file)
                if (code <= 0 || (expectedCode > 0 && code != expectedCode)) {
                    runCatching { file.delete() }
                    Toast.makeText(appCtx,
                        "The downloaded update was incomplete - try again from Settings",
                        Toast.LENGTH_LONG).show()
                    return
                }
                install(appCtx, file)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            appCtx.registerReceiver(receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            appCtx.registerReceiver(receiver, filter)
        }
    }

    private fun install(ctx: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not open update: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
