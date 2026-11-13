/**
 * English Movies module (Cinemeta metadata + TPB/apibay torrent candidates).
 *
 * Design: the addon serves only PUBLIC data (catalogs, search, meta, torrent
 * candidates). TorBox resolution happens IN THE APP with the user's own API
 * key (Settings screen) - keys never touch this server, and TorBox rate
 * limits are per-user, not per-server.
 *
 * Endpoints:
 *   GET /en/catalog/movie/:catalog/:skip?   -> Cinemeta catalog (cached 1h)
 *   GET /en/search/movie/:query.json        -> Cinemeta search (cached 1h)
 *   GET /en/meta/movie/:imdbId.json         -> Cinemeta meta (cached 24h)
 *   GET /en/torrents/movie/:imdbId.json     -> TPB candidates by title+year
 *                                               (cached 30 min)
 *
 * Cinemeta is Stremio's public metadata API: catalogs live on
 * cinemeta-catalogs.strem.io/{top}/catalog/movie/... while search/meta still
 * answer on v3-cinemeta.strem.io (verified Nov 2026).
 * TPB's apibay.org returns JSON with name/seeders/size/info_hash - no
 * scraping, no Cloudflare.
 */

const axios = require("axios");

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

const CATALOG_BASE = "https://cinemeta-catalogs.strem.io/top/catalog/movie";
const V3_BASE = "https://v3-cinemeta.strem.io";

// generic TTL cache
function makeCache(ttlMs) {
    const map = new Map();
    return {
        get(key) {
            const hit = map.get(key);
            if (hit && Date.now() - hit.ts < ttlMs) return hit.value;
            return undefined;
        },
        set(key, value) {
            if (map.size > 500) map.delete(map.keys().next().value);
            map.set(key, { value, ts: Date.now() });
        }
    };
}
const catalogCache = makeCache(60 * 60 * 1000);
const metaCache = makeCache(24 * 60 * 60 * 1000);
const torrentCache = makeCache(30 * 60 * 1000);

async function fetchJson(url, timeout = 15000) {
    const { data } = await axios.get(url, {
        headers: { "User-Agent": UA },
        timeout,
        validateStatus: (s) => s < 400
    });
    return data;
}

// ---------- Cinemeta ----------

async function enCatalog(catalog, skip, genre) {
    const key = `${catalog}:${skip || 0}:${genre || ""}`;
    const cached = catalogCache.get(key);
    if (cached) return cached;

    let url = `${CATALOG_BASE}/${catalog}.json`;
    if (genre) url = `${CATALOG_BASE}/${catalog}/genre=${encodeURIComponent(genre)}.json`;
    else if (skip) url = `${CATALOG_BASE}/${catalog}/skip=${parseInt(skip) || 0}.json`;
    const data = await fetchJson(url);
    const value = { metas: (data && data.metas) || [] };
    catalogCache.set(key, value);
    return value;
}

async function enSearch(query) {
    const key = `search:${query.toLowerCase()}`;
    const cached = catalogCache.get(key);
    if (cached) return cached;
    const data = await fetchJson(`${V3_BASE}/catalog/movie/search=${encodeURIComponent(query)}.json`);
    const value = { metas: (data && data.metas) || [] };
    catalogCache.set(key, value);
    return value;
}

async function enMeta(imdbId) {
    const cached = metaCache.get(imdbId);
    if (cached) return cached;
    const data = await fetchJson(`${V3_BASE}/meta/movie/${imdbId}.json`);
    if (!data || !data.meta) throw new Error("meta not found");
    metaCache.set(imdbId, data.meta);
    return data.meta;
}

// ---------- Torrent candidates (TPB / apibay) ----------

function qualityFromName(name) {
    const n = name.toLowerCase();
    const res = n.match(/(2160|1080|720|480)p/) ? n.match(/(2160|1080|720|480)p/)[1] + "p"
        : /4k|uhd/.test(n) ? "2160p" : "SD";
    let kind = "WEB";
    if (/remux/.test(n)) kind = "Remux";
    else if (/blu-?ray|brrip|bdrip/.test(n)) kind = "BluRay";
    else if (/web-?dl|webrip/.test(n)) kind = "WEB-DL";
    else if (/dvdrip/.test(n)) kind = "DVDRip";
    return `${res} ${kind}`;
}

