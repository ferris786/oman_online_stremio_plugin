/**
 * Kayihome.xyz Video Extraction with Bypass Techniques
 * 
 * This module uses Puppeteer with stealth plugins to bypass
 * kayihome's devtools detection and anti-bot measures.
 */

const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

// Enable stealth mode with all evasions
const stealth = StealthPlugin();
stealth.enabledEvasions.delete('chrome.runtime'); // Can cause issues
puppeteer.use(stealth);

class KayihomeExtractor {
  constructor(options = {}) {
    this.options = {
      headless: options.headless !== false, // default true
      timeout: options.timeout || 30000,
      waitTime: options.waitTime || 5000,
      ...options
    };
    this.browser = null;
    this.page = null;
  }

  /**
   * Initialize browser with anti-detection settings
   */
  async init() {
    if (this.browser) return;

    const launchOptions = {
      headless: this.options.headless ? 'new' : false,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-accelerated-2d-canvas',
        '--no-first-run',
        '--no-zygote',
        '--disable-gpu',
        '--window-size=1920,1080',
        '--disable-blink-features=AutomationControlled',
        '--disable-features=IsolateOrigins,site-per-process',
      ],
      ignoreDefaultArgs: ['--enable-automation'],
    };

    this.browser = await puppeteer.launch(launchOptions);
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
      // Override outerWidth/outerHeight to match viewport
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

      // Remove webdriver
      Object.defineProperty(navigator, 'webdriver', {
        get: () => undefined,
        enumerable: true,
        configurable: true
      });

      // Mock plugins
      Object.defineProperty(navigator, 'plugins', {
        get: () => [
          {
            name: 'Chrome PDF Plugin',
            filename: 'internal-pdf-viewer',
            description: 'Portable Document Format',
            version: undefined,
            length: 1,
            item: () => null,
            namedItem: () => null
          },
          {
            name: 'Chrome PDF Viewer',
            filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai',
            description: 'Portable Document Format plugin',
            version: undefined,
            length: 1,
            item: () => null,
            namedItem: () => null
          },
          {
            name: 'Native Client',
            filename: 'internal-nacl-plugin',
            description: 'Native Client module',
            version: undefined,
            length: 2,
            item: () => null,
            namedItem: () => null
          }
        ]
      });

      // Mock languages
      Object.defineProperty(navigator, 'languages', {
        get: () => ['en-US', 'en', 'tr']
      });

      // Override permissions
      const originalQuery = window.navigator.permissions.query;
      window.navigator.permissions.query = (parameters) => {
        if (parameters.name === 'notifications') {
          return Promise.resolve({ state: Notification.permission });
        }
        return originalQuery(parameters);
      };

      // Prevent devtools detection via debugger
      const originalConsoleClear = console.clear;
      console.clear = () => { };

