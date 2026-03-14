#!/usr/bin/env python3
"""
OK.ru and VK.com Video Extractors (Python)

This module provides video extraction capabilities for OK.ru (Odnoklassniki)
and VK.com (VKontakte) video hosting platforms.

Usage:
    python extractors_python.py okru <video_id>
    python extractors_python.py vk <owner_id> <video_id> [hash]
"""

import json
import re
import sys
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass, asdict

import requests
from bs4 import BeautifulSoup


@dataclass
class VideoStream:
    """Represents a video stream with metadata"""
    url: str
    quality: str
    seeking: int = 0
    disallowed: bool = False


@dataclass
class VideoInfo:
    """Represents extracted video information"""
    id: str
    title: str
    duration: int
    thumbnail: Optional[str]
    is_live: bool
    streams: List[VideoStream]
    hls_url: Optional[str] = None
    dash_manifest: Optional[str] = None


class OkRuExtractor:
    """Extractor for OK.ru (Odnoklassniki) videos"""
    
    BASE_URL = 'https://ok.ru'
    HEADERS = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
    }
    
    # Quality preference order (best to worst)
    QUALITY_ORDER = ['full', 'hd', 'sd', 'low', 'lowest', 'mobile']
    
    def parse_video_id(self, url: str) -> Optional[str]:
        """Extract video ID from various OK.ru URL formats"""
        patterns = [
            r'ok\.ru/videoembed/(\d+)',
            r'ok\.ru/video/(\d+)',
            r'ok\.ru/video/[a-z]+/(\d+)',
        ]
        
        for pattern in patterns:
            match = re.search(pattern, url)
            if match:
                return match.group(1)
        return None
    
    def extract(self, video_id: str) -> Optional[VideoInfo]:
        """Extract video information from OK.ru"""
        try:
            print(f"[OK.ru] Extracting video ID: {video_id}")
            
            embed_url = f"{self.BASE_URL}/videoembed/{video_id}"
            response = requests.get(embed_url, headers=self.HEADERS, timeout=15)
            response.raise_for_status()
            
            soup = BeautifulSoup(response.text, 'html.parser')
            data_div = soup.find('div', {'data-options': True})
            
            if not data_div:
                print("[OK.ru] No data-options div found - video may be private/deleted")
                return None
            
            # Parse data-options
            data_options = json.loads(data_div['data-options'])
            metadata = json.loads(data_options['flashvars']['metadata'])
            
            # Build streams list
            streams = []
            for v in metadata.get('videos', []):
                streams.append(VideoStream(
                    url=v['url'],
                    quality=v['name'],
                    seeking=v.get('seekSchema', 0),
                    disallowed=v.get('disallowed', False)
                ))
            
            return VideoInfo(
                id=video_id,
                title=metadata['movie']['title'],
                duration=int(metadata['movie']['duration']),
                thumbnail=metadata['movie'].get('poster'),
                is_live=metadata['movie'].get('isLive', False),
                streams=streams,
                hls_url=metadata.get('hlsManifestUrl'),
                dash_manifest=metadata['movie'].get('contentId')
            )
            
        except Exception as e:
            print(f"[OK.ru] Extraction failed: {e}")
            return None
    
    def get_best_quality(self, video_info: VideoInfo, preferred: str = 'hd') -> Optional[str]:
        """Get the best available stream URL"""
        if not video_info.streams:
            return None
        
        # Check preferred quality first
        for stream in video_info.streams:
            if stream.quality == preferred and not stream.disallowed:
                return stream.url
        
        # Fall back to quality order
        stream_map = {s.quality: s for s in video_info.streams if not s.disallowed}
        for quality in self.QUALITY_ORDER:
            if quality in stream_map:
                return stream_map[quality].url
        
        # Last resort: first available
        for stream in video_info.streams:
            if not stream.disallowed:
                return stream.url
        return None
    
    def get_stremio_stream(self, video_id: str, preferred_quality: str = 'hd') -> Optional[Dict]:
        """Get stream in Stremio format"""
        video_info = self.extract(video_id)
        if not video_info:
            return None
        
        stream_url = self.get_best_quality(video_info, preferred_quality)
        if not stream_url:
            return None
        
        return {
            'name': 'OK.ru',
            'title': f"OK.ru - {video_info.title} ({preferred_quality})",
            'url': stream_url,
            'behaviorHints': {
                'notWebReady': False,
                'proxyHeaders': {
                    'request': {
                        'User-Agent': self.HEADERS['User-Agent'],
                        'Referer': f"{self.BASE_URL}/",
                        'Accept': '*/*'
                    }
                }
            }
        }


