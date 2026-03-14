# Turktvuk vs Kayihome Analysis

## Summary

**The major difference is that Turktvuk and Kayihome use completely different approaches to deliver video data:**

### Turktvuk (WORKS)
- **API Endpoint**: `POST /player/index.php?data=HASH&do=getVideo`
- **Response Format**: JSON with streaming URL
- **Response Data**: Contains `hls`, `videoSource`, `securedLink`, and `ck` (decryption key)
- **Flow**: 
  1. Load player page
  2. Extract packed JavaScript for `ck` key
  3. Call API with POST + cookies + proper headers
  4. Get JSON response with stream URL
  5. Decrypt URL if needed using `ck` key

### Kayihome (FAILS - But Fixable!)
- **API Endpoint**: `GET /player/api.php?data=HASH&do=getVideo`
- **Response Format**: Plain text "url is empty"
- **Actual Video Location**: **EMBEDDED DIRECTLY IN HTML PAGE SOURCE**
- **Flow**:
  1. Load player page
  2. Parse HTML to find `fireload()` function
  3. Extract `videoUrl` and `hostList` from JavaScript
  4. Construct stream URL by combining host + videoUrl

## Key Finding: Kayihome Video Data in HTML

From the player page HTML (`https://kayihome.xyz/player/index.php?data=a733fa9b25f33689e2adbe72199f0e62`):

```javascript
function fireload(vhash) {
    FirePlayer(vhash, {
        "hostList": {
            "1": ["reabc.xyz", "aeabc.xyz", "eeabc.xyz", "keabc.xyz", "teabc.xyz"]
        },
        "videoUrl": "\/cdn\/hls\/421214a1fb82834cad25f01a165e8f0f\/master.txt",
        "videoServer": "1",
        "videoDisk": null,
        "videoPlayer": "jwplayer",
        // ... more config
        "videoData": {
            "videoImage": null,
            "videoSources": [{
                "file": "https:\/\/1\/cdn\/hls\/421214a1fb82834cad25f01a165e8f0f\/master.txt",
                "label": "HD",
                "type": "hls"
            }]
        }
    }, false);
}
```

## Why Kayihome API Returns "url is empty"

Kayihome's API (`api.php`) is designed to return "url is empty" because:
1. **The video URL is already embedded in the HTML** - no API call needed
2. The server expects the player to extract data from the inline JavaScript
3. The API endpoint might be legacy or used for different purposes

## Solution for Kayihome

Instead of calling the API, parse the player page HTML:

```javascript
async function extractKayihomeStream(iframeUrl) {
    const dataMatch = iframeUrl.match(/data=([a-f0-9]+)/);
    if (!dataMatch) return null;
    
    const dataParam = dataMatch[1];
    
    // Fetch the player page
    const { data: html } = await axios.get(iframeUrl, {
        headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Referer': 'https://kayifamily.com/'
        },
        timeout: 10000
    });
    
    // Extract videoUrl from fireload function
    const videoUrlMatch = html.match(/"videoUrl"\s*:\\s*"([^"]+)"/);
    if (videoUrlMatch) {
        const videoUrl = videoUrlMatch[1].replace(/\\\//g, '/');
        // Construct full URL using a host from hostList
        // Hosts: reabc.xyz, aeabc.xyz, eeabc.xyz, keabc.xyz, teabc.xyz
        const fullUrl = `https://reabc.xyz${videoUrl}`;
        return {
            url: fullUrl,
            type: 'hls',
            source: 'kayifamily-kayihome'
        };
    }
    
    return null;
}
```

## Comparison Table

| Feature | Turktvuk | Kayihome |
|---------|----------|----------|
| API Endpoint | `index.php?data=HASH&do=getVideo` | `api.php?data=HASH&do=getVideo` |
| HTTP Method | POST | GET |
| Request Body | `hash=HASH&r=referer` | None |
| Cookies Required | Yes (fireplayer_player) | No |
| Response Format | JSON | Plain text |
| Video Data Location | API Response | Embedded in HTML |
| Stream Host | turktvuk.com | Multiple (reabc.xyz, aeabc.xyz, etc.) |
| URL Encryption | Yes (AES with ck key) | No (direct URL) |
| Secured Link | Yes (with md5 & expires) | No |

## Host Comparison

### Turktvuk Stream URL Example:
```
https://turktvuk.com/cdn/hls/23a8ad8e50888f8f06f79c9a1ba1f28d/master.m3u8?md5=SJ52Oky4bKyTjTXUqCW21A&expires=1773503593
```

### Kayihome Stream URL Example:
```
https://reabc.xyz/cdn/hls/421214a1fb82834cad25f01a165e8f0f/master.txt
```

Note: Kayihome uses `.txt` extension but serves HLS content.

## Recommendations

1. **For Kayihome**: Parse the player page HTML to extract `videoUrl` from the `fireload()` function instead of calling the API.

2. **Host Rotation**: Kayihome provides multiple hosts (reabc.xyz, aeabc.xyz, etc.) - implement fallback if one fails.

3. **URL Construction**: Kayihome video URLs are direct and don't require decryption or token parameters.

4. **No Cookies Needed**: Unlike Turktvuk, Kayihome doesn't appear to require session cookies for accessing the stream.
