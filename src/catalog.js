const axios = require("axios");
const cheerio = require("cheerio");

const BASE_URL = "https://osmanonline.info";

// Cache for catalog results to improve performance
let seriesCache = null;

const POSTER_MAP = {
    // Main navigation series
    "Kurulus Osman": "https://image.tmdb.org/t/p/w500/tu4BWsGFHcYDWulZwHxylA91vo0.jpg",
    "Kudus Fatihi Selahaddin Eyyubi": "https://image.tmdb.org/t/p/w500/5bljg22nvfS0eP320L5GFYJz3Zb.jpg",
    "Mehmed Fetihler Sultani": "https://image.tmdb.org/t/p/w500/A8fHgHmcEQU1UcOcXhW3NXtwwcZ.jpg",
    "Kurulus Orhan": "https://image.tmdb.org/t/p/w500/1kEgzEvhJmw5eSxuGwn0o1cgHlK.jpg",

    // Additional series from homepage banners (TMDB posters)
    "Destan": "https://image.tmdb.org/t/p/w500/cSDEb3XvsML6VwYZ5HEJy7vQUS.jpg",
    "Dirilis Ertugrul": "https://image.tmdb.org/t/p/w500/rOar34cNLn2sgDH5FmAa1bvMpBv.jpg",
    "Uyanis Buyuk Selcuklu": "https://image.tmdb.org/t/p/w500/8B1nL3gthGN55BHTMzZOlzBYNkU.jpg",
    "Alparslan Buyuk Selcuklu": "https://image.tmdb.org/t/p/w500/4wKqK8T1wTdhXfhnZzz2TuJE2Zh.jpg",
    "Payitaht Abdulhamid": "https://image.tmdb.org/t/p/w500/fmaWiokhUVDsVkCfoWaQEDFZFFP.jpg",
    "Barbaroslar Akdenizin Kilici": "https://image.tmdb.org/t/p/w500/1VqjAWF5rW431wY4eEoV9oIyx0L.jpg",
    "Barbaros Hayreddin Sultanin Fermani": "https://image.tmdb.org/t/p/w500/8fWkyBfGhDJdDNhZ5J4z0r1IzIt.jpg",
    "Haci Bayram I Veli": "https://image.tmdb.org/t/p/w500/1yXOzjDVHeBPLH3KgQVATmK3UEB.jpg",
    "Mavera": "https://image.tmdb.org/t/p/w500/15fGkzZHAz3gDuqAhvN6PvvyaUn.jpg",
    "Mehmetcik Kutul Amare": "https://image.tmdb.org/t/p/w500/8DdZHf7mKcUT3u1YIAkYZ5X856C.jpg"
};

