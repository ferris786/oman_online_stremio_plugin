const axios = require('axios');

async function debug() {
    const url = 'https://kayifamilytv.com/v18/mehmed-fetihler-sultani-episode-69';
    console.log('Checking S03E69 on KayiFamilyTV...\n');
    
    try {
        const { data } = await axios.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            },
            timeout: 20000
        });
        
        // Find ALL ok.ru video IDs
        const okruMatches = data.match(/ok\.ru\/videoembed\/(\d+)/g);
        console.log('Ok.ru embeds found:', okruMatches ? okruMatches.length : 0);
        
        if (okruMatches) {
            const uniqueIds = [...new Set(okruMatches.map(m => m.match(/(\d+)$/)[1]))];
            console.log('Unique Ok.ru IDs:', uniqueIds);
            
            // Try extracting from each
            for (const videoId of uniqueIds.slice(0, 3)) {
                console.log(`\n--- Testing ID: ${videoId} ---`);
                
                try {
                    const embedRes = await axios.get(`https://ok.ru/videoembed/${videoId}?nochat=1`, {
                        headers: {
                            'User-Agent': 'Mozilla/5.0',
                            'Referer': 'https://kayifamilytv.com/'
                        },
                        timeout: 15000
                    });
                    
                    // Extract data-options
                    const dataOptionsMatch = embedRes.data.match(/data-options="([^"]+)"/);
                    if (dataOptionsMatch) {
                        const jsonStr = dataOptionsMatch[1].replace(/&quot;/g, '"');
                        const options = JSON.parse(jsonStr);
                        
                        if (options.flashvars && options.flashvars.metadata) {
                            const metadata = JSON.parse(options.flashvars.metadata);
                            
                            if (metadata.videos && metadata.videos.length > 0) {
                                const best = metadata.videos[metadata.videos.length - 1];
                                console.log('Title:', metadata.movie?.title);
                                console.log('Quality:', best.name);
                                console.log('URL domain:', new URL(best.url).hostname);
                                
                                // Test if URL is accessible
                                try {
                                    const testRes = await axios.head(best.url, {
                                        headers: {
                                            'User-Agent': 'Mozilla/5.0',
                                            'Referer': 'https://ok.ru/'
                                        },
                                        timeout: 10000,
                                        validateStatus: () => true
                                    });
                                    console.log('URL Status:', testRes.status);
                                } catch (e) {
                                    console.log('URL Test Error:', e.message);
                                }
                            }
                        }
                    }
                } catch (e) {
                    console.log('Error:', e.message);
                }
            }
        }
        
        // Also check for VK videos
        const vkMatches = data.match(/vk\.com\/[^"\s]+/g);
        console.log('\n\nVK links found:', vkMatches ? [...new Set(vkMatches)].slice(0, 5) : 'None');
        
    } catch (e) {
        console.error('Error:', e.message);
    }
}

debug();
