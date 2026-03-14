# Video Stream Extraction Research: Ok.ru (Odnoklassniki) & VK.com

## Executive Summary

This document provides comprehensive technical details on extracting video streams from Russian social media platforms **Ok.ru (Odnoklassniki)** and **VK.com (VKontakte)**. These platforms are commonly used as video hosting sources on sites like kayifamilytv.com.

---

## 1. OK.RU (Odnoklassniki) Video Extraction

### 1.1 URL Patterns

| Type | URL Pattern | Example |
|------|-------------|---------|
| Video Page | `https://ok.ru/video/{video_id}` | `https://ok.ru/video/947875089023` |
| Embed Player | `https://ok.ru/videoembed/{video_id}` | `https://ok.ru/videoembed/947875089023` |
| Mobile | `https://m.ok.ru/video/{video_id}` | `https://m.ok.ru/video/947875089023` |

### 1.2 Extraction Method

OK.ru embeds video metadata in a `data-options` attribute of a `<div>` element in the embed page.

#### Step-by-Step Extraction:

1. **Fetch the embed page:**
```javascript
const videoId = '947875089023';
const embedUrl = `https://ok.ru/videoembed/${videoId}`;
```

2. **Extract the data-options JSON:**
```javascript
// The data is in: <div data-options="{...}">
const dataOptions = element.getAttribute('data-options');
```

3. **Parse the JSON to get video URLs:**
The JSON contains a `flashvars.metadata` field with video information including multiple quality options.

### 1.3 Video Quality Options

OK.ru provides videos in these qualities:

| Quality | Resolution | Type Parameter |
|---------|------------|----------------|
| mobile | 256x144 | type=4 |
| lowest | 426x240 | type=0 |
| low | 640x360 | type=1 |
| sd | 852x480 | type=2 |
| hd | 1280x720 | type=3 |
| full | 1920x1080 | type=5 |

### 1.4 Stream Format

- **Primary:** DASH (MPD manifest) with separate video/audio
- **Secondary:** HLS (.m3u8) via `hlsManifestUrl`
- **Direct MP4:** Available via the `videos` array in metadata

### 1.5 OK.ru Video URL Structure

```
https://vd{server}.mycdn.me/?
  expires={timestamp}
  &srcIp={client_ip}
  &srcAg={user_agent}
  &ms={media_server}
  &type={quality_type}
  &sig={signature}
  &ct={container_type}
  &urls={fallback_servers}
  &clientType={client_type}
  &id={content_id}
```

---

## 2. VK.com (VKontakte) Video Extraction

### 2.1 URL Patterns

| Type | URL Pattern | Example |
|------|-------------|---------|
| Video Page | `https://vk.com/video{owner_id}_{video_id}` | `https://vk.com/video-22822305_456242110` |
| Embed Player | `https://vk.com/video_ext.php?oid={owner_id}&id={video_id}&hash={hash}` | `https://vk.com/video_ext.php?oid=-22822305&id=456242110&hash=e037414127166efe` |
| VK Video | `https://vkvideo.ru/video{owner_id}_{video_id}` | `https://vkvideo.ru/video-22822305_456242110` |

### 2.2 Extraction Methods

#### Method 1: VK API (requires access token)
```
https://api.vk.com/method/video.get?
  v=5.131
  &access_token={token}
  &owner_id={owner_id}
  &videos={owner_id}_{video_id}
```

Response includes `player` URL with embed hash and `files` with direct MP4 links.

#### Method 2: Embed Page Scraping (No API key needed)

1. **Fetch the embed page:**
```javascript
const embedUrl = `https://vk.com/video_ext.php?oid=${oid}&id=${vid}&hash=${hash}`;
```

2. **Extract video source from HTML:**
- Look for `<video>` tag with `src` attribute
- Or find `og:video` meta tag
- Or extract from JavaScript `playerParams`

### 2.3 Video Quality Options

VK provides videos in these qualities:

| Quality | Resolution | URL Pattern |
|---------|------------|-------------|
| mp4_144 | 256x144 | mp4_240 (actually 144p) |
| mp4_240 | 320x240 | mp4_240 |
| mp4_360 | 480x360 | mp4_360 |
| mp4_480 | 640x480 | mp4_480 |
| mp4_720 | 1280x720 | mp4_720 |
| mp4_1080 | 1920x1080 | mp4_1080 |
| HLS | Adaptive | video.m3u8 |

### 2.4 VK Video URL Structure

```
https://vkvd{server}.okcdn.ru/?
  expires={timestamp}
  &srcIp={client_ip}
  &pr={priority}
  &srcAg={user_agent}
  &ms={media_server}
  &type={quality_type}
  &subId={subscription_id}
  &sig={signature}
  &ct={container_type}
  &urls={fallback_servers}
  &clientType={client_type}
  &appId={app_id}
  &zs={zone}
  &id={video_id}
