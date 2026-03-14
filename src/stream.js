const axios = require("axios");
const cheerio = require("cheerio");
const CryptoJS = require("crypto-js");
const fs = require("fs");
const path = require("path");
const { getKayiFamilyStream } = require("./kayifamily");

const LOG_FILE = path.join(__dirname, "../debug_stream.log");

function log(message) {
    const timestamp = new Date().toISOString();
    const logMsg = `[${timestamp}] ${message}\n`;
    try {
        fs.appendFileSync(LOG_FILE, logMsg);
    } catch (e) {
        console.error("Failed to write to log file:", e);
    }
    console.log(message); // Also log to console
}

// AES JSON formatter for CryptoJS
const CryptoJSAesJson = {
    'encrypt': function (value, password) {
        return CryptoJS.AES.encrypt(JSON.stringify(value), password, { format: CryptoJSAesJson }).toString()
    },
    'decrypt': function (jsonStr, password) {
        return JSON.parse(CryptoJS.AES.decrypt(jsonStr, password, { format: CryptoJSAesJson }).toString(CryptoJS.enc.Utf8))
    },
    'stringify': function (cipherParams) {
        var j = { ct: cipherParams.ciphertext.toString(CryptoJS.enc.Base64) }
        if (cipherParams.iv) j.iv = cipherParams.iv.toString()
        if (cipherParams.salt) j.s = cipherParams.salt.toString()
        return JSON.stringify(j).replace(/\s/g, '')
    },
    'parse': function (jsonStr) {
        var j = JSON.parse(jsonStr)
        var cipherParams = CryptoJS.lib.CipherParams.create({ ciphertext: CryptoJS.enc.Base64.parse(j.ct) })
        if (j.iv) cipherParams.iv = CryptoJS.enc.Hex.parse(j.iv)
        if (j.s) cipherParams.salt = CryptoJS.enc.Hex.parse(j.s)
        return cipherParams
    }
}

