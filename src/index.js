const express = require("express");
const cors = require("cors");
const axios = require("axios");
const fs = require("fs");
const path = require("path");
const http = require("http");
const https = require("https");

// Pooled connections to upstream CDNs: without keep-alive every segment fetch
// pays a fresh TCP+TLS handshake, which shows up as playback stutter.
const httpAgent = new http.Agent({ keepAlive: true, maxSockets: 64, keepAliveMsecs: 30000 });
const httpsAgent = new https.Agent({ keepAlive: true, maxSockets: 64, keepAliveMsecs: 30000 });
const { addonBuilder, getRouter } = require("stremio-addon-sdk");
const manifest = require("./manifest");
const { getSeries, getMeta, searchSeries } = require("./catalog");
const { getStream } = require("./stream");
const xs = require("./extractors/xtremestream");
const en = require("./english");
const tmdb = require("./tmdb");
const publicbase = require("./publicbase");
publicbase.initFromEnv();
const QRCode = require("qrcode");
const urlModule = require("url");

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

const builder = new addonBuilder(manifest);

builder.defineCatalogHandler(async ({ type, id, extra }) => {
    console.log("Catalog request:", type, id);
    if (type === "series" && id === "osmanonline_catalog") {
        const metas = extra && extra.search ? await searchSeries(extra.search) : await getSeries();
        return { metas: metas };
    }
    return { metas: [] };
});

builder.defineMetaHandler(async ({ type, id }) => {
    console.log("Meta request:", type, id);
    // id should be series ID usually
    return await getMeta(type, id);
});

builder.defineStreamHandler(async ({ type, id }) => {
    console.log("Stream request:", type, id);
    return await getStream(type, id);
});