class VkExtractor:
    """Extractor for VK.com (VKontakte) videos"""
    
    BASE_URL = 'https://vk.com'
    HEADERS = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
        'Referer': 'https://vk.com/',
    }
    
    QUALITY_MAP = {
        'url1080': '1080p',
        'url720': '720p',
        'url480': '480p',
        'url360': '360p',
        'url240': '240p',
        'url144': '144p',
    }
    
    def parse_video_url(self, url: str) -> Optional[Tuple[str, str]]:
        """Extract owner_id and video_id from VK URL"""
        patterns = [
            r'video(-?\d+)_(\d+)',
            r'oid=(-?\d+)[^&]*&id=(\d+)',
            r'vkvideo\.ru/video(-?\d+)_(\d+)',
        ]
        
        for pattern in patterns:
            match = re.search(pattern, url)
            if match:
                return match.group(1), match.group(2)
        return None
    
    def extract(self, owner_id: str, video_id: str, hash: str = '') -> Optional[Dict]:
        """Extract video from VK embed page"""
        try:
            print(f"[VK] Extracting video: oid={owner_id}, id={video_id}")
            
            if hash:
                embed_url = f"{self.BASE_URL}/video_ext.php?oid={owner_id}&id={video_id}&hash={hash}"
            else:
                embed_url = f"{self.BASE_URL}/video_ext.php?oid={owner_id}&id={video_id}"
            
            response = requests.get(embed_url, headers=self.HEADERS, timeout=15)
            response.raise_for_status()
            
            return self._extract_from_html(response.text, owner_id, video_id)
            
        except Exception as e:
            print(f"[VK] Extraction failed: {e}")
            return None
    
    def _extract_from_html(self, html: str, owner_id: str, video_id: str) -> Optional[Dict]:
        """Extract video data from HTML using multiple methods"""
        
        # Method 1: Video tag
        match = re.search(r'<video[^>]+src=["\']([^"\']+)["\']', html)
        if match:
            print("[VK] Found video tag")
            return {
                'owner_id': owner_id,
                'video_id': video_id,
                'url': match.group(1),
                'type': 'direct',
                'qualities': {}
            }
        
        # Method 2: og:video meta
        match = re.search(r'<meta[^>]+property=["\']og:video["\'][^>]+content=["\']([^"\']+)["\']', html)
        if match:
            print("[VK] Found og:video meta")
            return {
                'owner_id': owner_id,
                'video_id': video_id,
                'url': match.group(1),
                'type': 'og',
                'qualities': {}
            }
        
        # Method 3: playerParams
        match = re.search(r'var\s+playerParams\s*=\s*({[\s\S]+?});', html)
        if match:
            print("[VK] Found playerParams")
            try:
                json_str = self._clean_js_to_json(match.group(1))
                params = json.loads(json_str)
                return self._parse_player_params(params, owner_id, video_id)
            except json.JSONDecodeError as e:
                print(f"[VK] Failed to parse playerParams: {e}")
        
        # Method 4: Direct MP4 URLs
        mp4_urls = re.findall(r'https://vkvd\d+\.okcdn\.ru/[^\s"\'<>]+\.mp4[^\s"\'<>]*', html)
        if mp4_urls:
            print(f"[VK] Found {len(mp4_urls)} MP4 URLs")
            qualities = self._organize_by_quality(mp4_urls)
            return {
                'owner_id': owner_id,
                'video_id': video_id,
                'url': mp4_urls[0],
                'type': 'scraped',
                'qualities': qualities
            }
        
        # Method 5: HLS manifest
        match = re.search(r'(https://vkvd\d+\.okcdn\.ru/[^\s"\'<>]+\.m3u8[^\s"\'<>]*)', html)
        if match:
            print("[VK] Found HLS manifest")
            return {
                'owner_id': owner_id,
                'video_id': video_id,
                'url': match.group(1),
                'type': 'hls',
                'qualities': {}
            }
        
        print("[VK] No video data found in HTML")
        return None
    
    def _clean_js_to_json(self, js_str: str) -> str:
        """Convert JavaScript object string to valid JSON"""
        # Replace single quotes with double quotes
        json_str = js_str.replace("'", '"')
        # Quote unquoted keys
        json_str = re.sub(r'([{,]\s*)(\w+):', r'\1"\2":', json_str)
        # Remove trailing commas
        json_str = re.sub(r',\s*([}])', r'\1', json_str)
        return json_str
    
    def _parse_player_params(self, params: Dict, owner_id: str, video_id: str) -> Dict:
        """Parse VK player parameters"""
        qualities = {}
        
        for key, label in self.QUALITY_MAP.items():
            if key in params:
                qualities[label] = params[key]
        
        # Find best URL
        best_url = None
        for key in ['url1080', 'url720', 'url480', 'url360', 'url240', 'url144', 'url']:
            if params.get(key):
                best_url = params[key]
                break
        
        return {
            'owner_id': owner_id,
            'video_id': video_id,
            'title': params.get('title') or params.get('video_title'),
            'duration': params.get('duration'),
            'thumbnail': params.get('thumb') or params.get('jpg'),
            'url': best_url,
            'hls_url': params.get('hls'),
            'type': 'hls' if params.get('hls') else 'mp4',
            'qualities': qualities
        }
    
    def _organize_by_quality(self, urls: List[str]) -> Dict[str, str]:
        """Organize URLs by quality based on URL parameters"""
        qualities = {}
        
        for url in urls:
            if 'type=3' in url or '1080' in url:
                qualities['1080p'] = url
            elif 'type=2' in url or '720' in url:
                qualities['720p'] = url
            elif 'type=1' in url or '480' in url:
                qualities['480p'] = url
            elif 'type=0' in url or '360' in url:
                qualities['360p'] = url
            else:
                qualities['unknown'] = url
        
        return qualities
    
    def get_best_quality(self, video_data: Dict, preferred: str = '720p') -> Optional[str]:
        """Get best quality URL from video data"""
        if not video_data:
            return None
        
        qualities = video_data.get('qualities', {})
        
        if preferred in qualities:
            return qualities[preferred]
        
        for quality in ['1080p', '720p', '480p', '360p', '240p']:
            if quality in qualities:
                return qualities[quality]
        
        return video_data.get('url')
    
    def get_stremio_stream(self, owner_id: str, video_id: str, 
                          hash: str = '', preferred_quality: str = '720p') -> Optional[Dict]:
        """Get stream in Stremio format"""
        video_data = self.extract(owner_id, video_id, hash)
        if not video_data:
            return None
        
        stream_url = self.get_best_quality(video_data, preferred_quality)
        if not stream_url:
            return None
        
        return {
            'name': 'VK.com',
            'title': f"VK.com - {video_data.get('title', 'Video')} ({preferred_quality})",
            'url': stream_url,
            'behaviorHints': {
                'notWebReady': False,
                'proxyHeaders': {
                    'request': {
                        'User-Agent': self.HEADERS['User-Agent'],
                        'Referer': f"{self.BASE_URL}/",
                        'Accept': '*/*'
                    }
                }
            }
        }


