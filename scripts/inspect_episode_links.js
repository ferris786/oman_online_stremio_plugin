const axios = require('axios');
const cheerio = require('cheerio');

async function run() {
    const url = 'https://osmanonline.co.uk/watch-barbaroslar-season-1-episode-1-with-english-subtitles/';
    console.log(`Inspecting links on: ${url}`);

    try {
        const { data } = await axios.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
        const $ = cheerio.load(data);

        console.log("Page Title:", $('title').text());

        $('a').each((i, el) => {
            const txt = $(el).text().trim().replace(/\s+/g, ' ');
            const href = $(el).attr('href');
            // Filter for interesting links
            if (href && (txt.includes('Part') || txt.includes('Watch') || txt.includes('Source') || href.includes('v11') || href.includes('player'))) {
                console.log(`[${i}] Text: "${txt}" | Href: ${href}`);
            }
        });

        // Also dump text just in case
        console.log("Preview text: " + $('body').text().substring(0, 200).replace(/\s+/g, ' '));
    } catch (err) {
        console.error(err.message);
    }
}

run();