// rough ranking: healthy seeders first, 1080p as the sweet-spot default;
// 4K is fully available but ranks below 1080p so autoplay picks sensibly
function rankCandidate(c) {
    let score = Math.min(parseInt(c.seeders) || 0, 200); // seeders matter most
    if (c.sizeBytes > 1.5e9 && c.sizeBytes < 9e9) score += 150; // sane size
    if (c.sizeBytes > 60e9) score -= 200;                    // true monsters
    if (c.quality.startsWith("1080p")) score += 120;
    if (c.quality.startsWith("720p")) score += 60;
    return score;
}

// YTS: exact by-IMDb movie lookup with every quality tier (720p/1080p/2160p)
// yts.lt redirects to yts.gg with an "API base is moving" notice naming
// movies-api.accel.li; try the new base first and fall back to the old one.
const YTS_BASES = ["https://movies-api.accel.li/api/v2", "https://yts.lt/api/v2"];

async function ytsTorrents(imdbId) {
    for (const base of YTS_BASES) {
        const list = await ytsFrom(base, imdbId);
        if (list.length) return list;
    }
    return [];
}

async function ytsFrom(base, imdbId) {
    try {
        const data = await fetchJson(`${base}/list_movies.json?query_term=${encodeURIComponent(imdbId)}`, 10000);
        const movie = data && data.data && data.data.movies && data.data.movies[0];
        if (!movie || !Array.isArray(movie.torrents)) return [];
        return movie.torrents.map(t => ({
            name: `${movie.title} ${t.quality} ${t.type || ""} YTS`.trim(),
            hash: String(t.hash || "").toLowerCase(),
            seeders: parseInt(t.seeds) || 0,
            sizeBytes: parseInt(t.size_bytes) || 0,
            quality: `${t.quality} ${t.type === "web" ? "WEB" : (t.type || "BluRay")}`,
            source: "YTS"
        })).filter(t => t.hash.length >= 20 && t.sizeBytes > 300e6 && t.sizeBytes < 70e9);
    } catch (e) {
        return []; // yts unreachable - fall back to other indexers
    }
}

async function enTorrents(imdbId) {
    const cached = torrentCache.get(imdbId);
    if (cached) return cached;

    const meta = await enMeta(imdbId);
    const title = meta.name.replace(/[^\w\s]/g, " ").replace(/\s+/g, " ").trim();
    const year = meta.year || "";

    // cat 207 = HD movies, 202 = movies (both queried)
    const queries = [
        `https://apibay.org/q.php?q=${encodeURIComponent(title + " " + year)}&cat=207`,
        `https://apibay.org/q.php?q=${encodeURIComponent(title)}&cat=202`
    ];

    const byHash = new Map();
    for (const q of queries) {
        try {
            const list = await fetchJson(q, 3000);
            if (!Array.isArray(list)) continue;
            for (const r of list) {
                if (!r || !r.info_hash || r.name === "No results returned") continue;
                const seeders = parseInt(r.seeders) || 0;
                const sizeBytes = parseInt(r.size) || 0;
                if (seeders < 3 || sizeBytes < 400e6 || sizeBytes > 70e9) continue;
                const lname = r.name.toLowerCase();
                if (/\b(cam|ts|screener|x?264.*HC|hdcam)\b/.test(lname)) continue;
                if (year && !lname.includes(String(year)) && title.length > 4) {
                    // allow yearless names for famous rips (yify etc.) but drop
                    // torrents that name a different year
                    const years = lname.match(/(19|20)\d{2}/g) || [];
                    if (years.length && !years.includes(String(year))) continue;
                }
                if (!byHash.has(r.info_hash)) {
                    byHash.set(r.info_hash, {
                        name: r.name,
                        hash: r.info_hash,
                        seeders,
                        sizeBytes,
                        quality: qualityFromName(r.name),
                        source: "TPB"
                    });
                }
            }
        } catch (e) { /* indexer down - continue */ }
    }

    // YTS first (exact IMDb match, all qualities incl 4K), TPB as supplement
    const yts = await ytsTorrents(imdbId);
    for (const t of yts) {
        if (!byHash.has(t.hash)) byHash.set(t.hash, t);
    }

    const candidates = [...byHash.values()].sort((a, b) => rankCandidate(b) - rankCandidate(a)).slice(0, 15);
    const value = { torrents: candidates, title: meta.name, year };
    torrentCache.set(imdbId, value);
    return value;
}

module.exports = { enCatalog, enSearch, enMeta, enTorrents };
