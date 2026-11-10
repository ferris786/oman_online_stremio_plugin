const axios = require("axios");
const cheerio = require("cheerio");
const fs = require("fs");
const path = require("path");
const { getKayiFamilyStream, toAbsoluteEpisode, extractBestbStream, extractStrmupStream, SERIES_NAME_MAP, SEASON_OFFSETS } = require("./kayifamily");
const { getKayiFamilyTVStream, SERIES_NAME_MAP: KFTV_MAP } = require("./kayifamilytv");
const { canonicalSlug } = require("./catalog");
const publicbase = require("./publicbase");
const { extractKayihome, extractWakeupummah, extractVidara, buildProxyUrl } = require("./extractors/fireplayer");
const { buildXsStreamUrl } = require("./extractors/xtremestream");

const LOG_FILE = path.join(__dirname, "../debug_stream.log");

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

function log(message) {
    const timestamp = new Date().toISOString();
    const logMsg = `[${timestamp}] ${message}\n`;
    try {
        fs.appendFileSync(LOG_FILE, logMsg);
    } catch (e) {
        // Non-fatal on read-only filesystems
    }
    console.log(message);
}

/**
 * Resolve any stream id (new or legacy) into the pieces every source needs:
 *   siteSlug  - osmanonline.co.uk series slug (e.g. "kurulus-osman")
 *   abs       - absolute episode number used by all three sites
 *
 * New ids:    osmanonline:{slug}:episode-{abs}
 * Legacy ids: osmanonline:watch-{slug}-with-english-subtitles:watch-...-season-S-episode-E-with-english-subtitles
 */
function resolveId(id) {
    const parts = id.split(":");
    if (parts.length < 3) return null;

    const rawSeries = parts[1];
    const rest = parts.slice(2).join(":");

    const siteSlug = canonicalSlug(rawSeries);

    // New format: episode-{abs}
    const absMatch = rest.match(/episode-(\d+)/);
    if (absMatch && !rest.startsWith("watch-")) {
        return { siteSlug, abs: parseInt(absMatch[1]) };
    }

    // Legacy format: ...season-S-episode-E...
    // The old osmanonline.info slugs used ABSOLUTE episode numbers inside
    // season-prefixed URLs (e.g. season-6-episode-193 = absolute 193), except in
    // season 1 where they coincide. Detect which scheme we got.
    const seasonMatch = rest.match(/season-(\d+)/);
    const episodeMatch = rest.match(/episode-(\d+)/);
    const season = seasonMatch ? parseInt(seasonMatch[1]) : 1;
    const episode = episodeMatch ? parseInt(episodeMatch[1]) : 1;

    const kayiSlug = SERIES_NAME_MAP[rawSeries] || SERIES_NAME_MAP[siteSlug];
    const offsets = kayiSlug ? SEASON_OFFSETS[kayiSlug] : null;
    let abs;
    if (offsets && offsets[season] !== undefined && episode > offsets[season]) {
        abs = episode; // already absolute numbering
    } else {
        abs = kayiSlug ? toAbsoluteEpisode(kayiSlug, season, episode) : (season - 1) * 100 + episode;
    }

    return { siteSlug, abs };
}

/**
 * Source 1: osmanonline.co.uk (redesigned Nov 2026)
 * Episode pages at /series/{slug}/episode-{abs}/ embed vidara.to players
 * (plus wakeupummah / kayihome / bestb / strmup mirrors).
 */
