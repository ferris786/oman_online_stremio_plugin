const axios = require("axios");
const cheerio = require("cheerio");
const fs = require("fs");
const path = require("path");
const { getKayiFamilyStream, toAbsoluteEpisode, extractBestbStream, extractStrmupStream, SERIES_NAME_MAP, SEASON_OFFSETS } = require("./kayifamily");
const { getKayiFamilyTVStream } = require("./kayifamilytv");
const { canonicalSlug } = require("./catalog");
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
                timeout: 20000,
                validateStatus: () => true
            });
            if (status !== 200) continue;

            const embed = (typeof data === "string" ? data : "").match(
                /https?:\/\/[a-z0-9.-]*xtremestream\.xyz\/player\/index\.php\?data=[a-f0-9]+/i
            );
            if (!embed) continue;

            const proxyHost = process.env.RENDER_EXTERNAL_URL || `http://127.0.0.1:${process.env.PORT || 7000}`;
            const streamUrl = await buildXsStreamUrl(embed[0], proxyHost);
            log(`osmanonline.live stream via xtremestream (${embed[0].match(/data=([a-f0-9]+)/)[1].slice(0, 10)}…)`);
            return { url: streamUrl, source: "osmanonline-live" };
        } catch (err) {
            log(`osmanonline.live error (${slug}): ${err.message}`);
        }
    }
    return null;
}

async function getStream(type, id) {
    log(`getStream called for type=${type}, id=${id}`);

    const resolved = resolveId(id);
    if (!resolved) {
        log("Invalid ID format");
        return { streams: [] };
    }
    const { siteSlug, abs } = resolved;
    log(`Resolved: series=${siteSlug}, absolute episode=${abs}`);

    const streams = [];

    // Source 1: OsmanOnline (new site -> vidara.to)
    try {
        const osmanStream = await getOsmanOnlineStream(siteSlug, abs);
        if (osmanStream) {
            streams.push({
                name: "OsmanOnline",
                title: `Source 1: OsmanOnline (E${abs})`,
                url: osmanStream.url
            });
            log("Added OsmanOnline stream");
        }
    } catch (err) {
        log(`OsmanOnline fetch error: ${err.message}`);
    }

    // Source 2: KayiFamily (kayihome.xyz / vidara / wakeupummah / okru / vk)
    try {
        const kayiStream = await getKayiFamilyStream(siteSlug, abs);
        if (kayiStream) {
            streams.push({
                name: "KayiFamily",
                title: `Source 2: KayiFamily (E${abs})`,
                url: kayiStream.url
            });
            log("Added KayiFamily stream");
        }
    } catch (err) {
        log(`KayiFamily fetch error: ${err.message}`);
    }

    // Source 3: KayiFamilyTV (wakeupummah FirePlayer mirrors)
    try {
        const kayiTVStream = await getKayiFamilyTVStream(siteSlug, abs);
        if (kayiTVStream) {
            streams.push({
                name: "KayiFamilyTV",
                title: `Source 3: KayiFamilyTV (E${abs})`,
                url: kayiTVStream.url
            });
            log("Added KayiFamilyTV stream");
        }
    } catch (err) {
        log(`KayiFamilyTV fetch error: ${err.message}`);
    }

    // Source 4: osmanonline.live (xtremestream, up to 1080p - plays through
    // the addon proxy because segments are AES-encrypted server-side)
    try {
        const liveStream = await getOsmanOnlineLiveStream(siteSlug, abs);
        if (liveStream) {
            streams.push({
                name: "OsmanOnlineLive",
                title: `Source 4: OsmanOnline.live 1080p (via addon proxy)`,
                url: liveStream.url
            });
            log("Added OsmanOnlineLive stream");
        }
    } catch (err) {
        log(`OsmanOnlineLive fetch error: ${err.message}`);
    }

    if (streams.length === 0) {
        log("No streams found from any source");
        return { streams: [] };
    }

    log(`Returning ${streams.length} stream(s)`);
    return { streams };
}

module.exports = { getStream };
