const axios = require("axios");
const cheerio = require("cheerio");

const BASE_URL = "https://osmanonline.info";

async function debugAvailableSeries() {
    try {
        console.log(`Fetching series from: ${BASE_URL}\n`);
        const { data } = await axios.get(BASE_URL, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }
        });
        const $ = cheerio.load(data);

        console.log("Page title:", $("title").text());
        console.log("Nav length:", $("#main-nav").length);
        console.log("\n=== All navigation links ===\n");

        const items = $("#main-nav li a");

        items.each((i, el) => {
            const title = $(el).text().trim();
            const href = $(el).attr("href");

            console.log(`${i + 1}. Title: "${title}"`);
            console.log(`   URL: ${href}`);
            console.log(`   Excluded: ${title === "Home" || title === "Contact Us" ? "YES" : "NO"}`);
            console.log("");
        });

        console.log(`\nTotal links found: ${items.length}`);

        // Also check if there are series links elsewhere
        console.log("\n=== Checking for other potential series links ===\n");
        const allLinks = $("a[href]");
        console.log(`Total links on page: ${allLinks.length}`);

        // Look for links that might be series
        const potentialSeries = [];
        allLinks.each((i, el) => {
            const href = $(el).attr("href");
            const text = $(el).text().trim();

            // Look for patterns that might indicate series
            if (href && (
                href.includes("kudus") ||
                href.includes("destan") ||
                href.includes("series") ||
                href.includes("dizileri")
            )) {
                potentialSeries.push({ text, href });
            }
        });

        if (potentialSeries.length > 0) {
            console.log("\nPotential series links found:");
            potentialSeries.forEach(s => {
                console.log(`  - "${s.text}" => ${s.href}`);
            });
        }

    } catch (error) {
        console.error("Error:", error.message);
    }
}

debugAvailableSeries();