async function getOsmanOnlineStream(siteSlug, abs) {
    const episodeUrl = `https://osmanonline.co.uk/series/${siteSlug}/episode-${abs}/`;

    try {
        log(`Fetching episode page: ${episodeUrl}`);
        const { data, status } = await axios.get(episodeUrl, {
            headers: { "User-Agent": UA },
            timeout: 20000,
            validateStatus: () => true
        });

        if (status !== 200) {
            log(`Episode page returned ${status}`);
            return null;
        }

        const $ = cheerio.load(data);

        // Collect iframe URLs from both src and lazy-loaded data-litespeed-src
        const iframes = [];
        $("iframe").each((i, el) => {
            const src = $(el).attr("src") || $(el).attr("data-litespeed-src") || "";
            if (src && src.startsWith("http")) iframes.push(src);
        });
        if (iframes.length === 0) {
            log("No iframes found on episode page");
            return null;
        }
        log(`Found ${iframes.length} iframe(s): ${iframes.map(u => u.slice(0, 60)).join(" | ")}`);

        const findIframe = (needle) => iframes.find(u => u.includes(needle));

        // 1. vidara.to (primary on the new site)
        const vidaraIframe = findIframe("vidara.to/e/");
        if (vidaraIframe) {
            const code = vidaraIframe.match(/vidara\.to\/e\/([a-zA-Z0-9]+)/)[1];
            const stream = await extractVidara(code, episodeUrl);
            if (stream) {
                log(`OsmanOnline stream via vidara.to (${code})`);
                return stream;
            }
        }

        // 2. wakeupummah.com FirePlayer mirrors
        const wuIframe = findIframe("wakeupummah.com");
        if (wuIframe) {
            const stream = await extractWakeupummah(wuIframe, episodeUrl);
            if (stream) {
                log("OsmanOnline stream via wakeupummah.com");
                return stream;
            }
        }

        // 3. kayihome.xyz
        const khIframe = findIframe("kayihome.xyz");
        if (khIframe) {
            const stream = await extractKayihome(khIframe);
            if (stream) {
                log("OsmanOnline stream via kayihome.xyz");
                return stream;
            }
        }

        // 4. strmup.to / bestb.stream (direct m3u8 pages)
        const strmupIframe = findIframe("strmup.to");
        if (strmupIframe) {
            const stream = await extractStrmupStream(strmupIframe);
            if (stream) return stream;
        }
        const bestbIframe = findIframe("bestb.stream");
        if (bestbIframe) {
            const stream = await extractBestbStream(bestbIframe);
            if (stream) return stream;
        }

        log("No supported video iframe on episode page");
        return null;

    } catch (error) {
        log(`Error in getOsmanOnlineStream: ${error.message}`);
        return null;
    }
}

/**
 * Source 4: osmanonline.live -> xtremestream.xyz (PeeStream XSFTK) players.
 * Streams are AES-encrypted on the CDN; the /xs/* routes in index.js decrypt
 * them on the fly, so this source always plays through the addon proxy.
 */
const LIVE_SLUG_MAP = {
    "kudus-fatihi-selahaddin-eyyubi": "salahuddin-ayyubi-english-subtitles",
    "alparslan-buyuk-selcuklu": "watch-alparslan-buyuk-selcuklu-with-english-subtitles"
};

async function getOsmanOnlineLiveStream(siteSlug, abs) {
    const candidates = [...new Set([
        LIVE_SLUG_MAP[siteSlug],
        siteSlug,
        `watch-${siteSlug}`,
        `watch-${siteSlug}-with-english-subtitles`
    ].filter(Boolean))];

    for (const slug of candidates) {
        const pageUrl = `https://osmanonline.live/${slug}-episode-${abs}/`;
        try {
            log(`Trying osmanonline.live: ${pageUrl}`);
            const { data, status } = await axios.get(pageUrl, {
                headers: { "User-Agent": UA },
                timeout: 9000,
                validateStatus: () => true
            });
            if (status !== 200) continue;

            const embed = (typeof data === "string" ? data : "").match(
                /https?:\/\/[a-z0-9.-]*xtremestream\.xyz\/player\/index\.php\?data=[a-f0-9]+/i
            );
            if (!embed) continue;

            const proxyHost = publicbase.get();
            const streamUrl = await buildXsStreamUrl(embed[0], proxyHost);
            log(`osmanonline.live stream via xtremestream (${embed[0].match(/data=([a-f0-9]+)/)[1].slice(0, 10)}…)`);
            return { url: streamUrl, source: "osmanonline-live" };
        } catch (err) {
            log(`osmanonline.live error (${slug}): ${err.message}`);
        }
    }
    return null;
}

// Resolved-stream cache: id -> { streams, ts }. Stream lists are stable for a
// given episode, and every URL is served/proxied by this addon, so a short
// cache makes repeat selections and player retries instant.
const streamCache = new Map();
const STREAM_CACHE_TTL = 10 * 60 * 1000;
const STREAM_CACHE_MAX = 200;

// Stremio shows an endless spinner when /stream takes too long. Sources are
// resolved in parallel under a HARD DEADLINE: whatever is ready when the
// deadline hits is what we return - a fast list with 2 sources beats a 60s
// wait for all 4.
const STREAM_DEADLINE_MS = 20000;

