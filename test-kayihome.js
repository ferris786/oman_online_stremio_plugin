/**
 * Test script for kayihome bypass
 * 
 * Usage: node test-kayihome.js [kayihome-player-url]
 */

const { KayihomeExtractor } = require('./src/kayihome-bypass');

const testUrl = process.argv[2] || 'https://kayihome.xyz/player/index.php?data=53e3a7161e428b65688f14b84d61c610';

console.log('==========================================');
console.log('Kayihome.xyz Bypass Test');
console.log('==========================================');
console.log('URL:', testUrl);
console.log('');

async function test() {
  const extractor = new KayihomeExtractor({ 
    headless: false, // Set to true for headless mode
    timeout: 30000,
    waitTime: 5000
  });

  try {
    console.log('Initializing browser...');
    await extractor.init();
    console.log('✓ Browser initialized\n');

    console.log('Attempting extraction...');
    console.log('(This may take 10-30 seconds)\n');

    const result = await extractor.extract(testUrl);

    console.log('\n==========================================');
    if (result) {
      console.log('✓ EXTRACTION SUCCESSFUL!');
      console.log('==========================================');
      console.log('Stream URL:', result.url);
      console.log('Type:', result.type);
      if (result.alternatives?.length > 0) {
        console.log('Alternative URLs:', result.alternatives.length);
        result.alternatives.forEach((url, i) => {
          console.log(`  ${i + 1}. ${url}`);
        });
      }
    } else {
      console.log('✗ EXTRACTION FAILED');
      console.log('==========================================');
      console.log('No video URL was found.');
      console.log('\nPossible reasons:');
      console.log('- The video data hash may be expired');
      console.log('- Kayihome may have updated their protection');
      console.log('- Network or browser automation issue');
    }
    console.log('==========================================\n');

  } catch (error) {
    console.error('\n✗ ERROR:', error.message);
    console.error(error.stack);
  } finally {
    console.log('Closing browser...');
    await extractor.close();
    console.log('Done.');
  }
}

test();
