const axios = require('axios');
const cheerio = require('cheerio');

async function inspect() {
    const url = 'https://osmanonline.co.uk/watch-rumi-season-3-episode-30-with-english-subtitles/';
    console.log(`Deep inspecting: ${url}`);

    try {
        const { data } = await axios.get(url, {
            headers: { 'User-Agent': 'Mozilla/5.0' }
        });
        const $ = cheerio.load(data);

        console.log("\n0. Page Title & Visible Text (First 500 chars):");
        console.log("Title:", $('title').text());
        console.log("Text:", $('body').text().replace(/\s+/g, ' ').trim().substring(0, 500));

        // Check for specific buttons
        console.log("\n0.5. 'Click here' or 'Watch' buttons:");
        $('a').each((i, el) => {
            const txt = $(el).text();
            if (txt.includes("Click") || txt.includes("Watch") || $(el).attr('class')?.includes('btn')) {
                console.log(`- Text: ${txt} | Href: ${$(el).attr('href')}`);
            }
        });

        console.log("\n1. All Iframes:");
        $('iframe').each((i, el) => console.log(`- src: ${$(el).attr('src')}`));

        console.log("\n2. All Video tags:");
        $('video').each((i, el) => console.log(`- src: ${$(el).attr('src')} | source children: ${$(el).find('source').length}`));

        console.log("\n3. Divs with 'player' or 'video' in id or class:");
        $('div[id*="player"], div[class*="player"], div[id*="video"], div[class*="video"]').each((i, el) => {
            console.log(`- Tag: div | Id: ${$(el).attr('id')} | Class: ${$(el).attr('class')}`);
            // console.log(`  Content: ${$(el).html().substring(0, 100)}...`);
        });

        console.log("\n4. Script tags containing 'player' or 'file' or 'sources':");
        $('script').each((i, el) => {
            const content = $(el).html();
            if (content && (content.includes('player') || content.includes('file:') || content.includes('sources:') || content.includes('jwplayer'))) {
                console.log(`- Script found with length ${content.length}. Preview: ${content.substring(0, 100).replace(/\n/g, ' ')}...`);
            }
        });

        // RAW HTML SEARCH
        console.log("\n5. RAW HTML Search for player keywords:");
        const content = data;
        const rawKeywords = ['sources:', 'file:', 'setup({', 'jwplayer', 'eval(function'];

        rawKeywords.forEach(k => {
            const idx = content.indexOf(k);
            if (idx !== -1) {
                console.log(`- Found '${k}' at index ${idx}`);
                console.log(`  Context: ...${content.substring(idx, idx + 300).replace(/\n/g, '')}...`);
            }
        });

    } catch (err) {
        console.error(err.message);
    }
}

inspect();
