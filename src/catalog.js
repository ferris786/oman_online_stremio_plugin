const axios = require("axios");
const cheerio = require("cheerio");

const BASE_URL = "https://osmanonline.info";

// Cache for catalog results to improve performance
let seriesCache = null;

// Comprehensive TMDB metadata for all series (posters + overviews)
const TMDB_METADATA = {
    // Main navigation series
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
        poster: "https://image.tmdb.org/t/p/w500/1kEgzEvhJmw5eSxuGwn0o1cgHlK.jpg",
        overview: "The story of Orhan Ghazi, the son of Osman I and the second Sultan of the Ottoman Empire."
    },

    // Additional series from homepage banners (TMDB posters + overviews)
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
        poster: "https://image.tmdb.org/t/p/w500/8DdZHf7mKcUT3u1YIAkYZ5X856C.jpg",
        overview: "During World War I, a group of determined soldiers fights to defend Ottoman territory against English invasion."
    },

    // Previously missing posters - now added!
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
        poster: "https://image.tmdb.org/t/p/w500/vlyQk1qV85ivpCJvt3EVTs9Egw.jpg",
        overview: "The struggles of 10 talented soldiers out to protect their homeland against its enemies."
    },
    "Young Ibn Sina": {
        poster: "https://image.tmdb.org/t/p/w500/cMeyeeGIX65vFmnb1PjqhjwLpYq.jpg",
        overview: "Life story of Avicenna, philosopher of the Islamic Golden Age."
    },
    "Gilani The Ascetic": {
        poster: "https://image.tmdb.org/t/p/w500/8Lw6rHrcgYUnpTBOO90bOTkIjt9.jpg",
        overview: "Gilani the Ascetic sets out to Baghdad seeking truth along with Eşref. Following an enduring seclusion, he finds out that the city of Baghdad is in chaos and he begins his historic struggle for equality and peace."
    },
    "Mahsusa": {
        poster: "https://image.tmdb.org/t/p/w500/icopMK2tpNdH8doBNuM6YZcDB1y.jpg",
        overview: "A Turkish historical drama series focusing on special operations during the Ottoman era."
    },
    "Vefa Sultan": {
        poster: "https://image.tmdb.org/t/p/w500/oDELdOgQuGW0L80MCPpdV9gID7L.jpg",
        overview: "Vefa Sultan immerses audiences in the spiritual heart of the Ottoman Empire, portraying the devoted life of Muslihuddin Mustafa—revered as one of Istanbul's spiritual sultans. Through its rich atmosphere, the production vividly recreates the era's social and cultural fabric, offering a captivating glimpse into the world of faith and tradition."
    }
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

                // Try to find metadata in our map (try exact match first, then fuzzy match)
                let poster = null;
                let description = `Watch ${seriesName} on OsmanOnline`;

                // Exact match
                if (TMDB_METADATA[seriesName]) {
                    poster = TMDB_METADATA[seriesName].poster;
                    description = TMDB_METADATA[seriesName].overview || description;
                } else {
                    // Fuzzy match: normalize titles for comparison
                    const normalizedName = seriesName.toLowerCase().replace(/[^a-z0-9]/g, '');
                    for (const [key, metadata] of Object.entries(TMDB_METADATA)) {
                        const normalizedKey = key.toLowerCase().replace(/[^a-z0-9]/g, '');
                        if (normalizedName === normalizedKey || normalizedName.includes(normalizedKey)) {
                            poster = metadata.poster;
                            description = metadata.overview || description;
                            break;
                        }
                    }
                }

                series.push({
                    id: id,
                    type: "series",
                    name: seriesName,
                    poster: poster,
                    description: description,
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

        // Selector based on research: a.shortc-button.big.red OR generic a with href containing 'episode-'
        let episodeLinks = $("a.shortc-button.big.red");

        // Fallback or additive: if few results, try generic links
        if (episodeLinks.length === 0) {
            console.log("No standard buttons found, trying generic links...");

            // Create a filter based on the series URL to avoid sidebar/footer links
            const seriesSlugMatch = series.url.match(/watch-([^/]+)-with-english-subtitles/);
            const seriesCore = seriesSlugMatch ? seriesSlugMatch[1] : null;
            console.log(`Debug - Series Core: ${seriesCore}`);

            console.log(`Debug - Initial generic candidates: ${$("a[href*='episode-']").length}`);
            episodeLinks = $("a[href*='episode-']").filter((i, el) => {
                const href = $(el).attr("href");
                if (!href || href.length < 10) return false;

                // Critical: Ensure the link belongs to THIS series
                const match = seriesCore && href.includes(seriesCore);
                // console.log(`Checking link: ${href} (Core: ${seriesCore}) -> Match: ${match}`);

                if (seriesCore && !match) {
                    return false;
                }
                return true;
            });
            console.log(`Debug - Generic links after filter: ${episodeLinks.length}`);
        }

        episodeLinks.each((i, el) => {
            const title = $(el).text().trim();
            let href = $(el).attr("href");

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
                // BUGFIX: Rumi series page links to 'watch-rumi-' which are 404s.
                // The actual valid episodes are 'watch-mavlana-celaleddin-rumi-'.
                // We must rewrite the href here.
                if (series.name === 'Rumi' && href.includes('watch-rumi-')) {
                    href = href.replace('watch-rumi-', 'watch-mavlana-celaleddin-rumi-');
                }

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
                background: series.poster, // Use poster as background per user request
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
