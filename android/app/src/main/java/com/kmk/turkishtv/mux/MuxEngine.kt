package com.kmk.turkishtv.mux

import com.kmk.turkishtv.Diag
import com.kmk.turkishtv.Api
import com.kmk.turkishtv.Torbox

/**
 * AIOStreams-style mux pipeline for English movies:
 *
 *   parallel provider fetch -> parse attributes -> dedupe by infohash ->
 *   one batched TorBox cache check -> filter (profile) ->
 *   lexicographic sort [cached, profile resolution, seeders, size] ->
 *   uniform formatting
 *
 * Providers run on parallel threads with an overall timeout; failures from
 * individual providers are swallowed (their results simply don't appear).
 */
object MuxEngine {

    data class Result(
        val streams: List<MuxStream>,     // sorted, formatted, ready for chips
        val cachedCount: Int,
        val uncachedCount: Int,
        val providerCount: Int,
        val providers: List<String> = emptyList()
    )

    /**
     * season/episode > 0 switches to TV mode: Torrentio is queried for the
     * episode (season packs arrive with the episode's fileIdx), TPB is
     * searched with the episode tag, and packs are ranked so an uncached
     * 40 GB season is never the first thing the player tries.
     */
    /**
     * How long to wait for the source providers in total. Long enough for a
     * slow-but-working one, short enough that a blocked one is not felt.
     */
    private const val PROVIDER_DEADLINE_MS = 9_000L

