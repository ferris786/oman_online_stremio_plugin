const axios = require("axios");
const cheerio = require("cheerio");

// osmanonline.co.uk was redesigned (Nov 2026). The old osmanonline.info domain is dead
// behind a Cloudflare 526, so the catalog now scrapes the new Next.js site:
//   series index: https://osmanonline.co.uk/series/  -> /series/{slug}/
//   episodes:     /series/{slug}/episode-{abs}/
// Season grouping comes from the JSON-LD TVSeries containsSeason block on each series page.
const BASE_URL = "https://osmanonline.co.uk";

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

// Cache for catalog results (6h TTL - the site is slow and this keeps Render snappy)
let seriesCache = null;
let seriesCacheTime = 0;
const CACHE_TTL = 6 * 60 * 60 * 1000;

// Legacy (old osmanonline.info) series id slug -> new co.uk series slug
const LEGACY_SLUG_MAP = {
    "watch-kurulus-osman-with-english-subtitles": "kurulus-osman",
    "watch-kurulus-orhan-with-english-subtitles": "kurulus-orhan",
    "watch-mehmed-fetihler-sultani-with-english-subtitles": "mehmed-fetihler-sultani",
    "watch-salahuddin-ayyubi-with-english-subtitles": "kudus-fatihi-selahaddin-eyyubi",
    "watch-kudus-fatihi-selahaddin-eyyubi-with-english-subtitles": "kudus-fatihi-selahaddin-eyyubi",
    "watch-mevlana-rumi-with-english-subtitles": "mevlana-rumi",
    "watch-rumi-with-english-subtitles": "mevlana-rumi",
    "watch-alparslan-buyuk-selcuklu-with-english-subtitles": "alparslan-buyuk-selcuklu",
    "watch-payitaht-abdulhamid-with-english-subtitles": "payitaht-abdulhamid",
    "watch-destan-with-english-subtitles": "destan",
    "watch-dirilis-ertugrul-with-english-subtitles": "dirilis-ertugrul",
    "watch-barbaroslar-akdenizin-kilici-with-english-subtitles": "barbaroslar",
    "watch-uyanis-buyuk-selcuklu-with-english-subtitles": "uyanis-buyuk-selcuklu"
};

// Poster metadata aliases where slug -> TMDB name matching is not a clean contains-match
const SLUG_TO_TMDB_KEY = {
    "bozkir-arslani-celaleddin": "Bozkir Aslani Celaleddin",
    "mavera-hace-ahmed-yesevi": "Mavera",
    "mevlana-rumi": "Rumi",
    "kudus-fatihi-selahaddin-eyyubi": "Kudus Fatihi Selahaddin Eyyubi"
};

