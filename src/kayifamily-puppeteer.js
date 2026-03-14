/**
 * Enhanced KayiFamily Stream Extractor with Puppeteer Fallback
 * 
 * This module extends the existing kayifamily.js with Puppeteer-based extraction
 * for sites that use devtools detection (like kayihome.xyz).
 */

const axios = require("axios");
const cheerio = require("cheerio");

// Enable debug logging
const DEBUG = process.env.DEBUG === 'true' || true;
function log(msg) {
    if (DEBUG) console.log(`[KayiFamily-Puppeteer] ${msg}`);
}

// Import the Puppeteer extractor (lazy loaded)
let puppeteerExtractor = null;

async function getPuppeteerExtractor() {
    if (!puppeteerExtractor) {
        const { extractM3U8FromKayihome } = require('./puppeteer-extractor');
        puppeteerExtractor = extractM3U8FromKayihome;
    }
    return puppeteerExtractor;
}

// Episode offset lookup for series with absolute numbering on KayiFamily
const SEASON_OFFSETS = {
    'kurulus-osman': {
        1: 0,   // Episodes 1-27
        2: 27,  // Episodes 28-64
        3: 64,  // Episodes 65-98
        4: 98,  // Episodes 99-130
        5: 130, // Episodes 131-164
        6: 164  // Episodes 165+
    },
    'kurulus-orhan': { 1: 0 },
    'mehmed-fetihler-sultani': { 1: 0, 2: 26, 3: 52 },
    'salahuddin-ayyubi': { 1: 0, 2: 30 },
    'mevlana-rumi': { 1: 0, 2: 18, 3: 36 },
    'alparslan-buyuk-selcuklu': { 1: 0, 2: 27 },
    'payitaht-abdulhamid': { 1: 0, 2: 26, 3: 52, 4: 78, 5: 104 },
    'destan': { 1: 0 },
    'dirilis-ertugrul': { 1: 0, 2: 76, 3: 126, 4: 176, 5: 226 },
    'barbaroslar-akdenizin-kilici': { 1: 0, 2: 31 },
    'uyanis-buyuk-selcuklu': { 1: 0, 2: 34 }
};

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

function toAbsoluteEpisode(seriesSlug, season, episode) {
    const offsets = SEASON_OFFSETS[seriesSlug];
    if (!offsets || !offsets[season]) {
        return (season - 1) * 100 + episode;
    }
    return offsets[season] + episode;
}

function buildKayiFamilyUrls(seriesSlug, season, absoluteEp, seasonEp) {
    const urls = [];
    
    // Pattern 1: Season-specific format with SEASON-RELATIVE episode number
    urls.push(`https://kayifamily.com/${seriesSlug}-season-${season}-episode-${seasonEp}/`);
    
    // Pattern 2: Season-specific format with ABSOLUTE episode number
    urls.push(`https://kayifamily.com/${seriesSlug}-season-${season}-episode-${absoluteEp}/`);
    
    // Pattern 3: Absolute format (older episodes)
    urls.push(`https://kayifamily.com/${seriesSlug}-episode-${absoluteEp}/`);
    
    // Pattern 4: With english subtitles suffix
    urls.push(`https://kayifamily.com/${seriesSlug}-episode-${absoluteEp}-in-english-subtitles/`);
    
    // Pattern 5: Season finale variant (absolute)
    urls.push(`https://kayifamily.com/${seriesSlug}-episode-${absoluteEp}-season-finale/`);
    
    // Pattern 6: Season finale variant (season-relative)
    urls.push(`https://kayifamily.com/${seriesSlug}-episode-${seasonEp}-season-finale/`);
    
    // Special cases
    if (seriesSlug === 'payitaht-abdulhamid') {
        urls.push(`https://kayifamily.com/payitaht-sultan-abdul-hamid-season-${season}-episode-${seasonEp}/`);
        urls.push(`https://kayifamily.com/payitaht-sultan-abdul-hamid-season-${season}-episode-${absoluteEp}/`);
    }
    
    return urls;
}

/**
 * Extract stream from bestb.stream iframe (direct extraction)
 */
async function extractBestbStream(iframeUrl) {
    try {
        log(`Extracting from bestb.stream: ${iframeUrl}`);
        
        const { data } = await axios.get(iframeUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': 'https://kayifamily.com/'
            },
            timeout: 10000
        });
        
        // Look for m3u8 URL
        const m3u8Match = data.match(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/);
        if (m3u8Match) {
            log(`Found M3U8 in bestb: ${m3u8Match[1]}`);
            return {
                url: m3u8Match[1],
                type: 'hls',
                source: 'kayifamily-bestb'
            };
        }
        
        // Look for video tag
        const videoMatch = data.match(/<video[^>]+src="([^"]+)"/);
        if (videoMatch) {
            log(`Found video src: ${videoMatch[1]}`);
            return {
                url: videoMatch[1],
                type: 'mp4',
                source: 'kayifamily-bestb'
            };
        }
        
        return null;
    } catch (error) {
        log(`Bestb extraction error: ${error.message}`);
        return null;
    }
}

/**
 * Extract stream from kayihome.xyz using Puppeteer (handles devtools detection)
 */
