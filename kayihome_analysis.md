# Kayihome.xyz Video Player Protection Analysis

## Executive Summary

Kayihome.xyz uses a multi-layered protection system based on FirePlayer. When scraping with axios, the API returns "url is empty" while the same URL works in a browser. This document analyzes the protection mechanisms and provides working bypass techniques.

## 1. Protection Mechanisms Identified

### 1.1 DevTools Detection
The player uses devtools-detector library which checks:
- `window.outerWidth` vs `window.innerWidth` differences
- `navigator.webdriver` property
- Console opening detection via timing attacks
- Debugger statement traps

### 1.2 CDP (Chrome DevTools Protocol) Detection
Modern anti-bot systems detect:
- `Runtime.Enable` CDP command usage
- Automation library fingerprints (Puppeteer, Playwright, Selenium)
- Headless browser indicators

### 1.3 Session/Cookie Requirements
- Requires valid session cookies
- Checks `Referer` header (must be from whitelisted domains)
- Validates `Origin` header
- Uses `X-Requested-With: XMLHttpRequest` for API calls

### 1.4 JavaScript Execution
- Video URL is encrypted in API response
- Uses CryptoJS AES encryption with dynamic keys
- Requires JavaScript evaluation to decrypt

## 2. API Endpoint Analysis

### 2.1 Get Video API
```
POST /player/api.php?data={HASH}&do=getVideo
```

**Required Headers:**
```
Content-Type: application/x-www-form-urlencoded
X-Requested-With: XMLHttpRequest
Referer: https://kayifamily.com/ or https://osmanonline.info/
Origin: https://kayihome.xyz
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36
```

**Request Body:**
```
hash={HASH}&r={REFERER_URL}
```

### 2.2 Response Format
**Success (Turktvuk - working):**
```json
{
  "hls": true,
  "videoSource": "https://.../master.txt",
  "securedLink": "https://.../master.txt",
  "ck": "encryption_key_for_non_hls"
}
```

**Failure (Kayihome - blocked):**
```
"url is empty"
```

## 3. Why Kayihome Fails vs Turktvuk Works

### 3.1 Differences
| Aspect | Turktvuk | Kayihome |
|--------|----------|----------|
| DevTools Detection | No | Yes |
| CDP Detection | Minimal | Aggressive |
| Referer Validation | Lenient | Strict |
| Session Required | Cookie-based | Fingerprint + Cookie |

### 3.2 Key Finding
Kayihome implements additional fingerprinting that detects:
1. Missing `window.outerWidth`/`outerHeight` (Puppeteer default issue)
2. `navigator.webdriver === true`
3. Automation timing patterns

## 4. Bypass Techniques

### 4.1 Technique 1: Puppeteer with Stealth (Recommended)

```javascript
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

// Enable stealth mode
puppeteer.use(StealthPlugin());

async function extractKayihomeStream(url) {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-accelerated-2d-canvas',
      '--disable-gpu',
      '--window-size=1920,1080'
    ]
  });

  const page = await browser.newPage();
  
  // Set viewport to match window size (critical for bypassing outerWidth check)
  await page.setViewport({
    width: 1920,
    height: 1080,
    deviceScaleFactor: 1
  });

  // Intercept and modify devtools detector
  await page.evaluateOnNewDocument(() => {
    // Override outerWidth/outerHeight
    Object.defineProperty(window, 'outerWidth', {
      get: () => window.innerWidth,
    });
    Object.defineProperty(window, 'outerHeight', {
      get: () => window.innerHeight + 110, // Account for browser chrome
    });
    
    // Remove webdriver
    Object.defineProperty(navigator, 'webdriver', {
      get: () => undefined,
    });
    
    // Override permissions
    const originalQuery = window.navigator.permissions.query;
    window.navigator.permissions.query = (parameters) => (
      parameters.name === 'notifications' 
        ? Promise.resolve({ state: Notification.permission })
        : originalQuery(parameters)
    );
  });

  // Capture m3u8 URL from network requests
  let m3u8Url = null;
  await page.setRequestInterception(true);
  page.on('request', (request) => {
    const url = request.url();
    if (url.includes('.m3u8') || url.includes('.txt')) {
      m3u8Url = url;
      console.log('Found M3U8:', m3u8Url);
    }
    request.continue();
  });

  // Navigate to player
  await page.goto(url, { waitUntil: 'networkidle2', timeout: 30000 });
  
  // Wait for video to load
  await page.waitForTimeout(5000);
  
  await browser.close();
  return m3u8Url;
}
```

