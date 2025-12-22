const axios = require('axios');
const cheerio = require('cheerio');

async function inspectLink(url) {
    console.log(`\n--- Inspecting: ${url} ---`);
    try {
        const res = await axios.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' }, validateStatus: false });
        console.log(`Status: ${res.status}`);
        if (res.status !== 200) return;

        const $ = cheerio.load(res.data);
        const iframes = $('iframe');
        console.log(`Iframes found: ${iframes.length}`);
        iframes.each((i, el) => {
            console.log(`[${i}] src: ${$(el).attr('src')}`);
        });

        // Check for specific player keywords in HTML if no iframes
        if (iframes.length === 0) {
            const html = res.data;
            ['turktvuk', 'streamify', 'player'].forEach(k => {
                if (html.includes(k)) console.log(`Found keyword '${k}' in HTML`);
            });
        }

    } catch (e) {
        console.error(`Error: ${e.message}`);
    }
}

async function run() {
    const seriesUrl = 'https://osmanonline.info/watch-barbaros-hayreddin-sultanin-fermani-with-english-subtitles/';
    console.log(`Fetching series page: ${seriesUrl}`);

    const { data } = await axios.get(seriesUrl, { headers: { 'User-Agent': 'Mozilla/5.0' } });
    const $ = cheerio.load(data);

    // Find Episode 1 and Episode 2 links
    let ep1Url, ep2Url;

    $('a').each((i, el) => {
        const txt = $(el).text().trim();
        const href = $(el).attr('href');

        if (txt.match(/Episode\s+1\b/i)) ep1Url = href;
        if (txt.match(/Episode\s+2\b/i)) ep2Url = href;
    });

    console.log(`\n\n[RESULT] Episode 1 URL: "${ep1Url}"`);
    console.log(`[RESULT] Episode 2 URL: "${ep2Url}"`);

    if (ep1Url) await inspectLink(ep1Url);
    if (ep2Url) await inspectLink(ep2Url);
}

run();
