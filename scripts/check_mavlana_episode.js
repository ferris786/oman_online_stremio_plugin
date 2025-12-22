const axios = require('axios');

async function check() {
    // User provided URL
    const url = 'https://osmanonline.info/watch-mavlana-celaleddin-rumi-season-1-episode-1-with-english-subtitles/';
    console.log(`Checking: ${url}`);
    try {
        const res = await axios.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
        console.log(`Status: ${res.status}`);
        console.log(`Data length: ${res.data.length}`);
    } catch (e) {
        console.error(`Error: ${e.message}`);
    }
}

check();