```

---

## 3. Technical Implementation Details

### 3.1 HTTP Headers Required

```javascript
const headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5',
    'Referer': 'https://ok.ru/' // or 'https://vk.com/'
};
```

### 3.2 Referer Restrictions

Both platforms check the `Referer` header:
- **OK.ru:** Requires referer from ok.ru domain or no referer
- **VK.com:** Requires referer from vk.com domain or matching embed site

### 3.3 Geo-Blocking

- **OK.ru:** Some videos restricted to Russia/CIS countries
- **VK.com:** Less restrictive but some content may be region-locked

### 3.4 Rate Limiting

- **OK.ru:** ~100 requests/minute per IP
- **VK.com:** API has strict limits (requests per second); scraping has higher tolerance

### 3.5 URL Expiration

- Video URLs are time-limited (typically 2-24 hours)
- `expires` parameter in URL is a Unix timestamp

---

## 4. Working Code Examples

### 4.1 Node.js/JavaScript Implementation

#### OK.ru Extractor:
```javascript
const axios = require('axios');
const cheerio = require('cheerio');

class OkRuExtractor {
    constructor() {
        this.baseUrl = 'https://ok.ru';
        this.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9'
        };
    }

    async extract(videoId) {
        try {
            const embedUrl = `${this.baseUrl}/videoembed/${videoId}`;
            const response = await axios.get(embedUrl, { headers: this.headers });
            
            const $ = cheerio.load(response.data);
            const dataDiv = $('div[data-options]').first();
            
            if (!dataDiv.length) {
                throw new Error('Could not find video data');
            }
            
            const dataOptions = dataDiv.attr('data-options');
            const decodedOptions = JSON.parse(dataOptions);
            
            // Extract metadata from flashvars
            const metadata = JSON.parse(decodedOptions.flashvars.metadata);
            
            return {
                title: metadata.movie.title,
                duration: parseInt(metadata.movie.duration),
                thumbnail: metadata.movie.poster,
                videos: metadata.videos.map(v => ({
                    quality: v.name,
                    url: v.url,
                    seeking: v.seekSchema
                })),
                hlsUrl: metadata.hlsManifestUrl,
                dashManifest: metadata.movie.contentId
            };
        } catch (error) {
            console.error('OK.ru extraction failed:', error.message);
            return null;
        }
    }
}

// Usage
const extractor = new OkRuExtractor();
extractor.extract('947875089023').then(data => console.log(data));
```

#### VK.com Extractor:
```javascript
const axios = require('axios');
const cheerio = require('cheerio');