/**
 * Validate that a stream's master playlist actually responds with HLS.
 * Kills "source shows in the app but fails to play" experiences: dead
 * videos (vidara per-file failures, kayihome scheme changes, expired
 * mirrors) are dropped before the player ever sees them.
 */
async function streamIsPlayable(url) {
    try {
        const { data, status } = await axios.get(url, {
            headers: { "User-Agent": UA },
            timeout: 7000,
            responseType: "text",
            transformResponse: x => x,
            validateStatus: () => true
        });
        if (status !== 200 || typeof data !== "string" || !data.startsWith("#EXTM3U")) {
            return false;
        }

        // The playlist can be valid while its SEGMENTS are gone from the CDN
        // (vidara removes old content). Spot-check the first segment with a
        // tiny Range request so dead sources are dropped BEFORE playback.
        const abs = (base, line) => {
            if (line.startsWith("http")) return line;
            if (line.startsWith("/")) return new URL(base).origin + line;
            return base.substring(0, base.lastIndexOf("/") + 1) + line;
        };
        let lines = data.split("\n").map(l => l.trim()).filter(l => l && !l.startsWith("#"));
        if (lines.length === 0) return false;

        let checkUrl = abs(url, lines[0]);
        const resp = await axios.get(checkUrl, {
            headers: { "User-Agent": UA, "Range": "bytes=0-200", "Accept": "*/*" },
            timeout: 7000,
            responseType: "arraybuffer",
            validateStatus: () => true
        });
        const ct = (resp.headers["content-type"] || "").toLowerCase();

        // if it's a variant playlist, check ITS first segment too
        if (ct.includes("mpegurl") || ct.includes("text")) {
            const child = Buffer.from(resp.data).toString("utf-8");
            if (!child.startsWith("#EXTM3U")) return false;
            const segLines = child.split("\n").map(l => l.trim()).filter(l => l && !l.startsWith("#"));
            if (segLines.length === 0) return true;

            // vidara (and friends) expire content UNEVENLY: early segments can
            // 404 while later ones live, and CDN geo-routing means our check
            // may not match the client's view. Sample THREE points (first /
            // middle / last) - any failure drops the source.
            const probe = async (segLine) => {
                const segUrl = abs(checkUrl, segLine);
                try {
                    const r = await axios.get(segUrl, {
                        headers: { "User-Agent": UA, "Range": "bytes=0-200" },
                        timeout: 7000,
                        responseType: "arraybuffer",
                        validateStatus: () => true
                    });
                    return r.status === 200 || r.status === 206;
                } catch (e) { return false; }
            };
            const probes = [
                segLines[0],
                segLines[Math.floor(segLines.length / 2)],
                segLines[segLines.length - 1]
            ];
            const results = await Promise.all(probes.map(probe));
            return results.every(Boolean);
        }

        return resp.status === 200 || resp.status === 206;
    } catch (e) {
        return false;
    }
}

/**
 * Last-resort source: KayiFamilyTV hosts some episodes as official full
 * YouTube uploads (e.g. Dirilis Ertugrul season 1, which osmanonline.co.uk
 * has no embeds for). The first youtube embed on the episode page is the
 * episode itself (subsequent recurring embeds are site promos). The TV app
 * opens these in the YouTube app.
 */
async function getYouTubeFallbackStream(siteSlug, abs) {
    const slug = KFTV_MAP[siteSlug];
    if (!slug) return null;
    const pageUrl = `https://kayifamilytv.com/${slug}-episode-${abs}/`;
    try {
        const { data, status } = await axios.get(pageUrl, {
            headers: { "User-Agent": UA },
            timeout: 10000,
            validateStatus: () => true
        });
        if (status !== 200) return null;
        const m = (typeof data === "string" ? data : "").match(
            /(?:src|data-litespeed-src)="https:\/\/www\.youtube\.com\/embed\/([A-Za-z0-9_-]{11})/
        );
        if (!m) return null;
        log(`YouTube fallback for ${siteSlug} E${abs}: ${m[1]}`);
        return { url: `https://www.youtube.com/watch?v=${m[1]}`, source: "youtube" };
    } catch (err) {
        return null;
    }
}

