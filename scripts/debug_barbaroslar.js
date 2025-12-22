const { getSeries, getMeta } = require('../src/catalog');
const { getStream } = require('../src/stream');

async function debugSeries(seriesName) {
    console.log(`\n=== Debugging ${seriesName} ===`);

    console.log("1. Finding Series...");
    const seriesList = await getSeries();
    const series = seriesList.find(s => s.name === seriesName);

    if (!series) {
        console.error(`CRITICAL: Series "${seriesName}" not found in catalog!`);
        console.log("Available series:");
        seriesList.forEach(s => console.log(`- ${s.name}`));
        return;
    }

    console.log(`Found series: ${series.name} (ID: ${series.id})`);
    console.log(`Series URL: ${series.url}`);

    console.log("\n2. Getting Meta (Episodes)...");
    const meta = await getMeta('series', series.id);

    if (!meta || !meta.meta || !meta.meta.videos || meta.meta.videos.length === 0) {
        console.error("CRITICAL: No episodes found!");
        return;
    }

    console.log(`Found ${meta.meta.videos.length} episodes.`);

    // Check Episode 1
    const ep1 = meta.meta.videos.find(v => v.episode === 1 && v.season === 1) || meta.meta.videos[0];
    console.log("\nChecking Episode 1:", ep1);
    const streams1 = await getStream('series', ep1.id);
    console.log("Streams for Ep 1:", JSON.stringify(streams1, null, 2));

    // Check Episode 3 (from screenshot)
    const ep3 = meta.meta.videos.find(v => v.episode === 3 && v.season === 1);
    if (ep3) {
        console.log("\nChecking Episode 3:", ep3);
        const streams3 = await getStream('series', ep3.id);
        console.log("Streams for Ep 3:", JSON.stringify(streams3, null, 2));
    } else {
        console.log("Episode 3 not mapped correctly or missing.");
    }
}

async function run() {
    await debugSeries("Barbaroslar Akdenizin Kilici");
}

run();
