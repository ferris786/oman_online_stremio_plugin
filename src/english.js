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

async function enCatalog(catalog, skip) {
    const key = `${catalog}:${skip || 0}`;
    const cached = catalogCache.get(key);
    if (cached) return cached;

    let url = `${CATALOG_BASE}/${catalog}.json`;
    if (skip) url = `${CATALOG_BASE}/${catalog}/skip=${parseInt(skip) || 0}.json`;
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

// rough ranking: prefer 1080p BluRay/WEB-DL around 2-8GB, healthy seeders
function rankCandidate(c) {
    let score = Math.min(parseInt(c.seeders) || 0, 200); // seeders matter most
    if (c.sizeBytes > 1.5e9 && c.sizeBytes < 9e9) score += 150; // sane size
    if (c.sizeBytes > 30e9) score -= 200;                    // remux monsters
    if (c.quality.startsWith("1080p")) score += 120;
    if (c.quality.startsWith("2160p")) score -= 60;          // heavy for TVs
    if (c.quality.startsWith("720p")) score += 60;
    return score;
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
            const list = await fetchJson(q, 10000);
            if (!Array.isArray(list)) continue;
            for (const r of list) {
                if (!r || !r.info_hash || r.name === "No results returned") continue;
                const seeders = parseInt(r.seeders) || 0;
                const sizeBytes = parseInt(r.size) || 0;
                if (seeders < 3 || sizeBytes < 400e6 || sizeBytes > 40e9) continue;
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

    const candidates = [...byHash.values()].sort((a, b) => rankCandidate(b) - rankCandidate(a)).slice(0, 12);
    const value = { torrents: candidates, title: meta.name, year };
    torrentCache.set(imdbId, value);
    return value;
}

module.exports = { enCatalog, enSearch, enMeta, enTorrents };
