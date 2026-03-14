/**
 * Puppeteer-based M3U8 Extractor for kayihome.xyz
 * 
 * This module uses puppeteer-extra with stealth plugin to bypass devtools detection
 * and extract m3u8 video URLs from kayihome.xyz player pages.
 */

const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

// Enable stealth plugin with all evasions
const stealth = StealthPlugin();
puppeteer.use(stealth);

// Debug logging
const DEBUG = process.env.DEBUG === 'true';
function log(...args) {
    if (DEBUG) console.log('[PuppeteerExtractor]', ...args);
}

/**
 * Default launch options for different environments
 */
const LAUNCH_OPTIONS = {
    // Standard options for local development
    default: {
        headless: 'new',
        args: [
            '--no-sandbox',
            '--disable-setuid-sandbox',
            '--disable-dev-shm-usage',
            '--disable-gpu',
            '--window-size=1920,1080',
            '--disable-blink-features=AutomationControlled'
        ],
        dumpio: false
    },
    // Optimized for Render free tier (low memory)
    render: {
        headless: 'new',
        args: [
            '--no-sandbox',
            '--disable-setuid-sandbox',
            '--disable-dev-shm-usage',
            '--disable-gpu',
            '--window-size=1280,720', // Smaller viewport for less memory
            '--disable-blink-features=AutomationControlled',
            '--single-process', // Reduce memory usage
            '--no-zygote',
            '--disable-extensions',
            '--disable-background-networking',
            '--disable-background-timer-throttling',
            '--disable-backgrounding-occluded-windows',
            '--disable-renderer-backgrounding',
            '--disable-features=ScriptStreaming,V8IdleTasks,InterestFeedContentSuggestions',
            '--memory-pressure-off',
            '--max_old_space_size=256', // Limit JS heap size
            '--disable-breakpad',
            '--disable-dev-profile',
            '--disable-software-rasterizer'
        ],
        dumpio: false,
        protocolTimeout: 30000
    }
};

/**
 * Inject anti-detection scripts into the page
 */