// Comprehensive TMDB metadata for all series (posters + overviews)
const TMDB_METADATA = {
    "Kurulus Osman": {
        poster: "https://image.tmdb.org/t/p/w500/tu4BWsGFHcYDWulZwHxylA91vo0.jpg",
        overview: "The series will focus on the life of Osman Bey, the son of Ertugrul Gazi and the founder of the Ottoman Empire."
    },
    "Kudus Fatihi Selahaddin Eyyubi": {
        poster: "https://image.tmdb.org/t/p/w500/5bljg22nvfS0eP320L5GFYJz3Zb.jpg",
        overview: "The story of Saladin, the Kurdish warrior who became the Sultan of Egypt and Syria and led the Muslim military campaign against the Crusader states."
    },
    "Mehmed Fetihler Sultani": {
        poster: "https://image.tmdb.org/t/p/w500/A8fHgHmcEQU1UcOcXhW3NXtwwcZ.jpg",
        overview: "The life of Mehmed the Conqueror, the Ottoman Sultan who conquered Constantinople at the age of 21."
    },
    "Kurulus Orhan": {
        poster: "https://i.ibb.co/Gv4Ckgz2/kurulus-easter-egg.jpg",
        overview: "The story of Orhan Ghazi, the son of Osman I and the second Sultan of the Ottoman Empire."
    },
    "Destan": {
        poster: "https://image.tmdb.org/t/p/w500/cSDEb3XvsML6VwYZ5HEJy7vQUS.jpg",
        overview: "The epic love between Akkiz, the legendary warrior mountain girl orphaned by Gök Khan Korkut Khan in the harsh steppes of Central Asia, and Gök Tegini Batuga, who was orphaned by Korkut Khan in the Gök Palace during Gokturk Khaganate."
    },
    "Dirilis Ertugrul": {
        poster: "https://image.tmdb.org/t/p/w500/rOar34cNLn2sgDH5FmAa1bvMpBv.jpg",
        overview: "Ertuğrul Bey and the Knights Templar in the 13th century Alba and step and step with the struggle against brutal Mongols depicts the process of establishing the Ottoman principality."
    },
    "Uyanis Buyuk Selcuklu": {
        poster: "https://image.tmdb.org/t/p/w500/8B1nL3gthGN55BHTMzZOlzBYNkU.jpg",
        overview: "The war of the Great Seljuk Emperor, Meliksah and her loyalist, Sencer against Hasan Sabbah, who is sworn to destroy the Seljuks."
    },
    "Alparslan Buyuk Selcuklu": {
        poster: "https://image.tmdb.org/t/p/w500/4wKqK8T1wTdhXfhnZzz2TuJE2Zh.jpg",
        overview: "It deals with the state structure, political events, wars of the Great Seljuk Empire and the life of Sultan Alparslan."
    },
    "Payitaht Abdulhamid": {
        poster: "https://image.tmdb.org/t/p/w500/fmaWiokhUVDsVkCfoWaQEDFZFFP.jpg",
        overview: "The fight of Abdülhamid II to keep the Ottoman Empire and Caliphate alive."
    },
    "Barbaroslar Akdenizin Kilici": {
        poster: "https://image.tmdb.org/t/p/w500/1VqjAWF5rW431wY4eEoV9oIyx0L.jpg",
        overview: "Barbaroslar: Sword of the Mediterranean retells the adventures of four brothers; Ishak, Oruc, Hizir, and Ilyas, who become seafarers and fight high tides and the secrets of the seas."
    },
    "Barbaros Hayreddin Sultanin Fermani": {
        poster: "https://image.tmdb.org/t/p/w500/8fWkyBfGhDJdDNhZ5J4z0r1IzIt.jpg",
        overview: "TRT's historical drama, based on the life of 'Barbaros' Hayreddin Pasha and his brothers. The series tells the adventures of Ishak, Oruc, Hizir, and Ilyas fighting high tides and the secrets of the seas in pursuit of the holy secret."
    },
    "Haci Bayram I Veli": {
        poster: "https://image.tmdb.org/t/p/w500/1yXOzjDVHeBPLH3KgQVATmK3UEB.jpg",
        overview: "The show is about a 14th century Turkish Sufi living in Anatolia and tells his spiritual journey over the course of his lifetime."
    },
    "Mavera": {
        poster: "https://image.tmdb.org/t/p/w500/15fGkzZHAz3gDuqAhvN6PvvyaUn.jpg",
        overview: "The fight of Hace Ahmed Yesevi, who was sent to Baghdad by Yusuf Hemedani."
    },
    "Mehmetcik Kutul Amare": {
        poster: "https://image.tmdb.org/t/p/w500/8DdZHf7GcKUT3u1YIAkYZ5X856C.jpg",
        overview: "During World War I, a group of determined soldiers fights to defend Ottoman territory against English invasion."
    },
    "Rumi": {
        poster: "https://image.tmdb.org/t/p/w500/oWMOYrtbV44eYbJ6gQYXWLpqjl2.jpg",
        overview: "In 13th-century Anatolia, as the Mongol threat looms and internal turmoil rages, Rumi, a wise spiritual figure, emerges to assuage people's fears. His timeless words unite reason and compassion, inspiring change."
    },
    "Bozkir Aslani Celaleddin": {
        poster: "https://image.tmdb.org/t/p/w500/1luRYqIi5C5WydghAwceRlbOXUA.jpg",
        overview: "Jalal al-Din Khwarazmshah, the ruler of the Khwarazmian Empire, initiated a tragic but united struggle against the Mongol invasion. After unsuccessful attempts by the Khwarazm shahs to stop the Mongol invasion that began in the Central Asian steppes, Jalal al-Din, the last ruler of the empire, put dynastic fights between Seljuks and Khwarazmians aside, and tried to establish a united front to stop the Mongols."
    },
    "Aziz Mahmud Hudayi Askin Yolculugu": {
        poster: "https://image.tmdb.org/t/p/w500/jHe1Z3kZ0bWNeCbapGenMyN9Jco.jpg",
        overview: "The spiritual journey of Aziz Mahmud Hüdayi, a prominent Ottoman Sufi scholar and saint."
    },
    "Al Sancak": {
        poster: "https://image.tmdb.org/t/p/w500/vlyQk1qV85ivpCJvt3EVTsE9Egw.jpg",
        overview: "The struggles of 10 talented soldiers out to protect their homeland against its enemies."
    },
    "Young Ibn Sina": {
        poster: "https://image.tmdb.org/t/p/w500/cMeyeeGIX65vFmnb1PjqHjwLpYq.jpg",
        overview: "Life story of Avicenna, philosopher of the Islamic Golden Age."
    },
    "Gilani The Ascetic": {
        poster: "https://image.tmdb.org/t/p/w500/8Lw6rHrcgYUnpTBOO90bOTkIjt8.jpg",
        overview: "Gilani the Ascetic sets out to Baghdad seeking truth along with Eşref. Following an enduring seclusion, he finds out that the city of Baghdad is in chaos and he begins his historic struggle for equality and peace."
    },
    "Mahsusa": {
        poster: "https://image.tmdb.org/t/p/w500/icopMK2tpNdH8doBNuM6YZcDB1u.jpg",
        overview: "A Turkish historical drama series focusing on special operations during the Ottoman era."
    },
    "Vefa Sultan": {
        poster: "https://image.tmdb.org/t/p/w500/oDELdOgQuGW0L80MCPpdV9gID7L.jpg",
        overview: "Vefa Sultan immerses audiences in the spiritual heart of the Ottoman Empire, portraying the devoted life of Muslihuddin Mustafa—revered as one of Istanbul's spiritual sultans. Through its rich atmosphere, the production vividly recreates the era's social and cultural fabric, offering a captivating glimpse into the world of faith and tradition."
    }
};

