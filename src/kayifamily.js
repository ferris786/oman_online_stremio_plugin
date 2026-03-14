const axios = require("axios");
const cheerio = require("cheerio");

// Enable debug logging
const DEBUG = true;
function log(msg) {
    if (DEBUG) console.log(`[KayiFamily] ${msg}`);
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
    'dirilis-ertugrul': { 1: 0, 2: 76, 3: 126, 4: 176, 5: 226 }, // Approximate
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
    
    // IMPORTANT: KayiFamily uses DIFFERENT numbering for different series/seasons
    // Recent episodes (Season 4+ typically) use SEASON-RELATIVE numbers
    // Older episodes use ABSOLUTE numbers
    
    // Pattern 1: Season-specific format with SEASON-RELATIVE episode number
    // This works for recent episodes (e.g., /kurulus-osman-season-6-episode-193/)
    urls.push(`https://kayifamily.com/${seriesSlug}-season-${season}-episode-${seasonEp}/`);
    
    // Pattern 2: Season-specific format with ABSOLUTE episode number
    // Fallback for some series
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
 * Extract stream from bestb.stream iframe
 * These are direct video URLs that might need proxying
 */
async function extractBestbStream(iframeUrl) {
    try {
        log(`Extracting from bestb.stream: ${iframeUrl}`);
        
        // Fetch the iframe page
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
        
        log('No stream found in bestb page');
        return null;
    } catch (error) {
        log(`Bestb extraction error: ${error.message}`);
        return null;
    }
}

/**
 * Extract stream from kayihome.xyz iframe
 * 
 * RESEARCH FINDINGS (March 2026):
 * 
 * Turktvuk vs Kayihome - Key Differences:
 * 
 * 1. API Approach:
 *    - Turktvuk: POST /player/index.php?data=HASH&do=getVideo returns JSON
 *    - Kayihome: GET /player/api.php?data=HASH&do=getVideo returns "url is empty"
 * 
 * 2. Video Data Location:
 *    - Turktvuk: API response contains videoSource, securedLink, ck key
 *    - Kayihome: Video URL embedded directly in player page HTML in fireload() function
 * 
 * 3. URL Structure:
 *    - Turktvuk: https://turktvuk.com/cdn/hls/HASH/master.m3u8?md5=...&expires=...
 *    - Kayihome: https://{host}.xyz/cdn/hls/HASH/master.txt
 *      Hosts: reabc.xyz, aeabc.xyz, eeabc.xyz, keabc.xyz, teabc.xyz
 * 
 * 4. Authentication:
 *    - Turktvuk: Requires cookies (fireplayer_player), POST with hash & referer
 *    - Kayihome: No cookies needed, direct URL from HTML
 * 
 * Why Kayihome API returns "url is empty":
 * - The API endpoint is either legacy or not implemented
 * - Video data is server-side rendered into the HTML page
 * - Client-side JavaScript (FirePlayer) reads the embedded config
 */
async function extractKayihomeStream(iframeUrl) {
    try {
        log(`Extracting from kayihome: ${iframeUrl}`);
        
        // Extract data parameter
        const dataMatch = iframeUrl.match(/data=([a-f0-9]+)/);
        if (!dataMatch) {
            log('No data parameter found in kayihome URL');
            return null;
        }
        
        const dataParam = dataMatch[1];
        
        // METHOD 1: Parse HTML page source (PRIMARY - This works!)
        // Kayihome embeds video config in the fireload() function
        log('Trying HTML parsing method...');
        
        try {
            const playerUrl = `https://kayihome.xyz/player/index.php?data=${dataParam}`;
            const { data: html } = await axios.get(playerUrl, {
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                    'Referer': 'https://kayifamily.com/'
                },
                timeout: 10000,
                validateStatus: () => true
            });
            
            // Check if page actually returned a 404 or no_video page (not just in JS code)
            // Note: no_video.html appears in devtools detection script, so check title or actual content
            const titleMatch = html.match(/<title>([^<]+)/i);
            const pageTitle = titleMatch ? titleMatch[1].toLowerCase() : '';
            
            if (html.includes('404 Not Found') || 
                pageTitle.includes('404') || 
                pageTitle.includes('not found') ||
                (html.includes('no_video.html') && html.includes('No Video Available')) ||
                html.includes('<h1>No Video Found</h1>')) {
                log('Kayihome player shows no video available');
                return null;
            }
            
            // Extract videoUrl from fireload function
            // Format: "videoUrl":"\/cdn\/hls\/HASH\/master.txt"
            const videoUrlMatch = html.match(/"videoUrl"\s*:\s*"([^"]+)"/);
            if (videoUrlMatch) {
                const videoUrl = videoUrlMatch[1].replace(/\\\//g, '/');
                log(`Found videoUrl: ${videoUrl}`);
                
                // Extract hostList for available hosts
                // Format: "hostList":{"1":["reabc.xyz","aeabc.xyz",...]}
                const hostListMatch = html.match(/"hostList"\s*:\s*\{[^}]+"1"\s*:\s*\[([^\]]+)\]/);
                const hosts = [];
                if (hostListMatch) {
                    const hostMatches = hostListMatch[1].match(/"([^"]+\.xyz)"/g);
                    if (hostMatches) {
                        hostMatches.forEach(h => {
                            hosts.push(h.replace(/"/g, ''));
                        });
                    }
                }
                
                // Default hosts if parsing fails
                if (hosts.length === 0) {
                    hosts.push('reabc.xyz', 'aeabc.xyz', 'eeabc.xyz', 'keabc.xyz', 'teabc.xyz');
                }
                log(`Available hosts: ${hosts.join(', ')}`);
                
                // Construct full URL using first host
                const fullUrl = `https://${hosts[0]}${videoUrl}`;
                log(`Returning stream URL: ${fullUrl}`);
                
                return {
                    url: fullUrl,
                    type: 'hls',
                    source: 'kayifamily-kayihome'
                };
            }
        } catch (htmlError) {
            log(`HTML parsing failed: ${htmlError.message}`);
        }
        
        // METHOD 2: Try API (FALLBACK - Likely won't work)
        log('Trying API method (fallback)...');
        
        try {
            const apiUrl = `https://kayihome.xyz/player/api.php?data=${dataParam}&do=getVideo`;
            const response = await axios.post(apiUrl,
                new URLSearchParams({
                    hash: dataParam,
                    r: 'https://kayifamily.com/'
                }), {
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                    'X-Requested-With': 'XMLHttpRequest',
                    'Origin': 'https://kayihome.xyz',
                    'Referer': iframeUrl
                },
                timeout: 10000
            });
            
            log(`API response: ${JSON.stringify(response.data).substring(0, 200)}`);
            
            // Check if response contains video data
            if (response.data) {
                if (typeof response.data === 'object') {
                    if (response.data.videoSource || response.data.securedLink) {
                        const streamUrl = response.data.securedLink || response.data.videoSource;
                        log(`Found video URL from API: ${streamUrl}`);
                        return {
                            url: streamUrl,
                            type: response.data.hls ? 'hls' : 'mp4',
                            source: 'kayifamily-kayihome'
                        };
                    }
                }
                
                if (typeof response.data === 'string' && response.data.startsWith('http')) {
                    log(`Found direct URL from API: ${response.data}`);
                    return {
                        url: response.data,
                        type: 'hls',
                        source: 'kayifamily-kayihome'
                    };
                }
            }
        } catch (apiError) {
            log(`API request failed: ${apiError.message}`);
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
 * strmup.to is similar to bestb.stream - iframe contains direct m3u8
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
        
        // Look for video tag source
        const videoMatch = data.match(/<video[^>]+src=["']([^"']+)["']/);
        if (videoMatch) {
            log(`Found video src: ${videoMatch[1]}`);
            return {
                url: videoMatch[1],
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
        
        log('No stream found in strmup.to page');
        return null;
        
    } catch (error) {
        log(`Strmup extraction error: ${error.message}`);
        return null;
    }
}

async function getKayiFamilyStream(osmanSeriesSlug, season, episode) {
    try {
        const kayiSeriesSlug = SERIES_NAME_MAP[osmanSeriesSlug];
        
        if (!kayiSeriesSlug) {
            log(`No KayiFamily mapping for series: ${osmanSeriesSlug}`);
            return null;
        }
        
        const absoluteEp = toAbsoluteEpisode(kayiSeriesSlug, season, episode);
        
        log(`Looking for ${kayiSeriesSlug} S${season}E${episode} (absolute: ${absoluteEp})`);
        
        // Pass both absolute and season-relative episode numbers
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
                
                // Check if this is a valid episode page by looking for video iframes
                // Note: Don't rely on "404" text as it appears in sidebars/footers of valid pages too
                const hasVideoContent = data.includes('bestb.stream') || 
                                       data.includes('kayihome.xyz') || 
                                       data.includes('vidara.to') ||
                                       data.includes('strmup.to');
                
                if (!hasVideoContent) {
                    log('No video content found on page (404 or no video)');
                    continue;
                }
                
                const $ = cheerio.load(data);
                const title = $('title').text();
                log(`Page title: ${title}`);
                
                // Check for valid episode page
                if (!title.toLowerCase().includes('episode') && !title.toLowerCase().includes(kayiSeriesSlug.replace(/-/g, ' '))) {
                    log('Not a valid episode page');
                    continue;
                }
                
                // Look for bestb.stream iframe (primary source)
                const bestbIframe = $('iframe[src*="bestb.stream"]').attr('src');
                if (bestbIframe) {
                    log(`Found bestb.stream iframe: ${bestbIframe}`);
                    const stream = await extractBestbStream(bestbIframe);
                    if (stream) {
                        log('Successfully extracted from bestb.stream');
                        return stream;
                    }
                }
                
                // Look for kayihome.xyz iframe (secondary source)
                const kayihomeIframe = $('iframe[src*="kayihome.xyz"]').attr('src');
                if (kayihomeIframe) {
                    log(`Found kayihome.xyz iframe: ${kayihomeIframe}`);
                    const stream = await extractKayihomeStream(kayihomeIframe);
                    if (stream) {
                        log('Successfully extracted from kayihome.xyz');
                        return stream;
                    }
                }
                
                // Look for strmup.to (newer CDN)
                const strmupIframe = $('iframe[src*="strmup.to"]').attr('src');
                if (strmupIframe) {
                    log(`Found strmup.to iframe: ${strmupIframe}`);
                    const stream = await extractStrmupStream(strmupIframe);
                    if (stream) {
                        log('Successfully extracted from strmup.to');
                        return stream;
                    }
                }
                
                // Look for vidara.to (legacy, might still exist on some pages)
                const vidaraIframe = $('iframe[src*="vidara.to"]').attr('src');
                if (vidaraIframe) {
                    log(`Found vidara.to iframe (legacy): ${vidaraIframe}`);
                    const filecodeMatch = vidaraIframe.match(/vidara\.to\/e\/([a-zA-Z0-9]+)/);
                    if (filecodeMatch) {
                        const apiUrl = `https://vidara.to/api/stream?filecode=${filecodeMatch[1]}`;
                        try {
                            const response = await axios.get(apiUrl, {
                                headers: {
                                    'User-Agent': 'Mozilla/5.0',
                                    'Referer': 'https://kayifamily.com/'
                                },
                                timeout: 10000
                            });
                            if (response.data && response.data.streaming_url) {
                                return {
                                    url: response.data.streaming_url,
                                    type: 'hls',
                                    source: 'kayifamily-vidara'
                                };
                            }
                        } catch (e) {
                            log(`Vidara API error: ${e.message}`);
                        }
                    }
                }
                
                log('No video iframe found on this page');
                
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
    getKayiFamilyStream,
    toAbsoluteEpisode,
    SERIES_NAME_MAP,
    SEASON_OFFSETS
};