async function getOsmanOnlineStream(episodeSlug) {
    // Domain fallback logic
    // Prioritize .co.uk as .info seems to have broken/expired iframes
    const domains = [
        "https://osmanonline.co.uk",
        "https://osmanonline.co.uk/v11",
        "https://osmanonline.info"
    ];
    let $ = null;
    let iframeSrc = null;
    let episodeUrl = "";

    for (const domain of domains) {
        episodeUrl = `${domain}/${episodeSlug}`;
        try {
            console.log(`Debug - Fetching stream page: ${episodeUrl}`);
            log(`Fetching episode page: ${episodeUrl}`);
            const { data } = await axios.get(episodeUrl, {
                headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36' }
            });
            $ = cheerio.load(data);

            // Try standard Turktvuk
            iframeSrc = $("iframe[src*='turktvuk.com']").attr("src");
            if (iframeSrc) {
                log(`Found Turktvuk iframe on ${domain}`);
                break;
            }

            // Try Datebox
            iframeSrc = $("iframe[src*='datebox']").attr("src");
            if (iframeSrc) {
                log(`Found Datebox iframe on ${domain}`);
                break;
            }

            // Try generic video iframes
            const genericIframe = $("iframe[src*='player'], iframe[src*='video'], iframe[src*='watch']").first().attr("src");
            if (genericIframe) {
                iframeSrc = genericIframe;
                log(`Found generic video iframe on ${domain}: ${iframeSrc}`);
                break;
            }

        } catch (err) {
            log(`Failed to fetch ${episodeUrl}: ${err.message}`);
        }
    }

    if (!iframeSrc) {
        log("No known video iframe found on any domain.");
        return null;
    }

    // Ensure scheme
    if (iframeSrc.startsWith("//")) iframeSrc = "https:" + iframeSrc;

    log(`Processing iframe: ${iframeSrc}`);

    try {
        if (iframeSrc.includes("turktvuk.com")) {
            // Fetch Turktvuk player page
            const playerRes = await axios.get(iframeSrc, {
                headers: {
                    "Referer": episodeUrl,
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }
            });

            const cookies = playerRes.headers['set-cookie'];
            let cookieHeader = "";
            if (cookies) {
                if (Array.isArray(cookies)) {
                    cookieHeader = cookies.map(c => c.split(';')[0]).join('; ');
                } else {
                    cookieHeader = cookies.split(';')[0];
                }
                log(`Captured Cookies: ${cookieHeader}`);
            }

            const content = playerRes.data;
            const packerRegex = /eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\),0,\{\}\)\)/s;
            const packedMatch = content.match(packerRegex);

            let ck = null;
            let hash = null;

            if (packedMatch) {
                const unpacked = unpack(packedMatch[0]);

                // Extract hash from FirePlayer("HASH", ...)
                const hashMatch = unpacked.match(/FirePlayer\(\s*["']([a-f0-9]+)["']/);
                if (hashMatch) hash = hashMatch[1];

                // Extract ck from JSON/Object ... "ck":"..."
                const ckMatch = unpacked.match(/["']ck["']\s*:\s*["']([^"']+)["']/);
                if (ckMatch) {
                    let rawCk = ckMatch[1];
                    ck = rawCk.replace(/\\\\x([0-9A-Fa-f]{2})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)));
                }
            }

            // Fallback: get hash from URL query if not found
            if (!hash && iframeSrc.includes("data=")) {
                hash = iframeSrc.split("data=")[1].split("&")[0];
            }

            if (!hash || !ck) {
                log(`Failed to extract Hash or CK key. Hash: ${hash}, CK: ${ck}`);
                return null;
            }

            // Call API
            const apiUrl = "https://turktvuk.com/player/index.php?data=" + hash + "&do=getVideo";
            log(`Calling API: ${apiUrl}`);

            const apiRes = await axios.post(apiUrl,
                new URLSearchParams({
                    hash: hash,
                    r: episodeUrl
                }), {
                headers: {
                    "X-Requested-With": "XMLHttpRequest",
                    "Referer": iframeSrc,
                    "Origin": "https://turktvuk.com",
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Cookie": cookieHeader
                }
            });

            const jData = apiRes.data;
            const proxyHost = process.env.RENDER_EXTERNAL_URL || `http://127.0.0.1:${process.env.PORT || 7000}`;

            if (jData.hls) {
                let finalStreamUrl = jData.securedLink || jData.videoSource;

                if (finalStreamUrl.includes(".m3u8")) {
                    finalStreamUrl = finalStreamUrl.replace(".m3u8", ".txt");
                }

                const proxyUrl = `${proxyHost}/proxy?url=${encodeURIComponent(finalStreamUrl)}&cookie=${encodeURIComponent(cookieHeader || "")}&.m3u8`;
                log("Returning Proxy URL");

                return {
                    url: proxyUrl,
                    source: 'osmanonline'
                };
            }

            if (jData.videoSources && jData.videoSources.length > 0) {
                const encryptedFile = jData.videoSources[0].file;
                const decryptedUrl = CryptoJSAesJson.decrypt(encryptedFile, ck);

                let finalStreamUrl = decryptedUrl;
                const proxyUrl = `${proxyHost}/proxy?url=${encodeURIComponent(finalStreamUrl)}&cookie=${encodeURIComponent(cookieHeader || "")}&.m3u8`;
                log("Returning Proxy URL (Fallback)");

                return {
                    url: proxyUrl,
                    source: 'osmanonline'
                };
            }
        }

        // Handle Streamify360
        if (iframeSrc.includes("streamify360.com")) {
            log("Streamify360 detected. Fetching player page...");
            const playerRes = await axios.get(iframeSrc, {
                headers: {
                    "Referer": episodeUrl,
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }
            });

            const content = playerRes.data;
            const sourceMatch = content.match(/sources:\s*\[\s*\{\s*["']file["']:\s*["']([^"']+)["']/);

            if (sourceMatch) {
                const streamUrl = sourceMatch[1];
                log(`Streamify360 Stream URL extracted: ${streamUrl}`);

                const proxyHost = process.env.RENDER_EXTERNAL_URL || `http://127.0.0.1:${process.env.PORT || 7000}`;
                const proxyUrl = `${proxyHost}/proxy?url=${encodeURIComponent(streamUrl)}&headers=${encodeURIComponent(JSON.stringify({ "Referer": "https://streamify360.com/" }))}&.m3u8`;

                return {
                    url: proxyUrl,
                    source: 'osmanonline'
                };
            }
        }

        return null;

    } catch (error) {
        log(`Error in getOsmanOnlineStream: ${error.message}`);
        return null;
    }
}

async function getStream(type, id) {
    log(`getStream called for type=${type}, id=${id}`);
    const parts = id.split(":");
    if (parts.length < 3) {
        log("Invalid ID format");
        return { streams: [] };
    }

    // id format: osmanonline:series_slug:episode_slug
    const seriesSlug = parts[1];
    const episodeSlug = parts.slice(2).join(":"); // Valid url slug
    log(`episodeSlug: ${episodeSlug}`);

    // Parse season and episode from slug
    // e.g., "watch-kurulus-osman-season-3-episode-45-with-english-subtitles"
    const seasonMatch = episodeSlug.match(/season-(\d+)/);
    const episodeMatch = episodeSlug.match(/episode-(\d+)/);
    
    const season = seasonMatch ? parseInt(seasonMatch[1]) : 1;
    const episode = episodeMatch ? parseInt(episodeMatch[1]) : 1;
    
    log(`Parsed: series=${seriesSlug}, season=${season}, episode=${episode}`);

    const streams = [];

    // Source 1: OsmanOnline (existing)
    try {
        log("Fetching from OsmanOnline...");
        const osmanStream = await getOsmanOnlineStream(episodeSlug);
        if (osmanStream) {
            // Add two options for OsmanOnline (native and external player)
            streams.push({
                name: "OsmanOnline",
                title: "Source 1: OsmanOnline (use VLC if no audio)",
                url: osmanStream.url,
                behaviorHints: {
                    notWebReady: true
                }
            });
            streams.push({
                name: "OsmanOnline (External)",
                title: "Source 1: OsmanOnline - External Player",
                url: osmanStream.url,
                behaviorHints: {
                    notWebReady: false
                }
            });
            log("Added OsmanOnline streams");
        }
    } catch (err) {
        log(`OsmanOnline fetch error: ${err.message}`);
    }

    // Source 2: KayiFamily (new)
    try {
        log("Fetching from KayiFamily...");
        const kayiStream = await getKayiFamilyStream(seriesSlug, season, episode);
        if (kayiStream) {
            streams.push({
                name: "KayiFamily",
                title: "Source 2: KayiFamily (usually better audio)",
                url: kayiStream.url,
                behaviorHints: {
                    notWebReady: true
                }
            });
            log("Added KayiFamily stream");
        }
    } catch (err) {
        log(`KayiFamily fetch error: ${err.message}`);
    }

    if (streams.length === 0) {
        log("No streams found from any source");
        return { streams: [] };
    }

    log(`Returning ${streams.length} stream(s)`);
    return { streams };
}

function unpack(packed) {
    try {
        const argsMatch = packed.match(/\}\s*\(\s*'([^']*)',\s*(\d+),\s*(\d+),\s*'([^']*)'\.split\('\|'\)/);
        if (!argsMatch) return "";
        let [_, p, a, c, kString] = argsMatch;
        a = parseInt(a); c = parseInt(c);
        let k = kString.split('|');
        const e = function (c) { return (c < a ? '' : e(parseInt(c / a))) + ((c = c % a) > 35 ? String.fromCharCode(c + 29) : c.toString(36)); };
        while (c--) { if (k[c]) { p = p.replace(new RegExp('\\b' + e(c) + '\\b', 'g'), k[c]); } }
        return p;
    } catch (err) { return ""; }
}

module.exports = { getStream };
