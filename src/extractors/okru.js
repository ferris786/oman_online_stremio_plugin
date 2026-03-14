/**
 * OK.ru (Odnoklassniki) Video Extractor
 * 
 * Extracts direct video URLs from OK.ru embed pages
 * Based on research of OK.ru video player structure
 */

const axios = require('axios');
const cheerio = require('cheerio');

const DEBUG = process.env.DEBUG === 'true';
function log(msg) {
    if (DEBUG) console.log(`[OK.ru] ${msg}`);
}

class OkRuExtractor {
    constructor() {
        this.baseUrl = 'https://ok.ru';
        this.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Accept-Encoding': 'gzip, deflate, br',
            'DNT': '1',
            'Connection': 'keep-alive',
            'Upgrade-Insecure-Requests': '1'
        };
    }

    /**
     * Parse video ID from various OK.ru URL formats
     */
    parseVideoId(url) {
        const patterns = [
            /ok\.ru\/videoembed\/(\d+)/,
            /ok\.ru\/video\/(\d+)/,
            /ok\.ru\/video\/[a-z]+\/(\d+)/
        ];

        for (const pattern of patterns) {
            const match = url.match(pattern);
            if (match) {
                return match[1];
            }
        }
        return null;
    }

    /**
     * Extract video information from OK.ru video ID
     */
    async extract(videoId) {
        try {
            log(`Extracting video ID: ${videoId}`);
            
            const embedUrl = `${this.baseUrl}/videoembed/${videoId}`;
            const response = await axios.get(embedUrl, { 
                headers: this.headers,
                timeout: 15000,
                validateStatus: (status) => status === 200
            });

            const $ = cheerio.load(response.data);
            
            // Find the data-options div
            const dataDiv = $('div[data-options]').first();
            
            if (!dataDiv.length) {
                log('No data-options div found - video may be private or deleted');
                return null;
            }

            // Parse the data-options JSON
            const dataOptionsStr = dataDiv.attr('data-options');
            let dataOptions;
            
            try {
                dataOptions = JSON.parse(dataOptionsStr);
            } catch (e) {
                log(`Failed to parse data-options: ${e.message}`);
                return null;
            }

            // Extract metadata from flashvars
            if (!dataOptions.flashvars || !dataOptions.flashvars.metadata) {
                log('No flashvars.metadata found');
                return null;
            }

            let metadata;
            try {
                metadata = JSON.parse(dataOptions.flashvars.metadata);
            } catch (e) {
                log(`Failed to parse metadata: ${e.message}`);
                return null;
            }

            // Build result object
            const result = {
                id: videoId,
                title: metadata.movie?.title || 'Unknown',
                duration: parseInt(metadata.movie?.duration) || 0,
                thumbnail: metadata.movie?.poster || null,
                isLive: metadata.movie?.isLive || false,
                videos: [],
                hlsUrl: metadata.hlsManifestUrl || null,
                dashManifest: metadata.movie?.contentId || null
            };

            // Extract video URLs by quality
            if (metadata.videos && Array.isArray(metadata.videos)) {
                result.videos = metadata.videos.map(v => ({
                    quality: v.name, // mobile, lowest, low, sd, hd, full
                    url: v.url,
                    seeking: v.seekSchema || 0,
                    disallowed: v.disallowed || false
                }));
            }

            log(`Found ${result.videos.length} quality options`);
            return result;

        } catch (error) {
            log(`Extraction failed: ${error.message}`);
            return null;
        }
    }

    /**
     * Get the best available quality URL
     */
    getBestQuality(videoData, preferredQuality = 'hd') {
        if (!videoData || !videoData.videos || videoData.videos.length === 0) {
            return null;
        }

        const qualityOrder = ['full', 'hd', 'sd', 'low', 'lowest', 'mobile'];
        
        // If preferred quality exists, use it
        const preferred = videoData.videos.find(v => v.quality === preferredQuality && !v.disallowed);
        if (preferred) {
            return preferred.url;
        }

        // Otherwise find best available
        for (const quality of qualityOrder) {
            const video = videoData.videos.find(v => v.quality === quality && !v.disallowed);
            if (video) {
                return video.url;
            }
        }

        // Fallback to first available
        const firstAvailable = videoData.videos.find(v => !v.disallowed);
        return firstAvailable ? firstAvailable.url : null;
    }

    /**
     * Get stream in Stremio format
     */
    async getStremioStream(videoId, preferredQuality = 'hd') {
        const videoData = await this.extract(videoId);
        
        if (!videoData) {
            return null;
        }

        const streamUrl = this.getBestQuality(videoData, preferredQuality);
        
        if (!streamUrl) {
            log('No suitable stream URL found');
            return null;
        }

        return {
            name: 'OK.ru',
            title: `OK.ru - ${videoData.title} (${preferredQuality})`,
            url: streamUrl,
            behaviorHints: {
                notWebReady: false,
                proxyHeaders: {
                    request: {
                        'User-Agent': this.headers['User-Agent'],
                        'Referer': `${this.baseUrl}/`,
                        'Accept': '*/*',
                        'Accept-Language': 'en-US,en;q=0.9'
                    }
                }
            }
        };
    }

    /**
     * Extract from a kayifamilytv-style page that has OK.ru iframe
     */
    async extractFromPage(pageUrl) {
        try {
            log(`Fetching page: ${pageUrl}`);
            
            const response = await axios.get(pageUrl, {
                headers: this.headers,
                timeout: 15000
            });

            const $ = cheerio.load(response.data);
            
            // Look for OK.ru iframe
            const okruIframe = $('iframe[src*="ok.ru"]').attr('src');
            
            if (!okruIframe) {
                log('No OK.ru iframe found on page');
                return null;
            }

            log(`Found OK.ru iframe: ${okruIframe}`);
            
            const videoId = this.parseVideoId(okruIframe);
            if (!videoId) {
                log('Could not parse video ID from iframe');
                return null;
            }

            return await this.extract(videoId);

        } catch (error) {
            log(`Page extraction failed: ${error.message}`);
            return null;
        }
    }
}

// Export singleton instance
module.exports = new OkRuExtractor();
module.exports.OkRuExtractor = OkRuExtractor;
