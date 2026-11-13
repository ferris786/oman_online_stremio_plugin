/**
 * TMDB enrichment for the Turkish catalog: backdrops, logos, genres, air
 * dates and per-episode stills / overviews / runtimes.
 *
 * The Turkish sites number episodes ABSOLUTELY (Kurulus Osman E170), while
 * TMDB numbers them per season. TMDB's season episode counts for these shows
 * match the sites' season boundaries (verified: Kurulus Osman 27/37/34/32/34
 * = the offsets in kayifamily.js), so absolute N maps to the N-th episode in
 * TMDB season order.
 *
 * Everything is cached in memory and mirrored to data/tmdb-cache.json so a
 * restart does not refetch 24 shows. Any failure degrades to "no enrichment";
 * the catalog and meta responses never depend on TMDB being up.
 *
 * Key: TMDB_API_KEY env (falls back to the project's long-standing key).
 */

const axios = require("axios");
const fs = require("fs");
const path = require("path");

const KEY = process.env.TMDB_API_KEY || "5cc53e03562e40db9fe6d58e70486bcf";
const API = "https://api.themoviedb.org/3";
const IMG = "https://image.tmdb.org/t/p/";

const CACHE_FILE = path.join(__dirname, "../data/tmdb-cache.json");
const SHOW_TTL = 12 * 60 * 60 * 1000;      // show details (last_air_date moves weekly)
const SEASON_TTL = 12 * 60 * 60 * 1000;    // latest season gains episodes
const OLD_SEASON_TTL = 14 * 24 * 60 * 60 * 1000;
const SEARCH_TTL = 30 * 24 * 60 * 60 * 1000;

// Site slug -> TMDB tv id. Search handles most shows; these pin the ones
// where the first search hit is wrong or the title transliteration differs.
const TMDB_IDS = {
    "kurulus-osman": 95603,
    "kurulus-orhan": 302658,
    "dirilis-ertugrul": 66017,
    "mehmed-fetihler-sultani": 246621,
    "kudus-fatihi-selahaddin-eyyubi": 232132,
    "mevlana-rumi": 225970,
    "alparslan-buyuk-selcuklu": 138171,
    "uyanis-buyuk-selcuklu": 110655,
    "payitaht-abdulhamid": 70788,
    "destan": 136506,
    "barbaroslar": 132752,
    "barbaros-hayreddin-sultanin-fermani": 216396,
    "bozkir-arslani-celaleddin": 118918,
    "al-sancak": 218477,
    "haci-bayram-i-veli": 157438,
    "aziz-mahmud-hudayi-askin-yolculugu": 248842,
    "gilani-the-ascetic": 226032,
    "imam-gazali": 314697,
    "isimsizler": 76528,
    "young-ibn-sina": 225964,
    "mahsusa": 226084,
    "mavera-hace-ahmed-yesevi": 123187,
    "mehmetcik-kutul-amare": 76661,
    "vefa-sultan": 285594
};

let cache = { search: {}, shows: {}, seasons: {} };
let saveTimer = null;

function loadCache() {
    try {
        if (fs.existsSync(CACHE_FILE)) {
            const parsed = JSON.parse(fs.readFileSync(CACHE_FILE, "utf-8"));
            cache = { search: {}, shows: {}, seasons: {}, ...parsed };
        }
    } catch (e) {
        console.warn("[TMDB] cache load failed:", e.message);
    }
}

function scheduleSave() {
    if (saveTimer) return;
    saveTimer = setTimeout(() => {
        saveTimer = null;
        try {
            fs.mkdirSync(path.dirname(CACHE_FILE), { recursive: true });
            fs.writeFileSync(CACHE_FILE, JSON.stringify(cache));
        } catch (e) {
            // read-only filesystem: memory cache still works
        }
    }, 2000).unref();
}

loadCache();

function fresh(entry, ttl) {
    return entry && (Date.now() - entry.ts) < ttl;
}

async function get(pathname, params = {}) {
    const { data } = await axios.get(`${API}${pathname}`, {
        params: { api_key: KEY, ...params },
        timeout: 12000
    });
    return data;
}

function img(p, size) {
    return p ? `${IMG}${size}${p}` : null;
}

function norm(s) {
    return (s || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9]/g, "");
}

/**
 * Resolve a series to a TMDB id: pinned map first, then a cached search over
 * the candidate names (curated title, site title, slug words), preferring
 * Turkish productions.
 */