### 4.2 Technique 2: CDP Patching (Advanced)

Use rebrowser-patches to fix CDP detection:

```bash
# Install patches
npx rebrowser-patches@latest patch
```

```javascript
// playwright.config.js or puppeteer
const { chromium } = require('playwright');

async function bypassWithPatchedCDP(url) {
  const browser = await chromium.launch({
    headless: false,
    args: ['--disable-blink-features=AutomationControlled']
  });

  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 },
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
  });

  const page = await context.newPage();
  
  // The patch handles Runtime.Enable detection automatically
  await page.goto(url);
  
  // Extract m3u8 via network interception
  const m3u8Url = await page.evaluate(async () => {
    // Access the video element if available
    const video = document.querySelector('video');
    if (video && video.src) return video.src;
    
    // Or check jwplayer configuration
    if (window.jwplayer) {
      const config = jwplayer().getConfig();
      return config.file || config.sources?.[0]?.file;
    }
    return null;
  });
  
  return m3u8Url;
}
```

### 4.3 Technique 3: Fetch API with Proper Headers

```javascript
const axios = require('axios');

async function tryDirectAPI(dataHash, referer) {
  const apiUrl = `https://kayihome.xyz/player/api.php?data=${dataHash}&do=getVideo`;
  
  try {
    const response = await axios.post(apiUrl, 
      new URLSearchParams({
        hash: dataHash,
        r: referer
      }), {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept': 'application/json, text/javascript, */*',
        'Accept-Language': 'en-US,en;q=0.9',
        'Content-Type': 'application/x-www-form-urlencoded',
        'X-Requested-With': 'XMLHttpRequest',
        'Origin': 'https://kayihome.xyz',
        'Referer': `https://kayihome.xyz/player/index.php?data=${dataHash}`,
        'Sec-Fetch-Dest': 'empty',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Site': 'same-origin'
      },
      withCredentials: true, // Important for cookies
      timeout: 10000
    });
    
    return response.data;
  } catch (error) {
    console.error('API Error:', error.message);
    return null;
  }
}
```

### 4.4 Technique 4: Playwright with Stealth

```javascript
const { chromium } = require('playwright-extra');
const stealth = require('playwright-extra-plugin-stealth');

chromium.use(stealth());

async function extractWithPlaywright(playerUrl) {
  const browser = await chromium.launch({
    headless: true,
    args: ['--disable-blink-features=AutomationControlled']
  });

  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 },
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
  });

  const page = await context.newPage();
  
  // Collect all network requests
  const requests = [];
  page.on('request', request => {
    const url = request.url();
    if (url.includes('.m3u8') || url.includes('.txt') || url.includes('master')) {
      requests.push(url);
    }
  });

  // Inject script to bypass devtools detection
  await page.addInitScript(() => {
    // Prevent devtools detection
    const devtools = { open: false, orientation: null };
    Object.defineProperty(window, 'devtools', {
      get: () => devtools,
      set: () => {}
    });
    
    // Override console clearing
    const clear = console.clear;
    console.clear = () => {};
  });

  await page.goto(playerUrl, { waitUntil: 'networkidle', timeout: 30000 });
  
  // Wait for player initialization
  await page.waitForTimeout(3000);
  
  await browser.close();
  
  return requests[0] || null;
}
```

## 5. Complete Working Solution

```javascript
// kayihome-bypass.js
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

puppeteer.use(StealthPlugin());

class KayihomeBypass {
  constructor() {
    this.browser = null;
    this.page = null;
  }

  async init() {
    this.browser = await puppeteer.launch({
      headless: 'new',
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-accelerated-2d-canvas',
        '--no-first-run',
        '--no-zygote',
        '--disable-gpu',
        '--window-size=1920,1080'
      ]
    });

    this.page = await this.browser.newPage();
    
    // Set realistic viewport
    await this.page.setViewport({
      width: 1920,
      height: 1080,
      deviceScaleFactor: 1,
      isMobile: false,
      hasTouch: false,
      isLandscape: true
    });

    // Apply anti-detection patches
    await this.page.evaluateOnNewDocument(() => {
      // Patch outer dimensions
      Object.defineProperty(window, 'outerWidth', {
        get: () => 1920,
        enumerable: true,
        configurable: true
      });
      
      Object.defineProperty(window, 'outerHeight', {
        get: () => 1080,
        enumerable: true,
        configurable: true
      });
      
      // Remove automation indicators
      delete navigator.__proto__.webdriver;
      
      // Mock plugins
      Object.defineProperty(navigator, 'plugins', {
        get: () => [
          { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer' },
          { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai' }
        ]
      });
      
      // Mock languages
      Object.defineProperty(navigator, 'languages', {
        get: () => ['en-US', 'en']
      });
    });
  }

  async extractStream(playerUrl) {
    if (!this.page) await this.init();
    
    const m3u8Urls = [];
    
    // Setup request interception
    await this.page.setRequestInterception(true);
    this.page.on('request', (request) => {
      const url = request.url();
      if (url.includes('.m3u8') || url.includes('.txt') || 
          (url.includes('master') && url.includes('http'))) {
        m3u8Urls.push(url);
      }
      request.continue();
    });

    // Also capture response bodies
    this.page.on('response', async (response) => {
      const url = response.url();
      if (url.includes('api.php') && url.includes('getVideo')) {
        try {
          const data = await response.json();
          console.log('API Response:', data);
          if (data.videoSource) m3u8Urls.push(data.videoSource);
          if (data.securedLink) m3u8Urls.push(data.securedLink);
        } catch (e) {}
      }
    });

    // Navigate with proper referer
    await this.page.goto(playerUrl, { 
      waitUntil: 'networkidle2',
      timeout: 30000,
      referer: 'https://kayifamily.com/'
    });

    // Wait for player to initialize
    await this.page.waitForTimeout(5000);
    
    // Try to trigger video load by simulating interaction
    await this.page.evaluate(() => {
      const player = document.querySelector('#player');
      if (player) {
        player.click();
        player.dispatchEvent(new Event('play'));
      }
    });

    await this.page.waitForTimeout(2000);
    
    return [...new Set(m3u8Urls)];
  }

  async close() {
    if (this.browser) {
      await this.browser.close();
      this.browser = null;
      this.page = null;
    }
  }
}

// Usage
async function main() {
  const bypass = new KayihomeBypass();
  
  try {
    const urls = await bypass.extractStream(
      'https://kayihome.xyz/player/index.php?data=YOUR_DATA_HASH'
    );
    console.log('Found M3U8 URLs:', urls);
  } finally {
    await bypass.close();
  }
}

module.exports = { KayihomeBypass };
```

## 6. Installation Requirements

```bash
# Install dependencies
npm install puppeteer-extra puppeteer-extra-plugin-stealth axios

# For Playwright alternative
npm install playwright-extra playwright-extra-plugin-stealth

# For CDP patching (recommended for advanced cases)
npx rebrowser-patches@latest patch
```

## 7. Testing the Bypass

```bash
# Test with a real kayihome URL
node -e "
const { KayihomeBypass } = require('./kayihome-bypass');
const bypass = new KayihomeBypass();
bypass.extractStream('https://kayihome.xyz/player/index.php?data=53e3a7161e428b65688f14b84d61c610')
  .then(urls => console.log('Success:', urls))
  .catch(err => console.error('Failed:', err))
  .finally(() => bypass.close());
"
```

## 8. Known Limitations

1. **Rate Limiting**: Kayihome may rate-limit requests from same IP
2. **Token Expiration**: Data hashes expire after some time
3. **IP Geolocation**: Some videos may be region-locked
4. **Cloudflare**: May occasionally trigger CAPTCHA challenges

## 9. Alternative Approach: Using Cached/Alternative Sources

Since kayihome is heavily protected, consider these alternatives:

1. **bestb.stream** - Direct m3u8 in page source
2. **strmup.to** - Simpler iframe structure  
3. **Turktvuk** - Same content, less protection
4. **kor4c.com** - Alternative player with different obfuscation

## 10. Summary

The most reliable bypass technique is **Puppeteer with Stealth Plugin** combined with:
- Proper viewport settings matching `outerWidth`/`outerHeight`
- Network request interception to capture m3u8 URLs
- JavaScript injection to patch detection mechanisms
- Realistic browser fingerprint

For production use, consider implementing a fallback chain:
1. Try direct API call first (fastest)
2. Fall back to Puppeteer if blocked
3. Cache successful m3u8 URLs to reduce scraping
4. Rotate user agents and IPs if rate-limited
