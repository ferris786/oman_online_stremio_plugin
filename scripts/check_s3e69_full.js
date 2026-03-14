const axios = require('axios');
const cheerio = require('cheerio');

async function checkEpisode() {
    const url = 'https://kayifamily.com/mehmed-fetihler-sultani-season-3-episode-69/';
    console.log('=== Checking S03E69 ===');
    console.log('URL:', url);
    
    try {
        const { data, status } = await axios.get(url, {
            headers: { 
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'en-US,en;q=0.5'
            },
            timeout: 15000,
            validateStatus: () => true
        });
        
        console.log('Status:', status);
        
        const titleMatch = data.match(/<title>([^<]+)/);
        console.log('Title:', titleMatch ? titleMatch[1].trim() : 'No title');
        
        // Look for ALL video sources
        console.log('\n--- Looking for video sources ---');
        
        // Pattern 1: vidara.to
        const vidaraMatch = data.match(/vidara\.to\/e\/([a-zA-Z0-9]+)/);
        if (vidaraMatch) {
            console.log('✓ Found vidara.to filecode:', vidaraMatch[1]);
        } else {
            console.log('✗ No vidara.to found');
        }
        
        // Pattern 2: strmup.to
        const strmupMatch = data.match(/strmup\.to\/([a-zA-Z0-9]+)/);
        if (strmupMatch) {
            console.log('✓ Found strmup.to code:', strmupMatch[1]);
        } else {
            console.log('✗ No strmup.to found');
        }
        
        // Pattern 3: bestb.stream
        const bestbMatch = data.match(/bestb\.stream\/([a-zA-Z0-9]+)/);
        if (bestbMatch) {
            console.log('✓ Found bestb.stream code:', bestbMatch[1]);
        } else {
            console.log('✗ No bestb.stream found');
        }
        
        // Pattern 4: kayihome.xyz
        const kayihomeMatch = data.match(/kayihome\.xyz[^"]+data=([a-f0-9]+)/);
        if (kayihomeMatch) {
            console.log('✓ Found kayihome.xyz data:', kayihomeMatch[1]);
        } else {
            console.log('✗ No kayihome.xyz found');
        }
        
        // Check ALL iframes
        const $ = cheerio.load(data);
        const allIframes = [];
        $('iframe').each((i, el) => {
            const src = $(el).attr('src');
            if (src) {
                allIframes.push(src);
            }
        });
        
        console.log('\n--- All iframes on page ---');
        allIframes.forEach((iframe, i) => {
            console.log(`${i + 1}. ${iframe}`);
        });
        
        // Check for "Source" buttons/links
        console.log('\n--- Looking for source buttons ---');
        const sourceLinks = [];
        $('a').each((i, el) => {
            const text = $(el).text().toLowerCase();
            if (text.includes('source') || text.includes('server') || text.includes('quality')) {
                sourceLinks.push({
                    text: $(el).text().trim(),
                    href: $(el).attr('href')
                });
            }
        });
        
        if (sourceLinks.length > 0) {
            sourceLinks.forEach(link => {
                console.log(`- "${link.text}" -> ${link.href}`);
            });
        } else {
            console.log('No source/quality selector found');
        }
        
        // Check for alternative URL patterns
        console.log('\n--- Checking alternative URL patterns ---');
        
        // Absolute episode URL (Season 3 Episode 69 = absolute episode 95)
        const absUrl = 'https://kayifamily.com/mehmed-fetihler-sultani-episode-95/';
        console.log('Trying:', absUrl);
        
        try {
            const absRes = await axios.get(absUrl, {
                headers: { 'User-Agent': 'Mozilla/5.0' },
                timeout: 10000,
                validateStatus: () => true
            });
            
            console.log('Status:', absRes.status);
            const absTitle = absRes.data.match(/<title>([^<]+)/);
            console.log('Title:', absTitle ? absTitle[1].trim() : 'No title');
            
            // Check for vidara on absolute URL
            const absVidara = absRes.data.match(/vidara\.to\/e\/([a-zA-Z0-9]+)/);
            if (absVidara) {
                console.log('✓ Found vidara.to on ABSOLUTE URL:', absVidara[1]);
            }
            
        } catch (e) {
            console.log('Error:', e.message);
        }
        
    } catch (e) {
        console.error('Error:', e.message);
    }
}

checkEpisode();