async function resolveId(slug, names) {
    if (TMDB_IDS[slug]) return TMDB_IDS[slug];
    const key = slug;
    if (fresh(cache.search[key], SEARCH_TTL)) return cache.search[key].id;

    const candidates = [...new Set((names || []).filter(Boolean))];
    for (const q of candidates) {
        try {
            const data = await get("/search/tv", { query: q, include_adult: false });
            const results = (data.results || []);
            if (!results.length) continue;
            const turkish = results.filter(r => (r.origin_country || []).includes("TR"));
            const wanted = norm(q);
            const exact = (turkish.length ? turkish : results).find(r =>
                norm(r.name) === wanted || norm(r.original_name) === wanted);
            const pick = exact || turkish[0] || results[0];
            cache.search[key] = { id: pick.id, ts: Date.now() };
            scheduleSave();
            return pick.id;
        } catch (e) {
            // try the next candidate name
        }
    }
    cache.search[key] = { id: null, ts: Date.now() };
    scheduleSave();
    return null;
}

async function show(id) {
    if (!id) return null;
    const hit = cache.shows[id];
    if (fresh(hit, SHOW_TTL)) return hit.data;
    try {
        const d = await get(`/tv/${id}`, {
            append_to_response: "images",
            include_image_language: "en,null"
        });
        const logos = (d.images && d.images.logos) || [];
        const logo = logos.find(l => l.iso_639_1 === "en") || logos[0];
        const seasons = (d.seasons || [])
            .filter(s => s.season_number > 0 && s.episode_count > 0)
            .sort((a, b) => a.season_number - b.season_number)
            .map(s => ({ n: s.season_number, count: s.episode_count }));
        const data = {
            id: d.id,
            name: d.name,
            overview: d.overview || null,
            backdrop: img(d.backdrop_path, "w1280"),
            poster: img(d.poster_path, "w500"),
            logo: logo ? img(logo.file_path, "w500") : null,
            genres: (d.genres || []).map(g => g.name).filter(g => g !== "War & Politics").concat(
                (d.genres || []).some(g => g.name === "War & Politics") ? ["History"] : []),
            firstAir: d.first_air_date || null,
            lastAir: d.last_air_date || null,
            nextAir: d.next_episode_to_air ? d.next_episode_to_air.air_date : null,
            runtime: Array.isArray(d.episode_run_time) && d.episode_run_time.length ? d.episode_run_time[0] : null,
            status: d.status || null,
            seasons,
            episodeCount: seasons.reduce((a, s) => a + s.count, 0)
        };
        cache.shows[id] = { data, ts: Date.now() };
        scheduleSave();
        return data;
    } catch (e) {
        console.warn(`[TMDB] show ${id} failed: ${e.message}`);
        return hit ? hit.data : null;
    }
}

async function season(id, n, isLatest) {
    const key = `${id}:${n}`;
    const hit = cache.seasons[key];
    if (fresh(hit, isLatest ? SEASON_TTL : OLD_SEASON_TTL)) return hit.data;
    try {
        const d = await get(`/tv/${id}/season/${n}`);
        const data = (d.episodes || []).map(e => ({
            episode: e.episode_number,
            name: e.name || null,
            overview: e.overview || null,
            still: img(e.still_path, "w500"),
            runtime: e.runtime || null,
            aired: e.air_date || null
        }));
        cache.seasons[key] = { data, ts: Date.now() };
        scheduleSave();
        return data;
    } catch (e) {
        console.warn(`[TMDB] season ${key} failed: ${e.message}`);
        return hit ? hit.data : [];
    }
}

/** absolute episode number -> { season, episode } by cumulative TMDB counts */
function absToSeasonEp(showData, abs) {
    let cursor = 0;
    for (const s of showData.seasons) {
        if (abs <= cursor + s.count) return { season: s.n, episode: abs - cursor };
        cursor += s.count;
    }
    return null;
}

/**
 * Per-episode details for a list of absolute episode numbers. Fetches only
 * the seasons actually touched; returns a Map abs -> episode info.
 */
async function episodes(showData, absList) {
    const out = new Map();
    if (!showData || !showData.seasons.length) return out;
    const lastSeason = showData.seasons[showData.seasons.length - 1].n;
    const needed = new Map(); // season -> [{abs, episode}]
    for (const abs of absList) {
        const se = absToSeasonEp(showData, abs);
        if (!se) continue;
        if (!needed.has(se.season)) needed.set(se.season, []);
        needed.get(se.season).push({ abs, episode: se.episode });
    }
    await Promise.all([...needed.entries()].map(async ([n, items]) => {
        const eps = await season(showData.id, n, n === lastSeason);
        for (const it of items) {
            const e = eps.find(x => x.episode === it.episode);
            if (e) out.set(it.abs, { ...e, season: n });
        }
    }));
    return out;
}

/**
 * Enrich catalog entries in place (bounded concurrency). Never throws.
 * Each entry: { slug, name, tmdbNames?: [] } gains tmdb fields when found.
 */
