/**
 * FirePlayer-family extractors (kayihome.xyz, wakeupummah.com) + vidara.to
 *
 * Verified working as of Nov 2026:
 *
 * kayihome.xyz:
 *   - Player page /player/index.php?data=HASH embeds videoUrl (/cdn/hls/HASH/master.txt)
 *     and videoServer (index into hostList).
 *   - The .xyz hostList hosts NO LONGER serve /cdn/hls/* (404). The master playlist is
 *     served by kayihome.xyz itself at:
 *       https://kayihome.xyz{videoUrl}?s={videoServer}&d=
 *     and REQUIRES the player page URL as Referer.
 *   - Master returns one absolute URL https://kayihome.xyz/m3/{token} (media playlist).
 *     The /m3/ request also requires the player page Referer, so it must be re-proxied
 *     with the same referer.
 *   - Segments are absolute https://{host}.xyz/cdn/down/.../OriginalN.jpg files
 *     (MPEG-TS disguised as JPEG) and need NO referer - they can be piped or fetched
 *     directly by the client.
 *
 * wakeupummah.com (used by kayifamilytv.com):
 *   - Embeds at /fireplayer/video/{HASH}. Video data via:
 *       POST https://wakeupummah.com/fireplayer/video/{HASH}?do=getVideo
 *       body: hash={HASH}&referrer={embedding page}
 *     Returns { videoSources: [{file, label, type}] }. Usually video.twimg.com HLS
 *     which plays WITHOUT referer, so the URL can be handed to Stremio directly.
 *
 * vidara.to (used by osmanonline.co.uk):
 *   - Embeds at /e/{filecode}. API changed from GET to POST JSON:
 *       POST https://vidara.to/api/stream
 *       body: {"filecode":"...","device":"desktop","codecs":{}}
 *     Returns { streaming_url } with an IP-locked token, so the URL MUST be proxied
 *     through this addon (the addon's IP is baked into the token).
 */

const axios = require("axios");
const publicbase = require("../publicbase");

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

function log(msg) {
    console.log(`[FirePlayer] ${msg}`);
}

function proxyHost() {
    return publicbase.get();
}

// Build a proxy URL that keeps the referer (and optional cookie) for nested fetches.
// Playlists are served from a path ending in .m3u8 (/proxy/master.m3u8) because
// ExoPlayer on Android TV only engages HLS mode when the URL PATH looks like a
// playlist - a ".m3u8" buried in the query string is not enough.
function buildProxyUrl(targetUrl, { referer = "", cookie = "", isPlaylist = false } = {}) {
    const path = isPlaylist ? "/proxy/master.m3u8" : "/proxy";
    let p = `${proxyHost()}${path}?url=${encodeURIComponent(targetUrl)}`;
    if (referer) p += `&referer=${encodeURIComponent(referer)}`;
    if (cookie) p += `&cookie=${encodeURIComponent(cookie)}`;
    return p;
}

/**
 * kayihome.xyz iframe -> playable (proxied) master playlist URL
 * iframeUrl: https://kayihome.xyz/player/index.php?data=HASH
 */
async function extractKayihome(iframeUrl) {
    try {
        const dataMatch = iframeUrl.match(/data=([a-f0-9]+)/);
        if (!dataMatch) {
            log(`No data param in ${iframeUrl}`);
            return null;
        }

        const playerUrl = `https://kayihome.xyz/player/index.php?data=${dataMatch[1]}`;
        const { data: html, status } = await axios.get(playerUrl, {
            headers: { "User-Agent": UA, "Referer": "https://kayifamily.com/" },
            timeout: 10000,
            validateStatus: () => true
        });

        if (status !== 200) {
            log(`kayihome player returned ${status}`);
            return null;
        }

        const titleMatch = html.match(/<title>([^<]+)/i);
        const pageTitle = titleMatch ? titleMatch[1].toLowerCase() : "";
        if (pageTitle.includes("404") || pageTitle.includes("not found") ||
            html.includes("<h1>No Video Found</h1>")) {
            log("kayihome player has no video");
            return null;
        }

        const videoUrlMatch = html.match(/"videoUrl"\s*:\s*"([^"]+)"/);
        if (!videoUrlMatch) {
            log("kayihome: no videoUrl in player page");
            return null;
        }
        const videoUrl = videoUrlMatch[1].replace(/\\\//g, "/");

        const serverMatch = html.match(/"videoServer"\s*:\s*"(\d+)"/) ||
                            html.match(/"videoServer"\s*:\s*(\d+)/);
        const videoServer = serverMatch ? serverMatch[1] : "1";

        // Master playlist is served by kayihome.xyz itself with s/d params (NOT the hostList hosts)
        const masterUrl = `https://kayihome.xyz${videoUrl}?s=${videoServer}&d=`;

        return {
            url: buildProxyUrl(masterUrl, { referer: playerUrl, isPlaylist: true }),
            type: "hls",
            source: "kayihome"
        };
    } catch (error) {
        log(`kayihome error: ${error.message}`);
        return null;
    }
}

