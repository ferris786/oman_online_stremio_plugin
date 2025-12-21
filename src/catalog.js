const axios = require("axios");
const cheerio = require("cheerio");

const BASE_URL = "https://osmanonline.info";

// Cache for catalog results to improve performance
let seriesCache = null;

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
        console.log("Nav length:", $("#main-nav").length);
        console.log("Nav HTML:", $("#main-nav").html().substring(0, 500)); // Log first 500 chars

        const series = [];

        // Try a more lenient selector
        const items = $("#main-nav li a"); // Removed (> ul >) to be safer

        items.each((i, el) => {
            const title = $(el).text().trim();
            const href = $(el).attr("href");

            if (title && href && href !== "#") {
                // Create a stable ID from the URL slug
                const id = "osmanonline:" + href.split("/").filter(Boolean).pop();

                series.push({
                    id: id,
                    type: "series",
                    name: title,
                    poster: null, // Scrape poster if available, or leave blank
                    description: `Watch ${title} on OsmanOnline`,
                    url: href // Store scraper URL for later use
                });
            }
        });

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