    fun fetch(
        imdbId: String,
        title: String,
        year: String,
        torboxKey: String?,
        config: MuxConfig,
        season: Int = 0,
        episode: Int = 0
    ): Result {
        val tv = season > 0 && episode > 0
        val epTag = if (tv) "S%02dE%02d".format(season, episode) else ""

        // ---- 1. parallel provider fetch (device-side: not DC-blocked) ----
        var tor: List<Api.Torrent> = emptyList()
        var yts: List<Api.Torrent> = emptyList()
        var tpb: List<Api.Torrent> = emptyList()
        var addon: List<Api.Torrent> = emptyList()

        val t0 = Thread { tor = try { if (tv) Api.torrentioEpisode(imdbId, season, episode) else Api.torrentioMovie(imdbId) } catch (e: Exception) { emptyList() } }
        val t1 = Thread { if (!tv) yts = try { Api.ytsByImdb(imdbId) } catch (e: Exception) { emptyList() } }
        val t2 = Thread { if (title.isNotEmpty()) tpb = try { Api.apibaySearch(if (tv) "$title $epTag" else title, if (tv) "" else year) } catch (e: Exception) { emptyList() } }
        val t3 = Thread { if (!tv) addon = try { Api.enTorrents(imdbId) } catch (e: Exception) { emptyList() } }
        t0.start(); t1.start(); t2.start(); t3.start()
        // Wait on a DEADLINE, not on every provider.
        //
        // Some of these hosts are blocked or throttled by domestic ISPs, and
        // a blocked one does not fail — it hangs until the socket times out.
        // Joining all four unconditionally meant one sulking provider held
        // up the other three, which is the "finding sources" spinner sitting
        // there for twenty seconds. Whatever has arrived by the deadline is
        // what gets used; a late provider simply misses this round.
        val deadline = System.currentTimeMillis() + PROVIDER_DEADLINE_MS
        for (t in listOf(t0, t1, t2, t3)) {
            val left = deadline - System.currentTimeMillis()
            if (left > 0) t.join(left) else break
        }

        // TPB results for an episode must actually be that episode (or a
        // pack that contains it); a stray result for another season is junk
        if (tv) tpb = tpb.filter { t ->
            val n = t.name.uppercase()
            n.contains(epTag) || Regex("""\bS%02d\b(?!E)""".format(season)).containsMatchIn(n) ||
                n.contains("SEASON $season") || n.contains("COMPLETE")
        }

        // ---- 2. parse + 3. dedupe by infohash (provider priority: YTS > TPB > ADDON) ----
        val byHash = LinkedHashMap<String, MuxStream>()
        fun ingest(list: List<Api.Torrent>, provider: String) {
            for (t in list) {
                val h = t.hash.lowercase()
                if (h.length < 20 || byHash.containsKey(h)) continue
                byHash[h] = MuxStream(
                    name = t.name, hash = h, sizeBytes = t.sizeBytes,
                    seeders = t.seeders, provider = provider,
                    attrs = ReleaseParser.parse(t.name), fileIdx = t.fileIdx
                )
            }
        }
        ingest(tor, "TOR")
        ingest(yts, "YTS")
        ingest(tpb, "TPB")
        ingest(addon, "SRV")

        var all = byHash.values.toList()
        // which provider actually answered, and how long the round took, so
        // "no sources" and "took forever" explain themselves next time
        Diag.log("sources", "providers answered", mapOf(
            "torrentio" to tor.size, "yts" to yts.size,
            "apibay" to tpb.size, "server" to addon.size,
            "unique" to all.size,
            "tookMs" to (System.currentTimeMillis() - (deadline - PROVIDER_DEADLINE_MS))))

        // ---- 4. one batched availability check ----
        var cachedSet: Set<String> = emptySet()
        var cacheReliable = true
        if (torboxKey != null && all.isNotEmpty()) {
            val check = Torbox.cachedHashes(torboxKey, all.map { it.hash })
            cachedSet = check.cached
            cacheReliable = check.reliable
        }
        all = all.map { it.copy(cached = cachedSet.contains(it.hash)) }
        Diag.log("sources", "cache checked", mapOf(
            "of" to all.size, "cached" to cachedSet.size, "reliable" to cacheReliable))

        // ---- 5. filter (profile-driven) ----
        // first drop hopeless uploads before they waste a resolve attempt:
        // RAR-packed/disc-image releases and RU-tracker dumps
        val archiveRe = Regex("""\.rar\b|\.iso\b|rutor|-RUS\b|\bRUS\b.*\(2\d{3}\)""", RegexOption.IGNORE_CASE)
        all = all.filter { s -> !archiveRe.containsMatchIn(s.name) }
        all = all.filter { s ->
            if (s.seeders < config.minSeeders) return@filter false
            if (config.hide4k && s.attrs.resolution == 2160) return@filter false
            val cap = config.sizeCap(s.attrs.resolution)
            if (cap > 0 && s.sizeBytes > cap) return@filter false
            // Only hide uncached sources when we actually KNOW they are
            // uncached. If TorBox was rate-limiting or a batch failed, every
            // source looks uncached and this would throw the lot away — a
            // film with fifty working sources reported as having none.
            if (!s.cached && !config.showUncached && cacheReliable) return@filter false
            true
        }

        // ---- 6. lexicographic sort, then DIVERSITY-AWARE selection ----
        // Sorting cached-first buries uncached 4K under a wall of cached
        // 1080p (measured: 19 4K streams, 0 in the top 12). Instead: sort
        // within each resolution tier, then round-robin the tiers in profile
        // order so every quality that exists is REPRESENTED - cached rows
        // still lead each tier.
        val resOrder = config.resolutionOrder()
        // TV: a cached season pack is the best thing there is (instant, and
        // the next episodes are already there); an uncached pack is the worst
        // (a 40 GB download for one episode), so it goes last.
        fun packRank(s: MuxStream): Int = when {
            !tv -> if (s.cached) 0 else 1
            s.cached && s.isPack -> 0
            s.cached -> 1
            !s.isPack -> 2
            else -> 3
        }
        fun tierSort(a: MuxStream, b: MuxStream): Int = compareValuesBy(
            a, b,
            { packRank(it) },
            { -it.seeders },                    // more seeders first
            { it.sizeBytes }                    // smaller first
        )

        val tiers = resOrder
            .map { r -> all.filter { it.attrs.resolution == r }.sortedWith(::tierSort) }
            .filter { it.isNotEmpty() }

        val picked = ArrayList<MuxStream>(config.maxResults)
        var round = 0
        while (picked.size < config.maxResults) {
            var added = false
            for (tier in tiers) {
                if (round < tier.size) {
                    picked.add(tier[round])
                    added = true
                    if (picked.size >= config.maxResults) break
                }
            }
            if (!added) break
            round++
        }

        val cachedCount = picked.count { it.cached }
        val providers = mutableListOf<String>()
        if (tor.isNotEmpty()) providers.add("Torrentio")
        if (yts.isNotEmpty()) providers.add("YTS")
        if (tpb.isNotEmpty()) providers.add("TPB")
        if (addon.isNotEmpty()) providers.add("SRV")
        return Result(
            streams = picked,
            cachedCount = cachedCount,
            uncachedCount = picked.size - cachedCount,
            providerCount = providers.size,
            providers = providers
        )
    }

}
