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

// Cinemeta movie catalogs, all carrying IMDb ids: top = Popular,
// year = New, imdbRating = Featured. Each accepts genre= and skip=.
const CINEMETA_CATALOGS = ["top", "year", "imdbRating"];

/**
 * Cinemeta's genre filter is loose: measured on 2026-11-13, 9 of the 50
 * films in genre=Comedy did not carry Comedy at all (A Dog's Perfect
 * Christmas was Drama only). Every meta already states its own genres, so
 * we drop the ones that do not actually match.
 */
function genreOf(meta) {
    return meta.genres || meta.genre || [];
}

async function enCatalog(catalog, skip, genre, type = "movie") {
    const key = `${type}:${catalog}:${skip || 0}:${genre || ""}`;
    const cached = catalogCache.get(key);
    if (cached) return cached;

    const base = `https://cinemeta-catalogs.strem.io/${catalog}/catalog/${type}/${catalog}`;
    let url = `${base}.json`;
    if (genre) url = `${base}/genre=${encodeURIComponent(genre)}.json`;
    else if (skip) url = `${base}/skip=${parseInt(skip) || 0}.json`;
    const data = await fetchJson(url);
    // Cinemeta's catalogs are clean today, but a mislabelled entry would
    // otherwise sit in the wrong row with no playable source, so drop
    // anything that does not declare the type we asked for.
    let metas = ((data && data.metas) || []).filter(m => !m.type || m.type === type);
    if (genre) {
        const want = genre.toLowerCase();
        const exact = metas.filter(m => genreOf(m).some(g => String(g).toLowerCase() === want));
        // only trust the filter when it leaves a usable row
        if (exact.length >= 8) metas = exact;
    }
    const value = { metas };
    catalogCache.set(key, value);
    return value;
}

/**
 * "Just added": releases that became watchable in the last day or two.
 * YTS is the only index that exposes an upload timestamp AND an IMDb id,
 * so a row built from it can always be played, unlike a cinema-listings
 * row where half the titles have no source yet. Junk is filtered on rating
 * and year; posters and summaries come straight from YTS.
 */
async function enLatestAdded() {
    const cached = catalogCache.get("latest-added");
    if (cached) return cached;
    const metas = [];
    for (const base of YTS_BASES) {
        try {
            const data = await fetchJson(
                `${base}/list_movies.json?sort_by=date_added&order_by=desc&limit=50`, 12000);
            const movies = (data && data.data && data.data.movies) || [];
            const thisYear = new Date().getFullYear();
            for (const m of movies) {
                if (!m.imdb_code || !/^tt\d+$/.test(m.imdb_code)) continue;
                if ((m.rating || 0) < 5.0) continue;
                if ((m.year || 0) < thisYear - 1) continue;
                if (metas.some(x => x.id === m.imdb_code)) continue;
                metas.push({
                    id: m.imdb_code,
                    type: "movie",
                    name: m.title_english || m.title,
                    poster: m.large_cover_image || m.medium_cover_image || null,
                    background: m.background_image_original || m.background_image || null,
                    description: m.summary || m.synopsis || null,
                    imdbRating: m.rating ? String(m.rating) : null,
                    runtime: m.runtime ? `${m.runtime} min` : null,
                    genres: m.genres || [],
                    year: m.year ? String(m.year) : null,
                    releaseInfo: m.year ? String(m.year) : null,
                    addedAt: m.date_uploaded || null
                });
            }
            if (metas.length) break;
        } catch (e) { /* try the next base */ }
    }
    const value = { metas };
    catalogCache.set("latest-added", value);
    return value;
}

async function enSearch(query, type = "movie") {
    const key = `search:${type}:${query.toLowerCase()}`;
    const cached = catalogCache.get(key);
    if (cached) return cached;
    const data = await fetchJson(`${V3_BASE}/catalog/${type}/search=${encodeURIComponent(query)}.json`);
    const value = { metas: (data && data.metas) || [] };
    catalogCache.set(key, value);
    return value;
}

async function enMeta(imdbId, type = "movie") {
    const key = `${type}:${imdbId}`;
    const cached = metaCache.get(key);
    if (cached) return cached;
    const data = await fetchJson(`${V3_BASE}/meta/${type}/${imdbId}.json`);
    if (!data || !data.meta) throw new Error("meta not found");
    metaCache.set(key, data.meta);
    return data.meta;
}