def test_okru(video_id: str):
    """Test OK.ru extractor"""
    print("\n" + "="*50)
    print("Testing OK.ru Extractor")
    print("="*50 + "\n")
    
    extractor = OkRuExtractor()
    result = extractor.extract(video_id)
    
    if result:
        print(f"Title: {result.title}")
        print(f"Duration: {result.duration}s")
        print(f"Is Live: {result.is_live}")
        print(f"\nAvailable streams:")
        for stream in result.streams:
            print(f"  - {stream.quality}: {stream.url[:60]}...")
        
        best = extractor.get_best_quality(result, 'hd')
        print(f"\nBest quality URL: {best[:80]}..." if best else "\nNo suitable URL found")
    else:
        print("Extraction failed!")


def test_vk(owner_id: str, video_id: str, hash: str = ''):
    """Test VK extractor"""
    print("\n" + "="*50)
    print("Testing VK.com Extractor")
    print("="*50 + "\n")
    
    extractor = VkExtractor()
    result = extractor.extract(owner_id, video_id, hash)
    
    if result:
        print(f"Title: {result.get('title', 'N/A')}")
        print(f"Duration: {result.get('duration', 'N/A')}s")
        print(f"Type: {result.get('type')}")
        
        if result.get('qualities'):
            print(f"\nQualities:")
            for quality, url in result['qualities'].items():
                print(f"  - {quality}: {url[:60]}...")
        
        best = extractor.get_best_quality(result, '720p')
        print(f"\nBest quality URL: {best[:80]}..." if best else "\nNo suitable URL found")
    else:
        print("Extraction failed!")


def main():
    if len(sys.argv) < 2:
        print("""
Usage:
    python extractors_python.py okru <video_id>
    python extractors_python.py vk <owner_id> <video_id> [hash]
        """)
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == 'okru':
        if len(sys.argv) < 3:
            print("Error: video_id required")
            sys.exit(1)
        test_okru(sys.argv[2])
    
    elif command == 'vk':
        if len(sys.argv) < 4:
            print("Error: owner_id and video_id required")
            sys.exit(1)
        test_vk(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else '')
    
    else:
        print(f"Unknown command: {command}")
        sys.exit(1)


if __name__ == '__main__':
    main()
