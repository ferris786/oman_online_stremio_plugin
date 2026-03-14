const axios = require('axios');

async function extractOkRu(videoId) {
    const embedUrl = `https://ok.ru/videoembed/${videoId}?nochat=1`;
    console.log('Fetching:', embedUrl);
    
    try {
        const { data } = await axios.get(embedUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': 'https://kayifamilytv.com/'
            },
            timeout: 15000
        });
        
        // Method 1: Look for data-options attribute
        const dataOptionsMatch = data.match(/data-options="([^"]+)"/);
        if (dataOptionsMatch) {
            console.log('\n✓ Found data-options attribute');
            
            // Decode HTML entities
            const jsonStr = dataOptionsMatch[1].replace(/&quot;/g, '"');
            
            try {
                const options = JSON.parse(jsonStr);
                
                if (options.flashvars && options.flashvars.metadata) {
                    const metadata = JSON.parse(options.flashvars.metadata);
                    console.log('Video title:', metadata.movie.title);
                    
                    if (metadata.videos) {
                        console.log('\n--- Available qualities ---');
                        metadata.videos.forEach(v => {
                            console.log(`${v.name}: ${v.url.substring(0, 80)}...`);
                        });
                        
                        // Return highest quality
                        const best = metadata.videos[metadata.videos.length - 1];
                        return {
                            title: metadata.movie.title,
                            quality: best.name,
                            url: best.url
                        };
                    }
                }
            } catch (e) {
                console.log('Parse error:', e.message);
            }
        }
        
        // Method 2: Look for direct mp4 URLs
        const mp4Match = data.match(/(https?:\/\/[^\s"<>]+\.mp4[^\s"<>]*)/);
        if (mp4Match) {
            console.log('\n✓ Found direct MP4 URL');
            return { url: mp4Match[1] };
        }
        
        // Method 3: Look for m3u8
        const m3u8Match = data.match(/(https?:\/\/[^\s"<>]+\.m3u8[^\s"<>]*)/);
        if (m3u8Match) {
            console.log('\n✓ Found HLS URL');
            return { url: m3u8Match[1] };
        }
        
        console.log('\n✗ No video URL found in embed page');
        return null;
        
    } catch (e) {
        console.error('Error:', e.message);
        return null;
    }
}

async function main() {
    // Test with the first video ID found
    const videoId = '12998454938142';
    console.log('=== Testing Ok.ru Extraction ===\n');
    
    const result = await extractOkRu(videoId);
    
    if (result) {
        console.log('\n=== SUCCESS ===');
        console.log('Result:', result);
        
        // Test if URL is accessible
        console.log('\nTesting stream URL...');
        try {
            const testRes = await axios.head(result.url, {
                headers: {
                    'User-Agent': 'Mozilla/5.0',
                    'Referer': 'https://ok.ru/'
                },
                timeout: 10000,
                validateStatus: () => true
            });
            console.log('Status:', testRes.status);
            console.log('Content-Type:', testRes.headers['content-type']);
            
            if (testRes.status === 200) {
                console.log('✓ URL is accessible!');
            }
        } catch (e) {
            console.log('Test error:', e.message);
        }
    }
}

main();
