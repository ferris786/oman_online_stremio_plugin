const axios = require('axios');

async function testNewPosters() {
    try {
        console.log("Testing newly added posters and descriptions...\n");

        const response = await axios.get('http://localhost:7000/catalog/series/osmanonline_catalog.json');
        const metas = response.data.metas;

        // Test the specifically added series
        const newSeries = [
            "Rumi",
            "Bozkir Aslani Celaleddin",
            "Aziz Mahmud",
            "Al Sancak",
            "Ibn Sina",
            "Gilani",
            "Mahsusa",
            "Vefa Sultan"
        ];

        console.log("Checking newly added series:");
        newSeries.forEach(target => {
            const found = metas.find(m => m.name && m.name.toLowerCase().includes(target.toLowerCase()));
            if (found) {
                console.log(`\n✅ ${target}`);
                console.log(`   Full Name: "${found.name}"`);
                console.log(`   Poster: ${found.poster ? '✅ ' + found.poster.substring(0, 60) + '...' : '❌ NO POSTER'}`);
                console.log(`   Description: ${found.description ? found.description.substring(0, 100) + '...' : 'NO DESCRIPTION'}`);
            } else {
                console.log(`\n❌ ${target} - NOT FOUND`);
            }
        });

    } catch (error) {
        console.error("Error:", error.message);
    }
}

testNewPosters();
