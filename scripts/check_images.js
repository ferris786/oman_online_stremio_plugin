const axios = require('axios');

const POSTERS = {
    "Kurulus Osman": "https://m.media-amazon.com/images/M/MV5BNjg0OTI3MzYtODQ1MS00NjEzLWI0YzMtZDYxYTZlOTdhOTM3XkEyXkFqcGdeQXVyMTUzOTcyODA5._V1_FMjpg_UX1000_.jpg",
    "Kurulus Orhan": "https://i.pinimg.com/736x/2a/85/65/2a85659881J768882.jpg",
    "Kudus Fatihi Selahaddin Eyyubi": "https://m.media-amazon.com/images/M/MV5BYTJlZWY2YjYtYjYxMC00ZGI5LThkZjYtMzY0YzYxYTU2N2M1XkEyXkFqcGdeQXVyMTY3ODkyNDkz._V1_.jpg",
    "Mehmed Fetihler Sultani": "https://m.media-amazon.com/images/M/MV5BN2IzY2M4YzAtOWMyMy00MHI2LTllMTItODdjMjE4ZDU5YTQxXkEyXkFqcGdeQXVyODQ4MjU1MzM@._V1_.jpg"
};

async function checkImages() {
    for (const [name, url] of Object.entries(POSTERS)) {
        try {
            const start = Date.now();
            const res = await axios.head(url);
            console.log(`[PASS] ${name}: ${res.status} (${Date.now() - start}ms) - ${res.headers['content-type']}`);
        } catch (e) {
            console.error(`[FAIL] ${name}: ${e.message}`);
            // Try GET if HEAD fails
            try {
                const res = await axios.get(url, { responseType: 'stream' });
                console.log(`[PASS-GET] ${name}: ${res.status} - ${res.headers['content-type']}`);
            } catch (e2) {
                console.error(`[FAIL-GET] ${name}: ${e2.message}`);
            }
        }
    }
}

checkImages();
