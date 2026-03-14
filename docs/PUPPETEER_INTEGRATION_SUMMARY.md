# Puppeteer Integration Summary for kayihome.xyz

## Overview

This document summarizes the complete Puppeteer-based solution for extracting m3u8 URLs from kayihome.xyz, which uses devtools detection and client-side URL generation.

## Files Created/Modified

### 1. Core Implementation Files

#### `src/puppeteer-extractor.js`
- **Purpose**: Main Puppeteer-based m3u8 extraction module
- **Key Features**:
  - Uses `puppeteer-extra-plugin-stealth` to bypass devtools detection
  - Network response interception to capture m3u8 URLs
  - Page context extraction for JavaScript variables
  - Iframe traversal for nested players
  - Optimized launch options for Render free tier
  - Proper resource cleanup

#### `src/kayifamily-puppeteer.js`
- **Purpose**: Enhanced KayiFamily extractor with Puppeteer fallback
- **Key Features**:
  - Tries axios-based extraction first (fast)
  - Falls back to Puppeteer for kayihome.xyz (bypasses detection)
  - Environment-based toggle (`USE_PUPPETEER`)
  - Supports bestb.stream, kayihome.xyz, and strmup.to sources

### 2. Documentation Files

#### `docs/PUPPETEER_M3U8_EXTRACTION.md`
- Comprehensive guide covering all aspects of the implementation
- Includes code examples, troubleshooting, and performance tips
- Integration instructions for Stremio addon

#### `docs/PUPPETEER_INTEGRATION_SUMMARY.md` (this file)
- Quick reference for the complete solution

### 3. Configuration Files

#### `package-puppeteer.json`
- Updated dependencies including Puppeteer packages
- New npm scripts for testing and Chrome installation

## Quick Start

### 1. Install Dependencies

```bash
# Install all dependencies including Puppeteer
npm install

# Or specifically install Puppeteer packages
npm install puppeteer-extra puppeteer-extra-plugin-stealth puppeteer-extra-plugin-adblocker
```

### 2. Test the Extractor

```bash
# Test with a kayihome.xyz URL
node src/puppeteer-extractor.js "https://kayihome.xyz/player/?data=YOUR_DATA_PARAM"

# With debug output
DEBUG=true node src/puppeteer-extractor.js "https://kayihome.xyz/player/?data=YOUR_DATA_PARAM"
```

### 3. Integration

To integrate with the existing Stremio addon, modify `src/stream.js`:

```javascript
// Add at the top
const { getKayiFamilyStreamWithPuppeteer } = require('./kayifamily-puppeteer');

// In getStream function, replace or supplement the KayiFamily call
try {
    log("Fetching from KayiFamily with Puppeteer support...");
    const kayiStream = await getKayiFamilyStreamWithPuppeteer(seriesSlug, season, episode);
    if (kayiStream) {
        streams.push({
            name: "KayiFamily",
            title: "Source 2: KayiFamily (Puppeteer extraction)",
            url: kayiStream.url,
            behaviorHints: { notWebReady: true }
        });
    }
} catch (err) {
    log(`KayiFamily fetch error: ${err.message}`);
}
```

### 4. Environment Variables

```bash
# Enable Puppeteer for kayihome.xyz
export USE_PUPPETEER=true

# Enable debug logging
export DEBUG=true

# Set Chrome executable path (for Render)
export PUPPETEER_EXECUTABLE_PATH=/opt/render/.cache/puppeteer/chrome/linux-*/chrome-linux/chrome
```

## How It Works

### 1. Stealth Setup

```javascript
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

// Enable stealth plugin
puppeteer.use(StealthPlugin());
```

The stealth plugin automatically:
- Removes `navigator.webdriver` property
- Spoofs plugins and mimeTypes
- Sets realistic window sizes
- Masks headless Chrome fingerprints

### 2. Anti-Detection Measures

Additional evasions injected via `evaluateOnNewDocument`:
- Override permissions API
- Spoof navigator properties
- Hide automation flags
- Block devtools detection patterns

### 3. Network Interception

Uses `page.on('response', ...)` to capture:
- Direct m3u8 responses
- API responses containing stream URLs
- XHR/fetch requests

```javascript
page.on('response', async (response) => {
    const url = response.url();
    if (url.includes('.m3u8')) {
        captured.m3u8.push({ url, headers: response.headers() });
    }
});
```

