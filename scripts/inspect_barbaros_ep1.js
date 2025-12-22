const axios = require('axios');
const cheerio = require('cheerio');

async function run() {
    const url = 'https://osmanonline.co.uk/v11/watch-barbaros-hayreddin-season-1-episode-1-with-english-subtitles';
    console.log(`Inspecting: ${url}`);

    try {
        const res = await axios.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' }, validateStatus: false });
        console.log(`Status: ${res.status}`);
        if (res.headers.location) console.log(`Redirect: ${res.headers.location}`);

        const $ = cheerio.load(res.data);
        const iframes = $('iframe');
        console.log(`Iframes: ${iframes.length}`);
        iframes.each((i, el) => console.log(`- src: ${$(el).attr('src')}`));

        if (iframes.length === 0) {
            console.log("No iframes found. Searching raw HTML...");
            if (res.data.includes('turktvuk')) {
                console.log("Found 'turktvuk' in HTML!");
                const idx = res.data.indexOf('turktvuk');
                console.log(res.data.substring(idx - 100, idx + 100));
            } else {
                console.log("'turktvuk' NOT found in HTML.");
            }
            // Check generically for iframe tag
            if (res.data.includes('<iframe')) {
                console.log("Found '<iframe' tag in raw text (cheerio missed it?)");
            }
        }

    } catch (e) {
        console.error(e.message);
    }
}

run();