      // Override Function.prototype.toString to hide patches
      const originalToString = Function.prototype.toString;
      Function.prototype.toString = function () {
        if (this === window.navigator.permissions.query) {
          return 'function query() { [native code] }';
        }
        return originalToString.call(this);
      };
    });

    // Set extra HTTP headers
    await this.page.setExtraHTTPHeaders({
      'Accept-Language': 'en-US,en;q=0.9,tr;q=0.8'
    });
  }

  /**
   * Extract video stream URL from kayihome player
   * @param {string} playerUrl - Full kayihome player URL
   * @returns {Promise<{url: string, type: string}|null>}
   */
  async extractStream(playerUrl) {
    await this.init();

    const m3u8Urls = [];
    const apiResponses = [];

    // Setup request interception
    await this.page.setRequestInterception(true);

    this.page.on('request', (request) => {
      const url = request.url();

      // Capture m3u8 and txt playlist URLs
      if (url.includes('.m3u8') ||
        (url.includes('.txt') && url.includes('cdn')) ||
        (url.includes('master') && url.includes('http'))) {
        m3u8Urls.push({
          url: url,
          type: 'hls',
          timestamp: Date.now()
        });
      }

      // Continue all requests
      request.continue();
    });

    // Capture API responses
    this.page.on('response', async (response) => {
      const url = response.url();
      if (url.includes('api.php') && url.includes('getVideo')) {
        try {
          const contentType = response.headers()['content-type'] || '';
          if (contentType.includes('json')) {
            const data = await response.json();
            apiResponses.push(data);
          } else {
            const text = await response.text();
            apiResponses.push({ text, url });
          }
        } catch (e) {
          // Non-JSON response
        }
      }
    });

    try {
      // Navigate to player page
      await this.page.goto(playerUrl, {
        waitUntil: 'networkidle2',
        timeout: this.options.timeout,
        referer: 'https://kayifamily.com/'
      });

      // Wait for player initialization
      await this.page.waitForTimeout(this.options.waitTime);

      // Try to interact with player to trigger video load
      await this.page.evaluate(() => {
        // Click on player area if exists
        const player = document.querySelector('#player, #playerbase, .jwplayer');
        if (player) {
          player.click();
        }

        // Try to trigger play
        const video = document.querySelector('video');
        if (video) {
          video.play().catch(() => { });
        }
      });

      // Wait a bit more for video to load
      await this.page.waitForTimeout(2000);

      // Check for jwplayer configuration
      const jwConfig = await this.page.evaluate(() => {
        if (window.jwplayer) {
          try {
            const config = jwplayer().getConfig();
            return {
              file: config.file,
              sources: config.sources,
              playlist: config.playlist
            };
          } catch (e) {
            return null;
          }
        }
        return null;
      });

      if (jwConfig) {
        if (jwConfig.file) {
          m3u8Urls.push({
            url: jwConfig.file,
            type: 'hls',
            source: 'jwplayer-config'
          });
        }
        if (jwConfig.sources) {
          jwConfig.sources.forEach(src => {
            if (src.file) {
              m3u8Urls.push({
                url: src.file,
                type: src.type || 'hls',
                source: 'jwplayer-sources'
              });
            }
          });
        }
      }

      // Return the first valid URL found
      if (m3u8Urls.length > 0) {
        // Deduplicate
        const unique = [...new Map(m3u8Urls.map(item => [item.url, item])).values()];
        return {
          url: unique[0].url,
          type: unique[0].type || 'hls',
          alternatives: unique.slice(1).map(u => u.url),
          apiResponses: apiResponses
        };
      }

      // Check if we got an API response with video data
      for (const resp of apiResponses) {
        if (resp.videoSource) {
          return {
            url: resp.videoSource,
            type: resp.hls ? 'hls' : 'mp4',
            apiResponses: apiResponses
          };
        }
        if (resp.securedLink) {
          return {
            url: resp.securedLink,
            type: 'hls',
            apiResponses: apiResponses
          };
        }
      }

      return null;

    } catch (error) {
      console.error('[KayihomeBypass] Extraction error:', error.message);
      return null;
    }
  }

  /**
   * Try to extract using direct API call first (faster)
   * @param {string} dataHash - The data parameter from URL
   * @param {string} referer - Referer URL
   * @returns {Promise<Object|null>}
   */
  async tryDirectAPI(dataHash, referer = 'https://kayifamily.com/') {
    const axios = require('axios');

    const apiUrl = `https://kayihome.xyz/player/api.php?data=${dataHash}&do=getVideo`;

    try {
      const response = await axios.post(apiUrl,
        new URLSearchParams({
          hash: dataHash,
          r: referer
        }), {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
          'Accept': 'application/json, text/javascript, */*; q=0.01',
          'Accept-Language': 'en-US,en;q=0.9,tr;q=0.8',
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Requested-With': 'XMLHttpRequest',
          'Origin': 'https://kayihome.xyz',
          'Referer': `https://kayihome.xyz/player/index.php?data=${dataHash}`,
          'Sec-Fetch-Dest': 'empty',
          'Sec-Fetch-Mode': 'cors',
          'Sec-Fetch-Site': 'same-origin'
        },
        timeout: 10000,
        withCredentials: true
      });

      const data = response.data;

      // Check if response is a valid video response
      if (typeof data === 'object') {
        if (data.videoSource || data.securedLink) {
          return {
            url: data.securedLink || data.videoSource,
            type: data.hls ? 'hls' : 'mp4',
            encrypted: !data.hls,
            ck: data.ck,
            raw: data
          };
        }
      }

      return null;
    } catch (error) {
      return null;
    }
  }

  /**
   * Main extraction method - tries direct API first, then falls back to browser
   * @param {string} playerUrl - Kayihome player URL
   * @returns {Promise<Object|null>}
   */
  async extract(playerUrl) {
    // Extract data hash from URL
    const dataMatch = playerUrl.match(/data=([a-f0-9]+)/);
    if (!dataMatch) {
      console.error('[KayihomeBypass] Invalid player URL');
      return null;
    }

    const dataHash = dataMatch[1];

    // Try direct API first (much faster)
    console.log('[KayihomeBypass] Trying direct API...');
    const directResult = await this.tryDirectAPI(dataHash);
    if (directResult) {
      console.log('[KayihomeBypass] Direct API success');
      return directResult;
    }

    // Fall back to browser automation
    console.log('[KayihomeBypass] Direct API failed, using browser...');
    return await this.extractStream(playerUrl);
  }

  /**
   * Close browser instance
   */
  async close() {
    if (this.browser) {
      await this.browser.close();
      this.browser = null;
      this.page = null;
    }
  }
}

module.exports = { KayihomeExtractor };

// If run directly, test extraction
if (require.main === module) {
  const testUrl = process.argv[2] || 'https://kayihome.xyz/player/index.php?data=53e3a7161e428b65688f14b84d61c610';

  console.log('Testing kayihome extraction...');
  console.log('URL:', testUrl);

  const extractor = new KayihomeExtractor({ headless: false });

  extractor.extract(testUrl)
    .then(result => {
      if (result) {
        console.log('\n✓ Extraction successful!');
        console.log('URL:', result.url);
        console.log('Type:', result.type);
        if (result.alternatives?.length) {
          console.log('Alternatives:', result.alternatives.length);
        }
      } else {
        console.log('\n✗ Extraction failed - no video URL found');
      }
    })
    .catch(err => {
      console.error('Error:', err.message);
    })
    .finally(() => {
      extractor.close();
    });
}
