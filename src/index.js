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

// Proxy Route - Fixed for Android TV / ExoPlayer compatibility
app.get("/proxy", async (req, res) => {
    const targetUrl = req.query.url;
    const cookie = req.query.cookie;
    const referer = req.query.referer || "https://streamify360.com/";  // Allow dynamic referer

    if (!targetUrl) {
        return res.status(400).send("Missing url param");
    }

    try {
        console.log(`Proxying: ${targetUrl}`);
        log(`Proxy request for: ${targetUrl}`);

        // Forward Range header for ExoPlayer seeking support (critical for Android TV)
        const rangeHeader = req.headers.range;
        if (rangeHeader) {
            log(`Forwarding Range header: ${rangeHeader}`);
        }

        const response = await axios.get(targetUrl, {
            headers: {
                "User-Agent": req.headers["user-agent"] || "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer": referer,
                "Cookie": cookie || "",
                "Accept": "*/*",
                // Forward Range header if present (critical for ExoPlayer seeking)
                ...(rangeHeader && { "Range": rangeHeader })
            },
            responseType: "stream",
            validateStatus: (status) => status < 400 || status === 206  // Allow 206 Partial Content
        });

        // Forward critical headers for ExoPlayer (Content-Length, Accept-Ranges, etc.)
        const headersToForward = [
            "content-type",
            "content-length",
            "content-range",
            "accept-ranges",
            "etag",
            "last-modified",
            "cache-control"
        ];
        
        headersToForward.forEach(header => {
            const value = response.headers[header];
            if (value) res.setHeader(header, value);
        });

        // Set CORS headers for cross-origin requests
        res.setHeader("Access-Control-Allow-Origin", "*");
        res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers", "Range, Accept-Encoding, Origin, X-Requested-With, Content-Type");
        res.setHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges");

        // Set status code to match upstream (200 OK or 206 Partial Content)
        res.status(response.status);

        const contentType = response.headers["content-type"] || "";
        
        // Check if playlist to rewrite (HLS m3u8)
        const isPlaylist = targetUrl.includes(".m3u8") || targetUrl.includes(".txt") ||
            contentType.includes("mpegurl") || contentType.includes("x-mpegurl");

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

                // Rewrite URLs to absolute URLs (critical for Android TV ExoPlayer)
                const baseUrl = targetUrl.substring(0, targetUrl.lastIndexOf("/") + 1);
                const hostUrl = new URL(targetUrl).origin;
                const proxyHost = `${req.protocol}://${req.get("host")}`;

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

                    // Use absolute URLs for Android TV compatibility
                    const proxyPath = `${proxyHost}/proxy?url=${encodeURIComponent(absoluteLine)}&cookie=${encodeURIComponent(cookie || "")}&referer=${encodeURIComponent(referer)}`;
                    return proxyPath;
                });

                res.send(rewritten);
            });

            response.data.on("error", (err) => {
                console.error("Stream error (playlist):", err);
                log(`Stream error (playlist): ${err.message}`);
                if (!res.headersSent) res.status(500).send("Stream error");
            });

        } else {
            // It's a media segment (TS, MP4, Key, etc.)
            // Just pipe it! This uses minimal RAM.
            response.data.pipe(res);

            response.data.on("error", (err) => {
                console.error("Stream error (pipe):", err);
                log(`Stream error (pipe): ${err.message}`);
                if (!res.destroyed) res.destroy();
            });

            // Handle client disconnect
            req.on("close", () => {
                response.data.destroy();
            });
        }

    } catch (e) {
        console.error("Proxy error:", e.message);
        log(`Proxy error: ${e.message}`);
        if (e.response) {
            if (!res.headersSent) res.sendStatus(e.response.status);
        } else {
            if (!res.headersSent) res.status(500).send(e.message);
        }
    }
});

// Handle HEAD requests for ExoPlayer (used for capability checks)
app.head("/proxy", async (req, res) => {
    const targetUrl = req.query.url;
    const cookie = req.query.cookie;
    const referer = req.query.referer || "https://streamify360.com/";

    if (!targetUrl) {
        return res.status(400).send("Missing url param");
    }

    try {
        const response = await axios.head(targetUrl, {
            headers: {
                "User-Agent": req.headers["user-agent"] || "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer": referer,
                "Cookie": cookie || "",
                "Accept": "*/*"
            },
            validateStatus: (status) => status < 400 || status === 206
        });

        // Forward headers
        const headersToForward = [
            "content-type",
            "content-length",
            "accept-ranges",
            "etag",
            "last-modified"
        ];
        
        headersToForward.forEach(header => {
            const value = response.headers[header];
            if (value) res.setHeader(header, value);
        });

        res.setHeader("Access-Control-Allow-Origin", "*");
        res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        res.setHeader("Access-Control-Expose-Headers", "Content-Length, Accept-Ranges");

        res.status(response.status).end();
    } catch (e) {
        console.error("Proxy HEAD error:", e.message);
        if (!res.headersSent) res.status(e.response?.status || 500).end();
    }
});


const addonInterface = builder.getInterface();
const addonRouter = getRouter(addonInterface);
app.use("/", addonRouter);

const PORT = process.env.PORT || 7000;
app.listen(PORT, () => {
    console.log(`Addon running on http://localhost:${PORT}`);
});
