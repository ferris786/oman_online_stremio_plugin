package com.kmk.turkishtv

import android.content.Context
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One HTTP stack for the whole app.
 *
 * Two reasons it is shared rather than one client per caller.
 *
 * The connection pool. The server speaks HTTP/2, so every request to it
 * can ride a single connection; four separate clients meant four pools and
 * four TLS handshakes to the same host, which costs real time when the box
 * is on the other side of the world.
 *
 * The disk cache. The server already marks catalogues `max-age=3600` and
 * the app ignored it, so opening Movies refetched all two dozen rows every
 * single time. With a cache those become local reads until the hour is up.
 *
 * Callers that need different timeouts derive from the base with
 * newBuilder(), which keeps the pool and the cache shared.
 */
object Http {

    private const val CACHE_MB = 40L

    @Volatile private var base: OkHttpClient? = null

    /** Called once from App.onCreate, before anything makes a request. */
    fun init(ctx: Context) {
        if (base != null) return
        val dir = File(ctx.cacheDir, "http")
        base = OkHttpClient.Builder()
            .cache(Cache(dir, CACHE_MB * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * The shared client. Falling back to a plain one keeps the app working
     * if anything ever asks before init, rather than crashing over a cache.
     */
    fun client(): OkHttpClient = base ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build().also { base = it }

    /** same pool and cache, different patience */
    fun withTimeouts(connectS: Long, readS: Long, writeS: Long = 15): OkHttpClient =
        client().newBuilder()
            .connectTimeout(connectS, TimeUnit.SECONDS)
            .readTimeout(readS, TimeUnit.SECONDS)
            .writeTimeout(writeS, TimeUnit.SECONDS)
            .build()

    /**
     * Serve a catalogue from disk when it is fresh, and fall back to the
     * network when it is not. The server's own hour is respected, so this
     * only avoids repeating a request it already answered.
     */
    val CACHEABLE: CacheControl = CacheControl.Builder()
        .maxStale(6, TimeUnit.HOURS)   // a stale row beats an empty one offline
        .build()

    /** bytes served from cache versus fetched, for the diagnostics panel */
    fun cacheStats(): String {
        val c = base?.cache ?: return "no cache"
        return runCatching { "${c.hitCount()} hits / ${c.requestCount()} requests" }
            .getOrDefault("cache unavailable")
    }
}
