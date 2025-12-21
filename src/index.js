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
app.get("/proxy", async (req, res) => {
    const targetUrl = req.query.url;
    const cookie = req.query.cookie;

    if (!targetUrl) {
        return res.status(400).send("Missing url param");
    }

    try {
        console.log(`Proxying: ${targetUrl}`);
        console.log(`Proxy Cookie: ${cookie}`);

        const response = await axios.get(targetUrl, {
            headers: {
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer": "https://streamify360.com/",
                "Cookie": cookie || "",
                "Accept": "*/*",
                "Accept-Encoding": "identity"
            },
            responseType: "arraybuffer", // Get raw buffer
            validateStatus: (status) => status < 400
        });

        const contentType = response.headers["content-type"];
        res.setHeader("Content-Type", contentType);

        // Forward some headers?
        // res.setHeader("Access-Control-Allow-Origin", "*"); // handled by cors

        // Check if playlist to rewrite
        // .txt or .m3u8 or application/vnd.apple.mpegurl
        const isPlaylist = targetUrl.includes(".txt") || targetUrl.includes(".m3u8") ||
            (contentType && contentType.includes("mpegurl"));

        if (isPlaylist) {
            let content = response.data.toString("utf-8");

            // Force HLS content type for Stremio to ensure it recognizes the playlist
            res.setHeader("Content-Type", "application/vnd.apple.mpegurl");

            // Rewrite URLs
            // 1. Absolute URLs (http...) -> /proxy?url=...
            // 2. Relative URLs (/...) -> /proxy?url=absolute...
            // 3. Simple filenames -> /proxy?url=base+filename...

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

                // Encode for proxy
                // Assuming we are running on same host, headers.host gives us current host
                // Actually we just need path relative to current server
                const proxyPath = `/proxy?url=${encodeURIComponent(absoluteLine)}&cookie=${encodeURIComponent(cookie || "")}`;
                return proxyPath;
            });

            res.send(rewritten);
        } else {
            // Segment, just pipe
            res.send(response.data);
        }

    } catch (e) {
        console.error("Proxy error:", e.message);
        if (e.response) {
            res.status(e.response.status).send(e.message);
        } else {
            res.status(500).send(e.message);
        }
    }
});


const addonInterface = builder.getInterface();
const addonRouter = getRouter(addonInterface);
app.use("/", addonRouter);

const PORT = process.env.PORT || 7000;
app.listen(PORT, () => {
    console.log(`Addon running on http://localhost:${PORT}`);
});