async function injectAntiDetection(page) {
    await page.evaluateOnNewDocument(() => {
        // Hide webdriver property
        Object.defineProperty(navigator, 'webdriver', {
            get: () => undefined,
            configurable: true
        });

        // Override permissions API
        const originalQuery = window.navigator.permissions?.query;
        if (originalQuery) {
            window.navigator.permissions.query = (parameters) => {
                if (parameters.name === 'notifications') {
                    return Promise.resolve({ state: Notification.permission });
                }
                return originalQuery.call(window.navigator.permissions, parameters);
            };
        }

        // Spoof plugins
        Object.defineProperty(navigator, 'plugins', {
            get: () => [
                { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer' },
                { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer' },
                { name: 'Native Client', filename: 'internal-nacl-plugin' }
            ]
        });

        // Spoof mimeTypes
        Object.defineProperty(navigator, 'mimeTypes', {
            get: () => [
                { type: 'application/pdf', suffixes: 'pdf', description: 'Portable Document Format' },
                { type: 'application/x-google-chrome-pdf', suffixes: 'pdf', description: 'Portable Document Format' }
            ]
        });

        // Spoof languages
        Object.defineProperty(navigator, 'languages', {
            get: () => ['en-US', 'en']
        });

        // Override chrome object
        window.chrome = window.chrome || {};
        window.chrome.runtime = window.chrome.runtime || {};

        // Hide automation from console
        const originalConsoleLog = console.log;
        console.log = function(...args) {
            // Filter out common detection patterns
            const blockedPatterns = ['devtools', 'selenium', 'webdriver', 'phantom'];
            if (args[0] && typeof args[0] === 'string') {
                for (const pattern of blockedPatterns) {
                    if (args[0].toLowerCase().includes(pattern)) return;
                }
            }
            return originalConsoleLog.apply(this, args);
        };

        // Prevent debugger detection
        const originalDebugger = window.debugger;
        Object.defineProperty(window, 'debugger', {
            get: () => false,
            set: () => true
        });

        // Override window size detection
        const originalOuterWidth = window.outerWidth;
        const originalOuterHeight = window.outerHeight;
        Object.defineProperty(window, 'outerWidth', {
            get: () => window.innerWidth
        });
        Object.defineProperty(window, 'outerHeight', {
            get: () => window.innerHeight
        });
    });
}

/**
 * Set up network interception to capture m3u8 URLs
 */
function setupNetworkCapture(page, captured) {
    // Capture responses (more reliable than request interception)
    page.on('response', async (response) => {
        const url = response.url();
        const contentType = response.headers()['content-type'] || '';

        // Capture m3u8 URLs
        if (url.includes('.m3u8') || url.includes('.m3u')) {
            log('M3U8 response captured:', url);
            captured.m3u8.push({
                url,
                headers: response.headers(),
                timestamp: Date.now(),
                source: 'response'
            });
        }

        // Capture API responses that might contain stream URLs
        if ((url.includes('api') || url.includes('getVideo')) && 
            (contentType.includes('json') || contentType.includes('javascript') || contentType.includes('text'))) {
            try {
                // Clone response to read body
                const text = await response.text().catch(() => null);
                if (text) {
                    // Look for m3u8 URL in response
                    const m3u8Match = text.match(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/);
                    if (m3u8Match) {
                        log('M3U8 found in API response:', m3u8Match[1]);
                        captured.m3u8.push({
                            url: m3u8Match[1],
                            timestamp: Date.now(),
                            source: 'api_response'
                        });
                    }

                    // Look for JSON with video data
                    try {
                        const json = JSON.parse(text);
                        const streamUrl = json.videoSource || json.hls || json.stream || json.url || json.file;
                        if (streamUrl && (streamUrl.includes('.m3u8') || streamUrl.includes('http'))) {
                            log('Stream URL found in JSON:', streamUrl);
                            captured.m3u8.push({
                                url: streamUrl,
                                timestamp: Date.now(),
                                source: 'api_json'
                            });
                        }
                    } catch (e) {
                        // Not valid JSON, ignore
                    }
                }
            } catch (e) {
                // Ignore errors reading response
            }
        }
    });

    // Log failed requests for debugging
    page.on('requestfailed', (request) => {
        log('Request failed:', request.url(), request.failure()?.errorText);
    });
}

/**
 * Extract stream URL from page JavaScript context
 */
async function extractFromPageContext(page) {
    return await page.evaluate(() => {
        const results = [];

        // Check for common player variables
        const playerVars = [
            'sources', 'videoSources', 'playlist', 'playerConfig',
            'streamData', 'videoData', 'hlsUrl', 'streamUrl',
            'videoUrl', 'manifestUrl', 'src'
        ];

        for (const varName of playerVars) {
            if (window[varName]) {
                const value = window[varName];
                if (typeof value === 'string' && value.includes('http')) {
                    results.push({ source: `window.${varName}`, url: value });
                } else if (typeof value === 'object') {
                    // Try to extract URL from object
                    const jsonStr = JSON.stringify(value);
                    const match = jsonStr.match(/(https?:\/\/[^"'<>]+\.(?:m3u8|mp4)[^"'<>]*)/);
                    if (match) {
                        results.push({ source: `window.${varName}`, url: match[1] });
                    }
                }
            }
        }

        // Check video elements
        const videos = document.querySelectorAll('video');
        videos.forEach((video, index) => {
            if (video.src && video.src.includes('http')) {
                results.push({ source: `video[${index}].src`, url: video.src });
            }
            if (video.currentSrc && video.currentSrc.includes('http')) {
                results.push({ source: `video[${index}].currentSrc`, url: video.currentSrc });
            }
            // Check data attributes
            for (const attr of ['data-src', 'data-url', 'data-source', 'data-stream']) {
                if (video.dataset[attr.replace('data-', '')]) {
                    results.push({
                        source: `video[${index}].${attr}`,
                        url: video.dataset[attr.replace('data-', '')]
                    });
                }
            }
        });

        // Check source elements
        const sources = document.querySelectorAll('video source, audio source');
        sources.forEach((source, index) => {
            if (source.src && source.src.includes('http')) {
                results.push({ source: `source[${index}]`, url: source.src });
            }
        });

        // Look for m3u8 in script tags
        const scripts = document.querySelectorAll('script:not([src])');
        for (const script of scripts) {
            const text = script.textContent || '';
            // Look for m3u8 URLs
            const m3u8Matches = text.matchAll(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/g);
            for (const match of m3u8Matches) {
                results.push({ source: 'script_inline', url: match[1] });
            }
            // Look for common player initialization patterns
            const patterns = [
                /file["\']?\s*[:=]\s*["\']([^"\']+)["\']/,
                /src["\']?\s*[:=]\s*["\']([^"\']+)["\']/,
                /url["\']?\s*[:=]\s*["\']([^"\']+)["\']/
            ];
            for (const pattern of patterns) {
                const match = text.match(pattern);
                if (match && match[1].includes('http')) {
                    results.push({ source: 'script_pattern', url: match[1] });
                }
            }
        }

        return results;
    });
}

/**
 * Extract from iframes recursively
 */
async function extractFromIframes(page) {
    const results = [];
    const frames = page.frames();
    log(`Checking ${frames.length} frames`);

    for (const frame of frames) {
        try {
            const frameUrl = frame.url();
            if (!frameUrl || frameUrl === 'about:blank' || frameUrl.startsWith('javascript:')) {
                continue;
            }

            log('Checking frame:', frameUrl);

            // Wait for frame to be ready
            await frame.waitForLoadState('domcontentloaded', { timeout: 5000 }).catch(() => { });

            // Extract from frame context
            const frameData = await frame.evaluate(() => {
                const results = [];

                // Check video elements
                const videos = document.querySelectorAll('video');
                videos.forEach(video => {
                    if (video.src) results.push({ type: 'video_src', url: video.src });
                    if (video.currentSrc) results.push({ type: 'video_currentSrc', url: video.currentSrc });
                });

                // Check for player variables
                const vars = ['sources', 'videoSources', 'streamUrl', 'hlsUrl'];
                for (const v of vars) {
                    if (window[v]) {
                        if (typeof window[v] === 'string' && window[v].includes('http')) {
                            results.push({ type: `window.${v}`, url: window[v] });
                        }
                    }
                }

                return results;
            });

            results.push(...frameData);

        } catch (e) {
            log('Error accessing frame:', e.message);
        }
    }

    return results;
}

/**
 * Main extraction function
 * @param {string} iframeUrl - The kayihome.xyz iframe URL
 * @param {object} options - Configuration options
 * @returns {Promise<string|null>} - The extracted m3u8 URL or null
 */
async function extractM3U8FromKayihome(iframeUrl, options = {}) {
    const {
        timeout = 30000,
        headless = true,
        environment = process.env.RENDER ? 'render' : 'default'
    } = options;

    let browser = null;
    let page = null;

    // Storage for captured URLs
    const captured = {
        m3u8: [],
        apiData: []
    };

    log('Starting extraction for:', iframeUrl);
    log('Environment:', environment);

    try {
        // Get launch options based on environment
        const launchOpts = {
            ...LAUNCH_OPTIONS[environment] || LAUNCH_OPTIONS.default,
            headless
        };

        // Allow custom executable path
        if (process.env.PUPPETEER_EXECUTABLE_PATH) {
            launchOpts.executablePath = process.env.PUPPETEER_EXECUTABLE_PATH;
        }

        log('Launching browser...');
        browser = await puppeteer.launch(launchOpts);

        log('Creating new page...');
        page = await browser.newPage();

        // Set user agent
        await page.setUserAgent(
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        );

        // Set viewport
        await page.setViewport({ width: 1280, height: 720 });

        // Set extra headers
        await page.setExtraHTTPHeaders({
            'Accept-Language': 'en-US,en;q=0.9',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8'
        });

        // Handle dialogs
        page.on('dialog', async dialog => {
            log('Dialog detected:', dialog.type(), dialog.message());
            await dialog.dismiss();
        });

        // Inject anti-detection
        await injectAntiDetection(page);

        // Setup network capture
        setupNetworkCapture(page, captured);

        // Navigate to page
        log('Navigating to page...');
        await page.goto(iframeUrl, {
            waitUntil: 'networkidle2',
            timeout
        });

        // Wait for initial load
        log('Page loaded, waiting for player...');
        await new Promise(r => setTimeout(r, 2000));

        // Try to find and click play button (if needed)
        try {
            const playSelectors = [
                'video',
                '.video-player',
                '#player',
                '[class*="player"]',
                '[class*="video"]',
                'button[class*="play"]',
                '.vjs-big-play-button'
            ];

            for (const selector of playSelectors) {
                const element = await page.$(selector);
                if (element) {
                    log('Clicking element:', selector);
                    await element.click().catch(() => { });
                    await new Promise(r => setTimeout(r, 1000));
                    break;
                }
            }
        } catch (e) {
            log('Click interaction failed:', e.message);
        }

        // Wait for network activity
        log('Waiting for stream URLs...');
        await new Promise(r => setTimeout(r, 4000));

        // Check if we already have m3u8 from network capture
        if (captured.m3u8.length > 0) {
            log('M3U8 found from network capture');
            // Return the most recent one
            const bestUrl = captured.m3u8[captured.m3u8.length - 1].url;
            return bestUrl;
        }

        // Extract from page context
        log('Extracting from page context...');
        const pageResults = await extractFromPageContext(page);
        log('Page context results:', pageResults.length);

        for (const result of pageResults) {
            if (result.url && result.url.includes('.m3u8')) {
                log('M3U8 found in page context:', result.url);
                return result.url;
            }
        }

        // Extract from iframes
        log('Checking iframes...');
        const iframeResults = await extractFromIframes(page);
        log('Iframe results:', iframeResults.length);

        for (const result of iframeResults) {
            if (result.url && result.url.includes('.m3u8')) {
                log('M3U8 found in iframe:', result.url);
                return result.url;
            }
        }

        // If we found any URL, return the first HTTP one
        const allResults = [...pageResults, ...iframeResults];
        for (const result of allResults) {
            if (result.url && result.url.startsWith('http')) {
                log('Stream URL found:', result.url);
                return result.url;
            }
        }

        log('No stream URL found');
        return null;

    } catch (error) {
        console.error('[PuppeteerExtractor] Error:', error.message);
        return null;

    } finally {
        if (browser) {
            log('Closing browser...');
            try {
                await browser.close();
            } catch (e) {
                // Force kill if needed
                if (browser.process()) {
                    browser.process().kill('SIGKILL');
                }
            }
        }
    }
}

/**
 * Test function for CLI usage
 */
async function testExtraction() {
    const testUrl = process.argv[2];
    if (!testUrl) {
        console.log('Usage: node puppeteer-extractor.js <kayihome-url>');
        console.log('Example: node puppeteer-extractor.js "https://kayihome.xyz/player/?data=abc123"');
        process.exit(1);
    }

    console.log('Testing extraction with debug mode enabled...');
    const result = await extractM3U8FromKayihome(testUrl, {
        debug: true,
        headless: false,
        timeout: 45000
    });

    if (result) {
        console.log('\n✓ SUCCESS! Extracted URL:');
        console.log(result);
        process.exit(0);
    } else {
        console.log('\n✗ FAILED: Could not extract stream URL');
        process.exit(1);
    }
}

// Run test if called directly
if (require.main === module) {
    testExtraction();
}

module.exports = {
    extractM3U8FromKayihome,
    injectAntiDetection,
    setupNetworkCapture
};
