package com.kmk.turkishtv

import android.content.Context
import android.os.Build
import android.os.Debug
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Remote diagnostics.
 *
 * The TV is the only place that knows why playback stops, and its logcat
 * cannot be read from anywhere else. So the app keeps its own small log and
 * posts it to the server, where it can be read back after the fact.
 *
 * Two things matter for this to be worth anything:
 *
 *  - Events hit DISK before they are sent. If Android kills the process
 *    mid-episode - one of the two live theories for the stopping - an
 *    in-memory buffer would die with it and take the evidence along. The
 *    pending file is posted on the next launch instead.
 *  - Nothing here may break playback. Every path is wrapped, the work runs
 *    on one background thread, and a failed upload just leaves the file for
 *    next time.
 *
 * Only playback and lifecycle facts are recorded: no titles the viewer
 * would not want leaving the house, no keys, no tokens.
 */
object Diag {

    private const val PENDING = "diag-pending.jsonl"
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val FLUSH_EVERY_MS = 25_000L
    private const val MAX_BATCH = 150

    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diag").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val http by lazy {
        Http.withTimeouts(8, 10, 10)
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var app: Context? = null
    private var deviceId = "?"
    private var appLabel = "?"
    private var started = false

    /** true while the user has diagnostics switched on in Settings */
    fun enabled(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("diag_remote", true)
    }.getOrDefault(true)

    fun setEnabled(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean("diag_remote", on).apply()
        }
    }

    // ------------------------------------------------------------------ setup

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        deviceId = prefs.getString("diag_device", null) ?: UUID.randomUUID().toString()
            .take(8).also { prefs.edit().putString("diag_device", it).apply() }
        appLabel = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")

        // A crash is the one event that must survive the process. Record it
        // synchronously, then let the default handler do its job.
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            runCatching {
                appendNow(event("fatal", "crash", err.javaClass.simpleName + ": " + err.message,
                    mapOf("thread" to thread.name, "stack" to shortStack(err))))
            }
            prior?.uncaughtException(thread, err)
        }

        log("app", "launched", mapOf(
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
            "heapMax" to "${Runtime.getRuntime().maxMemory() / 1048576}MB"
        ))
        io.scheduleWithFixedDelay({ runCatching { flush() } }, 6, FLUSH_EVERY_MS / 1000, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ writing

    fun log(tag: String, msg: String, data: Map<String, Any?>? = null) = put("info", tag, msg, data)
    fun warn(tag: String, msg: String, data: Map<String, Any?>? = null) = put("warn", tag, msg, data)
    fun error(tag: String, msg: String, data: Map<String, Any?>? = null) = put("error", tag, msg, data)

    private fun put(level: String, tag: String, msg: String, data: Map<String, Any?>?) {
        val ctx = app ?: return
        if (!enabled(ctx)) return
        val e = event(level, tag, msg, data)
        runCatching { io.execute { runCatching { appendNow(e) } } }
    }

    private fun event(level: String, tag: String, msg: String, data: Map<String, Any?>?): JSONObject {
        val o = JSONObject()
        o.put("t", stamp.format(Date()))
        o.put("level", level)
        o.put("tag", tag)
        o.put("msg", msg)
        if (data != null && data.isNotEmpty()) {
            val d = JSONObject()
            for ((k, v) in data) d.put(k, v?.toString() ?: "")
            o.put("data", d)
        }
        return o
    }

    private fun appendNow(e: JSONObject) {
        val ctx = app ?: return
        val f = File(ctx.filesDir, PENDING)
        if (f.length() > MAX_FILE_BYTES) trim(f)
        f.appendText(e.toString() + "\n")
    }

    /** keep the newest half; a log that has grown unsent is not worth a full disk */
    private fun trim(f: File) {
        runCatching {
            val lines = f.readLines()
            f.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }

    // ------------------------------------------------------------------ sending

    /** Post what is pending. Only clears the file on a confirmed 2xx. */
    private fun flush() {
        val ctx = app ?: return
        if (!enabled(ctx)) return
        val f = File(ctx.filesDir, PENDING)
        if (!f.exists() || f.length() == 0L) return

        val all = runCatching { f.readLines().filter { it.isNotBlank() } }.getOrNull() ?: return
        if (all.isEmpty()) { runCatching { f.delete() }; return }
        val batch = all.take(MAX_BATCH)

        val events = JSONArray()
        for (line in batch) runCatching { events.put(JSONObject(line)) }
        val body = JSONObject()
            .put("device", "$deviceId · ${Build.MODEL}")
            .put("app", appLabel)
            .put("profile", runCatching { Profiles.activeName(ctx) }.getOrDefault(""))
            .put("events", events)
            .toString()

        val ok = runCatching {
            http.newCall(
                Request.Builder()
                    .url(Config.BASE_URL + "/log")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        if (!ok) return                       // leave the file; try again later
        val rest = all.drop(batch.size)
        runCatching {
            if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n", postfix = "\n"))
        }
    }

    /** Called when something important just happened and should not wait. */
    fun flushSoon() {
        runCatching { io.schedule({ runCatching { flush() } }, 1, TimeUnit.SECONDS) }
    }

    // ------------------------------------------------------------------ helpers

    /** heap and native use, the numbers that would show a memory kill coming */
    fun memory(): Map<String, Any?> {
        val rt = Runtime.getRuntime()
        return mapOf(
            "heapUsedMB" to (rt.totalMemory() - rt.freeMemory()) / 1048576,
            "heapMaxMB" to rt.maxMemory() / 1048576,
            "nativeMB" to Debug.getNativeHeapAllocatedSize() / 1048576
        )
    }

    private fun shortStack(err: Throwable): String =
        err.stackTrace.take(6).joinToString(" | ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }

    /** a host is useful for spotting one bad CDN; a full signed URL is not */
    fun host(url: String?): String = runCatching {
        android.net.Uri.parse(url ?: "").host ?: "-"
    }.getOrDefault("-")
}
