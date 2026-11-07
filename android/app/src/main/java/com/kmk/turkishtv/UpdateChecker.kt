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
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Self-update: the addon serves /version.json and /apk. On launch we compare
 * versionCode and offer a one-click download + install (Android's "install
 * unknown apps" flow applies once per app).
 */
object UpdateChecker {

    fun check(activity: Activity) {
        Thread {
            try {
                val raw = java.net.URL(Config.BASE_URL + "/version.json")
                    .openStream().bufferedReader().use { it.readText() }
                val remote = JSONObject(raw).optInt("versionCode", -1)
                if (remote < 0) return@Thread

                val local = activity.packageManager
                    .getPackageInfo(activity.packageName, 0)
                    .let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode }

                if (remote > local) {
                    val name = JSONObject(raw).optString("versionName", "?")
                    activity.runOnUiThread { prompt(activity, name) }
                }
            } catch (e: Exception) {
                // no update info available - stay quiet
            }
        }.start()
    }

    private fun prompt(activity: Activity, versionName: String) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("Update available")
            .setMessage("Wahab TV $versionName is ready.\nDownload and install now?")
            .setPositiveButton("Update now") { _, _ -> download(activity) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity,
                "Allow installs for Wahab TV once, then press Update again",
                Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")))
            return
        }

        val req = DownloadManager.Request(Uri.parse(Config.BASE_URL + "/apk"))
            .setTitle("Wahab TV update")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "wahabtv-update.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                ctx.unregisterReceiver(this)
                install(ctx)
            }
        }
        activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun install(ctx: Context) {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "wahabtv-update.apk")
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
