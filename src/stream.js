const axios = require("axios");
const cheerio = require("cheerio");
const CryptoJS = require("crypto-js");

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

async function getStream(type, id) {
    const parts = id.split(":");
    if (parts.length < 3) return { streams: [] };

    // id format: osmanonline:series_slug:episode_slug_or_full_url
    // In catalog.js we passed the href as the 3rd part, but regex split might have messed it up if it contained colons?
    // Actually our ID generation was just `id:episodeSlug` (see catalog.js).
    // wait, `videos.push({ id: \`\${id}:\${episodeSlug}\`, ... })`
    // `id` was `osmanonline:slug`.
    // So distinct parts: `osmanonline`, `series_slug`, `episode_slug`.

    const episodeSlug = parts.slice(2).join(":"); // Valid url slug
    const episodeUrl = `https://osmanonline.info/${episodeSlug}`;

    try {
        console.log(`Fetching episode page: ${episodeUrl}`);
        const { data } = await axios.get(episodeUrl, {
            headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36' }
        });
        const $ = cheerio.load(data);

        let iframeSrc = $("iframe[src*='turktvuk.com']").attr("src");
        if (!iframeSrc) {
            console.log("No Turktvuk iframe found");
            return { streams: [] };
        }

        // Ensure scheme
        if (iframeSrc.startsWith("//")) iframeSrc = "https:" + iframeSrc;

        console.log(`Found iframe: ${iframeSrc}`);

        // Fetch Turktvuk player page
        const playerRes = await axios.get(iframeSrc, {
            headers: {
                "Referer": episodeUrl,
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
        });

        // console.log("Player Page Response Headers:", JSON.stringify(playerRes.headers, null, 2));
        // require("fs").writeFileSync("headers_dump.json", JSON.stringify(playerRes.headers, null, 2));

        const cookies = playerRes.headers['set-cookie'];
        let cookieHeader = "";
        if (cookies) {
            if (Array.isArray(cookies)) {
                cookieHeader = cookies.map(c => c.split(';')[0]).join('; ');
            } else {
                cookieHeader = cookies.split(';')[0];
            }
            console.log("Captured Cookies:", cookieHeader);
        }

        const content = playerRes.data;
        const packerRegex = /eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\),0,\{\}\)\)/s;
        const packedMatch = content.match(packerRegex);

        let ck = null;
        let hash = null;

        if (packedMatch) {
            // console.log("Unpacking script...");
            const unpacked = unpack(packedMatch[0]);

            // Extract hash from FirePlayer("HASH", ...)
            const hashMatch = unpacked.match(/FirePlayer\(\s*["']([a-f0-9]+)["']/);
            if (hashMatch) hash = hashMatch[1];

            // Extract ck from JSON/Object ... "ck":"..."
            // Handle escaped hex chars if present
            const ckMatch = unpacked.match(/["']ck["']\s*:\s*["']([^"']+)["']/);
            if (ckMatch) {
                let rawCk = ckMatch[1];
                // Decode \\xHH
                ck = rawCk.replace(/\\\\x([0-9A-Fa-f]{2})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)));
            }
        }

        // Fallback: get hash from URL query if not found
        if (!hash && iframeSrc.includes("data=")) {
            hash = iframeSrc.split("data=")[1].split("&")[0];
        }

        if (!hash || !ck) {
            console.error("Failed to extract Hash or CK key", { hash, ck });
            return { streams: [] };
        }

        console.log(`Hash: ${hash}, CK: ${ck}`);

        // Call API
        const apiUrl = "https://turktvuk.com/player/index.php?data=" + hash + "&do=getVideo";
        console.log(`Calling API: ${apiUrl}`);

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
                "Cookie": cookieHeader // Pass the cookie!
            }
        });

        const jData = apiRes.data;
        // console.log("API Response:", JSON.stringify(jData));
        // require("fs").writeFileSync("api_dump.json", JSON.stringify(jData, null, 2));

        if (jData.hls) {
            // Prioritize securedLink which contains the token
            let finalStreamUrl = jData.securedLink || jData.videoSource;

            // Construct Proxy URL
            // Force .txt extension on the inner URL if it is .m3u8
            if (finalStreamUrl.includes(".m3u8")) {
                finalStreamUrl = finalStreamUrl.replace(".m3u8", ".txt");
                console.log("Forced extension to .txt (bypass 403):", finalStreamUrl);
            }

            const proxyHost = "http://127.0.0.1:" + (process.env.PORT || 7000);
            const proxyUrl = `${proxyHost}/proxy?url=${encodeURIComponent(finalStreamUrl)}&cookie=${encodeURIComponent(cookieHeader || "")}&.m3u8`;
            console.log("Proxy URL:", proxyUrl);

            return {
                streams: [
                    {
                        title: "Auto (HLS) [Proxy]",
                        url: proxyUrl,
                        behaviorHints: {
                            notWebReady: true
                        }
                    }
                ]
            };
        }

        if (jData.videoSources && jData.videoSources.length > 0) {
            const encryptedFile = jData.videoSources[0].file;
            const decryptedUrl = CryptoJSAesJson.decrypt(encryptedFile, ck);
            console.log("Decrypted URL:", decryptedUrl);

            // Fix: turktvuk sometimes returns .txt for m3u8 playlists
            let finalStreamUrl = decryptedUrl;

            // Construct Proxy URL
            const proxyHost = "http://127.0.0.1:" + (process.env.PORT || 7000);
            const proxyUrl = `${proxyHost}/proxy?url=${encodeURIComponent(finalStreamUrl)}&cookie=${encodeURIComponent(cookieHeader || "")}&.m3u8`;
            console.log("Proxy URL (Fallback):", proxyUrl);

            return {
                streams: [
                    {
                        title: "Auto (HLS) [Proxy]",
                        url: proxyUrl,
                        behaviorHints: {
                            notWebReady: true
                        }
                    }
                ]
            };
        }

        return { streams: [] };

    } catch (error) {
        console.error("Error in getStream:", error.message);
        return { streams: [] };
    }
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
