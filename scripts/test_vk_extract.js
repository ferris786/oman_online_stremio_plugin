const axios = require('axios');

async function extractVK(embedUrl) {
    console.log('Fetching VK embed:', embedUrl);
    
    try {
        const { data } = await axios.get(embedUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': 'https://kayifamilytv.com/'
            },
            timeout: 15000
        });
        
        // Method 1: Look for video tag src
        const videoMatch = data.match(/<video[^>]+src=["']([^"']+)["']/);
        if (videoMatch) {
            console.log('\n✓ Found video tag src');
            return { url: videoMatch[1] };
        }
        
        // Method 2: Look for og:video meta
        const ogMatch = data.match(/<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']/);
        if (ogMatch) {
            console.log('\n✓ Found og:video meta');
            return { url: ogMatch[1] };
        }
        
        // Method 3: Look for player params in JS
        const playerParamsMatch = data.match(/var\s+playerParams\s*=\s*([\{\[][^;]+);/s);
        if (playerParamsMatch) {
            console.log('\n✓ Found playerParams');
            try {
                const params = JSON.parse(playerParamsMatch[1]);
                if (params.url) {
                    return { url: params.url };
                }
            } catch (e) {}
        }
        
        // Method 4: Look for mp4 URLs directly
        const mp4Matches = data.match(/https?:\/\/[^\s"<>]+\.mp4[^\s"<>]*/g);
        if (mp4Matches && mp4Matches.length > 0) {
            console.log('\n✓ Found MP4 URLs:', mp4Matches.length);
            return { url: mp4Matches[0] };
        }
        
        // Method 5: Look for hls/m3u8
        const m3u8Match = data.match(/(https?:\/\/[^\s"<>]+\.m3u8[^\s"<>]*)/);
        if (m3u8Match) {
            console.log('\n✓ Found HLS URL');
            return { url: m3u8Match[1] };
        }
        
        console.log('\n✗ No video URL found');
        return null;
        
    } catch (e) {
        console.error('Error:', e.message);
        return null;
    }
}

async function main() {
    // VK embed URLs from the research
    const testUrls = [
        'https://vk.com/video_ext.php?oid=-230035790&id=456239087&hash=1358e0c845be6e59&hd=1',
        'https://vkvideo.ru/video_ext.php?oid=-230035790&id=456239087&hash=1358e0c845be6e59'
    ];
    
    for (const url of testUrls) {
        console.log('\n' + '='.repeat(60));
        const result = await extractVK(url);
        if (result) {
            console.log('Result:', result.url.substring(0, 100) + '...');
        }
    }
}

main();
