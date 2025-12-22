const axios = require('axios');
const cheerio = require('cheerio');

async function inspectIframes() {
    // Try the URL that getStream likely fell back to
    const url = 'https://osmanonline.co.uk/watch-barbaroslar-season-1-episode-1-with-english-subtitles/';
    console.log(`Inspecting via Puppeteer / Cheerio: ${url} `);

    try {
        const { data } = await axios.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }
        });
        const $ = cheerio.load(data);

        console.log("Iframes found:");
        $('iframe').each((i, el) => {
            console.log(`[${i}]src: ${$(el).attr('src')} `);
        });

        console.log("\nLinks found (checking for players):");
        $('a').each((i, el) => {
            const href = $(el).attr('href');
            const text = $(el).text().trim();
            if (href && (href.includes('player') || href.includes('video') || href.includes('watch') || text.includes('Source') || href.includes('datebox'))) {
                console.log(`[${i}]Text: "${text}" | Href: ${href} `);
            }
        });

        const keywords = ['turktvuk', 'streamify', 'datebox', 'player', 'm3u8', 'mp4', 'eval(function', 'sources:'];
        console.log("\nSearching for keywords in HTML:");
        keywords.forEach(k => {
            if (data.includes(k)) {
                console.log(`Found keyword '${k}'`);
                // Print context
                const idx = data.indexOf(k);
                // Print a decent chunk to see what's happening
                console.log(`Context: ...${data.substring(idx - 100, idx + 200)}...`);
            }
        });

    } catch (e) {
        console.error(e.message);
    }
}

inspectIframes();
