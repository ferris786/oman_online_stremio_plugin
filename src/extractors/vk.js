/**
 * VK.com (VKontakte) Video Extractor
 * 
 * Extracts direct video URLs from VK.com embed pages
 * Based on research of VK video player structure
 */

const axios = require('axios');
const cheerio = require('cheerio');

const DEBUG = process.env.DEBUG === 'true';
function log(msg) {
    if (DEBUG) console.log(`[VK] ${msg}`);
}

class VkExtractor {
    constructor() {
        this.baseUrl = 'https://vk.com';
        this.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Accept-Encoding': 'gzip, deflate, br',
            'Referer': 'https://vk.com/',
            'DNT': '1',
            'Connection': 'keep-alive',
            'Upgrade-Insecure-Requests': '1'
        };
    }

    /**
     * Parse VK video URL to extract owner_id and video_id
     */
    parseVideoUrl(url) {
        const patterns = [
            // Standard video URL: vk.com/video-22822305_456242110
            /video(-?\d+)_(\d+)/,
            // Embed URL: vk.com/video_ext.php?oid=-22822305&id=456242110&hash=...
            /oid=(-?\d+)[^&]*&id=(\d+)/,
            // VK Video URL: vkvideo.ru/video-22822305_456242110
            /vkvideo\.ru\/video(-?\d+)_(\d+)/
        ];

        for (const pattern of patterns) {
            const match = url.match(pattern);
            if (match) {
                return {
                    ownerId: match[1],
                    videoId: match[2]
                };
            }
        }
        return null;
    }

    /**
     * Extract video from VK embed page
     */
    async extract(ownerId, videoId, hash = '') {
        try {
            log(`Extracting video: oid=${ownerId}, id=${videoId}`);

            // Build embed URL
            let embedUrl;
            if (hash) {
                embedUrl = `${this.baseUrl}/video_ext.php?oid=${ownerId}&id=${videoId}&hash=${hash}`;
            } else {
                embedUrl = `${this.baseUrl}/video_ext.php?oid=${ownerId}&id=${videoId}`;
            }

            log(`Fetching embed: ${embedUrl}`);

            const response = await axios.get(embedUrl, {
                headers: this.headers,
                timeout: 15000,
                validateStatus: (status) => status === 200 || status === 404
            });

            if (response.status === 404) {
                log('Video not found (404)');
                return null;
            }

            // Try multiple extraction methods
            return await this._extractFromHtml(response.data, ownerId, videoId);

        } catch (error) {
            log(`Extraction failed: ${error.message}`);
            return null;
        }
    }

    /**
     * Extract video data from HTML using multiple methods
     */
    async _extractFromHtml(html, ownerId, videoId) {
        // Method 1: Look for video tag with src
        const videoTagMatch = html.match(/<video[^>]+src=["']([^"']+)["']/);
        if (videoTagMatch) {
            log('Found video tag with src');
            return {
                ownerId,
                videoId,
                url: videoTagMatch[1],
                type: 'direct',
                qualities: {}
            };
        }

        // Method 2: Look for og:video meta tag
        const ogVideoMatch = html.match(/<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']/);
        if (ogVideoMatch) {
            log('Found og:video meta tag');
            return {
                ownerId,
                videoId,
                url: ogVideoMatch[1],
                type: 'og',
                qualities: {}
            };
        }

        // Method 3: Extract from playerParams JavaScript object
        const playerParamsMatch = html.match(/var\s+playerParams\s*=\s*({[\s\S]+?});/);
        if (playerParamsMatch) {
            log('Found playerParams');
            try {
                // Clean up the JavaScript object to make it valid JSON
                let jsonStr = playerParamsMatch[1];
                
                // Replace single quotes with double quotes
                jsonStr = jsonStr.replace(/'/g, '"');
                
                // Add quotes to unquoted keys
                jsonStr = jsonStr.replace(/([{,]\s*)(\w+):/g, '$1"$2":');
                
                // Remove trailing commas
                jsonStr = jsonStr.replace(/,\s*([}])/g, '$1');
                
                const playerParams = JSON.parse(jsonStr);
                return this._parsePlayerParams(playerParams, ownerId, videoId);
            } catch (e) {
                log(`Failed to parse playerParams: ${e.message}`);
            }
        }

        // Method 4: Extract from flashvars
        const flashvarsMatch = html.match(/flashvars\s*=\s*({[\s\S]+?});/);
        if (flashvarsMatch) {
            log('Found flashvars');
            try {
                let jsonStr = flashvarsMatch[1];
                jsonStr = jsonStr.replace(/'/g, '"');
                jsonStr = jsonStr.replace(/([{,]\s*)(\w+):/g, '$1"$2":');
                const flashvars = JSON.parse(jsonStr);
                
                if (flashvars.url || flashvars.url720) {
                    return this._parsePlayerParams(flashvars, ownerId, videoId);
                }
            } catch (e) {
                log(`Failed to parse flashvars: ${e.message}`);
            }
        }

        // Method 5: Look for vkvd.okcdn.ru MP4 URLs directly
        const mp4Matches = html.match(/https:\/\/vkvd\d+\.okcdn\.ru\/[^\s"'<>]+\.mp4[^\s"'<>]*/g);
        if (mp4Matches && mp4Matches.length > 0) {
            log(`Found ${mp4Matches.length} direct MP4 URLs`);
            
            // Try to organize by quality
            const qualities = {};
            mp4Matches.forEach(url => {
                if (url.includes('type=3') || url.includes('1080')) {
                    qualities['1080p'] = url;
                } else if (url.includes('type=2') || url.includes('720')) {
                    qualities['720p'] = url;
                } else if (url.includes('type=1') || url.includes('480')) {
                    qualities['480p'] = url;
                } else if (url.includes('type=0') || url.includes('360')) {
                    qualities['360p'] = url;
                } else {
                    qualities['unknown'] = url;
                }
            });

            return {
                ownerId,
                videoId,
                url: mp4Matches[0],
                type: 'scraped',
                qualities
            };
        }

        // Method 6: Look for HLS manifest
        const hlsMatch = html.match(/(https:\/\/vkvd\d+\.okcdn\.ru\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/);
        if (hlsMatch) {
            log('Found HLS manifest');
            return {
                ownerId,
                videoId,
                url: hlsMatch[1],
                type: 'hls',
                qualities: {}
            };
        }

        log('No video data found in HTML');
        return null;
    }

    /**
     * Parse VK playerParams object
     */
    _parsePlayerParams(params, ownerId, videoId) {
        const qualities = {};
        
        // Map of quality keys to labels
        const qualityMap = {
            'url1080': '1080p',
            'url720': '720p',
            'url480': '480p',
            'url360': '360p',
            'url240': '240p',
            'url144': '144p'
        };

        for (const [key, label] of Object.entries(qualityMap)) {
            if (params[key]) {
                qualities[label] = params[key];
            }
        }

        // Determine best URL
        let bestUrl = null;
        const qualityOrder = ['url1080', 'url720', 'url480', 'url360', 'url240', 'url144', 'url'];
        for (const key of qualityOrder) {
            if (params[key]) {
                bestUrl = params[key];
                break;
            }
        }

        return {
            ownerId,
            videoId,
            title: params.title || params.video_title,
            duration: params.duration,
            thumbnail: params.thumb || params.jpg,
            url: bestUrl,
            hlsUrl: params.hls,
            dashUrl: params.dash,
            type: params.hls ? 'hls' : 'mp4',
            qualities
        };
    }

    /**
     * Get best quality URL from extracted data
     */
    getBestQuality(videoData, preferredQuality = '720p') {
        if (!videoData) return null;

        // If we have organized qualities
        if (videoData.qualities && videoData.qualities[preferredQuality]) {
            return videoData.qualities[preferredQuality];
        }

        // Quality preference order
        const qualityOrder = ['1080p', '720p', '480p', '360p', '240p', '144p', 'unknown'];
        
        if (videoData.qualities) {
            for (const quality of qualityOrder) {
                if (videoData.qualities[quality]) {
                    return videoData.qualities[quality];
                }
            }
        }

        // Fallback to main URL
        return videoData.url;
    }

    /**
     * Get stream in Stremio format
     */
    async getStremioStream(ownerId, videoId, hash = '', preferredQuality = '720p') {
        const videoData = await this.extract(ownerId, videoId, hash);
        
        if (!videoData) {
            return null;
        }

        const streamUrl = this.getBestQuality(videoData, preferredQuality);
        
        if (!streamUrl) {
            log('No suitable stream URL found');
            return null;
        }

        return {
            name: 'VK.com',
            title: `VK.com - ${videoData.title || 'Video'} (${preferredQuality})`,
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
     * Extract from a page that contains VK embed iframe
     */
    async extractFromPage(pageUrl) {
        try {
            log(`Fetching page: ${pageUrl}`);
            
            const response = await axios.get(pageUrl, {
                headers: {
                    ...this.headers,
                    'Referer': 'https://kayifamily.com/'
                },
                timeout: 15000
            });

            const $ = cheerio.load(response.data);
            
            // Look for VK iframe
            const vkIframe = $('iframe[src*="vk.com"], iframe[src*="vkvideo.ru"]').attr('src');
            
            if (!vkIframe) {
                log('No VK iframe found on page');
                return null;
            }

            log(`Found VK iframe: ${vkIframe}`);
            
            const parsed = this.parseVideoUrl(vkIframe);
            if (!parsed) {
                log('Could not parse video info from iframe');
                return null;
            }

            // Try to extract hash from iframe URL
            const hashMatch = vkIframe.match(/hash=([a-f0-9]+)/);
            const hash = hashMatch ? hashMatch[1] : '';

            return await this.extract(parsed.ownerId, parsed.videoId, hash);

        } catch (error) {
            log(`Page extraction failed: ${error.message}`);
            return null;
        }
    }

    /**
     * Extract using VK API (requires access token)
     */
    async extractWithApi(ownerId, videoId, accessToken) {
        try {
            log(`Using VK API for ${ownerId}_${videoId}`);
            
            const apiUrl = 'https://api.vk.com/method/video.get';
            const params = {
                v: '5.131',
                access_token: accessToken,
                owner_id: ownerId,
                videos: `${ownerId}_${videoId}`,
                extended: 1
            };

            const response = await axios.get(apiUrl, { 
                params, 
                timeout: 10000 
            });

            if (response.data.error) {
                log(`API error: ${JSON.stringify(response.data.error)}`);
                return null;
            }

            const video = response.data.response.items[0];
            if (!video) {
                log('No video found in API response');
                return null;
            }

            return {
                ownerId,
                videoId,
                title: video.title,
                description: video.description,
                duration: video.duration,
                views: video.views,
                thumbnail: video.photo_800 || video.photo_640 || video.photo_320,
                playerUrl: video.player,
                files: video.files || {},
                platform: video.platform,
                canDownload: video.can_download === 1,
                isPrivate: video.is_private === 1
            };

        } catch (error) {
            log(`API extraction failed: ${error.message}`);
            return null;
        }
    }
}

// Export singleton instance
module.exports = new VkExtractor();
module.exports.VkExtractor = VkExtractor;
