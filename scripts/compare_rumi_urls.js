const axios = require('axios');
const cheerio = require('cheerio');

async function checkUrl(url) {
    try {
        console.log(`Checking: ${url}`);
        const res = await axios.get(url, {
            maxRedirects: 5,
            validateStatus: false
        });
        console.log(`Status: ${res.status}`);
        if (res.headers.location) console.log(`Redirect to: ${res.headers.location}`);
        return res.status;
    } catch (e) {
        console.log(`Error: ${e.message}`);
        return 0;
    }
}

async function run() {
    console.log("--- Checking User Provided URL (Mavlana) ---");
    // Series page guess
    await checkUrl('https://osmanonline.info/watch-mavlana-celaleddin-rumi-with-english-subtitles/');
    // Episode page user provided
    await checkUrl('https://osmanonline.info/watch-mavlana-celaleddin-rumi-season-1-episode-1-with-english-subtitles/');

    console.log("\n--- Checking Current Scraped URL (Rumi) ---");
    // Series page our scraper finds
    await checkUrl('https://osmanonline.info/watch-rumi-with-english-subtitles/');
    // Episode page our scraper likely generates
    await checkUrl('https://osmanonline.info/watch-rumi-season-1-episode-1-with-english-subtitles/');
}

run();
