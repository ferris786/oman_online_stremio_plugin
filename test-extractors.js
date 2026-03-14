/**
 * Test script for OK.ru and VK.com video extractors
 * 
 * Usage:
 *   node test-extractors.js okru <video_id>
 *   node test-extractors.js vk <owner_id> <video_id> [hash]
 *   node test-extractors.js kayi <page_url>
 */

const extractors = require('./src/extractors');

async function testOkRu(videoId) {
    console.log('\n=== Testing OK.ru Extractor ===');
    console.log(`Video ID: ${videoId}\n`);
    
    const result = await extractors.okru.extract(videoId);
    
    if (result) {
        console.log('Extraction successful!');
        console.log(`Title: ${result.title}`);
        console.log(`Duration: ${result.duration}s`);
        console.log(`Thumbnail: ${result.thumbnail}`);
        console.log(`Is Live: ${result.isLive}`);
        console.log(`\nAvailable qualities:`);
        
        result.videos.forEach(v => {
            console.log(`  - ${v.quality}: ${v.url.substring(0, 80)}...`);
        });
        
        if (result.hlsUrl) {
            console.log(`\nHLS URL: ${result.hlsUrl}`);
        }
        
        const bestUrl = extractors.okru.getBestQuality(result, 'hd');
        console.log(`\nBest quality URL (HD): ${bestUrl ? bestUrl.substring(0, 80) + '...' : 'None'}`);
        
        const stremioStream = await extractors.okru.getStremioStream(videoId, 'hd');
        console.log('\nStremio stream config:');
        console.log(JSON.stringify(stremioStream, null, 2));
    } else {
        console.log('Extraction failed!');
    }
}

async function testVk(ownerId, videoId, hash = '') {
    console.log('\n=== Testing VK.com Extractor ===');
    console.log(`Owner ID: ${ownerId}`);
    console.log(`Video ID: ${videoId}`);
    console.log(`Hash: ${hash || '(none)'}\n`);
    
    const result = await extractors.vk.extract(ownerId, videoId, hash);
    
    if (result) {
        console.log('Extraction successful!');
        console.log(`Title: ${result.title || 'N/A'}`);
        console.log(`Duration: ${result.duration || 'N/A'}s`);
        console.log(`Thumbnail: ${result.thumbnail || 'N/A'}`);
        console.log(`Type: ${result.type}`);
        
        if (Object.keys(result.qualities).length > 0) {
            console.log(`\nAvailable qualities:`);
            for (const [quality, url] of Object.entries(result.qualities)) {
                console.log(`  - ${quality}: ${url.substring(0, 80)}...`);
            }
        }
        
        console.log(`\nMain URL: ${result.url ? result.url.substring(0, 80) + '...' : 'None'}`);
        
        if (result.hlsUrl) {
            console.log(`HLS URL: ${result.hlsUrl.substring(0, 80)}...`);
        }
        
        const stremioStream = await extractors.vk.getStremioStream(ownerId, videoId, hash, '720p');
        console.log('\nStremio stream config:');
        console.log(JSON.stringify(stremioStream, null, 2));
    } else {
        console.log('Extraction failed!');
    }
}

async function testKayiFamilyPage(pageUrl) {
    console.log('\n=== Testing KayiFamily Page Extraction ===');
    console.log(`URL: ${pageUrl}\n`);
    
    // Try OK.ru extraction from page
    console.log('Trying OK.ru extraction...');
    const okruResult = await extractors.okru.extractFromPage(pageUrl);
    if (okruResult) {
        console.log('OK.ru extraction successful!');
        console.log(`Title: ${okruResult.title}`);
        console.log(`Qualities: ${okruResult.videos.map(v => v.quality).join(', ')}`);
    } else {
        console.log('OK.ru extraction failed or no OK.ru iframe found');
    }
    
    // Try VK extraction from page
    console.log('\nTrying VK extraction...');
    const vkResult = await extractors.vk.extractFromPage(pageUrl);
    if (vkResult) {
        console.log('VK extraction successful!');
        console.log(`Title: ${vkResult.title || 'N/A'}`);
        console.log(`Qualities: ${Object.keys(vkResult.qualities).join(', ')}`);
    } else {
        console.log('VK extraction failed or no VK iframe found');
    }
}

async function main() {
    const args = process.argv.slice(2);
    
    if (args.length < 1) {
        console.log(`
Usage:
  node test-extractors.js okru <video_id>
    Example: node test-extractors.js okru 947875089023
    
  node test-extractors.js vk <owner_id> <video_id> [hash]
    Example: node test-extractors.js vk -22822305 456242110 e037414127166efe
    
  node test-extractors.js kayi <page_url>
    Example: node test-extractors.js kayi "https://kayifamily.com/..."
        `);
        process.exit(1);
    }
    
    const command = args[0];
    
    try {
        switch (command) {
            case 'okru':
                if (args.length < 2) {
                    console.log('Error: Video ID required');
                    process.exit(1);
                }
                await testOkRu(args[1]);
                break;
                
            case 'vk':
                if (args.length < 3) {
                    console.log('Error: Owner ID and Video ID required');
                    process.exit(1);
                }
                await testVk(args[1], args[2], args[3] || '');
                break;
                
            case 'kayi':
                if (args.length < 2) {
                    console.log('Error: Page URL required');
                    process.exit(1);
                }
                await testKayiFamilyPage(args[1]);
                break;
                
            default:
                console.log(`Unknown command: ${command}`);
                process.exit(1);
        }
    } catch (error) {
        console.error('Error:', error.message);
        process.exit(1);
    }
    
    console.log('\n=== Done ===\n');
}

main();
