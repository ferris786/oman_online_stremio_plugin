const { getSeries, getMeta } = require("./src/catalog");
const { getStream } = require("./src/stream");

async function test() {
    console.log("--- Testing Catalog (Series) ---");
    const series = await getSeries();
    console.log(`Found ${series.length} series.`);
    if (series.length > 0) {
        console.log("First series:", series[0]);
    } else {
        console.error("No series found!");
        return;
    }

    console.log("\n--- Testing Meta (Episodes) ---");
    // Find Kurulus Osman
    const targetSeries = series.find(s => s.name.toLowerCase().includes("kurulus osman")) || series[0];
    const seriesId = targetSeries.id;
    console.log(`Testing with series: ${targetSeries.name} (${seriesId})`);

    const meta = await getMeta("series", seriesId);

    // Debug: fetch scripts.php to reverse engineer
    try {
        console.log("Fetching scripts.php...");
        const scriptRes = await require("axios").get("https://turktvuk.com/player/assets/scripts.php?v=6");
        require("fs").writeFileSync("scripts_dump.js", scriptRes.data);
        console.log("Dumped scripts.php to scripts_dump.js");
    } catch (e) { console.error("Script fetch failed", e.message); }

    if (meta && meta.meta && meta.meta.videos.length > 0) {
        console.log(`Found ${meta.meta.videos.length} episodes.`);

        const preview = meta.meta.videos.slice(0, 5).map(v => ({
            title: v.title,
            season: v.season,
            episode: v.episode,
            id: v.id
        }));
        console.log("Episodes Preview:", JSON.stringify(preview, null, 2));

        // console.log("First 3 episodes:", JSON.stringify(meta.meta.videos.slice(0, 3), null, 2));

        console.log("\n--- Testing Stream (Video URL) ---");
        // Test stream extraction for the first episode
        const episodeId = meta.meta.videos[0].id;
        console.log(`Fetching stream for: ${episodeId}`);

        const stream = await getStream("series", episodeId);
        // console.log("Stream result:", JSON.stringify(stream, null, 2));
        if (stream.streams && stream.streams.length > 0) {
            const proxyUrl = stream.streams[0].url;
            console.log("PROXY_URL=" + proxyUrl);
            require("fs").writeFileSync("url.txt", proxyUrl);

            // Verification: Fetch the proxy URL to see what it returns
            try {
                const proxyRes = await require("axios").get(proxyUrl);
                console.log("Proxy Status:", proxyRes.status);
                require("fs").writeFileSync("proxy_dump.m3u8", proxyRes.data);
                console.log("Dumped proxy content to proxy_dump.m3u8");
            } catch (err) {
                console.error("Failed to fetch proxy URL:", err.message);
                if (err.response) {
                    console.error("Status:", err.response.status);
                }
            }
        } else {
            console.log("No streams found");
        }

    } else {
        console.error("No episodes found or meta failed!");
    }
}

test();