// Proxy Route
// Query params:
//   url      - target URL (required)
//   referer  - Referer header to send upstream (kayihome.xyz CDNs require the
//              exact player page URL as referer; vidara segments are IP-locked)
//   cookie   - Cookie header to send upstream (legacy turktvuk support)
//
// Playlists are also served at /proxy/master.m3u8?url=... - a path ending in
// .m3u8 so players that sniff HLS by URL path (ExoPlayer on Android TV) work.
async function proxyHandler(req, res) {
    const targetUrl = req.query.url;
    const cookie = req.query.cookie;
    const referer = req.query.referer;

    if (!targetUrl) {
        return res.status(400).send("Missing url param");
    }

    try {
        console.log(`Proxying: ${targetUrl.slice(0, 120)}`);

        const headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept": "*/*",
            "Accept-Encoding": "identity"
        };
        if (referer) headers["Referer"] = referer;
        if (cookie) headers["Cookie"] = cookie;
        if (req.headers.range) headers["Range"] = req.headers.range;

        const response = await axios.get(targetUrl, {
            headers,
            httpAgent, httpsAgent,
            responseType: "stream", // STREAMING: Important for low memory usage
            validateStatus: (status) => status < 400
        });

        const contentType = response.headers["content-type"];
        if (contentType) res.setHeader("Content-Type", contentType);

        // Check if playlist to rewrite
        // .txt / .m3u8 / vnd.apple.mpegurl / kayihome /m3/ media playlists (served as text/plain)
        const isPlaylist = targetUrl.includes(".txt") || targetUrl.includes(".m3u8") ||
            targetUrl.includes("/m3/") ||
            (contentType && contentType.includes("mpegurl"));

        // If it's a playlist, we MUST buffer it to rewrite the URLs inside
        if (isPlaylist) {
            // Collect stream data into a buffer
            const chunks = [];
            response.data.on("data", (chunk) => chunks.push(chunk));

            response.data.on("end", () => {
                const buffer = Buffer.concat(chunks);
                let content = buffer.toString("utf-8");

                // Force HLS content type for Stremio to ensure it recognizes the playlist
                res.setHeader("Content-Type", "application/vnd.apple.mpegurl");
                res.setHeader("Cache-Control", "public, max-age=60");

                // Rewrite URLs, carrying the referer/cookie context to child requests.
                //
                // BANDWIDTH: video segments are served DIRECT from the upstream CDNs
                // (not through this proxy). Verified: the segment CDNs used by our
                // sources (vidara 97bf1.com .css segments, kayihome .xyz hosts .jpg
                // segments) require no referer/token - only the playlists do. Piping
                // every segment through a free Render dyno was the main cause of
                // playback buffering, so only playlists (small text files) go through
                // the proxy: master playlists keep their children proxied (they may
                // need referer/token context), media playlists (#EXTINF = actual
                // segments) get direct absolute URLs.
                const isMediaPlaylist = content.includes("#EXTINF");
                const baseUrl = targetUrl.substring(0, targetUrl.lastIndexOf("/") + 1);
                const hostUrl = new URL(targetUrl).origin;

                const rewritten = content.replace(/^(?!#)(.+)$/gm, (line) => {
                    line = line.trim();
                    if (!line) return "";

                    let absoluteLine = line;
                    if (line.startsWith("http")) {
                        absoluteLine = line;
                    } else if (line.startsWith("/")) {
                        absoluteLine = hostUrl + line;
                    } else {
                        absoluteLine = baseUrl + line;
                    }

                    if (isMediaPlaylist) {
                        return absoluteLine;
                    }

                    let proxyPath = `/proxy?url=${encodeURIComponent(absoluteLine)}`;
                    if (referer) proxyPath += `&referer=${encodeURIComponent(referer)}`;
                    if (cookie) proxyPath += `&cookie=${encodeURIComponent(cookie)}`;
                    return proxyPath;
                });

                res.setHeader("Content-Length", Buffer.byteLength(rewritten));
                res.status(200);
                res.end(rewritten);
            });

            response.data.on("error", (err) => {
                console.error("Stream error (playlist):", err);
                if (!res.headersSent) res.status(500).send("Stream error");
            });

        } else {
            // It's a media segment (TS, MP4, Key, etc.)
            // Just pipe it! This uses minimal RAM.
            if (response.headers["content-length"]) res.setHeader("Content-Length", response.headers["content-length"]);
            if (response.headers["content-range"]) res.setHeader("Content-Range", response.headers["content-range"]);
            if (response.headers["accept-ranges"]) res.setHeader("Accept-Ranges", response.headers["accept-ranges"]);
            res.setHeader("Cache-Control", "public, max-age=3600");
            response.data.pipe(res);

            response.data.on("error", (err) => {
                console.error("Stream error (pipe):", err);
                // Cannot send error status if headers usually already sent by pipe, but good to log
            });
        }

    } catch (e) {
        console.error("Proxy error:", e.message);
        if (e.response) {
            // Since we used responseType: stream, e.response.data is a stream. 
            // We might want to read it to see the error message if needed, but usually status is enough.
            if (!res.headersSent) res.sendStatus(e.response.status);
        } else {
            if (!res.headersSent) res.status(500).send(e.message);
        }
    }
}

app.get("/proxy", proxyHandler);
app.get("/proxy/:filename", proxyHandler);


// Android TV app download - one easy link for the Downloader app on the TV:
//   https://<this-addon>/apk
// The APK is built from android/ (see android/README.md) and committed here,
// so this link always serves the latest committed build.
const APK_PATH = path.join(__dirname, "../android/apk/turkishtv.apk");
app.get("/version.json", (req, res) => {
    const versionFile = path.join(__dirname, "../android/apk/version.json");
    if (!fs.existsSync(versionFile)) {
        return res.status(404).json({ error: "no version info" });
    }
    res.setHeader("Content-Type", "application/json");
    res.sendFile(versionFile);
});

const APK_GITHUB_RELEASE = process.env.APK_RELEASE_URL || null;
app.get(["/apk", "/app.apk"], (req, res) => {
    if (APK_GITHUB_RELEASE) return res.redirect(302, APK_GITHUB_RELEASE);
    if (!fs.existsSync(APK_PATH)) {
        return res.status(404).send("APK not built - see android/README.md");
    }
    res.setHeader("Content-Type", "application/vnd.android.package-archive");
    res.setHeader("Content-Disposition", 'attachment; filename="turkishtv.apk"');
    res.sendFile(APK_PATH);
});

// ---------------------------------------------------------------------------
// Phone pairing: TV shows a short code + QR; the user opens /pair/<code> on
// their phone, pastes their TorBox API key there (real keyboard), and the TV
// polls until it arrives. No long typing on the TV remote. Keys are held in
// memory only long enough to hand them to the TV, then discarded.
// ---------------------------------------------------------------------------
const pairCodes = new Map(); // code -> { key, ts, delivered }
const PAIR_TTL = 15 * 60 * 1000;

function newPairCode() {
    const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    let code;
    do {
        code = Array.from({ length: 6 }, () => alphabet[Math.floor(Math.random() * alphabet.length)]).join("");
        code = code.slice(0, 3) + "-" + code.slice(3);
    } while (pairCodes.has(code));
    pairCodes.set(code, { key: null, ts: Date.now(), delivered: false });
    // opportunistic cleanup
    for (const [c, v] of pairCodes) {
        if (Date.now() - v.ts > PAIR_TTL) pairCodes.delete(c);
    }
    return code;
}

async function torboxKeyValid(key) {
    try {
        const resp = await axios.get("https://api.torbox.app/v1/api/torrents/mylist?limit=1", {
            headers: { Authorization: `Bearer ${key}` },
            timeout: 12000,
            validateStatus: () => true
        });
        return resp.status === 200 && resp.data && resp.data.success === true;
    } catch (e) {
        return false;
    }
}

app.post("/pair/start", (req, res) => res.json({ code: newPairCode() }));

app.get("/pair/qr/:code", async (req, res) => {
    const host = publicbase.get();
    try {
        const png = await QRCode.toBuffer(`${host}/pair/${req.params.code}`, { width: 420, margin: 2 });
        res.setHeader("Content-Type", "image/png");
        res.setHeader("Cache-Control", "no-store");
        res.send(png);
    } catch (e) {
        res.status(500).send("qr failed");
    }
});

app.get("/pair/:code/check", (req, res) => {
    const entry = pairCodes.get(req.params.code);
    if (!entry || Date.now() - entry.ts > PAIR_TTL) {
        return res.json({ status: "expired" });
    }
    if (entry.key) {
        entry.delivered = true;
        const key = entry.key;
        setTimeout(() => pairCodes.delete(req.params.code), 60000);
        return res.json({ status: "ready", key });
    }
    res.json({ status: "waiting" });
});

// Phone page: paste the TorBox key (mobile friendly)
app.get("/pair/:code", (req, res) => {
    const code = req.params.code;
    const entry = pairCodes.get(code);
    if (!entry || Date.now() - entry.ts > PAIR_TTL) {
        return res.send("<html><body style='font-family:sans-serif;background:#141419;color:#f2f2f2;text-align:center;padding-top:15vh'><h2>Code expired</h2><p>Generate a new code on the TV (Settings → Pair from phone).</p></body></html>");
    }
    if (entry.key) {
        return res.send("<html><body style='font-family:sans-serif;background:#141419;color:#f2f2f2;text-align:center;padding-top:15vh'><h2>✓ Paired</h2><p>The TV has your key. You can close this page.</p></body></html>");
    }
    res.send(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Wahab TV pairing</title></head>
<body style="font-family:sans-serif;background:#141419;color:#f2f2f2;max-width:480px;margin:0 auto;padding:28px">
<h2 style="color:#e0b34d;margin-bottom:4px">Wahab TV</h2>
<p style="color:#9a9aa2;margin-top:0">Pairing code <b style="color:#f2f2f2;font-size:20px;letter-spacing:2px">${code}</b></p>
<p>Paste your <b>TorBox API key</b> below — it is verified with TorBox before pairing.</p>
<a href="https://torbox.app/settings" target="_blank" style="display:block;text-align:center;padding:12px;border:1px solid #3a3a44;border-radius:8px;color:#e0b34d;text-decoration:none">Open TorBox → Settings → API Key → Copy&nbsp;▸</a>
<form method="POST" action="/pair/${code}">
<input name="key" autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="Paste the API key here"
 style="width:100%;box-sizing:border-box;padding:14px;font-size:16px;border-radius:8px;border:1px solid #333;margin-top:12px;background:#1c1c24;color:#fff">
<button style="width:100%;padding:14px;margin-top:12px;border:0;border-radius:8px;background:#e0b34d;color:#141419;font-size:16px;font-weight:bold">Pair this TV</button>
</form>
<p id="msg" style="min-height:22px;color:#e0b34d"></p>
</body></html>`);
});

app.post("/pair/:code", async (req, res) => {
    const entry = pairCodes.get(req.params.code);
    const key = (req.body && req.body.key || "").trim();
    const fail = (msg) => res.send(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="font-family:sans-serif;background:#141419;color:#f2f2f2;max-width:480px;margin:0 auto;padding:28px">
<h2 style="color:#e0b34d">${msg}</h2><p><a style="color:#e0b34d" href="/pair/${req.params.code}">← try again</a></p></body></html>`);

    if (!entry || Date.now() - entry.ts > PAIR_TTL) return fail("Code expired");
    if (entry.key) return fail("Already paired");
    if (key.length < 20) return fail("That doesn't look like an API key");

    const valid = await torboxKeyValid(key);
    if (!valid) return fail("TorBox rejected that key - check it and try again");

    entry.key = key;
    res.send(`<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="font-family:sans-serif;background:#141419;color:#f2f2f2;text-align:center;padding-top:15vh">
<h2 style="color:#e0b34d">✓ Key verified</h2><p>Check the TV - it should confirm in a few seconds.</p></body></html>`);
});

// ---------------------------------------------------------------------------
// English Movies (Cinemeta + TPB candidates). TorBox resolution happens in
// the app with the user's own API key - see src/english.js.
// ---------------------------------------------------------------------------
const enCatalogHandler = async (req, res) => {
    try {
        const { catalog, genre } = req.params;
        if (!en.CINEMETA_CATALOGS.includes(catalog)) return res.status(400).json({ error: "bad catalog" });
        if (genre && !/^[A-Za-z -]+$/.test(genre)) return res.status(400).json({ error: "bad genre" });
        const out = await en.enCatalog(catalog, req.params.skip, genre);
        res.setHeader("Cache-Control", "public, max-age=3600");
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
};
// "Just added" must come before /:catalog so it is not read as a catalog id
app.get("/en/catalog/movie/latest", async (req, res) => {
    try {
        const out = await en.enLatestAdded();
        res.setHeader("Cache-Control", "public, max-age=1800");
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});
app.get("/en/catalog/movie/:catalog", enCatalogHandler);
app.get("/en/catalog/movie/:catalog/genre/:genre", enCatalogHandler);
app.get("/en/catalog/movie/:catalog/:skip", enCatalogHandler);

// ---------------------------------------------------------------------------
// English TV series: same Cinemeta catalogues, type=series. Episode ids are
// Stremio's tt:season:episode, which Torrentio and OpenSubtitles key on.
// ---------------------------------------------------------------------------
const enSeriesCatalogHandler = async (req, res) => {
    try {
        const { catalog, genre } = req.params;
        if (!en.CINEMETA_CATALOGS.includes(catalog)) return res.status(400).json({ error: "bad catalog" });
        if (genre && !/^[A-Za-z -]+$/.test(genre)) return res.status(400).json({ error: "bad genre" });
        const out = await en.enCatalog(catalog, req.params.skip, genre, "series");
        res.setHeader("Cache-Control", "public, max-age=3600");
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
};
app.get("/en/catalog/series/:catalog", enSeriesCatalogHandler);
app.get("/en/catalog/series/:catalog/genre/:genre", enSeriesCatalogHandler);
app.get("/en/catalog/series/:catalog/:skip", enSeriesCatalogHandler);

app.get("/en/search/series/:query", async (req, res) => {
    try {
        const out = await en.enSearch(req.params.query, "series");
        res.setHeader("Cache-Control", "public, max-age=3600");
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

app.get("/en/meta/series/:imdbId", async (req, res) => {
    try {
        if (!/^tt\d+$/.test(req.params.imdbId)) return res.status(400).json({ error: "bad id" });
        const meta = await en.enSeriesMeta(req.params.imdbId);
        res.setHeader("Cache-Control", "public, max-age=3600");
        res.json({ meta });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

app.get("/en/subtitles/series/:id", async (req, res) => {
    try {
        if (!/^tt\d+:\d+:\d+$/.test(req.params.id)) return res.status(400).json({ error: "bad id" });
        const out = await en.enSubtitles(req.params.id, "series");
        res.setHeader("Cache-Control", "public, max-age=21600");
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

// "Because you watched X" - TMDB recommendations for a finished film
app.get("/en/similar/movie/:imdbId", async (req, res) => {
    try {
        if (!/^tt\d+$/.test(req.params.imdbId)) return res.status(400).json({ error: "bad id" });
        const out = await tmdb.recommendationsForImdb(req.params.imdbId);
        res.setHeader("Cache-Control", "public, max-age=21600");
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

// ---------------------------------------------------------------------------
// Search (TMDB-backed type-ahead), actor search, subtitles
// ---------------------------------------------------------------------------

app.get("/en/suggest/movie/:query", async (req, res) => {
    try {
        const out = await tmdb.suggestMovies(req.params.query);
        res.setHeader("Cache-Control", "public, max-age=3600");
        res.json({ suggestions: out });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

// a picked suggestion -> the full Cinemeta meta (resolves tmdb id -> imdb id)
app.get("/en/tmdb/movie/:tmdbId", async (req, res) => {
    try {
        const tmdbId = parseInt(req.params.tmdbId);
        if (!(tmdbId > 0)) return res.status(400).json({ error: "bad id" });
        const imdbId = await tmdb.imdbIdOf(tmdbId);
        if (!imdbId) return res.status(404).json({ error: "no imdb id for this title" });
        let meta = null;
        try { meta = await en.enMeta(imdbId); } catch (e) { /* not in Cinemeta */ }
        res.setHeader("Cache-Control", "public, max-age=86400");
        res.json({ imdbId, meta });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

// ---------------------------------------------------------------------------
// The Reel: curated discovery lists. The app spins through the catalogue of
// names, then asks for the one it lands on.
// ---------------------------------------------------------------------------
const lists = require("./lists");

app.get("/en/lists", (req, res) => {
    res.setHeader("Cache-Control", "public, max-age=3600");
    res.json({ lists: lists.catalogue() });
});

app.get("/en/list/:id", async (req, res) => {
    try {
        const r = lists.recipe(req.params.id);
        if (!r) return res.status(404).json({ error: "no such list" });
        const suggestions = await tmdb.discover(r.params, r.id);
        res.setHeader("Cache-Control", "public, max-age=21600");
        res.json({ id: r.id, title: r.title, subtitle: r.subtitle, suggestions });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

app.get("/en/person/:query", async (req, res) => {
    try {
        const out = await tmdb.searchPeople(req.params.query);
        res.setHeader("Cache-Control", "public, max-age=86400");
        res.json({ people: out });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

app.get("/en/person/:personId/movies", async (req, res) => {
    try {
        const id = parseInt(req.params.personId);
        if (!(id > 0)) return res.status(400).json({ error: "bad id" });
        const out = await tmdb.personMovies(id);
        res.setHeader("Cache-Control", "public, max-age=86400");
        res.json({ suggestions: out });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

// IMDb publishes no review API, so these are TMDB's user reviews. The
// IMDb rating itself still comes from the Cinemeta meta.
app.get("/en/reviews/movie/:imdbId", async (req, res) => {
    try {
        if (!/^tt\d+$/.test(req.params.imdbId)) return res.status(400).json({ error: "bad id" });
        const reviews = await tmdb.reviewsForImdb(req.params.imdbId);
        res.setHeader("Cache-Control", "public, max-age=86400");
        res.json({ reviews });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

// Cinemeta usually carries trailerStreams already; this is the fallback.
app.get("/en/trailer/movie/:imdbId", async (req, res) => {
    try {
        if (!/^tt\d+$/.test(req.params.imdbId)) return res.status(400).json({ error: "bad id" });
        const ytId = await tmdb.trailerForImdb(req.params.imdbId);
        res.setHeader("Cache-Control", "public, max-age=86400");
        res.json({ ytId });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

app.get("/en/subtitles/movie/:imdbId", async (req, res) => {
    try {
        if (!/^tt\d+$/.test(req.params.imdbId)) return res.status(400).json({ error: "bad id" });
        const out = await en.enSubtitles(req.params.imdbId);
        res.setHeader("Cache-Control", "public, max-age=21600");
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

app.get("/en/search/movie/:query", async (req, res) => {
    try {
        const out = await en.enSearch(req.params.query);
        res.setHeader("Cache-Control", "public, max-age=3600");
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

app.get("/en/meta/movie/:imdbId", async (req, res) => {
    try {
        const meta = await en.enMeta(req.params.imdbId);
        res.json({ meta });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

app.get("/en/torrents/movie/:imdbId", async (req, res) => {
    try {
        const out = await en.enTorrents(req.params.imdbId);
        res.json(out);
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

// ---------------------------------------------------------------------------
// xtremestream (osmanonline.live) VOD pipeline: materialize HLS playlists and
// decrypt AES segments on the fly. Paths end in .m3u8 (ExoPlayer HLS sniffing).
// ---------------------------------------------------------------------------
function xsPlaylist(res, text) {
    res.setHeader("Content-Type", "application/vnd.apple.mpegurl");
    res.setHeader("Content-Length", Buffer.byteLength(text));
    res.setHeader("Cache-Control", "public, max-age=300");
    res.status(200);
    res.end(text);
}

app.get("/xs/master.m3u8", async (req, res) => {
    const { data, u } = req.query;
    if (!data || !u) return res.status(400).send("Missing data/u");
    try {
        const state = await xs.bootstrap(data, u);
        const names = xs.variantNames(state.manifest);
        if (!names.length) return res.status(404).send("No variants");

        const lines = [
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-INDEPENDENT-SEGMENTS"
        ];
        const audioTracks = state.manifest.audio || [];
        for (const track of audioTracks) {
            const lang = track.lang || "und";
            lines.push(
                `#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="${track.name || lang}",` +
                (lang !== "und" ? `LANGUAGE="${lang}",` : "") +
                `DEFAULT="${track.default ? "YES" : "NO"}",AUTOSELECT=YES,` +
                `URI="/xs/audio.m3u8?data=${encodeURIComponent(data)}&u=${encodeURIComponent(u)}&a=${encodeURIComponent(lang)}"`
            );
        }
        for (const name of names) {
            const info = state.manifest[name];
            let attrs = `BANDWIDTH=${parseInt(info.bw) || 0}`;
            if (info.res) attrs += `,RESOLUTION=${info.res}`;
            if (audioTracks.length) attrs += `,AUDIO="aud"`;
            lines.push(`#EXT-X-STREAM-INF:${attrs}`);
            lines.push(`/xs/media.m3u8?data=${encodeURIComponent(data)}&u=${encodeURIComponent(u)}&q=${encodeURIComponent(name)}`);
        }
        xsPlaylist(res, lines.join("\n") + "\n");
    } catch (e) {
        console.error("[xs/master]", e.message);
        res.status(502).send("xtremestream: " + e.message);
    }
});

function xsMediaPlaylistText(durs, td, uriFor) {
    // Per the HLS spec TARGETDURATION must be >= the longest EXTINF (rounded);
    // use the manifest hint only as a floor so odd durations never break players.
    const maxDur = Math.max(1, ...durs.map(d => parseFloat(d) || 0), parseInt(td) || 0);
    const lines = [
        "#EXTM3U",
        "#EXT-X-VERSION:3",
        `#EXT-X-TARGETDURATION:${Math.round(maxDur)}`,
        "#EXT-X-MEDIA-SEQUENCE:0",
        "#EXT-X-PLAYLIST-TYPE:VOD",
        "#EXT-X-INDEPENDENT-SEGMENTS"
    ];
    durs.forEach((dur, i) => {
        lines.push(`#EXTINF:${dur},`);
        lines.push(uriFor(i));
    });
    lines.push("#EXT-X-ENDLIST");
    return lines.join("\n") + "\n";
}

app.get("/xs/media.m3u8", async (req, res) => {
    const { data, u, q } = req.query;
    if (!data || !u || !/^\d+p?$/.test(q || "")) return res.status(400).send("Bad data/u/q");
    try {
        const state = await xs.bootstrap(data, u);
        const variant = state.manifest[q];
        if (!variant || !Array.isArray(variant.durs)) return res.status(404).send("Unknown quality");
        const qs = `data=${encodeURIComponent(data)}&u=${encodeURIComponent(u)}&q=${encodeURIComponent(q)}`;
        xsPlaylist(res, xsMediaPlaylistText(variant.durs, variant.td, i => `/xs/segment.ts?${qs}&i=${i}`));
    } catch (e) {
        console.error("[xs/media]", e.message);
        res.status(502).send("xtremestream: " + e.message);
    }
});

app.get("/xs/audio.m3u8", async (req, res) => {
    const { data, u, a } = req.query;
    if (!data || !u || !/^[A-Za-z0-9_-]+$/.test(a || "")) return res.status(400).send("Bad data/u/a");
    try {
        const state = await xs.bootstrap(data, u);
        const track = (state.manifest.audio || []).find(t => t.lang === a);
        if (!track || !Array.isArray(track.durs)) return res.status(404).send("Unknown audio track");
        const qs = `data=${encodeURIComponent(data)}&u=${encodeURIComponent(u)}&a=${encodeURIComponent(a)}`;
        xsPlaylist(res, xsMediaPlaylistText(track.durs, track.td, i => `/xs/segment.ts?${qs}&i=${i}`));
    } catch (e) {
        console.error("[xs/audio]", e.message);
        res.status(502).send("xtremestream: " + e.message);
    }
});

app.get("/xs/segment.ts", async (req, res) => {
    const { data, u, q, a, i } = req.query;
    if (!data || !u) return res.status(400).send("Missing data/u");
    const index = parseInt(i);
    if (!(index >= 0)) return res.status(400).send("Bad index");
    const isAudio = !!a && /^[A-Za-z0-9_-]+$/.test(a);
    if (!isAudio && !(/^\d+p?$/.test(q || ""))) return res.status(400).send("Bad q");

    let state;
    try {
        state = await xs.bootstrap(data, u);
    } catch (e) {
        return res.status(502).send("xtremestream: " + e.message);
    }

    const referer = state.origin + "/";
    const url = isAudio ? xs.audioSegmentUrl(state, index, a) : xs.segmentUrl(state, index, q);

    // Server-side decryption (the app-side XDS had a passthrough bug; will
    // revisit client-side decryption in v4.1). Sources 1-3 are already
    // bandwidth-free via direct segments; this only affects Source 4.
    try {
        const raw = await xs.fetchEncryptedSegment(url, referer);

        if (isAudio) {
            // audio segments are clear TS - passthrough
            res.setHeader("Content-Type", "video/mp2t");
            res.setHeader("Content-Length", raw.length);
            res.setHeader("Cache-Control", "public, max-age=3600");
            return res.status(200).end(raw);
        }

        // decrypt; on garbage/stale key, force one fresh bootstrap + key
        for (const force of [false, true]) {
            try {
                const key = await xs.getKey(state, force);
                const plain = xs.decryptSegment(raw, index, key);
                if (plain[0] !== 0x47) throw new Error("not TS after decrypt");
                res.setHeader("Content-Type", "video/mp2t");
                res.setHeader("Content-Length", plain.length);
                res.setHeader("Cache-Control", "public, max-age=3600");
                return res.status(200).end(plain);
            } catch (e) {
                if (force) throw e;
                // retry once with fresh bootstrap
                try {
                    state = await xs.bootstrap(data, u, true);
                } catch (e2) {
                    throw e;
                }
            }
        }
    } catch (e) {
        console.error(`[xs/segment ${data.slice(0, 8)}/${i}]`, e.message);
        if (!res.headersSent) res.status(502).send("xtremestream: " + e.message);
    }
});

// remember the public base URL from request headers (nginx/Caddy set
// X-Forwarded-Proto + Host) so stream URLs are built with the right host
app.use((req, res, next) => { publicbase.observe(req); next(); });

const addonInterface = builder.getInterface();
const addonRouter = getRouter(addonInterface);
app.use("/", addonRouter);

const PORT = process.env.PORT || 7000;
app.listen(PORT, () => {
    console.log(`Addon running on http://localhost:${PORT}`);

    // Render free dynos sleep after ~15 min without traffic; waking up takes
    // 30-60s which players experience as buffering/timeouts. A light self-ping
    // every 10 min keeps the service warm. Disable with KEEP_ALIVE=false.
    if (process.env.RENDER_EXTERNAL_URL && process.env.KEEP_ALIVE !== "false") {
        console.log("Keep-alive ping enabled for", process.env.RENDER_EXTERNAL_URL);
        setInterval(() => {
            axios.get(process.env.RENDER_EXTERNAL_URL + "/manifest.json", { timeout: 15000 })
                .then(() => console.log("keep-alive ping ok"))
                .catch(() => {});
        }, 10 * 60 * 1000).unref();
    }
});