function slugToTitle(slug) {
    return slug.split("-")
        .map(word => word.charAt(0).toUpperCase() + word.slice(1))
        .join(" ");
}

function lookupMetadata(slug, name) {
    if (SLUG_TO_TMDB_KEY[slug] && TMDB_METADATA[SLUG_TO_TMDB_KEY[slug]]) {
        return TMDB_METADATA[SLUG_TO_TMDB_KEY[slug]];
    }
    if (name && TMDB_METADATA[name]) return TMDB_METADATA[name];

    // Fuzzy: normalized containment either way
    const norm = s => (s || "").toLowerCase().replace(/[^a-z0-9]/g, "");
    const n = norm(name || slug);
    for (const [key, metadata] of Object.entries(TMDB_METADATA)) {
        const k = norm(key);
        if (n === k || n.includes(k) || k.includes(n)) return metadata;
    }
    return null;
}

// Legacy "watch-..." series id -> new slug; passthrough for already-new slugs
function canonicalSlug(seriesIdPart) {
    if (LEGACY_SLUG_MAP[seriesIdPart]) return LEGACY_SLUG_MAP[seriesIdPart];
    if (seriesIdPart.startsWith("watch-")) {
        let s = seriesIdPart.replace(/^watch-/, "").replace(/-with-english-subtitles$/, "");
        // Some legacy slugs never existed on the new site; fuzzy-correct against catalog later
        return s;
    }
    return seriesIdPart;
}

