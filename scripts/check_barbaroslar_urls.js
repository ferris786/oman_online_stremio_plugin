const axios = require('axios');

async function check(url) {
    console.log(`Checking: ${url}`);
    try {
        const res = await axios.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' }, validateStatus: false });
        console.log(`Status: ${res.status}`);
        if (res.headers.location) console.log(`Redirect: ${res.headers.location}`);
    } catch (e) {
        console.error(`Error: ${e.message}`);
    }
}

async function run() {
    await check('https://osmanonline.co.uk/watch-barbaroslar-season-1-episode-1-with-english-subtitles');
    await check('https://osmanonline.co.uk/watch-barbaroslar-season-1-episode-1-with-english-subtitles/');
    await check('https://osmanonline.co.uk/v11/watch-barbaroslar-season-1-episode-1-with-english-subtitles');
    // Also check "barbaroslar-akdenizin-kilici" slug potentially?
}

run();
