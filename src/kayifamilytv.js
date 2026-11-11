const axios = require("axios");
const { extractWakeupummah } = require("./extractors/fireplayer");
const publicbase = require("./publicbase");

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

const DEBUG = true;
function log(msg) {
    if (DEBUG) console.log(`[KayiFamilyTV] ${msg}`);
}

// Series slug (new co.uk slug or legacy watch- slug) -> KayiFamilyTV slug
const SERIES_NAME_MAP = {
    // New osmanonline.co.uk slugs
    'mehmed-fetihler-sultani': 'mehmed-fetihler-sultani',
    'kurulus-osman': 'kurulus-osman',
    'kurulus-orhan': 'kurulus-orhan',
    'kudus-fatihi-selahaddin-eyyubi': 'salahuddin-ayyubi',
    'payitaht-abdulhamid': 'payitaht-abdulhamid',
    'dirilis-ertugrul': 'dirilis-ertugrul',
    'alparslan-buyuk-selcuklu': 'alparslan-buyuk-selcuklu',
    'destan': 'destan',
    'mevlana-rumi': 'mevlana-rumi',
    // Legacy osmanonline.info slugs
    'watch-mehmed-fetihler-sultani-with-english-subtitles': 'mehmed-fetihler-sultani',
    'watch-kurulus-osman-with-english-subtitles': 'kurulus-osman',
    'watch-kurulus-orhan-with-english-subtitles': 'kurulus-orhan',
    'watch-salahuddin-ayyubi-with-english-subtitles': 'salahuddin-ayyubi',
    'watch-kudus-fatihi-selahaddin-eyyubi-with-english-subtitles': 'salahuddin-ayyubi',
    'watch-payitaht-abdulhamid-with-english-subtitles': 'payitaht-abdulhamid',
    'watch-dirilis-ertugrul-with-english-subtitles': 'dirilis-ertugrul',
    'watch-alparslan-buyuk-selcuklu-with-english-subtitles': 'alparslan-buyuk-selcuklu',
    'watch-destan-with-english-subtitles': 'destan',
    'watch-mevlana-rumi-with-english-subtitles': 'mevlana-rumi',
    'watch-rumi-with-english-subtitles': 'mevlana-rumi'
};

/**
 * Extract video from Ok.ru embed (legacy - some older pages may still use it)
 * Ok.ru provides JSON metadata in data-options attribute
 */
async function extractOkRu(videoId) {
    const embedUrl = `https://ok.ru/videoembed/${videoId}?nochat=1`;
    log(`Extracting from Ok.ru: ${embedUrl}`);

    try {
        const { data } = await axios.get(embedUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Referer': 'https://kayifamilytv.com/'
            },
            timeout: 20000
        });

        const dataOptionsMatch = data.match(/data-options="([^"]+)"/);
        if (!dataOptionsMatch) {
            log('No data-options found');
            return null;
        }

        const jsonStr = dataOptionsMatch[1].replace(/&quot;/g, '"');
        const options = JSON.parse(jsonStr);

        if (!options.flashvars || !options.flashvars.metadata) {
            log('No metadata in data-options');
            return null;
        }

        const metadata = JSON.parse(options.flashvars.metadata);

        if (!metadata.videos || metadata.videos.length === 0) {
            log('No videos in metadata');
            return null;
        }

        const best = metadata.videos[metadata.videos.length - 1];
        log(`Found quality: ${best.name}`);

        return {
            url: best.url,
            title: metadata.movie?.title || 'Unknown',
            quality: best.name,
            type: 'mp4'
        };

    } catch (error) {
        log(`Ok.ru extraction error: ${error.message}`);
        return null;
    }
}

/**
 * Get stream(s) from KayiFamilyTV.
 *
 * Nov 2026: KayiFamilyTV dropped ok.ru embeds. Episode pages now embed
 * wakeupummah.com FirePlayer iframes (lazy-loaded via data-litespeed-src)
 * plus vkvideo.ru mirrors. We try every wakeupummah embed (each is a
 * different server mirror) and fall back to ok.ru for older pages.
 *
 * Returns a single best stream or null.
 */
