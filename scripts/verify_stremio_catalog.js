const axios = require('axios');

async function verifyCatalog() {
    try {
        console.log("Fetching catalog from localhost:7000...\n");

        const response = await axios.get('http://localhost:7000/catalog/series/osmanonline_catalog.json');
        const metas = response.data.metas;

        console.log(`========================================`);
        console.log(`STREMIO CATALOG: Found ${metas.length} series`);
        console.log(`========================================\n`);

        // Check for specific series
        const targetSeries = ["Destan", "Kudus Fatihi", "Kurulus Osman", "Dirilis Ertugrul"];

        console.log("Checking for user-requested series:");
        targetSeries.forEach(target => {
            const found = metas.find(m => m.name && m.name.toLowerCase().includes(target.toLowerCase()));
            if (found) {
                console.log(`  ✅ ${target} - FOUND`);
                console.log(`     Name: "${found.name}"`);
                console.log(`     Poster: ${found.poster ? '✅ Yes' : '❌ No'}`);
            } else {
                console.log(`  ❌ ${target} - NOT FOUND`);
            }
        });

        console.log("\n========================================");
        console.log("All Series in Stremio Catalog:");
        console.log("========================================");

        metas.forEach((m, idx) => {
            const posterStatus = m.poster ? '✅' : '❌';
            console.log(`${idx + 1}. ${posterStatus} ${m.name || 'NAME_MISSING'}`);
        });

        // Count series with/without posters
        const withPosters = metas.filter(m => m.poster).length;
        const withoutPosters = metas.filter(m => !m.poster).length;

        console.log("\n========================================");
        console.log(`Series with posters: ${withPosters}`);
        console.log(`Series without posters: ${withoutPosters}`);
        console.log("========================================\n");

    } catch (error) {
        console.error("Error verifying catalog:", error.message);
        if (error.response) {
            console.error("Response:", error.response.data);
        }
    }
}

verifyCatalog();