async function getSeries() {
    if (seriesCache) return seriesCache;

    try {
        console.log(`Fetching series from: ${BASE_URL}`);
        const { data } = await axios.get(BASE_URL, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }
        });
        const $ = cheerio.load(data);

        console.log("Page title:", $("title").text());

        const series = [];
        const seenUrls = new Set(); // Track URLs to avoid duplicates

        // HYBRID APPROACH: Scrape all series collection links from homepage
        // Pattern: links containing "watch-" and "with-english-subtitles" but NOT "episode-"
        const allLinks = $("a[href*='watch-'][href*='with-english-subtitles']");

        console.log(`Total links matching pattern: ${allLinks.length}`);

        allLinks.each((i, el) => {
            const href = $(el).attr("href");
            const linkText = $(el).text().trim();

            // Filter out episode links (we only want series collection pages)
            if (href && !href.match(/episode-\d+/i)) {
                // Normalize URL to just the slug for duplicate detection
                const normalizedSlug = href.split("/").filter(Boolean).pop();

                if (seenUrls.has(normalizedSlug)) {
                    return; // Skip duplicate
                }
                seenUrls.add(normalizedSlug);

                // Extract clean series name from URL
                // URL pattern: watch-{series-name}-with-english-subtitles
                let seriesName = null;
                const urlMatch = href.match(/watch-(.+?)-with-english-subtitles/i);

                if (urlMatch) {
                    // Convert URL slug to title case
                    seriesName = urlMatch[1]
                        .split('-')
                        .map(word => word.charAt(0).toUpperCase() + word.slice(1))
                        .join(' ');
                }

                // Use link text if available and cleaner than URL-derived name
                if (linkText && linkText.length > 0 && linkText.length < 100 && !linkText.includes('\n')) {
                    seriesName = linkText;
                }

                // Skip if we couldn't extract a valid name
                if (!seriesName || seriesName === "Home" || seriesName === "Contact Us") {
                    return;
                }

                // Create a stable ID from the URL slug
                const slug = href.split("/").filter(Boolean).pop();
                const id = "osmanonline:" + slug;

                // Try to find poster in our map (try exact match first, then fuzzy match)
                let poster = null;

                // Exact match
                if (POSTER_MAP[seriesName]) {
                    poster = POSTER_MAP[seriesName];
                } else {
                    // Fuzzy match: normalize titles for comparison
                    const normalizedName = seriesName.toLowerCase().replace(/[^a-z0-9]/g, '');
                    for (const [key, value] of Object.entries(POSTER_MAP)) {
                        const normalizedKey = key.toLowerCase().replace(/[^a-z0-9]/g, '');
                        if (normalizedName === normalizedKey || normalizedName.includes(normalizedKey)) {
                            poster = value;
                            break;
                        }
                    }
                }

                series.push({
                    id: id,
                    type: "series",
                    name: seriesName,
                    poster: poster,
                    description: `Watch ${seriesName} on OsmanOnline`,
                    url: href
                });
            }
        });

        console.log(`Discovered ${series.length} unique series`);
        series.forEach(s => console.log(`  - ${s.name} (${s.poster ? 'has poster' : 'no poster'})`));

        seriesCache = series;
        return series;
    } catch (error) {
        console.error("Error fetching series:", error);
        return [];
    }
}

async function getModules(seriesId) {
    // This function would usually return meta details, but for catalog we can just return the list
    // If the catalog request asks for a specific series, we need to handle that logic if supported
    return getSeries();
}

async function getMeta(type, id) {
    // Logic to scrape episodes for a specific series
    // ID format: osmanonline:slug

    // We need to find the series URL first (usually by listing all or parsing the ID)
    const seriesList = await getSeries();
    const series = seriesList.find(s => s.id === id);

    if (!series) {
        throw new Error("Series not found");
    }

    try {
        console.log(`Fetching series page: ${series.url}`);
        const { data } = await axios.get(series.url);
        const $ = cheerio.load(data);

        const videos = [];

        // Debug selector
        console.log("Episodes found:", $("a.shortc-button.big.red").length);

        // Selector based on research: a.shortc-button.big.red
        $("a.shortc-button.big.red").each((i, el) => {
            const title = $(el).text().trim();
            const href = $(el).attr("href");

            // Extract episode number or season/episode from text if possible
            // Format example: "Season 1 Episode 1 Bolum 1"
            let season = 1;
            let episode = i + 1;

            const seasonMatch = title.match(/Season\s+(\d+)/i);
            const episodeMatch = title.match(/Episode\s+(\d+)/i);

            if (seasonMatch) {
                season = parseInt(seasonMatch[1]);
            }
            if (episodeMatch) {
                episode = parseInt(episodeMatch[1]);
            }

            if (href) {
                // ID format: osmanonline:slug:url_hash_or_slug
                // We'll use the episode page slug as the unique identifier part
                const episodeSlug = href.split("/").filter(Boolean).pop();

                videos.push({
                    id: `${id}:${episodeSlug}`,
                    title: title,
                    released: new Date().toISOString(), // Placeholder
                    season: season,
                    episode: episode,
                    url: href // Store for stream scraping
                });
            }
        });

        // Reverse to show latest first or based on site order? Site usually has lists.
        // Assuming site lists usually descending or specific order.

        return {
            meta: {
                id: series.id,
                type: "series",
                name: series.name,
                poster: series.poster,
                description: series.description,
                videos: videos
            }
        };

    } catch (error) {
        console.error("Error fetching episodes:", error);
        return null;
    }
}

module.exports = { getSeries, getMeta };
