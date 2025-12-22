const { getSeries, getMeta } = require('../src/catalog');
const { getStream } = require('../src/stream');

async function debugOrhan() {
    console.log("1. Finding Series...");
    const series = await getSeries();
    const orhan = series.find(s => s.name === "Kurulus Orhan");

    if (!orhan) {
        console.error("CRITICAL: Kurulus Orhan not found in catalog!");
        return;
    }
    console.log("Found Series:", orhan);

    console.log("\n2. Getting Meta (Episodes)...");
    const meta = await getMeta('series', orhan.id);

    if (!meta || !meta.meta || !meta.meta.videos.length) {
        console.error("CRITICAL: No episodes found for Kurulus Orhan!");
        return;
    }

    console.log(`Found ${meta.meta.videos.length} episodes.`);
    const firstEpisode = meta.meta.videos[0];
    console.log("First Episode:", firstEpisode);

    console.log("\n3. Testing Stream Extraction for First Episode...");
    // Simulate getStream logic
    const streams = await getStream('series', firstEpisode.id);
    console.log("Streams Result:", JSON.stringify(streams, null, 2));
}

debugOrhan();
