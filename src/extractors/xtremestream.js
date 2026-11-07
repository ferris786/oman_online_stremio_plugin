/**
 * xtremestream.xyz (PeeStream "XSFTK" ftk_engine) extractor + VOD pipeline.
 *
 * Protocol (verified live, Nov 2026 — see also AutoFlix-CLI's xs pipeline,
 * which documented the scheme; the session dance below was derived from the
 * player page and live API probing):
 *
 * 1. GET player page  https://{host}/player/index.php?data={dataId}
 *    (Referer = embedding page) -> HTML contains BOTH:
 *      XSFTK.boot("{dataId}", "{vt}", player)     boot token (vt)
 *      window._xsSid = "{32-hex}"                 server-rendered session id
 *
 * 2. GET /player/proto_api.php?data={id}&op=pulse&vt={vt}&sid={sid}
 *    -> "ok" (registers the session; REQUIRED before op=key)
 *
 * 3. GET ...&op=key&vt={vt}&sid={sid}   with header X-XS-P: 1
 *    -> raw 16-byte AES-128-CBC key
 *    (without X-XS-P -> 403 "forbidden-hdr"; without pulse -> 403 "pulse";
 *     bad sid -> 403 "sess")
 *
 * 4. GET ...&op=manifest&vt={vt}&sid={sid}
 *    -> binary payload XOR-filtered with sha256("M|{dataId}|{vt}")
 *    -> JSON: { "<quality>": {durs:[...], bw, res, td, n}, audio?: [...],
 *               cdn: [hosts], edge: bool }
 *
 * 5. Video segment i of quality q:
 *    https://{cdn[i % cdn.length]}/cdn/down/{dataId}/{q}_enc/{q}{i}.html
 *    AES-128-CBC encrypted, IV = i as 16-byte big-endian, plaintext = MPEG-TS.
 *    Audio segments (when manifest.audio exists) are CLEAR TS at
 *    /cdn/down/{dataId}/hls_audio/{lang}/seg_{i:03d}.aac
 *
 * The /xs/* routes in index.js materialize standard HLS playlists and decrypt
 * segments on the fly, so any HLS player (Stremio/ExoPlayer/hls.js) just sees
 * a normal VOD stream.
 */

const axios = require("axios");
const crypto = require("crypto");
const http = require("http");
const https = require("https");

// Pooled upstream connections - one TLS handshake per CDN host instead of one
// per segment, which materially reduces per-segment latency for /xs playback.
const httpAgent = new http.Agent({ keepAlive: true, maxSockets: 64, keepAliveMsecs: 30000 });
const httpsAgent = new https.Agent({ keepAlive: true, maxSockets: 64, keepAliveMsecs: 30000 });

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

function log(msg) {
    console.log(`[XtremeStream] ${msg}`);
}

// dataId -> { playerUrl, origin, vt, sid, manifest, key, ts }
const stateCache = new Map();
const STATE_TTL = 30 * 60 * 1000; // 30 min; re-bootstrapped on any failure

function originOf(playerUrl) {
    const m = playerUrl.match(/^https?:\/\/[^/]+/);
    return m ? m[0] : playerUrl;
}

async function fetchPage(playerUrl, referer) {
    const resp = await axios.get(playerUrl, {
        headers: { "User-Agent": UA, "Referer": referer || originOf(playerUrl) + "/" },
        timeout: 12000,
        httpAgent, httpsAgent,
        validateStatus: () => true
    });
    if (resp.status !== 200 || !resp.data || typeof resp.data !== "string") {
        throw new Error(`player page returned ${resp.status}`);
    }
    return resp.data;
}

function apiGet(origin, params, playerUrl) {
    const qs = new URLSearchParams(params).toString();
    return axios.get(`${origin}/player/proto_api.php?${qs}`, {
        headers: {
            "User-Agent": UA,
            "Referer": playerUrl,
            "X-XS-P": "1"
        },
        timeout: 12000,
        httpAgent, httpsAgent,
        responseType: "arraybuffer",
        validateStatus: () => true
    });
}

function xorFilter(payload, dataId, token) {
    const digest = crypto.createHash("sha256").update(`M|${dataId}|${token}`).digest();
    const out = Buffer.allocUnsafe(payload.length);
    for (let i = 0; i < payload.length; i++) {
        out[i] = payload[i] ^ digest[i % digest.length];
    }
    return out;
}

/**
 * Bootstrap (or re-bootstrap) a data id: fresh page -> vt + sid -> pulse ->
 * manifest. Cached per data id; use force=true after any upstream rejection.
 */
