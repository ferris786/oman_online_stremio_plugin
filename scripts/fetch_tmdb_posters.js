const axios = require('axios');

const API_KEY = "5cc53e03562e40db9fe6d58e70486bcf";
const BEARER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1Y2M1M2UwMzU2MmU0MGRiOWZlNmQ1OGU3MDQ4NmJjZiIsIm5iZiI6MTQ3MDU2MzUxOS45NjcsInN1YiI6IjU3YTcwNGJmOTI1MTQxNWZlYTAwMDVlNSIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.Kn3XzgxN9SLfO8dGdQoLUq-QJqr4EOVKWZMS9nv1Ywo";

const QUERIES = [
    "Kuruluş Osman",
    "Kudüs Fatihi Selahaddin Eyyubi",
    "Mehmed: Fetihler Sultanı",
    "Kuruluş Orhan"
];

async function searchShow(query) {
    try {
        const url = `https://api.themoviedb.org/3/search/tv?query=${encodeURIComponent(query)}&include_adult=false&language=en-US&page=1`;
        const res = await axios.get(url, {
            headers: {
                Authorization: `Bearer ${BEARER_TOKEN}`,
                accept: 'application/json'
            }
        });

        if (res.data.results && res.data.results.length > 0) {
            const show = res.data.results[0];
            const posterUrl = `https://image.tmdb.org/t/p/w500${show.poster_path}`;
            console.log(`FOUND: "${query}" -> ${show.original_name} (${show.first_air_date})`);
            console.log(`URL: ${posterUrl}`);
            return { query, url: posterUrl };
        } else {
            console.log(`NOT FOUND: "${query}"`);
            return { query, url: null };
        }
    } catch (e) {
        console.error(`ERROR searching for "${query}":`, e.message);
        return { query, url: null };
    }
}

const fs = require('fs');

async function run() {
    console.log("Fetching posters from TMDB...");
    const results = {};
    for (const q of QUERIES) {
        const res = await searchShow(q);
        if (res.url) {
            results[q] = res.url;
        }
    }
    fs.writeFileSync('posters.json', JSON.stringify(results, null, 2));
    console.log("Done. Wrote to posters.json");
}

run();
