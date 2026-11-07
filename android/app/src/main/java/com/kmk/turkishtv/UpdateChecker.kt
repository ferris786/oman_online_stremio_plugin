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
 * A previously downloaded (but not installed) update is detected on launch
 * and offered for direct install instead of re-downloading.
 */
object UpdateChecker {

    private fun updateFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "wahabtv-update.apk")

    fun check(activity: Activity) {
        Thread {
            try {
                val installed = currentVersionCode(activity)
                val local = updateFile(activity)

                // A downloaded-but-not-installed update? Install it directly.
                if (local.exists()) {
                    val archiveCode = activity.packageManager
                        .getPackageArchiveInfo(local.absolutePath, 0)
                        ?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode }
                        ?: -1
                    when {
                        archiveCode > installed -> {
                            activity.runOnUiThread { promptInstall(activity, archiveCode) }
                            return@Thread
                        }
                        archiveCode in 0..installed -> local.delete() // stale file
                    }
                }

                val raw = java.net.URL(Config.BASE_URL + "/version.json")
                    .openStream().bufferedReader().use { it.readText() }
                val remote = JSONObject(raw).optInt("versionCode", -1)
                if (remote > installed) {
                    val name = JSONObject(raw).optString("versionName", "?")
                    activity.runOnUiThread { prompt(activity, name) }
                }
            } catch (e: Exception) {
                // no update info available - stay quiet
            }
        }.start()
    }

    private fun currentVersionCode(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    private fun prompt(activity: Activity, versionName: String) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("Update available")
            .setMessage("Wahab TV $versionName is ready.\nDownload and install now?")
            .setPositiveButton("Update now") { _, _ -> download(activity) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun promptInstall(activity: Activity, code: Int) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("Update downloaded")
            .setMessage("Update to version $code is ready to install.\nInstall now?")
            .setPositiveButton("Install") { _, _ ->
                if (canInstall(activity)) install(activity) else requestInstallPermission(activity)
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

    private fun download(activity: Activity) {
        if (!canInstall(activity)) {
            requestInstallPermission(activity)
            return
        }

        val req = DownloadManager.Request(Uri.parse(Config.BASE_URL + "/apk"))
            .setTitle("Wahab TV update")
            .setDestinationInExternalFilesDir(
                activity, Environment.DIRECTORY_DOWNLOADS, "wahabtv-update.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()

        // Register on the APPLICATION context so the completion broadcast is
        // received even if the activity is destroyed while downloading.
        val appCtx = activity.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { appCtx.unregisterReceiver(this) } catch (e: Exception) { /* already unregistered */ }
                val statusOk = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    c.moveToFirst() &&
                        c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
                }
                if (statusOk) install(appCtx)
                else Toast.makeText(appCtx, "Update download failed - try again",
                    Toast.LENGTH_LONG).show()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            appCtx.registerReceiver(receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            appCtx.registerReceiver(receiver, filter)
        }
    }

    private fun install(ctx: Context) {
        try {
            val file = updateFile(ctx)
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
