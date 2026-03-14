const axios = require('axios');
const cheerio = require('cheerio');

async function analyzeKayiFamilyTV() {
    const url = 'https://kayifamilytv.com/v18/mehmed-fetihler-sultani-episode-71/';
    console.log('Analyzing:', url);
    console.log('='.repeat(60));
    
    try {
        const { data } = await axios.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            },
            timeout: 15000
        });
        
        const $ = cheerio.load(data);
        
        // Get title
        const title = $('title').text();
        console.log('Page Title:', title);
        
        // Look for video tabs/sources
        console.log('\n--- Video Sources ---');
        
        // Method 1: Look for tabs
        const tabs = [];
        $('.tab-title, .wp-tab-title, [class*="tab"]').each((i, el) => {
            const text = $(el).text().trim();
            if (text && (text.toLowerCase().includes('okru') || 
                         text.toLowerCase().includes('vk') || 
                         text.toLowerCase().includes('video'))) {
                tabs.push(text);
            }
        });
        
        if (tabs.length > 0) {
            console.log('Found tabs:', tabs);
        }
        
        // Method 2: Look for iframes
        const iframes = [];
        $('iframe').each((i, el) => {
            const src = $(el).attr('src');
            if (src && (src.includes('ok.ru') || src.includes('vk.com') || src.includes('videa'))) {
                iframes.push({
                    src: src,
                    platform: src.includes('ok.ru') ? 'OK.ru' : 
                             src.includes('vk.com') ? 'VK' : 
                             src.includes('videa') ? 'Videa' : 'Unknown'
                });
            }
        });
        
        console.log('\nFound', iframes.length, 'video iframes:');
        iframes.forEach((iframe, i) => {
            console.log(`${i + 1}. ${iframe.platform}: ${iframe.src.substring(0, 100)}...`);
        });
        
        // Method 3: Look for any video links
        console.log('\n--- All video-related links ---');
        $('a').each((i, el) => {
            const href = $(el).attr('href');
            const text = $(el).text().trim();
            if (href && (href.includes('ok.ru') || href.includes('vk.com') || href.includes('videa'))) {
                console.log(`- ${text}: ${href}`);
            }
        });
        
        // Check for episode availability
        console.log('\n--- Episode Info ---');
        const episodeMatch = title.match(/Episode\s*(\d+)/i);
        if (episodeMatch) {
            console.log('Episode number:', episodeMatch[1]);
        }
        
    } catch (e) {
        console.error('Error:', e.message);
    }
}

analyzeKayiFamilyTV();
