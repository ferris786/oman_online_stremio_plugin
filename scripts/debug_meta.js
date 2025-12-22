const { getSeries, getMeta } = require('../src/catalog');

async function run() {
    console.log("1. Finding Rumi...");
    const series = await getSeries();
    const rumi = series.find(s => s.name === 'Rumi');
    if (!rumi) {
        console.error("Rumi not found in catalog!");
        return;
    }
    console.log("Rumi ID:", rumi.id);
    console.log("Rumi URL:", rumi.url);

    console.log("\n2. Getting Meta/Episodes for Rumi...");
    const meta = await getMeta('series', rumi.id);
    if (!meta) {
        console.error("getMeta returned null");
        return;
    }
    console.log("Meta Videos Count:", meta.meta.videos.length);
    if (meta.meta.videos.length > 0) {
        console.log("First Video:", meta.meta.videos[0]);
    }
}

run();