async function getStream(type, id) {
    log(`getStream called for type=${type}, id=${id}`);

    const cached = streamCache.get(id);
    if (cached && (Date.now() - cached.ts) < STREAM_CACHE_TTL) {
        log(`Returning ${cached.streams.length} cached stream(s)`);
        return { streams: cached.streams };
    }

    const resolved = resolveId(id);
    if (!resolved) {
        log("Invalid ID format");
        return { streams: [] };
    }
    const { siteSlug, abs } = resolved;
    log(`Resolved: series=${siteSlug}, absolute episode=${abs}`);

    // Resolve all four sources in parallel under a hard deadline: the stream
    // list arrives in the time of the slowest source THAT FITS the deadline,
    // and anything still pending is simply skipped this round (the cache and
    // the player's own retry will pick it up later).
    // Speed-first order (tested segment latencies): direct-CDN sources lead,
    // the Render-proxied 1080p source goes last despite its quality.
    const defs = [
        { name: "KayiFamilyTV", title: `Source 1: KayiFamilyTV (fastest · E${abs})`, fn: () => getKayiFamilyTVStream(siteSlug, abs) },
        { name: "OsmanOnline", title: `Source 2: OsmanOnline (E${abs})`, fn: () => getOsmanOnlineStream(siteSlug, abs) },
        { name: "KayiFamily", title: `Source 3: KayiFamily (E${abs})`, fn: () => getKayiFamilyStream(siteSlug, abs) },
        { name: "OsmanOnlineLive", title: "Source 4: OsmanOnline.live 1080p (via addon proxy)", fn: () => getOsmanOnlineLiveStream(siteSlug, abs) }
    ];

    const streams = [];
    let done = 0;
    const workers = defs.map(async (def) => {
        try {
            const value = await def.fn();
            if (value && value.url) {
                streams.push({ name: def.name, title: def.title, url: value.url });
                log(`Added ${def.name} stream`);
            }
        } catch (err) {
            log(`${def.name} fetch error: ${err && err.message}`);
        } finally {
            done++;
        }
    });

    const deadline = new Promise(resolve => setTimeout(resolve, STREAM_DEADLINE_MS));
    await Promise.race([Promise.all(workers), deadline]);

    if (done < defs.length) {
        log(`Deadline hit: ${done}/${defs.length} sources resolved, returning partial list`);
    }

    // Preserve canonical source order regardless of completion order
    streams.sort((a, b) => defs.findIndex(d => d.name === a.name) - defs.findIndex(d => d.name === b.name));

    // Verify every stream actually serves HLS before offering it. A dead
    // source in the list is worse than a shorter list: players latch onto
    // it and fail. (Playlists are small; this is 1-2s per source and the
    // surviving list is cached.)
    if (streams.length > 0) {
        const checks = await Promise.all(streams.map(st => streamIsPlayable(st.url)));
        const before = streams.length;
        for (let i = streams.length - 1; i >= 0; i--) {
            if (!checks[i]) {
                log(`Dropping unplayable source: ${streams[i].name}`);
                streams.splice(i, 1);
            }
        }
        if (streams.length < before) {
            log(`Validation: ${before} -> ${streams.length} playable source(s)`);
        }
    }

    if (streams.length === 0) {
        // Last resort: official YouTube uploads via KayiFamilyTV (opens the
        // YouTube app on Android TV). Covers e.g. Dirilis Ertugrul S1.
        try {
            const yt = await getYouTubeFallbackStream(siteSlug, abs);
            if (yt) {
                streams.push({
                    name: "YouTube",
                    title: "Official full episode on YouTube (opens YouTube app)",
                    url: yt.url
                });
                log("Added YouTube fallback stream");
            }
        } catch (err) {
            log(`YouTube fallback error: ${err.message}`);
        }
    }

    if (streams.length === 0) {
        log("No streams found from any source");
        return { streams: [] };
    }

    // Don't cache fallback-only results: a transient upstream blip would pin
    // the YouTube fallback for the whole TTL even after sources recover.
    const onlyFallback = streams.length === 1 && streams[0].name === "YouTube";
    if (!onlyFallback) {
        if (streamCache.size > STREAM_CACHE_MAX) {
            streamCache.delete(streamCache.keys().next().value);
        }
        streamCache.set(id, { streams, ts: Date.now() });
    }

    log(`Returning ${streams.length} stream(s)`);
    return { streams };
}

module.exports = { getStream };
