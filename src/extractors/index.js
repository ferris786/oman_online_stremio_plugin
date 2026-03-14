/**
 * Video Extractors Index
 * 
 * Consolidates extractors for various video hosting platforms
 */

const okru = require('./okru');
const vk = require('./vk');

module.exports = {
    okru,
    vk,
    
    // Helper function to detect and extract from any supported source
    async extractFromUrl(url) {
        // Try OK.ru
        if (url.includes('ok.ru')) {
            const videoId = okru.parseVideoId(url);
            if (videoId) {
                return await okru.extract(videoId);
            }
        }
        
        // Try VK
        if (url.includes('vk.com') || url.includes('vkvideo.ru')) {
            const parsed = vk.parseVideoUrl(url);
            if (parsed) {
                const hashMatch = url.match(/hash=([a-f0-9]+)/);
                const hash = hashMatch ? hashMatch[1] : '';
                return await vk.extract(parsed.ownerId, parsed.videoId, hash);
            }
        }
        
        return null;
    },
    
    // Helper to get Stremio stream from URL
    async getStremioStreamFromUrl(url, preferredQuality = '720p') {
        // Try OK.ru
        if (url.includes('ok.ru')) {
            const videoId = okru.parseVideoId(url);
            if (videoId) {
                return await okru.getStremioStream(videoId, preferredQuality);
            }
        }
        
        // Try VK
        if (url.includes('vk.com') || url.includes('vkvideo.ru')) {
            const parsed = vk.parseVideoUrl(url);
            if (parsed) {
                const hashMatch = url.match(/hash=([a-f0-9]+)/);
                const hash = hashMatch ? hashMatch[1] : '';
                return await vk.getStremioStream(parsed.ownerId, parsed.videoId, hash, preferredQuality);
            }
        }
        
        return null;
    }
};
