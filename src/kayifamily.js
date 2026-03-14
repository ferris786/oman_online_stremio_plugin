const axios = require("axios");
const cheerio = require("cheerio");

// Episode offset lookup for series with absolute numbering on KayiFamily
// These offsets convert season-relative episodes to absolute episode numbers
const SEASON_OFFSETS = {
    'kurulus-osman': {
        1: 0,   // Episodes 1-27
        2: 27,  // Episodes 28-64 (27+37)
        3: 64,  // Episodes 65-98 (27+37+34)
        4: 98,  // Episodes 99-130 (27+37+34+32)
        5: 130, // Episodes 131-164 (27+37+34+32+34)
        6: 164  // Episodes 165+ (27+37+34+32+34+34)
    },
    'kurulus-orhan': {
        1: 0
    },
    'mehmed-fetihler-sultani': {
        1: 0,
        2: 26,  // Verify: Season 2 starts at episode 27
        3: 52   // Verify: Season 3 starts at episode 53
    },
    'salahuddin-ayyubi': {
        1: 0,
        2: 30   // Verify actual count for season 1
    },
    'mevlana-rumi': {
        1: 0,
        2: 18,  // Verify actual count
        3: 36   // Verify actual count
    },
    'alparslan-buyuk-selcuklu': {
        1: 0,
        2: 27   // Verify actual count for season 1
    },
    'payitaht-abdulhamid': {
        1: 0,
        2: 26,
        3: 52,
        4: 78,
        5: 104
    },
    'destan': {
        1: 0
    }
};

// Series name mapping (OsmanOnline slug -> KayiFamily slug)
const SERIES_NAME_MAP = {
    'watch-kurulus-osman-with-english-subtitles': 'kurulus-osman',
    'watch-kurulus-orhan-with-english-subtitles': 'kurulus-orhan',
    'watch-mehmed-fetihler-sultani-with-english-subtitles': 'mehmed-fetihler-sultani',
    'watch-salahuddin-ayyubi-with-english-subtitles': 'salahuddin-ayyubi',
    'watch-kudus-fatihi-selahaddin-eyyubi-with-english-subtitles': 'salahuddin-ayyubi',
    'watch-mevlana-rumi-with-english-subtitles': 'mevlana-rumi',
    'watch-rumi-with-english-subtitles': 'mevlana-rumi',
    'watch-alparslan-buyuk-selcuklu-with-english-subtitles': 'alparslan-buyuk-selcuklu',
    'watch-payitaht-abdulhamid-with-english-subtitles': 'payitaht-abdulhamid',
    'watch-destan-with-english-subtitles': 'destan',
    'watch-dirilis-ertugrul-with-english-subtitles': 'dirilis-ertugrul',
    'watch-barbaroslar-akdenizin-kilici-with-english-subtitles': 'barbaroslar-akdenizin-kilici',
    'watch-uyanis-buyuk-selcuklu-with-english-subtitles': 'uyanis-buyuk-selcuklu'
};

/**
 * Convert season-relative episode number to absolute episode number
 * @param {string} seriesSlug - Series slug (KayiFamily format)
 * @param {number} season - Season number
 * @param {number} episode - Episode number within season
 * @returns {number} - Absolute episode number
 */
function toAbsoluteEpisode(seriesSlug, season, episode) {
    const offsets = SEASON_OFFSETS[seriesSlug];
    if (!offsets || !offsets[season]) {
        // If we don't have offsets, assume absolute = season * 100 + episode
        // This is a fallback and may not be accurate
        return (season - 1) * 100 + episode;
    }
    return offsets[season] + episode;
}

/**
 * Build KayiFamily episode URLs to try
 * @param {string} seriesSlug - Series slug (KayiFamily format)
 * @param {number} season - Season number
 * @param {number} absoluteEp - Absolute episode number
 * @returns {string[]} - Array of URLs to try
 */
function buildKayiFamilyUrls(seriesSlug, season, absoluteEp) {
    const urls = [];
    
    // Pattern 1: Season-specific format (newer episodes)
    // e.g., /kurulus-osman-season-6-episode-193/
    urls.push(`https://kayifamily.com/${seriesSlug}-season-${season}-episode-${absoluteEp}/`);
    
    // Pattern 2: Absolute format (older episodes)
    // e.g., /kurulus-osman-episode-194-season-finale/
    urls.push(`https://kayifamily.com/${seriesSlug}-episode-${absoluteEp}/`);
    
    // Pattern 3: With english subtitles suffix
    urls.push(`https://kayifamily.com/${seriesSlug}-episode-${absoluteEp}-in-english-subtitles/`);
    
    // Pattern 4: Alternative naming for some series
    if (seriesSlug === 'payitaht-abdulhamid') {
        urls.push(`https://kayifamily.com/payitaht-sultan-abdul-hamid-season-${season}-episode-${absoluteEp}/`);
    }
    if (seriesSlug === 'salahuddin-ayyubi') {
        urls.push(`https://kayifamily.com/salahuddin-ayyubi-season-${season}-episode-${absoluteEp}/`);
    }
    
    return urls;
}