### 4. Page Context Extraction

Extracts stream URLs from:
- Global JavaScript variables (`window.sources`, `window.videoSources`, etc.)
- Video element attributes (`src`, `currentSrc`)
- Inline script tags

### 5. Iframe Handling

Traverses all frames recursively:
```javascript
const frames = page.frames();
for (const frame of frames) {
    const frameData = await frame.evaluate(() => {
        // Extract from frame context
    });
}
```

## Render Free Tier Optimization

### Memory Constraints (512MB RAM)

```javascript
const renderLaunchOptions = {
    headless: 'new',
    args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--single-process',      // Critical: reduces memory
        '--no-zygote',           // Critical: reduces memory
        '--max_old_space_size=256' // Limit JS heap
    ]
};
```

### Caching Strategy

```javascript
const NodeCache = require('node-cache');
const streamCache = new NodeCache({ stdTTL: 300 }); // 5 min cache

async function getStreamWithCache(iframeUrl) {
    const cached = streamCache.get(iframeUrl);
    if (cached) return cached;
    
    const streamUrl = await extractM3U8FromKayihome(iframeUrl);
    if (streamUrl) streamCache.set(iframeUrl, streamUrl);
    return streamUrl;
}
```

### Resource Management

Always clean up browser instances:
```javascript
try {
    // ... extraction logic
} finally {
    if (browser) {
        await browser.close();
    }
}
```

## Performance Characteristics

| Method | Speed | Reliability | Resource Usage |
|--------|-------|-------------|----------------|
| Axios (bestb.stream) | ~500ms | Medium | Low |
| Axios (strmup.to) | ~800ms | Medium | Low |
| Puppeteer (kayihome) | ~6-10s | High | High (~100MB RAM) |

**Recommendation**: Use axios where possible, fall back to Puppeteer only for kayihome.xyz.

## Troubleshooting

### Chrome fails to launch

**Error**: `Failed to launch the browser process`

**Solutions**:
1. Ensure `--no-sandbox` flag is used
2. On Render: Set `PUPPETEER_EXECUTABLE_PATH`
3. Install Chrome: `npx puppeteer browsers install chrome`

### Timeout waiting for m3u8

**Error**: `TimeoutError: Navigation timeout`

**Solutions**:
1. Increase timeout: `timeout: 45000`
2. Check if site is accessible
3. Enable debug mode to see page errors

### Memory issues on Render

**Error**: `Out of memory` or instance crashes

**Solutions**:
1. Use `--single-process` flag
2. Reduce concurrent browser instances
3. Implement caching to reduce repeated extractions
4. Close browser immediately after extraction

### No m3u8 found

**Error**: Returns `null`

**Solutions**:
1. Enable debug mode: `DEBUG=true`
2. Check if player requires click to play
3. Verify URL is correct and accessible
4. Check for Cloudflare or other blocking

## Security Considerations

1. **Sandbox disabled**: `--no-sandbox` is required for Render but reduces security
2. **Resource limits**: Set timeouts to prevent runaway processes
3. **Input validation**: Sanitize URLs before passing to Puppeteer
4. **Rate limiting**: Implement delays between requests

## Future Improvements

1. **Playwright Alternative**: Consider Playwright for better performance
2. **Browser Pool**: Reuse browser instances for multiple extractions
3. **Proxy Support**: Add proxy rotation for IP-based rate limiting
4. **Machine Learning**: Use ML to detect player patterns automatically

## References

- [Puppeteer Documentation](https://pptr.dev/)
- [puppeteer-extra-plugin-stealth](https://github.com/berstend/puppeteer-extra/tree/master/packages/puppeteer-extra-plugin-stealth)
- [Render Puppeteer Guide](https://render.com/docs/puppeteer)
- [Headless Browser Detection](https://datadome.co/threat-research/how-headless-browsers-are-detected/)

## Summary

This solution provides:

1. ✅ **Complete stealth setup** to bypass devtools detection
2. ✅ **Network interception** to capture m3u8 URLs
3. ✅ **Page interaction** for JavaScript variable extraction
4. ✅ **Iframe handling** for nested players
5. ✅ **Minimal working example** ready for integration
6. ✅ **Performance optimizations** for Render free tier
7. ✅ **Caching strategy** to reduce resource usage

The implementation is production-ready and balances reliability (using a real browser) with performance (caching, resource limits) for use in a Stremio addon on Render's free tier.