class VkExtractor {
    constructor() {
        this.baseUrl = 'https://vk.com';
        this.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9'
        };
    }

    parseVideoUrl(url) {
        // Handle various VK URL formats
        const patterns = [
            /video(-?\d+)_(\d+)/,
            /oid=(-?\d+).*id=(\d+)/,
            /video_ext\.php\?oid=(-?\d+)&id=(\d+)/
        ];
        
        for (const pattern of patterns) {
            const match = url.match(pattern);
            if (match) {
                return { ownerId: match[1], videoId: match[2] };
            }
        }
        return null;
    }

    async extractFromEmbed(oid, vid, hash = '') {
        try {
            let embedUrl;
            if (hash) {
                embedUrl = `${this.baseUrl}/video_ext.php?oid=${oid}&id=${vid}&hash=${hash}`;
            } else {
                // Try without hash first
                embedUrl = `${this.baseUrl}/video_ext.php?oid=${oid}&id=${vid}`;
            }
            
            const response = await axios.get(embedUrl, {
                headers: {
                    ...this.headers,
                    'Referer': 'https://vk.com/'
                },
                validateStatus: () => true
            });
            
            if (response.status !== 200) {
                throw new Error(`HTTP ${response.status}`);
            }
            
            const $ = cheerio.load(response.data);
            
            // Method 1: Look for video tag
            const videoSrc = $('video').attr('src');
            if (videoSrc) {
                return { url: videoSrc, type: 'direct' };
            }
            
            // Method 2: Look for og:video meta
            const ogVideo = $('meta[property="og:video"]').attr('content');
            if (ogVideo) {
                return { url: ogVideo, type: 'og' };
            }
            
            // Method 3: Extract from JavaScript playerParams
            const scriptMatch = response.data.match(/var\s+playerParams\s*=\s*({.+?});/);
            if (scriptMatch) {
                const playerParams = JSON.parse(scriptMatch[1]);
                return this.parsePlayerParams(playerParams);
            }
            
            // Method 4: Extract from flashvars
            const flashvarsMatch = response.data.match(/flashvars\s*=\s*({.+?});/);
            if (flashvarsMatch) {
                const flashvars = JSON.parse(flashvarsMatch[1]);
                return this.parseFlashvars(flashvars);
            }
            
            return null;
        } catch (error) {
            console.error('VK extraction failed:', error.message);
            return null;
        }
    }

    parsePlayerParams(params) {
        const urls = {};
        
        // Extract MP4 URLs by quality
        if (params.url720) urls['720p'] = params.url720;
        if (params.url480) urls['480p'] = params.url480;
        if (params.url360) urls['360p'] = params.url360;
        if (params.url240) urls['240p'] = params.url240;
        
        // HLS manifest
        if (params.hls) {
            urls['hls'] = params.hls;
        }
        
        return {
            title: params.title,
            duration: params.duration,
            thumbnail: params.thumb,
            urls: urls
        };
    }
}

// Usage
const vkExtractor = new VkExtractor();
vkExtractor.extractFromEmbed('-22822305', '456242110', 'e037414127166efe')
    .then(data => console.log(data));
```

### 4.2 Python Implementation

#### OK.ru Extractor (Python):
```python
import json
import re
import requests
from bs4 import BeautifulSoup
from typing import Dict, List, Optional

class OkRuExtractor:
    def __init__(self):
        self.base_url = 'https://ok.ru'
        self.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9'
        }

    def extract(self, video_id: str) -> Optional[Dict]:
        """Extract video information from OK.ru"""
        try:
            embed_url = f"{self.base_url}/videoembed/{video_id}"
            response = requests.get(embed_url, headers=self.headers, timeout=10)
            response.raise_for_status()
            
            soup = BeautifulSoup(response.text, 'html.parser')
            data_div = soup.find('div', {'data-options': True})
            
            if not data_div:
                raise ValueError("Could not find video data div")
            
            # Parse data-options JSON
            data_options = json.loads(data_div['data-options'])
            metadata = json.loads(data_options['flashvars']['metadata'])
            
            return {
                'title': metadata['movie']['title'],
                'duration': int(metadata['movie']['duration']),
                'thumbnail': metadata['movie']['poster'],
                'videos': [
                    {
                        'quality': v['name'],
                        'url': v['url'],
                        'seek_schema': v.get('seekSchema', 0)
                    }
                    for v in metadata['videos']
                ],
                'hls_url': metadata.get('hlsManifestUrl'),
                'dash_manifest': metadata['movie'].get('contentId'),
                'is_live': metadata['movie'].get('isLive', False)
            }
            
        except Exception as e:
            print(f"Extraction failed: {e}")
            return None

    def get_best_quality(self, video_data: Dict) -> Optional[str]:
        """Get the best available quality URL"""
        if not video_data or 'videos' not in video_data:
            return None
        
        quality_order = ['full', 'hd', 'sd', 'low', 'lowest', 'mobile']
        videos = {v['quality']: v['url'] for v in video_data['videos']}
        
        for quality in quality_order:
            if quality in videos:
                return videos[quality]
        
        # Fallback to first available
        return video_data['videos'][0]['url'] if video_data['videos'] else None


