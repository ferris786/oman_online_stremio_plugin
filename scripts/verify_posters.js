const { getSeries } = require('../src/catalog');

async function test() {
    console.log("Testing getSeries()...");
    const series = await getSeries();
    console.log(`Found ${series.length} series.`);

    let allGood = true;
    const targets = ["Kurulus Osman", "Kurulus Orhan", "Kudus Fatihi Selahaddin Eyyubi", "Mehmed Fetihler Sultani"];

    series.forEach(s => {
        if (targets.includes(s.name)) {
            console.log(`[${s.name}] Poster: ${s.poster}`);
            if (!s.poster) {
                console.error(`ERROR: Missing poster for ${s.name}`);
                allGood = false;
            }
        }
    });

    if (allGood) {
        console.log("SUCCESS: All target series have posters.");
    } else {
        console.error("FAILURE: Some series are missing posters.");
    }
}

test();
