package com.kmk.turkishtv

import android.app.Application
import android.content.ComponentCallbacks2
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Coil's defaults give the bitmap cache ~25% of the app heap. The home
 * screen can hold thirty-odd rows of artwork at once, and the player then
 * runs on top of it with a video buffer, which is a lot to ask of a TV box.
 *
 * The cache is capped here and pushed onto disk instead, so re-showing a
 * row is still instant but costs no heap. The app also drops the bitmap
 * cache as soon as Android signals pressure, rather than letting the
 * system kill the foreground activity - which looked like playback
 * "stopping and going back to the main screen".
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // First thing up, so a crash during start-up is still recorded, and
        // anything left unsent from the last run gets posted.
        // the shared connection pool and disk cache, before anything
        // makes a request
        Http.init(this)
        Diag.init(this)
        Coil.setImageLoader {
            ImageLoader.Builder(this)
                .memoryCache {
                    MemoryCache.Builder(this)
                        .maxSizePercent(0.12)       // was ~0.25 by default
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("artwork"))
                        .maxSizeBytes(220L * 1024 * 1024)
                        .build()
                }
                .respectCacheHeaders(false)
                .crossfade(true)
                .build()
        }
    }

    /**
     * Memory pressure is one of the two live theories for playback stopping
     * partway through, so every signal is recorded with the heap figures
     * attached. A TRIM_MEMORY_COMPLETE here followed by silence is the
     * system killing us; a stop with no trim at all means it was the stream.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Diag.warn("memory", "trim level $level (${trimName(level)})", Diag.memory())
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
            Diag.flushSoon()
        }
    }

    private fun trimName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete - next to be killed"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "ui hidden"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running critical"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running low"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "running moderate"
        else -> "?"
    }
}
