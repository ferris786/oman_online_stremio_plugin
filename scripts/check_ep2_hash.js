const axios = require('axios');

async function check() {
    const url = 'https://turktvuk.com/player/index.php?data=f0dd4a99fba6075a9494772b58f95280'; // Ep 2 hash from log
    console.log(`Checking Ep 2 Iframe: ${url}`);

    try {
        const res = await axios.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0',
                'Referer': 'https://osmanonline.info/watch-barbaroslar-season-1-episode-2-with-english-subtitles'
            }
        });
        const title = (res.data.match(/<title>(.*?)<\/title>/) || [])[1];
        console.log(`Title: ${title}`);
    } catch (e) {
        console.error(e.message);
    }
}

run();
async function run() { await check(); }