async function bootstrap(dataId, playerUrl, force = false) {
    const cached = stateCache.get(dataId);
    if (!force && cached && (Date.now() - cached.ts) < STATE_TTL) return cached;

    const html = await fetchPage(playerUrl, "https://osmanonline.live/");
    const vtMatch = html.match(/XSFTK\.boot\("[^"]+",\s*"([a-f0-9]+\.\d+)"/);
    const sidMatch = html.match(/_xsSid\s*=\s*"([a-f0-9]{16,64})"/);
    if (!vtMatch || !sidMatch) throw new Error("vt/sid not found on player page");

    const origin = originOf(playerUrl);
    const vt = vtMatch[1];
    const sid = sidMatch[1];

    // Register the session, then decode the manifest
    const pulse = await apiGet(origin, { data: dataId, op: "pulse", vt, sid }, playerUrl);
    if (pulse.status !== 200) throw new Error(`pulse returned ${pulse.status}`);

    const manifestResp = await apiGet(origin, { data: dataId, op: "manifest", vt, sid }, playerUrl);
    if (manifestResp.status !== 200) throw new Error(`manifest returned ${manifestResp.status}`);
    let manifest;
    try {
        manifest = JSON.parse(xorFilter(Buffer.from(manifestResp.data), dataId, vt).toString("utf-8"));
    } catch (e) {
        throw new Error("manifest decode failed");
    }
    if (!manifest.cdn || !manifest.cdn.length) throw new Error("manifest has no cdn hosts");

    const state = { dataId, playerUrl, origin, vt, sid, manifest, key: null, ts: Date.now() };
    stateCache.set(dataId, state);
    log(`bootstrapped ${dataId.slice(0, 10)}… (qualities: ${variantNames(manifest).join("/")})`);
    return state;
}

async function getKey(state, force = false) {
    if (!force && state.key) return state.key;
    const resp = await apiGet(state.origin, { data: state.dataId, op: "key", vt: state.vt, sid: state.sid }, state.playerUrl);
    if (resp.status !== 200 || resp.data.length !== 16) {
        throw new Error(`key returned ${resp.status}`);
    }
    state.key = Buffer.from(resp.data);
    return state.key;
}

function variantNames(manifest) {
    const names = Object.keys(manifest).filter(k => manifest[k] && typeof manifest[k] === "object" && Array.isArray(manifest[k].durs));
    return names.sort((a, b) => (parseInt(String(b).replace(/\D/g, "")) || 0) - (parseInt(String(a).replace(/\D/g, "")) || 0));
}

function segmentUrl(state, index, quality) {
    const cdns = state.manifest.cdn;
    const host = cdns[index % cdns.length];
    return `https://${host}/cdn/down/${state.dataId}/${quality}_enc/${quality}${index}.html`;
}

function audioSegmentUrl(state, index, lang) {
    const cdns = state.manifest.cdn;
    const host = cdns[index % cdns.length];
    return `https://${host}/cdn/down/${state.dataId}/hls_audio/${lang}/seg_${String(index).padStart(3, "0")}.aac`;
}

async function fetchEncryptedSegment(url, referer) {
    const resp = await axios.get(url, {
        headers: { "User-Agent": UA, "Referer": referer || "https://oo.xtremestream.xyz/" },
        timeout: 30000,
        httpAgent, httpsAgent,
        responseType: "arraybuffer",
        validateStatus: () => true
    });
    if (resp.status !== 200) throw new Error(`segment fetch ${resp.status}`);
    return Buffer.from(resp.data);
}

function decryptSegment(raw, index, key) {
    // IV = segment index as 16-byte big-endian
    const iv = Buffer.alloc(16);
    iv.writeBigUInt64BE(BigInt(index), 8);
    const decipher = crypto.createDecipheriv("aes-128-cbc", key, iv);
    decipher.setAutoPadding(false);
    return Buffer.concat([decipher.update(raw), decipher.final()]);
}

/**
 * Public entry: build the addon-served master playlist URL for a player page.
 */
async function buildXsStreamUrl(playerUrl, proxyHost) {
    const dataMatch = playerUrl.match(/[?&]data=([a-f0-9]+)/i);
    if (!dataMatch) throw new Error("no data param in player url");
    const dataId = dataMatch[1];

    await bootstrap(dataId, playerUrl); // fail fast if the protocol changed

    const qs = `data=${encodeURIComponent(dataId)}&u=${encodeURIComponent(playerUrl)}`;
    return `${proxyHost}/xs/master.m3u8?${qs}`;
}

module.exports = {
    bootstrap,
    getKey,
    variantNames,
    segmentUrl,
    audioSegmentUrl,
    fetchEncryptedSegment,
    decryptSegment,
    buildXsStreamUrl
};