async function extractKayihomeStream(iframeUrl) {
    try {
        log(`Extracting from kayihome using Puppeteer: ${iframeUrl}`);
        
        const extractM3U8FromKayihome = await getPuppeteerExtractor();
        
        const streamUrl = await extractM3U8FromKayihome(iframeUrl, {
            timeout: 30000,
            headless: true,
            environment: process.env.RENDER ? 'render' : 'default'
        });
        
        if (streamUrl) {
            log(`Successfully extracted from kayihome: ${streamUrl}`);
            return {
                url: streamUrl,
                type: streamUrl.includes('.m3u8') ? 'hls' : 'mp4',
                source: 'kayifamily-kayihome-puppeteer'
            };
        }
        
        log('No stream found in kayihome page');
        return null;
    } catch (error) {
        log(`Kayihome extraction error: ${error.message}`);
        return null;
    }
}

/**
 * Extract stream from strmup.to iframe
 */
async function extractStrmupStream(iframeUrl) {
    try {
        log(`Extracting from strmup.to: ${iframeUrl}`);
        
        const { data, status } = await axios.get(iframeUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': 'https://kayifamily.com/',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
            },
            timeout: 10000,
            validateStatus: () => true
        });
        
        // Check for 521 error (Cloudflare origin down)
        if (status === 521 || data.includes('521') || data.includes('Web server is down')) {
            log('strmup.to server is down (521)');
            return null;
        }
        
        // Look for m3u8 URL
        const m3u8Match = data.match(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/);
        if (m3u8Match) {
            log(`Found m3u8: ${m3u8Match[1]}`);
            return {
                url: m3u8Match[1],
                type: 'hls',
                source: 'kayifamily-strmup'
            };
        }
        
        // Look for sources in JavaScript
        const sourcesMatch = data.match(/sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']/);
        if (sourcesMatch) {
            log(`Found sources in JS: ${sourcesMatch[1]}`);
            return {
                url: sourcesMatch[1],
                type: 'hls',
                source: 'kayifamily-strmup'
            };
        }
        
        return null;
    } catch (error) {
        log(`Strmup extraction error: ${error.message}`);
        return null;
    }
}

/**
 * Main function to get stream from KayiFamily with Puppeteer support
 */
async function getKayiFamilyStreamWithPuppeteer(osmanSeriesSlug, season, episode) {
    try {
        const kayiSeriesSlug = SERIES_NAME_MAP[osmanSeriesSlug];
        
        if (!kayiSeriesSlug) {
            log(`No KayiFamily mapping for series: ${osmanSeriesSlug}`);
            return null;
        }
        
        const absoluteEp = toAbsoluteEpisode(kayiSeriesSlug, season, episode);
        
        log(`Looking for ${kayiSeriesSlug} S${season}E${episode} (absolute: ${absoluteEp})`);
        
        const urls = buildKayiFamilyUrls(kayiSeriesSlug, season, absoluteEp, episode);
        
        for (const url of urls) {
            try {
                log(`Trying: ${url}`);
                
                const { data, status } = await axios.get(url, {
                    headers: {
                        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
                    },
                    timeout: 15000,
                    validateStatus: () => true
                });
                
                if (status !== 200) {
                    log(`URL returned status ${status}`);
                    continue;
                }
                
                // Check for video content
                const hasVideoContent = data.includes('bestb.stream') || 
                                       data.includes('kayihome.xyz') || 
                                       data.includes('vidara.to') ||
                                       data.includes('strmup.to');
                
                if (!hasVideoContent) {
                    log('No video content found on page');
                    continue;
                }
                
                const $ = cheerio.load(data);
                const title = $('title').text();
                log(`Page title: ${title}`);
                
                // Check for bestb.stream iframe (primary source - works with axios)
                const bestbIframe = $('iframe[src*="bestb.stream"]').attr('src');
                if (bestbIframe) {
                    log(`Found bestb.stream iframe: ${bestbIframe}`);
                    const stream = await extractBestbStream(bestbIframe);
                    if (stream) {
                        log('Successfully extracted from bestb.stream');
                        return stream;
                    }
                }
                
                // Check for kayihome.xyz iframe (use Puppeteer for devtools detection)
                const kayihomeIframe = $('iframe[src*="kayihome.xyz"]').attr('src');
                if (kayihomeIframe) {
                    log(`Found kayihome.xyz iframe: ${kayihomeIframe}`);
                    
                    // Check if Puppeteer is enabled
                    const usePuppeteer = process.env.USE_PUPPETEER === 'true';
                    
                    if (usePuppeteer) {
                        const stream = await extractKayihomeStream(kayihomeIframe);
                        if (stream) {
                            log('Successfully extracted from kayihome.xyz using Puppeteer');
                            return stream;
                        }
                    } else {
                        log('Puppeteer not enabled, skipping kayihome.xyz');
                    }
                }
                
                // Check for strmup.to
                const strmupIframe = $('iframe[src*="strmup.to"]').attr('src');
                if (strmupIframe) {
                    log(`Found strmup.to iframe: ${strmupIframe}`);
                    const stream = await extractStrmupStream(strmupIframe);
                    if (stream) {
                        log('Successfully extracted from strmup.to');
                        return stream;
                    }
                }
                
            } catch (error) {
                log(`Error fetching ${url}: ${error.message}`);
                continue;
            }
        }
        
        log(`Could not find KayiFamily stream for ${kayiSeriesSlug} S${season}E${episode}`);
        return null;
        
    } catch (error) {
        log(`KayiFamily stream error: ${error.message}`);
        return null;
    }
}

module.exports = {
    getKayiFamilyStreamWithPuppeteer,
    extractKayihomeStream,
    extractBestbStream,
    extractStrmupStream,
    SERIES_NAME_MAP,
    SEASON_OFFSETS
};
