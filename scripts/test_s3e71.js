const axios = require('axios');

async function test() {
    // Vidara filecode for S03E71
    const filecode = '4KepslcHx5Ri0';
    
    console.log('Getting vidara stream for S03E71...');
    const apiRes = await axios.get(`https://vidara.to/api/stream?filecode=${filecode}`, {
        headers: {
            'User-Agent': 'Mozilla/5.0',
            'Referer': 'https://kayifamily.com/'
        }
    });
    
    console.log('Stream URL:', apiRes.data.streaming_url);
    
    // Test the stream
    console.log('\nTesting stream accessibility...');
    const streamRes = await axios.get(apiRes.data.streaming_url, {
        headers: {
            'User-Agent': 'Mozilla/5.0',
            'Referer': 'https://vidara.to/'
        },
        timeout: 10000,
        validateStatus: () => true
    });
    
    console.log('Status:', streamRes.status);
    console.log('Content-Type:', streamRes.headers['content-type']);
    
    if (streamRes.status === 200) {
        console.log('\nM3U8 Content (first 500 chars):');
        console.log(streamRes.data.substring(0, 500));
        
        // Parse the m3u8 to find segment URLs
        console.log('\n--- Checking variant streams ---');
        const lines = streamRes.data.split('\n');
        for (const line of lines) {
            if (line.includes('.m3u8') || line.includes('index_')) {
                console.log('Variant:', line);
                
                // Test if variant is accessible
                const baseUrl = apiRes.data.streaming_url.substring(0, apiRes.data.streaming_url.lastIndexOf('/') + 1);
                const variantUrl = line.startsWith('http') ? line : baseUrl + line;
                
                try {
                    const varRes = await axios.get(variantUrl, {
                        headers: {
                            'User-Agent': 'Mozilla/5.0',
                            'Referer': 'https://vidara.to/'
                        },
                        timeout: 5000,
                        validateStatus: () => true
                    });
                    console.log('  Status:', varRes.status);
                } catch (e) {
                    console.log('  Error:', e.message);
                }
            }
        }
    } else {
        console.log('Stream returned error status');
    }
}

test().catch(console.error);