async function getSeries() {
    if (seriesCache && (Date.now() - seriesCacheTime) < CACHE_TTL) return seriesCache;

    try {
        console.log(`Fetching series index from: ${BASE_URL}/series/`);
        const { data } = await axios.get(`${BASE_URL}/series/`, {
            headers: { "User-Agent": UA },
            timeout: 20000
        });
        const $ = cheerio.load(data);

        // The index embeds each series' TMDB poster via /_next/image/?url=<tmdb url>
        // Build slug -> poster map so series missing from our TMDB_METADATA still
        // get real posters in the app.
        const sitePosters = {};
        $("a[href*='/series/']").each((i, el) => {
            const href = $(el).attr("href") || "";
            const m = href.match(/\/series\/([a-z0-9-]+)\/?$/i);
            if (!m || m[1] === "series") return;
            const img = $(el).find("img[src*='tmdb'], img[srcset*='tmdb']").first();
            const src = img.attr("src") || "";
            const im = src.match(/[?&]url=([^&]+)/);
            if (im && !sitePosters[m[1]]) {
                try {
                    sitePosters[m[1]] = decodeURIComponent(im[1]);
                } catch (e) { /* ignore */ }
            }
        });

        const series = [];
        const seen = new Set();

        $("a[href*='/series/']").each((i, el) => {
            const href = $(el).attr("href") || "";
            // Only series pages, not episode pages or the index itself
            const m = href.match(/\/series\/([a-z0-9-]+)\/?$/i);
            if (!m) return;
            const slug = m[1];
            if (slug === "series" || seen.has(slug)) return;
            seen.add(slug);

            const linkText = $(el).text().trim().replace(/\s+/g, " ");
            let name = (linkText && linkText.length > 2 && linkText.length < 80 && !/season|episode/i.test(linkText))
                ? linkText
                : slugToTitle(slug);

            const metadata = lookupMetadata(slug, name);

            // If link text is a truncated subset of the full title, use the full title
            const fullTitle = metadata && Object.keys(TMDB_METADATA).find(k => TMDB_METADATA[k] === metadata);
            if (fullTitle && name.length < fullTitle.length &&
                fullTitle.toLowerCase().startsWith(name.toLowerCase())) {
                name = fullTitle;
            }

            series.push({
                id: "osmanonline:" + slug,
                type: "series",
                name: name,
                poster: (metadata && metadata.poster) || sitePosters[slug] || null,
                description: metadata ? metadata.overview : `Watch ${name} with English subtitles on OsmanOnline`,
                slug: slug
            });
        });

        console.log(`Discovered ${series.length} series on ${BASE_URL}`);
        seriesCache = series;
        seriesCacheTime = Date.now();
        return series;
    } catch (error) {
        console.error("Error fetching series:", error.message);
        return seriesCache || [];
    }
}

// Group absolute episode numbers into seasons using JSON-LD containsSeason
function groupEpisodes(absEpisodes, ldSeasons) {
    const videos = [];

    if (ldSeasons && ldSeasons.length) {
        let cursor = 1;
        for (const s of ldSeasons) {
            const seasonNum = parseInt(s.seasonNumber);
            const count = parseInt(s.numberOfEpisodes);
            if (!seasonNum || !count) continue;
            for (let e = 0; e < count; e++) {
                const abs = cursor + e;
                if (absEpisodes.includes(abs)) {
                    videos.push({ abs, season: seasonNum, episode: e + 1 });
                }
            }
            cursor += count;
        }
        // Any episodes beyond the LD total: append to last season
        const overflow = absEpisodes.filter(a => a >= cursor);
        const lastSeason = ldSeasons.length ? parseInt(ldSeasons[ldSeasons.length - 1].seasonNumber) : 1;
        overflow.forEach((abs, i) => videos.push({ abs, season: lastSeason, episode: abs - cursor + 1 }));
        return videos;
    }

    // Fallback: single season with absolute numbering
    return absEpisodes.map(abs => ({ abs, season: 1, episode: abs }));
}

