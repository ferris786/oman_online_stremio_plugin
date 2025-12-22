const { getSeries, getMeta } = require('../src/catalog');
const { getStream } = require('../src/stream');

async function debugSeries(seriesName) {
    console.log(`\n=== Debugging ${seriesName} ===`);
    console.log("1. Finding Series...");
    const series = await getSeries();
    const target = series.find(s => s.name === seriesName);

    if (!target) {
        console.error(`CRITICAL: ${seriesName} not found in catalog!`);
        return;
    }
    console.log("Found Series:", target);

    console.log("\n2. Getting Meta (Episodes)...");
    const meta = await getMeta('series', target.id);

    if (!meta || !meta.meta || !meta.meta.videos.length) {
        console.error(`CRITICAL: No episodes found for ${seriesName}!`);
        return;
    }

    console.log(`Found ${meta.meta.videos.length} episodes.`);
    const firstEpisode = meta.meta.videos[0];
    console.log("Checking FIRST Episode:", firstEpisode);

    console.log("\n3. Testing Stream Extraction for First Episode...");
    const streams = await getStream('series', firstEpisode.id);
    console.log("Streams Result:", JSON.stringify(streams, null, 2));
}

async function run() {
    await debugSeries("Barbaros Hayreddin Sultanin Fermani");
}

run();
