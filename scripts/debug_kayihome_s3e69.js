const axios = require('axios');

async function debugKayihome() {
    const dataParam = '53e3a7161e428b65688f14b84d61c610';
    const url = `https://kayihome.xyz/player/index.php?data=${dataParam}`;
    
    console.log('Fetching:', url);
    
    try {
        const { data, status } = await axios.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': 'https://kayifamily.com/'
            },
            timeout: 15000,
            validateStatus: () => true
        });
        
        console.log('Status:', status);
        console.log('Content length:', data.length);
        
        // Check for no_video
        if (data.includes('no_video.html') || data.includes('No Video Found')) {
            console.log('Page shows: NO VIDEO AVAILABLE');
        }
        
        // Look for title
        const titleMatch = data.match(/<title>([^<]+)/);
        console.log('Title:', titleMatch ? titleMatch[1].trim() : 'No title');
        
        // Check for fireload function
        if (data.includes('fireload')) {
            console.log('Found fireload function');
            
            // Extract fireload content
            const fireloadMatch = data.match(/function fireload\([^)]*\)\s*\{([^}]+\{[^}]*\}[^}]*)\}/);
            if (fireloadMatch) {
                console.log('Fireload content:', fireloadMatch[0].substring(0, 500));
            }
        }
        
        // Check for videoUrl
        const videoUrlMatch = data.match(/"videoUrl"\s*:\s*"([^"]+)"/);
        if (videoUrlMatch) {
            console.log('Found videoUrl:', videoUrlMatch[1]);
        } else {
            console.log('No videoUrl found in page');
        }
        
        // Check for FirePlayer call
        const fireplayerMatch = data.match(/FirePlayer\s*\(\s*["']([^"']+)["']\s*,\s*(\{[^;]+\})/s);
        if (fireplayerMatch) {
            console.log('Found FirePlayer call with config');
        }
        
        // Print first 1000 chars of response
        console.log('\n--- First 1000 chars of HTML ---');
        console.log(data.substring(0, 1000));
        
    } catch (e) {
        console.error('Error:', e.message);
    }
}

debugKayihome();
