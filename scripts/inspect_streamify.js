const axios = require('axios');
const cheerio = require('cheerio');

async function inspectStreamify() {
    const url = 'https://streamify360.com/video/LRTKhnHU5e7';
    console.log(`Fetching ${url}...`);

    try {
        const { data } = await axios.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Referer': 'https://osmanonline.info/'
            }
        });

        console.log("Response length:", data.length);

        // Check for .m3u8 or .mp4
        if (data.includes(".m3u8")) console.log("Found .m3u8 in content");
        if (data.includes(".mp4")) console.log("Found .mp4 in content");

        // Check for packer
        if (data.includes("eval(function(p,a,c,k,e,d)")) console.log("Found packed script");

        const $ = cheerio.load(data);
        const scripts = $('script');
        scripts.each((i, el) => {
            const content = $(el).html();
            if (content && (content.includes("source") || content.includes("file") || content.includes("player"))) {
                console.log(`Script ${i}:`, content.substring(0, 500) + "...");
            }
        });

    } catch (e) {
        console.error(e.message);
    }
}

inspectStreamify();
