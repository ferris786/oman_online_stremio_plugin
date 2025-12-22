const { getSeries } = require('../src/catalog');

async function testSeriesDiscovery() {
    console.log("Testing series discovery with new hybrid approach...\n");

    try {
        const series = await getSeries();

        console.log(`\n========================================`);
        console.log(`SUMMARY: Found ${series.length} total series`);
        console.log(`========================================\n`);

        // Check for specific series mentioned by user
        const targetSeries = ["Destan", "Kudus Fatihi", "Kurulus Osman", "Dirilis Ertugrul"];

        console.log("Checking for user-requested series:");
        targetSeries.forEach(target => {
            const found = series.find(s => s.name.toLowerCase().includes(target.toLowerCase()));
            if (found) {
                console.log(`  ✅ ${target} - FOUND as "${found.name}"`);
                console.log(`     URL: ${found.url}`);
                console.log(`     Poster: ${found.poster ? '✅ Yes' : '❌ No'}`);
            } else {
                console.log(`  ❌ ${target} - NOT FOUND`);
            }
        });

        console.log("\n========================================");
        console.log("All Series with Poster Status:");
        console.log("========================================");

        series.forEach((s, idx) => {
            const posterStatus = s.poster ? '✅' : '❌';
            console.log(`${idx + 1}. ${posterStatus} ${s.name}`);
        });

        // Count series with/without posters
        const withPosters = series.filter(s => s.poster).length;
        const withoutPosters = series.filter(s => !s.poster).length;

        console.log("\n========================================");
        console.log(`Series with posters: ${withPosters}`);
        console.log(`Series without posters: ${withoutPosters}`);
        console.log("========================================");

    } catch (error) {
        console.error("Error testing series discovery:", error);
    }
}

testSeriesDiscovery();
