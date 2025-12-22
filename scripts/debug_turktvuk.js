const axios = require('axios');

async function check(referer) {
    const url = 'https://turktvuk.com/player/index.php?data=9cc138f8dc04cbf16240daa92d8d50e2'; // Ep 1 hash
    console.log(`Checking with Referer: ${referer || "NONE"}`);

    try {
        const headers = { 'User-Agent': 'Mozilla/5.0' };
        if (referer) headers['Referer'] = referer;

        const res = await axios.get(url, { headers: headers });
        const title = (res.data.match(/<title>(.*?)<\/title>/) || [])[1];
        console.log(`Title: ${title}`);
        if (!title.includes('Error')) {
            console.log("SUCCESS! Content length:", res.data.length);
        }
    } catch (e) {
        console.error(e.message);
    }
}

async function run() {
    await check('https://osmanonline.info/');
    await check('https://osmanonline.info/watch-barbaroslar-season-1-episode-1-with-english-subtitles');
    await check('https://osmanonline.co.uk/');
    await check('https://osmanonline.co.uk/watch-barbaroslar-season-1-episode-1-with-english-subtitles/');
    await check(null);
}

run();
