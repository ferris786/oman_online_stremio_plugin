const axios = require('axios');
const cheerio = require('cheerio');

async function analyzeDeeper() {
    const url = 'https://kayifamilytv.com/v18/mehmed-fetihler-sultani-episode-71/';
    
    try {
        const { data } = await axios.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            },
            timeout: 15000
        });
        
        // Look for wp-tabs (WordPress tabs plugin)
        const tabsMatch = data.match(/data-tab="(\d+)"/g);
        console.log('Tab data attributes found:', tabsMatch ? tabsMatch.length : 0);
        
        // Look for any ok.ru or vk links in the HTML
        const okruMatches = data.match(/ok\.ru\/[^"\s<>]+/g);
        const vkMatches = data.match(/vk\.com\/[^"\s<>]+/g);
        
        console.log('Ok.ru URLs found:', okruMatches ? [...new Set(okruMatches)].slice(0, 5) : 'None');
        console.log('VK URLs found:', vkMatches ? [...new Set(vkMatches)].slice(0, 5) : 'None');
        
        // Look for JavaScript data
        const scriptMatches = data.match(/var\s+\w+\s*=\s*\{[^}]+\}/g);
        if (scriptMatches) {
            console.log('\nJavaScript data objects found:', scriptMatches.length);
            scriptMatches.slice(0, 3).forEach((match, i) => {
                if (match.includes('video') || match.includes('url') || match.includes('src')) {
                    console.log(`Script ${i}:`, match.substring(0, 200));
                }
            });
        }
        
        // Check for WP Expand Tabs shortcode data
        const expandTabsMatch = data.match(/\[wp_expand_tabs[^\]]*\]/);
        if (expandTabsMatch) {
            console.log('\nWP Expand Tabs shortcode:', expandTabsMatch[0]);
        }
        
        // Look for data-src or data-url attributes
        const dataUrlMatches = data.match(/data-(?:src|url)=["']([^"']+)["']/g);
        if (dataUrlMatches) {
            console.log('\nData URLs found:', dataUrlMatches.slice(0, 5));
        }
        
        // Print a section of the HTML around video area
        const videoSection = data.match(/<div[^>]*class=["'][^"']*video[^"']*["'][^>]*>([\s\S]{0,1000})/i);
        if (videoSection) {
            console.log('\n--- Video section HTML (first 1000 chars) ---');
            console.log(videoSection[0]);
        }
        
    } catch (e) {
        console.error('Error:', e.message);
    }
}

analyzeDeeper();
