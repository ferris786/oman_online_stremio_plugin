const axios = require('axios');
const cheerio = require('cheerio');

async function check() {
    const url = 'https://kayifamily.com/mehmed-fetihler-sultani-season-3-episode-69/';
    
    try {
        const { data } = await axios.get(url, {
            headers: { 'User-Agent': 'Mozilla/5.0' },
            timeout: 15000
        });
        
        const $ = cheerio.load(data);
        
        // Check for vidara
        const vidara = $('iframe[src*="vidara.to"]').attr('src');
        if (vidara) {
            console.log('Found vidara:', vidara);
            
            // Extract filecode
            const match = vidara.match(/vidara\.to\/e\/([a-zA-Z0-9]+)/);
            if (match) {
                const filecode = match[1];
                console.log('Filecode:', filecode);
                
                // Call vidara API
                const apiRes = await axios.get(`https://vidara.to/api/stream?filecode=${filecode}`, {
                    headers: {
                        'User-Agent': 'Mozilla/5.0',
                        'Referer': 'https://kayifamily.com/'
                    }
                });
                
                console.log('Vidara API response:', JSON.stringify(apiRes.data, null, 2));
                
                if (apiRes.data.streaming_url) {
                    // Test the URL
                    console.log('\nTesting stream URL...');
                    const testRes = await axios.get(apiRes.data.streaming_url, {
                        headers: { 'User-Agent': 'Mozilla/5.0' },
                        timeout: 10000,
                        validateStatus: () => true
                    });
                    console.log('Stream status:', testRes.status);
                }
            }
        } else {
            console.log('No vidara iframe found on S03E69');
        }
        
    } catch (e) {
        console.error('Error:', e.message);
    }
}

check();
