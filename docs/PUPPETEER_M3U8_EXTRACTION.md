# Puppeteer M3U8 Extraction Guide for kayihome.xyz

## Overview

This guide provides a complete solution for extracting m3u8 video URLs from kayihome.xyz using Puppeteer with stealth capabilities to bypass devtools detection.

## Table of Contents

1. [Puppeteer Stealth Setup](#1-puppeteer-stealth-setup)
2. [Network Interception](#2-network-interception)
3. [Page Interaction](#3-page-interaction)
4. [Minimal Working Example](#4-minimal-working-example)
5. [Performance Considerations](#5-performance-considerations)
6. [Integration with Stremio Addon](#6-integration-with-stremio-addon)

---

## 1. Puppeteer Stealth Setup

### Installation

```bash
npm install puppeteer-extra puppeteer-extra-plugin-stealth puppeteer-extra-plugin-adblocker
```

### Why Stealth Plugin?

kayihome.xyz likely uses:
- `navigator.webdriver` detection
- Chrome runtime fingerprinting
- Headless Chrome detection via plugins/mimeTypes
- DevTools detection via window size, console behavior

### Stealth Configuration

```javascript
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const AdblockerPlugin = require('puppeteer-extra-plugin-adblocker');

// Configure stealth plugin with all evasions enabled
const stealth = StealthPlugin();
stealth.enabledEvasions.delete('chrome.runtime'); // Keep if needed
puppeteer.use(stealth);

// Optional: Block ads to speed up loading
puppeteer.use(AdblockerPlugin({ blockTrackers: true }));

// Launch configuration for Render free tier
const launchOptions = {
    headless: 'new', // Use new headless mode (not "shell")
    args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-accelerated-2d-canvas',
        '--disable-gpu',
        '--window-size=1920,1080',
        '--disable-web-security',
        '--disable-features=IsolateOrigins,site-per-process',
        '--disable-blink-features=AutomationControlled'
    ],
    // IMPORTANT: For Render free tier, use minimal resources
    dumpio: false, // Disable Chrome debug output
    protocolTimeout: 30000
};
```

### Handling Alerts/Popups

```javascript
// Handle dialogs that detection might trigger
page.on('dialog', async dialog => {
    console.log(`Dialog detected: ${dialog.type()} - ${dialog.message()}`);
    await dialog.dismiss(); // Always dismiss detection popups
});

// Override window.alert, confirm, prompt to prevent blocking
await page.evaluateOnNewDocument(() => {
    window.alert = () => {};
    window.confirm = () => true;
    window.prompt = () => null;
});
```

### Advanced Evasion: Override DevTools Detection

```javascript
await page.evaluateOnNewDocument(() => {
    // Override console methods that check for devtools
    const originalConsoleLog = console.log;
    console.log = function(...args) {
        // Filter out detection patterns
        if (args[0] && typeof args[0] === 'string' && args[0].includes('devtools')) return;
        return originalConsoleLog.apply(this, args);
    };
    
    // Override debugger detection
    Object.defineProperty(window, 'debugger', {
        get: () => false,
        set: () => true
    });
    
    // Hide automation flags
    Object.defineProperty(navigator, 'webdriver', {
        get: () => undefined
    });
    
    // Spoof plugins
    Object.defineProperty(navigator, 'plugins', {
        get: () => [1, 2, 3, 4, 5]
    });
    
    // Spoof languages
    Object.defineProperty(navigator, 'languages', {
        get: () => ['en-US', 'en']
    });
});
```

---

## 2. Network Interception

### Capturing m3u8 Requests

The most reliable method to capture m3u8 URLs is using the `response` event (not request interception):

```javascript
const capturedUrls = {
    m3u8: [],
    mp4: [],
    api: []
};

// Listen for all responses
page.on('response', async (response) => {
    const url = response.url();
    const resourceType = response.request().resourceType();
    
    // Capture m3u8 URLs
    if (url.includes('.m3u8') || url.includes('master.m3u8')) {
        console.log('[M3U8 Found]', url);
        capturedUrls.m3u8.push({
            url,
            headers: response.headers(),
            timestamp: Date.now()
        });
    }
    
    // Capture mp4 segments (sometimes used instead of m3u8)
    if (url.includes('.mp4') && (url.includes('segment') || url.includes('frag'))) {
        console.log('[MP4 Segment Found]', url);
        capturedUrls.mp4.push(url);
    }
    
    // Capture API responses that might contain stream URLs
    if (url.includes('api') || url.includes('getVideo') || url.includes('stream')) {
        try {
            const contentType = response.headers()['content-type'] || '';
            if (contentType.includes('json') || contentType.includes('javascript')) {
                const text = await response.text();
                // Look for m3u8 in response
                const m3u8Match = text.match(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/);
                if (m3u8Match) {
                    console.log('[M3U8 in API]', m3u8Match[1]);
                    capturedUrls.api.push(m3u8Match[1]);
                }
            }
        } catch (e) {
            // Ignore errors reading response body
        }
    }
});
```

### Alternative: Request Interception (for modifying requests)

```javascript
await page.setRequestInterception(true);

page.on('request', (request) => {
    // Log all requests for debugging
    if (request.url().includes('.m3u8') || request.url().includes('video')) {
        console.log('[Request]', request.method(), request.url());
    }
    
    // Continue all requests (don't block)
    if (!request.isInterceptResolutionHandled()) {
        request.continue();
    }
});
```

### Using Chrome DevTools Protocol (Advanced)

For lower-level network monitoring:

```javascript
const client = await page.target().createCDPSession();
await client.send('Network.enable');

client.on('Network.requestWillBeSent', (params) => {
    if (params.request.url.includes('.m3u8')) {
        console.log('[CDP M3U8]', params.request.url);
    }
});

client.on('Network.responseReceived', (params) => {
    if (params.response.url.includes('.m3u8')) {
        console.log('[CDP Response]', params.response.url);
    }
});
```

---

## 3. Page Interaction

### Waiting for Player to Initialize

```javascript
// Method 1: Wait for video element
await page.waitForSelector('video', { timeout: 10000 });

// Method 2: Wait for iframe (if player is in iframe)
await page.waitForSelector('iframe', { timeout: 10000 });

// Method 3: Wait for network idle (player loaded)
await page.waitForNetworkIdle({ idleTime: 500, timeout: 15000 });

// Method 4: Custom wait function with retry
async function waitForPlayerLoad(page, timeout = 15000) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
        const hasVideo = await page.evaluate(() => {
            return !!document.querySelector('video') || 
                   !!document.querySelector('iframe');
        });
        if (hasVideo) return true;
        await new Promise(r => setTimeout(r, 500));
    }
    return false;
}
```

### Handling Iframes

```javascript
// Find and interact with iframes
async function extractFromIframe(page) {
    const frames = page.frames();
    console.log(`Found ${frames.length} frames`);
    
    for (const frame of frames) {
        try {
            const frameUrl = frame.url();
            console.log('Frame URL:', frameUrl);
            
            // Skip empty or about:blank frames
            if (!frameUrl || frameUrl === 'about:blank') continue;
            
            // Wait for frame to load
            await frame.waitForLoadState('networkidle', { timeout: 5000 })
                .catch(() => {});
            
            // Look for video elements in frame
            const videoSrc = await frame.evaluate(() => {
                const video = document.querySelector('video');
                return video ? video.src : null;
            });
            
            if (videoSrc) {
                console.log('Video source found in iframe:', videoSrc);
                return videoSrc;
            }
            
            // Look for player configuration
            const playerConfig = await frame.evaluate(() => {
                // Common player variables
                const vars = ['player', 'videojs', 'jwplayer', 'hls', 'sources'];
                for (const v of vars) {
                    if (window[v]) return { name: v, value: window[v] };
                }
                return null;
            });
            
            if (playerConfig) {
                console.log('Player config found:', playerConfig);
            }
            
        } catch (e) {
            console.log('Error accessing frame:', e.message);
        }
    }
    return null;
}
```

### Extracting Data from JavaScript Variables

```javascript
async function extractStreamFromPage(page) {
    return await page.evaluate(() => {
        const results = [];
        
        // Check for common video sources patterns
        const patterns = [
            'sources',
            'videoSources', 
            'playlist',
            'streamUrl',
            'videoUrl',
            'hlsUrl',
            'manifestUrl'
        ];
        
        for (const pattern of patterns) {
            if (window[pattern]) {
                results.push({
                    source: `window.${pattern}`,
                    value: window[pattern]
                });
            }
        }
        
        // Check for video element sources
        const videos = document.querySelectorAll('video');
        videos.forEach((v, i) => {
            if (v.src) {
                results.push({
                    source: `video[${i}].src`,
                    value: v.src
                });
            }
            if (v.dataset.src) {
                results.push({
                    source: `video[${i}].dataset.src`,
                    value: v.dataset.src
                });
            }
        });
        
        // Check for source elements
        const sources = document.querySelectorAll('video source');
        sources.forEach((s, i) => {
            if (s.src) {
                results.push({
                    source: `source[${i}]`,
                    value: s.src
                });
            }
        });
        
        // Look for m3u8 in script tags
        const scripts = document.querySelectorAll('script');
        for (const script of scripts) {
            const text = script.textContent || '';
            const m3u8Match = text.match(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/);
            if (m3u8Match) {
                results.push({
                    source: 'script',
                    value: m3u8Match[1]
                });
            }
        }
        
        return results;
    });
}
```

---

## 4. Minimal Working Example

### Complete Implementation

```javascript
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

// Enable stealth plugin
puppeteer.use(StealthPlugin());

/**
 * Extract m3u8 URL from kayihome.xyz player page
 * @param {string} iframeUrl - The kayihome.xyz iframe URL
 * @param {object} options - Configuration options
 * @returns {Promise<string|null>} - The m3u8 URL or null
 */
async function extractM3U8FromKayihome(iframeUrl, options = {}) {
    const {
        timeout = 30000,
        debug = false,
        headless = true
    } = options;
    
    let browser = null;
    let page = null;
    
    // Store captured URLs
    const captured = {
        m3u8: [],
        apiResponse: null
    };
    
    try {
        // Launch browser
        browser = await puppeteer.launch({
            headless,
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage',
                '--disable-gpu',
                '--window-size=1920,1080',
                '--disable-blink-features=AutomationControlled'
            ]
        });
        
        page = await browser.newPage();
        
        // Set user agent
        await page.setUserAgent(
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        );
        
        // Set viewport
        await page.setViewport({ width: 1920, height: 1080 });
        
        // Handle dialogs
        page.on('dialog', async dialog => {
            if (debug) console.log(`[Dialog] ${dialog.type()}: ${dialog.message()}`);
            await dialog.dismiss();
        });
        
        // Inject anti-detection script
        await page.evaluateOnNewDocument(() => {
            // Hide webdriver
            Object.defineProperty(navigator, 'webdriver', {
                get: () => undefined
            });
            
            // Override permissions
            const originalQuery = window.navigator.permissions.query;
            window.navigator.permissions.query = (parameters) => (
                parameters.name === 'notifications' 
                    ? Promise.resolve({ state: Notification.permission })
                    : originalQuery(parameters)
            );
            
            // Spoof plugins
            Object.defineProperty(navigator, 'plugins', {
                get: () => [
                    { name: 'Chrome PDF Plugin' },
                    { name: 'Chrome PDF Viewer' },
                    { name: 'Native Client' }
                ]
            });
        });
        
        // Listen for m3u8 responses
        page.on('response', async (response) => {
            const url = response.url();
            
            // Capture m3u8 URLs
            if (url.includes('.m3u8')) {
                if (debug) console.log('[M3U8 Found]', url);
                captured.m3u8.push({
                    url,
                    headers: response.headers(),
                    time: Date.now()
                });
            }
            
            // Capture API responses with video data
            if (url.includes('api') && url.includes('getVideo')) {
                try {
                    const text = await response.text();
                    captured.apiResponse = text;
                    
                    // Try to parse and extract URL
                    try {
                        const json = JSON.parse(text);
                        if (json.videoSource || json.hls || json.stream) {
                            const streamUrl = json.videoSource || json.hls || json.stream;
                            if (debug) console.log('[API Stream]', streamUrl);
                            captured.m3u8.push({ url: streamUrl, source: 'api' });
                        }
                    } catch (e) {
                        // Not JSON, check for URL in text
                        const match = text.match(/(https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*)/);
                        if (match) {
                            captured.m3u8.push({ url: match[1], source: 'api_text' });
                        }
                    }
                } catch (e) {
                    // Ignore
                }
            }
        });
        
        // Navigate to the iframe
        if (debug) console.log('[Navigate]', iframeUrl);
        
        await page.goto(iframeUrl, {
            waitUntil: 'networkidle2',
            timeout
        });
        
        // Wait for player to initialize
        await new Promise(r => setTimeout(r, 3000));
        
        // Check for iframes
        const frames = page.frames();
        if (debug) console.log(`[Frames] Found ${frames.length} frames`);
        
        // Try to interact with player (click to start)
        try {
            await page.click('video, .video-player, #player, [class*="player"]', { timeout: 2000 });
        } catch (e) {
            // Click not required or element not found
        }
        
        // Wait for more network activity
        await new Promise(r => setTimeout(r, 3000));
        
        // Extract from page context
        const pageData = await page.evaluate(() => {
            const results = [];
            
            // Check global variables
            const vars = ['sources', 'videoSources', 'playlist', 'playerConfig', 'streamData'];
            for (const v of vars) {
                if (window[v]) {
                    results.push({ type: 'window', name: v, value: window[v] });
                }
            }
            
            // Check video elements
            document.querySelectorAll('video').forEach((v, i) => {
                if (v.src) results.push({ type: 'video', src: v.src });
            });
            
            return results;
        });
        
        if (debug) console.log('[Page Data]', pageData);
        
        // Return best URL
        if (captured.m3u8.length > 0) {
            // Return the most recent m3u8 URL
            return captured.m3u8[captured.m3u8.length - 1].url;
        }
        
        return null;
        
    } catch (error) {
        console.error('[Extraction Error]', error.message);
        return null;
        
    } finally {
        if (browser) {
            await browser.close();
        }
    }
}

// Export for use in Stremio addon
module.exports = { extractM3U8FromKayihome };

// CLI test
if (require.main === module) {
    const testUrl = process.argv[2];
    if (!testUrl) {
        console.log('Usage: node puppeteer-extractor.js <kayihome-url>');
        process.exit(1);
    }
    
    extractM3U8FromKayihome(testUrl, { debug: true, headless: false })
        .then(url => {
            if (url) {
                console.log('\n✓ Success! M3U8 URL:');
                console.log(url);
            } else {
                console.log('\n✗ Failed to extract M3U8 URL');
            }
            process.exit(url ? 0 : 1);
        });
}
```

---

## 5. Performance Considerations

### Render Free Tier Optimization

Render free tier has:
- 512MB RAM
- Limited CPU
- Spins down after inactivity

```javascript
// Optimized launch options for Render
const renderLaunchOptions = {
    headless: 'new',
    args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage', // Critical for low memory
        '--disable-gpu',
        '--single-process', // Reduce memory usage
        '--no-zygote', // Reduce memory usage
        '--disable-extensions',
        '--disable-background-networking',
        '--disable-background-timer-throttling',
        '--disable-backgrounding-occluded-windows',
        '--disable-renderer-backgrounding',
        '--disable-features=ScriptStreaming,V8IdleTasks',
        '--memory-pressure-off',
        '--max-old-space-size=256' // Limit JS heap
    ],
    // Use system Chrome if available (smaller footprint)
    executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || undefined
};
```

### Caching Strategies

```javascript
const NodeCache = require('node-cache');
const streamCache = new NodeCache({ stdTTL: 300 }); // 5 minute cache

async function getStreamWithCache(iframeUrl) {
    const cacheKey = `stream_${Buffer.from(iframeUrl).toString('base64')}`;
    
    // Check cache
    const cached = streamCache.get(cacheKey);
    if (cached) {
        console.log('[Cache] HIT');
        return cached;
    }
    
    // Extract fresh
    const streamUrl = await extractM3U8FromKayihome(iframeUrl);
    if (streamUrl) {
        streamCache.set(cacheKey, streamUrl);
    }
    
    return streamUrl;
}
```

### Resource Management

```javascript
// Always use try-finally to ensure cleanup
async function safeExtract(url) {
    let browser;
    try {
        browser = await puppeteer.launch(options);
        // ... extraction logic
        return result;
    } finally {
        if (browser) {
            try {
                await browser.close();
            } catch (e) {
                // Force kill if needed
                process.kill(browser.process().pid, 'SIGKILL');
            }
        }
    }
}
```

### Parallel Processing Limits

```javascript
// Limit concurrent browser instances on Render
const pLimit = require('p-limit');
const limit = pLimit(2); // Max 2 concurrent extractions

async function extractMultiple(urls) {
    const promises = urls.map(url => 
        limit(() => extractM3U8FromKayihome(url))
    );
    return await Promise.all(promises);
}
```

---

## 6. Integration with Stremio Addon

### Updated kayifamily.js Integration

```javascript
// In src/kayifamily.js

// Option 1: Use Puppeteer for all extractions (slower but reliable)
const { extractM3U8FromKayihome } = require('./puppeteer-extractor');

async function extractKayihomeStream(iframeUrl) {
    // Try axios first (fast)
    const fastResult = await extractWithAxios(iframeUrl);
    if (fastResult) return fastResult;
    
    // Fall back to Puppeteer (slow but bypasses detection)
    console.log('[Kayihome] Falling back to Puppeteer extraction');
    const streamUrl = await extractM3U8FromKayihome(iframeUrl, {
        timeout: 25000,
        debug: process.env.DEBUG === 'true',
        headless: true
    });
    
    if (streamUrl) {
        return {
            url: streamUrl,
            type: 'hls',
            source: 'kayifamily-kayihome-puppeteer'
        };
    }
    
    return null;
}

// Option 2: Environment-based toggle
async function extractKayihomeStream(iframeUrl) {
    const usePuppeteer = process.env.USE_PUPPETEER === 'true';
    
    if (usePuppeteer) {
        const { extractM3U8FromKayihome } = require('./puppeteer-extractor');
        const url = await extractM3U8FromKayihome(iframeUrl);
        return url ? { url, type: 'hls', source: 'kayihome-puppeteer' } : null;
    }
    
    // Use existing axios-based extraction
    return await extractWithAxios(iframeUrl);
}
```

### package.json Dependencies

```json
{
  "dependencies": {
    "axios": "^1.13.2",
    "cheerio": "^1.1.2",
    "cors": "^2.8.5",
    "crypto-js": "^4.2.0",
    "express": "^5.2.1",
    "node-cache": "^5.1.2",
    "p-limit": "^3.1.0",
    "puppeteer-extra": "^3.3.6",
    "puppeteer-extra-plugin-adblocker": "^0.6.3",
    "puppeteer-extra-plugin-stealth": "^2.11.2",
    "stremio-addon-sdk": "^1.6.10"
  }
}
```

### Render.yaml Configuration

```yaml
services:
  - type: web
    name: oman-online-stremio
    env: node
    buildCommand: npm install && npx puppeteer browsers install chrome
    startCommand: node src/index.js
    envVars:
      - key: NODE_ENV
        value: production
      - key: USE_PUPPETEER
        value: true
      - key: PUPPETEER_EXECUTABLE_PATH
        value: /opt/render/.cache/puppeteer/chrome/linux-*/chrome-linux/chrome
    plan: free
```

---

## Troubleshooting

### Common Issues

1. **Chrome fails to launch on Render**
   - Solution: Use `--no-sandbox` flag
   - Install Chrome during build: `npx puppeteer browsers install chrome`

2. **Timeout waiting for m3u8**
   - Increase timeout
   - Check if player requires interaction (click to play)

3. **Empty response body**
   - Some responses can only be read once
   - Use `response.text()` or `response.json()` only once

4. **Memory issues on Render**
   - Use `--single-process` flag
   - Limit concurrent browsers
   - Close browser immediately after extraction

### Debug Mode

```javascript
// Enable detailed logging
const DEBUG = process.env.DEBUG === 'true';

if (DEBUG) {
    page.on('console', msg => console.log('[Page Console]', msg.text()));
    page.on('pageerror', err => console.log('[Page Error]', err.message));
    page.on('requestfailed', req => console.log('[Request Failed]', req.url()));
}
```

---

## Summary

This guide provides a complete Puppeteer-based solution for extracting m3u8 URLs from kayihome.xyz:

1. **Stealth Setup**: Uses `puppeteer-extra-plugin-stealth` to bypass detection
2. **Network Interception**: Captures m3u8 URLs via response events
3. **Iframe Handling**: Traverses frames to find video sources
4. **Performance**: Optimized for Render free tier with caching
5. **Integration**: Can be used as fallback when axios extraction fails

The implementation balances reliability (using a real browser) with performance (caching, resource limits) for production use in a Stremio addon.