async function getKayiFamilyTVStream(osmanSeriesSlug, abs) {
    try {
        const seriesSlug = SERIES_NAME_MAP[osmanSeriesSlug];

        if (!seriesSlug) {
            log(`No mapping for series: ${osmanSeriesSlug}`);
            return null;
        }

        const episodeUrl = `https://kayifamilytv.com/${seriesSlug}-episode-${abs}/`;
        log(`Fetching: ${episodeUrl}`);
        const { data, status } = await axios.get(episodeUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
                'Accept-Language': 'en-US,en;q=0.5'
            },
            timeout: 10000,
            validateStatus: () => true
        });

        if (status !== 200) {
            log(`Episode page returned ${status}`);
            return null;
        }

        const titleMatch = data.match(/<title>([^<]+)/i);
        const pageTitle = titleMatch ? titleMatch[1].toLowerCase() : '';
        if (pageTitle.includes('404') || pageTitle.includes('not found') || pageTitle.includes('error')) {
            log('Page not found (based on title)');
            return null;
        }

        // vidmoly.org embeds (Mehmed and other shows): the embed page carries
        // a plain-text m3u8 with a ~12h token. Segments REQUIRE Referer:
        // vidmoly.org, so the stream is served through the addon proxy which
        // attaches that referer to playlist and segment fetches alike.
        const vidmolyEmbeds = [...new Set(
            (data.match(/https?:\/\/vidmoly\.[a-z.]+\/embed-[a-z0-9]+\.html/gi) || [])
        )];
        log(`Found ${vidmolyEmbeds.length} vidmoly embed(s)`);

        for (const embed of vidmolyEmbeds) {
            try {
                const res = await axios.get(embed, {
                    headers: {
                        "User-Agent": UA,
                        "Referer": "https://kayifamilytv.com/"
                    },
                    timeout: 15000,
                    validateStatus: () => true
                });
                const m3u8 = (res.data.match(/https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*/) || [])[0];
                if (!m3u8) {
                    log(`vidmoly embed has no m3u8: ${embed.slice(-30)}`);
                    continue;
                }
                log(`Extracted vidmoly m3u8 (${m3u8.slice(0, 60)}...)`);
                return {
                    url: `${publicbase.get()}/proxy/master.m3u8?url=${encodeURIComponent(m3u8)}&referer=${encodeURIComponent("https://vidmoly.org/")}`,
                    type: "hls",
                    source: "kayifamilytv-vidmoly"
                };
            } catch (e) {
                log(`vidmoly embed error: ${e.message}`);
            }
        }

        // wakeupummah.com FirePlayer embeds (covers src= and data-litespeed-src=)
        const wuEmbeds = [...new Set(
            (data.match(/https?:\/\/wakeupummah\.com\/fireplayer\/video\/[a-f0-9]+/g) || [])
        )];
        log(`Found ${wuEmbeds.length} wakeupummah embed(s)`);

        for (const embed of wuEmbeds) {
            const stream = await extractWakeupummah(embed, episodeUrl);
            if (stream) {
                log(`Successfully extracted via wakeupummah: ${embed.slice(-16)}`);
                return {
                    url: stream.url,
                    type: stream.type,
                    source: 'kayifamilytv-fireplayer'
                };
            }
        }

        // Legacy ok.ru fallback
        const okruMatches = data.match(/ok\.ru\/videoembed\/(\d+)/g);
        if (okruMatches && okruMatches.length > 0) {
            log(`Falling back to ${okruMatches.length} Ok.ru embed(s)`);
            for (const match of okruMatches) {
                const videoId = match.match(/(\d+)$/)[1];
                const stream = await extractOkRu(videoId);
                if (stream) {
                    return {
                        url: stream.url,
                        type: 'mp4',
                        source: 'kayifamilytv-okru',
                        quality: stream.quality,
                        title: stream.title
                    };
                }
            }
        }

        log('No playable embeds found on KayiFamilyTV page');
        return null;

    } catch (error) {
        log(`KayiFamilyTV error: ${error.message}`);
        return null;
    }
}

module.exports = {
    getKayiFamilyTVStream,
    extractOkRu,
    SERIES_NAME_MAP
};