async function getMeta(type, id) {
    // id format: osmanonline:{slug} where slug may be a legacy "watch-..." slug
    const parts = id.split(":");
    if (parts.length < 2) throw new Error("Invalid id");

    let slug = canonicalSlug(parts[1]);
    let series = null;
    const seriesList = await getSeries();

    series = seriesList.find(s => s.slug === slug);
    if (!series) {
        // Fuzzy: try catalog containment (e.g. legacy slug variants)
        const norm = s => s.toLowerCase().replace(/[^a-z0-9]/g, "");
        const n = norm(slug);
        series = seriesList.find(s => norm(s.slug) === n) ||
                 seriesList.find(s => norm(s.slug).includes(n) || n.includes(norm(s.slug)));
    }
    if (!series) {
        // Not in catalog (new series might exist before cache refresh): build a minimal entry
        const metadata = lookupMetadata(slug, null);
        series = {
            id, slug,
            name: slugToTitle(slug),
            poster: metadata ? metadata.poster : null,
            description: metadata ? metadata.overview : `Watch ${slugToTitle(slug)} with English subtitles on OsmanOnline`
        };
    }

    try {
        const seriesUrl = `${BASE_URL}/series/${series.slug}/`;
        console.log(`Fetching series page: ${seriesUrl}`);
        const { data } = await axios.get(seriesUrl, {
            headers: { "User-Agent": UA },
            timeout: 20000
        });
        const $ = cheerio.load(data);

        // Season info from JSON-LD TVSeries.containsSeason
        let ldSeasons = null;
        let ldName = null;
        let ldDescription = null;
        // JSON-LD may be a single object, an array, or a @graph
        $("script[type='application/ld+json']").each((i, el) => {
            if (ldSeasons) return;
            try {
                const parsed = JSON.parse($(el).text());
                const nodes = Array.isArray(parsed) ? parsed
                    : (parsed["@graph"] ? parsed["@graph"] : [parsed]);
                for (const ld of nodes) {
                    if ((ld["@type"] === "TVSeries" || ld.containsSeason) && ld.containsSeason) {
                        ldSeasons = ld.containsSeason;
                        ldName = ld.name || null;
                        ldDescription = ld.description || null;
                        break;
                    }
                }
            } catch (e) { /* malformed ld+json - ignore */ }
        });

        // Episode links: /series/{slug}/episode-{abs}/
        const absSet = new Set();
        $(`a[href*='/series/${series.slug}/episode-']`).each((i, el) => {
            const href = $(el).attr("href") || "";
            const m = href.match(/episode-(\d+)/);
            if (m) absSet.add(parseInt(m[1]));
        });
        const absEpisodes = [...absSet].sort((a, b) => a - b);
        console.log(`Found ${absEpisodes.length} episodes for ${series.slug} (seasons: ${ldSeasons ? ldSeasons.length : "unknown"})`);

        const grouped = groupEpisodes(absEpisodes, ldSeasons);

        const videos = grouped.map(({ abs, season, episode }) => ({
            id: `osmanonline:${series.slug}:episode-${abs}`,
            title: `Episode ${abs}`,
            season: season,
            episode: episode,
            url: `${seriesUrl}episode-${abs}/`
        }));

        return {
            meta: {
                id: id, // echo the requested id exactly
                type: "series",
                name: ldName || series.name,
                poster: series.poster,
                background: series.poster,
                description: series.description || ldDescription,
                videos: videos
            }
        };
    } catch (error) {
        console.error("Error fetching episodes:", error.message);
        return null;
    }
}

module.exports = { getSeries, getMeta, canonicalSlug, LEGACY_SLUG_MAP };
