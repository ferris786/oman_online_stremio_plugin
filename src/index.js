const express = require("express");
const cors = require("cors");
const axios = require("axios");
const { addonBuilder, getRouter } = require("stremio-addon-sdk");
const manifest = require("./manifest");
const { getSeries, getMeta } = require("./catalog");
const { getStream } = require("./stream");
const urlModule = require("url");

const app = express();
app.use(cors());

const builder = new addonBuilder(manifest);

builder.defineCatalogHandler(async ({ type, id, extra }) => {
    console.log("Catalog request:", type, id);
    if (type === "series" && id === "osmanonline_catalog") {
        const metas = await getSeries();
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

                // Rewrite URLs, carrying the referer/cookie context to child requests
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


const addonInterface = builder.getInterface();
const addonRouter = getRouter(addonInterface);
app.use("/", addonRouter);

const PORT = process.env.PORT || 7000;
app.listen(PORT, () => {
    console.log(`Addon running on http://localhost:${PORT}`);
});