async function enrichAll(seriesList, concurrency = 4) {
    const queue = [...seriesList];
    const worker = async () => {
        while (queue.length) {
            const s = queue.shift();
            try {
                const id = await resolveId(s.slug, [...(s.tmdbNames || []), s.name]);
                const d = await show(id);
                if (!d) continue;
                s.tmdbId = d.id;
                s.background = d.backdrop;
                s.logo = d.logo;
                s.genres = d.genres;
                s.year = d.firstAir ? d.firstAir.slice(0, 4) : null;
                s.lastAired = d.lastAir;
                s.nextAir = d.nextAir;
                s.runtime = d.runtime;
                s.status = d.status;
                s.seasonCount = d.seasons.length;
                s.episodeCount = d.episodeCount;
                if (!s.poster) s.poster = d.poster;
                if (!s.description || s.description.startsWith("Watch ")) s.description = d.overview || s.description;
            } catch (e) {
                // leave the entry un-enriched
            }
        }
    };
    await Promise.all(Array.from({ length: concurrency }, worker));
    return seriesList;
}

// ---------------------------------------------------------------------------
// Movie search, actor search (English side).
//
// TMDB search is far better than Cinemeta's: ~0.6s, popularity-ranked, so
// "spider" puts the Spider-Man films first. It has one gap - results carry
// no IMDb id, and the torrent indexers need one - so suggestions are served
// from TMDB and the IMDb id is resolved only when something is picked.
// ---------------------------------------------------------------------------

const SUGGEST_TTL = 6 * 60 * 60 * 1000;
const PERSON_TTL = 24 * 60 * 60 * 1000;
const EXT_TTL = 30 * 24 * 60 * 60 * 1000;
const suggestCache = new Map();
const personCache = new Map();

function memo(map, key, ttl, build) {
    const hit = map.get(key);
    if (hit && Date.now() - hit.ts < ttl) return Promise.resolve(hit.value);
    return build().then(value => {
        if (map.size > 400) map.delete(map.keys().next().value);
        map.set(key, { value, ts: Date.now() });
        return value;
    });
}

function movieSuggestion(m) {
    return {
        tmdbId: m.id,
        name: m.title || m.original_title,
        year: (m.release_date || "").slice(0, 4) || null,
        poster: img(m.poster_path, "w342"),
        background: img(m.backdrop_path, "w780"),
        description: m.overview || null,
        rating: m.vote_average ? m.vote_average.toFixed(1) : null,
        popularity: m.popularity || 0
    };
}

/** type-ahead movie suggestions, popularity-ranked, no IMDb id yet */
function suggestMovies(query) {
    const q = query.trim().toLowerCase();
    if (q.length < 2) return Promise.resolve([]);
    return memo(suggestCache, q, SUGGEST_TTL, async () => {
        const data = await get("/search/movie", { query: q, include_adult: false });
        return (data.results || [])
            .filter(m => m.poster_path)
            .sort((a, b) => (b.popularity || 0) - (a.popularity || 0))
            .slice(0, 20)
            .map(movieSuggestion);
    });
}

/** tmdb movie id -> imdb id (cached hard; it never changes) */
function imdbIdOf(tmdbId) {
    return memo(suggestCache, `ext:${tmdbId}`, EXT_TTL, async () => {
        const d = await get(`/movie/${tmdbId}/external_ids`);
        return d && d.imdb_id ? d.imdb_id : null;
    });
}

/** actor / director search: who they are and what they are known for */
function searchPeople(query) {
    const q = query.trim().toLowerCase();
    if (q.length < 2) return Promise.resolve([]);
    return memo(personCache, `p:${q}`, PERSON_TTL, async () => {
        const data = await get("/search/person", { query: q, include_adult: false });
        return (data.results || [])
            .filter(p => p.profile_path)
            .slice(0, 10)
            .map(p => ({
                personId: p.id,
                name: p.name,
                role: p.known_for_department || null,
                photo: img(p.profile_path, "w342"),
                knownFor: (p.known_for || []).map(k => k.title || k.name).filter(Boolean).slice(0, 3)
            }));
    });
}

/** an actor's films, best known first */
function personMovies(personId) {
    return memo(personCache, `pm:${personId}`, PERSON_TTL, async () => {
        const d = await get(`/person/${personId}/movie_credits`);
        const seen = new Set();
        return (d.cast || [])
            .filter(m => m.poster_path && !seen.has(m.id) && seen.add(m.id))
            .sort((a, b) => (b.popularity || 0) - (a.popularity || 0))
            .slice(0, 40)
            .map(movieSuggestion);
    });
}

module.exports = {
    resolveId, show, episodes, enrichAll, absToSeasonEp, TMDB_IDS,
    suggestMovies, imdbIdOf, searchPeople, personMovies
};