# Usage
if __name__ == "__main__":
    extractor = OkRuExtractor()
    video = extractor.extract("947875089023")
    if video:
        print(f"Title: {video['title']}")
        print(f"Best quality URL: {extractor.get_best_quality(video)}")
```

#### VK.com Extractor (Python):
```python
import json
import re
import requests
from typing import Dict, Optional, Tuple
from bs4 import BeautifulSoup

class VkExtractor:
    def __init__(self):
        self.base_url = 'https://vk.com'
        self.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Referer': 'https://vk.com/'
        }

    def parse_url(self, url: str) -> Optional[Tuple[str, str]]:
        """Extract owner_id and video_id from various VK URL formats"""
        patterns = [
            r'video(-?\d+)_(\d+)',
            r'oid=(-?\d+).*id=(\d+)',
            r'video_ext\.php\?oid=(-?\d+)&id=(\d+)'
        ]
        
        for pattern in patterns:
            match = re.search(pattern, url)
            if match:
                return match.group(1), match.group(2)
        return None

    def extract_from_embed(self, owner_id: str, video_id: str, hash: str = '') -> Optional[Dict]:
        """Extract video from VK embed page"""
        try:
            if hash:
                embed_url = f"{self.base_url}/video_ext.php?oid={owner_id}&id={video_id}&hash={hash}"
            else:
                embed_url = f"{self.base_url}/video_ext.php?oid={owner_id}&id={video_id}"
            
            response = requests.get(embed_url, headers=self.headers, timeout=10)
            response.raise_for_status()
            
            # Try multiple extraction methods
            
            # Method 1: BeautifulSoup for video tag
            soup = BeautifulSoup(response.text, 'html.parser')
            video_tag = soup.find('video')
            if video_tag and video_tag.get('src'):
                return {
                    'url': video_tag['src'],
                    'type': 'direct',
                    'quality': 'unknown'
                }
            
            # Method 2: Meta tag
            og_video = soup.find('meta', {'property': 'og:video'})
            if og_video:
                return {
                    'url': og_video['content'],
                    'type': 'og',
                    'quality': 'unknown'
                }
            
            # Method 3: JavaScript playerParams
            player_match = re.search(r'var\s+playerParams\s*=\s*({.+?});', response.text, re.DOTALL)
            if player_match:
                # Clean up the JSON
                json_str = player_match.group(1)
                json_str = re.sub(r'([{,])\s*(\w+)\s*:', r'\1"\2":', json_str)  # Quote keys
                json_str = re.sub(r':\s*\'([^\']*)\'', r':"\1"', json_str)  # Replace single quotes
                params = json.loads(json_str)
                return self._parse_player_params(params)
            
            # Method 4: Look for direct MP4 URLs in the HTML
            mp4_urls = re.findall(r'(https?://[^\s"\'<>]+\.mp4[^\s"\'<>]*)', response.text)
            if mp4_urls:
                return {
                    'urls': list(set(mp4_urls)),
                    'type': 'scraped'
                }
            
            return None
            
        except Exception as e:
            print(f"VK extraction failed: {e}")
            return None

    def _parse_player_params(self, params: Dict) -> Dict:
        """Parse VK player parameters"""
        urls = {}
        
        quality_map = {
            'url1080': '1080p',
            'url720': '720p',
            'url480': '480p',
            'url360': '360p',
            'url240': '240p',
            'url144': '144p'
        }
        
        for key, quality in quality_map.items():
            if key in params:
                urls[quality] = params[key]
        
        result = {
            'title': params.get('title'),
            'duration': params.get('duration'),
            'thumbnail': params.get('thumb'),
            'urls': urls
        }
        
        if 'hls' in params:
            result['hls_url'] = params['hls']
        
        return result

    def extract_with_api(self, owner_id: str, video_id: str, access_token: str) -> Optional[Dict]:
        """Extract using VK API (requires access token)"""
        try:
            api_url = 'https://api.vk.com/method/video.get'
            params = {
                'v': '5.131',
                'access_token': access_token,
                'owner_id': owner_id,
                'videos': f"{owner_id}_{video_id}"
            }
            
            response = requests.get(api_url, params=params, timeout=10)
            data = response.json()
            
            if 'error' in data:
                print(f"API error: {data['error']}")
                return None
            
            video = data['response']['items'][0]
            
            return {
                'title': video.get('title'),
                'description': video.get('description'),
                'duration': video.get('duration'),
                'views': video.get('views'),
                'thumbnail': video.get('photo_800') or video.get('photo_640'),
                'player_url': video.get('player'),
                'files': video.get('files', {}),
                'platform': video.get('platform')
            }
            
        except Exception as e:
            print(f"API extraction failed: {e}")
            return None