/**
 * English series meta, trimmed for the TV: the series fields the app's
 * series page needs plus every episode with its Stremio id (tt:s:e), which
 * is also what Torrentio and OpenSubtitles key on.
 */
async function enSeriesMeta(imdbId) {
    const m = await enMeta(imdbId, "series");
    const videos = (m.videos || [])
        .filter(v => v.id && v.season > 0)   // season 0 = specials; keep the page simple
        .map(v => ({
            id: v.id,
            season: v.season,
            episode: v.episode || v.number,
            title: v.name || v.title || `Episode ${v.episode || v.number}`,
            overview: v.overview || v.description || null,
            thumbnail: v.thumbnail || null,
            released: v.released || v.firstAired || null,
            rating: v.rating || null
        }));
    return {
        id: m.id || imdbId,
        name: m.name,
        poster: m.poster || null,
        background: m.background || m.poster || null,
        logo: m.logo || null,
        description: m.description || null,
        genres: m.genres || m.genre || [],
        year: m.year || m.releaseInfo || null,
        imdbRating: m.imdbRating || null,
        runtime: m.runtime || null,
        status: m.status || null,
        trailer: (m.trailerStreams && m.trailerStreams[0] && m.trailerStreams[0].ytId) ||
            (m.trailers && m.trailers[0] && m.trailers[0].source) || null,
        videos
    };
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

// ---------- Subtitles (Stremio's public OpenSubtitles addon) ----------

/**
 * Subtitles for a movie, from opensubtitles-v3.strem.io - no API key, and
 * the files are already converted to UTF-8 SRT, which is what ExoPlayer
 * wants. Results are deduplicated per language and capped so a TV remote
 * never has to scroll a list of ninety.
 *
 * Turkish series are hard-subbed (the English text is burned into the
 * video), so this is only wired up for English movies.
 */
const SUB_LANG_NAMES = {
    eng: "English", ara: "Arabic", tur: "Turkish", fre: "French", ger: "German",
    spa: "Spanish", por: "Portuguese", ita: "Italian", rus: "Russian", dut: "Dutch",
    hin: "Hindi", urd: "Urdu", ind: "Indonesian", may: "Malay", per: "Persian",
    chi: "Chinese", zho: "Chinese", jpn: "Japanese", kor: "Korean", heb: "Hebrew"
};
const SUB_PREFERRED = ["eng", "ara", "tur", "urd", "hin"];
const SUB_PER_LANG = 3;

const subtitleCache = makeCache(6 * 60 * 60 * 1000);

async function enSubtitles(id, type = "movie") {
    const key = `${type}:${id}`;
    const cached = subtitleCache.get(key);
    if (cached) return cached;
    let subtitles = [];
    try {
        const data = await fetchJson(
            `https://opensubtitles-v3.strem.io/subtitles/${type}/${encodeURIComponent(id)}.json`, 12000);
        const seenUrl = new Set();
        const perLang = {};
        for (const s of (data && data.subtitles) || []) {
            if (!s.url || !s.lang || seenUrl.has(s.url)) continue;
            seenUrl.add(s.url);
            perLang[s.lang] = perLang[s.lang] || [];
            if (perLang[s.lang].length >= SUB_PER_LANG) continue;
            perLang[s.lang].push({
                id: String(s.id || s.url),
                lang: s.lang,
                label: SUB_LANG_NAMES[s.lang] || s.lang.toUpperCase(),
                url: s.url
            });
        }
        // preferred languages first, then everything else alphabetically
        const langs = Object.keys(perLang).sort((a, b) => {
            const ia = SUB_PREFERRED.indexOf(a), ib = SUB_PREFERRED.indexOf(b);
            if (ia !== -1 || ib !== -1) return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
            return a.localeCompare(b);
        });
        for (const l of langs) subtitles.push(...perLang[l]);
    } catch (e) {
        console.warn(`[subs] ${key}: ${e.message}`);
    }
    const value = { subtitles };
    subtitleCache.set(key, value);
    return value;
}

module.exports = { enCatalog, enLatestAdded, enSearch, enMeta, enSeriesMeta, enTorrents, enSubtitles, CINEMETA_CATALOGS };
