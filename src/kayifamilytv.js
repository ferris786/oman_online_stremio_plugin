const axios = require("axios");
const cheerio = require("cheerio");

const DEBUG = true;
function log(msg) {
    if (DEBUG) console.log(`[KayiFamilyTV] ${msg}`);
}

// Series name mapping (OsmanOnline slug -> KayiFamilyTV slug)
const SERIES_NAME_MAP = {
    'watch-mehmed-fetihler-sultani-with-english-subtitles': 'mehmed-fetihler-sultani',
    'watch-kurulus-osman-with-english-subtitles': 'kurulus-osman',
    'watch-kurulus-orhan-with-english-subtitles': 'kurulus-orhan',
    'watch-salahuddin-ayyubi-with-english-subtitles': 'salahuddin-ayyubi',
    'watch-kudus-fatihi-selahaddin-eyyubi-with-english-subtitles': 'salahuddin-ayyubi',
    'watch-payitaht-abdulhamid-with-english-subtitles': 'payitaht-abdulhamid',
    'watch-dirilis-ertugrul-with-english-subtitles': 'dirilis-ertugrul',
    'watch-alparslan-buyuk-selcuklu-with-english-subtitles': 'alparslan-buyuk-selcuklu',
    'watch-destan-with-english-subtitles': 'destan',
    'watch-mevlana-rumi-with-english-subtitles': 'mevlana-rumi',
    'watch-rumi-with-english-subtitles': 'mevlana-rumi'
};

/**
 * Extract video from Ok.ru embed
 * Ok.ru provides JSON metadata in data-options attribute
 */
async function extractOkRu(videoId) {
    const embedUrl = `https://ok.ru/videoembed/${videoId}?nochat=1`;
    log(`Extracting from Ok.ru: ${embedUrl}`);
    
    try {
        const { data } = await axios.get(embedUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Referer': 'https://kayifamilytv.com/'
            },
            timeout: 20000
        });
        
        // Extract data-options JSON
        const dataOptionsMatch = data.match(/data-options="([^"]+)"/);
        if (!dataOptionsMatch) {
            log('No data-options found');
            return null;
        }
        
        // Decode HTML entities and parse JSON
        const jsonStr = dataOptionsMatch[1].replace(/&quot;/g, '"');
        const options = JSON.parse(jsonStr);
        
        if (!options.flashvars || !options.flashvars.metadata) {
            log('No metadata in data-options');
            return null;
        }
        
        const metadata = JSON.parse(options.flashvars.metadata);
        
        if (!metadata.videos || metadata.videos.length === 0) {
            log('No videos in metadata');
            return null;
        }
        
        // Get best quality (last in array: mobile, lowest, low, sd, hd, full)
        const best = metadata.videos[metadata.videos.length - 1];
        log(`Found quality: ${best.name}`);
        
        return {
            url: best.url,
            title: metadata.movie?.title || 'Unknown',
            quality: best.name,
            type: 'mp4'
        };
        
    } catch (error) {
        log(`Ok.ru extraction error: ${error.message}`);
        return null;
    }
}

/**
 * Get stream from KayiFamilyTV
 */
async function getKayiFamilyTVStream(osmanSeriesSlug, season, episode) {
    try {
        const seriesSlug = SERIES_NAME_MAP[osmanSeriesSlug];
        
        if (!seriesSlug) {
            log(`No mapping for series: ${osmanSeriesSlug}`);
            return null;
        }
        
        // Build URL - KayiFamilyTV uses episode-{number} format (no trailing slash)
        const episodeUrl = `https://kayifamilytv.com/v18/${seriesSlug}-episode-${episode}`;
        log(`Fetching: ${episodeUrl}`);
        
        const { data } = await axios.get(episodeUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
                'Accept-Language': 'en-US,en;q=0.5'
            },
            timeout: 20000
        });
        
        // Check if page is valid (check title, not content which may have '404' in scripts)
        const titleMatch = data.match(/<title>([^<]+)/i);
        const pageTitle = titleMatch ? titleMatch[1].toLowerCase() : '';
        
        if (pageTitle.includes('404') || pageTitle.includes('not found') || pageTitle.includes('error')) {
            log('Page not found (based on title)');
            return null;
        }
        
        // Extract Ok.ru video IDs
        const okruMatches = data.match(/ok\.ru\/videoembed\/(\d+)/g);
        
        if (!okruMatches || okruMatches.length === 0) {
            log('No Ok.ru videos found');
            return null;
        }
        
        log(`Found ${okruMatches.length} Ok.ru embed(s)`);
        
        // Try each Ok.ru video (usually multiple qualities or mirrors)
        for (const match of okruMatches) {
            const videoId = match.match(/(\d+)$/)[1];
            const stream = await extractOkRu(videoId);
            
            if (stream) {
                log(`Successfully extracted from Ok.ru: ${videoId}`);
                return {
                    url: stream.url,
                    type: 'mp4',
                    source: 'kayifamilytv-okru',
                    quality: stream.quality,
                    title: stream.title
                };
            }
        }
        
        log('Failed to extract from all Ok.ru videos');
        return null;
        
    } catch (error) {
        log(`KayiFamilyTV error: ${error.message}`);
        return null;
    }
}

module.exports = {
    getKayiFamilyTVStream,
    extractOkRu,
    SERIES_NAME_MAP
};