/**
 * wakeupummah.com FirePlayer embed -> direct m3u8
 * embedUrl: https://wakeupummah.com/fireplayer/video/{HASH}
 * referrerSite: the page embedding it (e.g. https://kayifamilytv.com/)
 */
async function extractWakeupummah(embedUrl, referrerSite) {
    try {
        const hashMatch = embedUrl.match(/\/fireplayer\/video\/([a-f0-9]+)/);
        if (!hashMatch) {
            log(`Cannot parse wakeupummah hash from ${embedUrl}`);
            return null;
        }
        const hash = hashMatch[1];

        const res = await axios.post(
            `https://wakeupummah.com/fireplayer/video/${hash}?do=getVideo`,
            new URLSearchParams({ hash, referrer: referrerSite || "" }).toString(),
            {
                headers: {
                    "User-Agent": UA,
                    "X-Requested-With": "XMLHttpRequest",
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Referer": embedUrl,
                    "Origin": "https://wakeupummah.com"
                },
                timeout: 10000,
                validateStatus: () => true
            }
        );

        if (res.status !== 200 || !res.data || !res.data.videoSources || !res.data.videoSources.length) {
            log(`wakeupummah getVideo failed (${res.status})`);
            return null;
        }

        // Only HLS sources are playable through this pipeline. Some mirrors
        // hand out a plain MP4 (e.g. an archive.org download): proxying that
        // as a "playlist" made stream validation pull the whole multi-GB
        // file, which hung /stream for minutes. Prefer an HLS entry; if the
        // mirror has none, let the caller try the next mirror.
        const isHls = (u) => /\.m3u8|\.txt|\/hls\//i.test(u);
        const src = res.data.videoSources.find(v => v.file && v.file.startsWith("http") && isHls(v.file));
        if (!src || !src.file) {
            log(`wakeupummah: no HLS videoSource (${res.data.videoSources.map(v => (v.file || "").slice(0, 40)).join(", ")})`);
            return null;
        }

        const url = src.file.replace(/\\\//g, "/");
        // video.twimg.com HLS plays without referer - return direct to save addon bandwidth
        const needsProxy = !url.includes("video.twimg.com");
        return {
            url: needsProxy ? buildProxyUrl(url, { referer: embedUrl, isPlaylist: true }) : url,
            type: "hls",
            source: "wakeupummah"
        };
    } catch (error) {
        log(`wakeupummah error: ${error.message}`);
        return null;
    }
}

/**
 * vidara.to embed -> (proxied) m3u8
 * filecode from https://vidara.to/e/{filecode}
 * referer: the page embedding vidara (for the API call)
 */
async function extractVidara(filecode, referer) {
    try {
        const res = await axios.post(
            "https://vidara.to/api/stream",
            { filecode, device: "desktop", codecs: {} },
            {
                headers: {
                    "User-Agent": UA,
                    "Content-Type": "application/json",
                    "Referer": referer || "https://vidara.to/",
                    "Origin": "https://vidara.to"
                },
                timeout: 10000,
                validateStatus: () => true
            }
        );

        if (res.status !== 200 || !res.data || !res.data.streaming_url) {
            log(`vidara API failed: ${res.status} ${JSON.stringify(res.data || {}).slice(0, 120)}`);
            return null;
        }

        // streaming_url token is IP-locked to the caller (this addon), so it MUST be proxied
        return {
            url: buildProxyUrl(res.data.streaming_url, { referer: "https://vidara.to/", isPlaylist: true }),
            type: "hls",
            source: "vidara"
        };
    } catch (error) {
        log(`vidara error: ${error.message}`);
        return null;
    }
}

module.exports = { extractKayihome, extractWakeupummah, extractVidara, buildProxyUrl };
