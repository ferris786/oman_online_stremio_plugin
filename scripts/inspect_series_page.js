const axios = require('axios');
const cheerio = require('cheerio');

async function inspect() {
    const url = 'https://osmanonline.info/watch-barbaros-hayreddin-sultanin-fermani-with-english-subtitles/';
    console.log(`Inspecting links on: ${url}`);

    try {
        const { data } = await axios.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
        const $ = cheerio.load(data);

        $('a').each((i, el) => {
            const txt = $(el).text().trim().replace(/\s+/g, ' ');
            const href = $(el).attr('href');
            if (txt.includes('Episode 1 ') || txt.includes('Episode 2 ')) {
                console.log(`[${i}] Text: "${txt}" | Href: ${href}`);
            }
        });

    } catch (err) {
        console.error(err.message);
    }
}

inspect();