/**
 * Extract stream from vidara.to
 * @param {string} filecode - Vidara file code
 * @returns {Promise<Object|null>} - Stream info or null
 */
async function extractVidaraStream(filecode) {
    try {
        const apiUrl = `https://vidara.to/api/stream?filecode=${filecode}`;
        
        const response = await axios.get(apiUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': 'https://kayifamily.com/',
                'Accept': 'application/json'
            },
            timeout: 10000
        });
        
        if (response.data && response.data.streaming_url) {
            return {
                url: response.data.streaming_url,
                title: response.data.title || 'KayiFamily Stream',
                type: 'hls',
                source: 'kayifamily'
            };
        }
        
        return null;
    } catch (error) {
        console.error(`Vidara extraction error: ${error.message}`);
        return null;
    }
}

/**
 * Get stream from KayiFamily for a specific episode
 * @param {string} osmanSeriesSlug - Series slug from OsmanOnline (e.g., 'watch-kurulus-osman-with-english-subtitles')
 * @param {number} season - Season number
 * @param {number} episode - Episode number
 * @returns {Promise<Object|null>} - Stream info or null
 */
async function getKayiFamilyStream(osmanSeriesSlug, season, episode) {
    try {
        // Map OsmanOnline series name to KayiFamily format
        const kayiSeriesSlug = SERIES_NAME_MAP[osmanSeriesSlug];
        
        if (!kayiSeriesSlug) {
            console.log(`No KayiFamily mapping for series: ${osmanSeriesSlug}`);
            return null;
        }
        
        // Calculate absolute episode number
        const absoluteEp = toAbsoluteEpisode(kayiSeriesSlug, season, episode);
        
        console.log(`Looking for ${kayiSeriesSlug} S${season}E${episode} = absolute episode ${absoluteEp}`);
        
        // Build URLs to try
        const urls = buildKayiFamilyUrls(kayiSeriesSlug, season, absoluteEp);
        
        // Try each URL pattern
        for (const url of urls) {
            try {
                console.log(`Trying: ${url}`);
                
                const { data } = await axios.get(url, {
                    headers: {
                        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
                    },
                    timeout: 15000
                });
                
                const $ = cheerio.load(data);
                
                // Look for vidara.to iframe
                const vidaraIframe = $('iframe[src*="vidara.to"]').attr('src');
                if (vidaraIframe) {
                    console.log(`Found vidara iframe: ${vidaraIframe}`);
                    
                    // Extract filecode from URL like https://vidara.to/e/X2HoeUbaZlgUU
                    const filecodeMatch = vidaraIframe.match(/vidara\.to\/e\/([a-zA-Z0-9]+)/);
                    if (filecodeMatch) {
                        const filecode = filecodeMatch[1];
                        const streamInfo = await extractVidaraStream(filecode);
                        
                        if (streamInfo) {
                            console.log(`Successfully extracted KayiFamily stream`);
                            return streamInfo;
                        }
                    }
                }
                
                // Also check for other video sources
                const otherIframe = $('iframe[src*="kayihome.xyz"]').attr('src');
                if (otherIframe) {
                    console.log(`Found kayihome iframe (not implemented): ${otherIframe}`);
                    // Could implement kayihome extraction here if needed
                }
                
            } catch (error) {
                if (error.response && error.response.status === 404) {
                    console.log(`URL not found: ${url}`);
                } else {
                    console.log(`Error fetching ${url}: ${error.message}`);
                }
                continue; // Try next URL pattern
            }
        }
        
        console.log(`Could not find KayiFamily stream for ${kayiSeriesSlug} S${season}E${episode}`);
        return null;
        
    } catch (error) {
        console.error(`KayiFamily stream error: ${error.message}`);
        return null;
    }
}

module.exports = {
    getKayiFamilyStream,
    toAbsoluteEpisode,
    SERIES_NAME_MAP,
    SEASON_OFFSETS
};
