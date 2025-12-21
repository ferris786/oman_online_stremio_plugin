
const { getSeries } = require('../src/catalog');

async function test() {
    console.log("Running getSeries...");
    const series = await getSeries();

    const home = series.find(s => s.name === "Home");
    const contact = series.find(s => s.name === "Contact Us");

    if (home || contact) {
        console.error("FAILED: 'Home' or 'Contact Us' still present!");
        if (home) console.log("Found: Home");
        if (contact) console.log("Found: Contact Us");
    } else {
        console.log("SUCCESS: 'Home' and 'Contact Us' were removed.");
    }

    // Optional: Log names of first few items to confirm we have data
    console.log("First 5 items:", series.slice(0, 5).map(s => s.name));
}

test();