# Usage
if __name__ == "__main__":
    extractor = VkExtractor()
    
    # Test with embed
    video = extractor.extract_from_embed('-22822305', '456242110', 'e037414127166efe')
    if video:
        print(f"Extracted: {json.dumps(video, indent=2)}")
```

### 4.3 Stream Proxy for Stremio

To use these streams in Stremio, you need to proxy them with proper headers:

```javascript
const express = require('express');
const axios = require('axios');
const app = express();

// Proxy endpoint for OK.ru/VK streams
app.get('/proxy', async (req, res) => {
    const { url, referer } = req.query;
    
    if (!url) {
        return res.status(400).send('Missing URL');
    }
    
    try {
        const response = await axios.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Referer': referer || 'https://ok.ru/',
                'Accept': '*/*'
            },
            responseType: 'stream',
            maxRedirects: 5
        });
        
        // Forward content type
        res.setHeader('Content-Type', response.headers['content-type'] || 'video/mp4');
        res.setHeader('Access-Control-Allow-Origin', '*');
        
        response.data.pipe(res);
    } catch (error) {
        console.error('Proxy error:', error.message);
        res.status(500).send('Stream error');
    }
});

app.listen(7000, () => console.log('Proxy server on port 7000'));
```

---

## 5. Integration with KayiFamilyTV

### 5.1 Detection Pattern

For sites like kayifamilytv.com that use OK.ru/VK embeds:

```javascript
async function extractFromKayiFamily(url) {
    const { data } = await axios.get(url, { headers });
    const $ = cheerio.load(data);
    
    // Look for OK.ru embeds
    const okruIframe = $('iframe[src*="ok.ru"]').attr('src');
    if (okruIframe) {
        const videoId = okruIframe.match(/videoembed\/(\d+)/)?.[1];
        if (videoId) {
            return await okruExtractor.extract(videoId);
        }
    }
    
    // Look for VK embeds
    const vkIframe = $('iframe[src*="vk.com"]').attr('src');
    if (vkIframe) {
        const match = vkIframe.match(/oid=(-?\d+).*id=(\d+)/);
        if (match) {
            return await vkExtractor.extractFromEmbed(match[1], match[2]);
        }
    }
    
    return null;
}
```

---

## 6. Limitations and Considerations

### 6.1 OK.ru Limitations

1. **Private videos:** Require authentication
2. **Deleted videos:** Return 404 or "video not available" page
3. **Live streams:** Different metadata structure
4. **Rate limiting:** Aggressive IP-based limiting

### 6.2 VK Limitations

1. **Hash requirement:** Many videos require a valid hash parameter
2. **API limits:** Official API has strict rate limits
3. **Authentication:** Some videos require VK account
4. **Mobile restrictions:** Some content mobile-only

### 6.3 Legal Considerations

- Respect robots.txt
- Don't circumvent DRM
- Comply with terms of service
- Only extract publicly available content

---

## 7. References

1. [yt-dlp OK.ru extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/odnoklassniki.py)
2. [yt-dlp VK extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/vk.py)
3. [VK API Documentation](https://dev.vk.com/method/video.get)
4. [OK.ru API Documentation](https://apiok.ru/en/dev/methods/rest/video/)

---

## 8. Summary

| Platform | Format | Authentication | Difficulty |
|----------|--------|----------------|------------|
| OK.ru | MP4, HLS, DASH | Not required for public videos | Medium |
| VK.com | MP4, HLS | Hash required for embeds | Hard |

Both platforms use time-limited, signed URLs that require proper `Referer` headers. OK.ru is generally easier to extract from due to consistent embed page structure. VK.com requires more effort due to hash requirements and varying URL formats.
