/**
 * Collections: franchises and themed sets, for the app's Collections tab.
 *
 * Two kinds, because TMDB models them differently:
 *
 *  - "collection" ids are TMDB's own official franchise collections
 *    (/collection/{id}). They come back ordered and complete, which is
 *    exactly what "the Harry Potter collection" means.
 *  - "discover" entries are /discover/movie queries, for franchises TMDB
 *    does NOT model as one collection. Marvel is the clear case: searching
 *    collections for "marvel" only finds sub-sets like Captain Marvel, so
 *    the MCU is served by its keyword instead.
 *
 * Every id below was resolved through TMDB search and checked to return at
 * least two films. A guessed id yields an empty row, which looks broken.
 */

const FRANCHISES = [
    { id: "harry-potter", title: "Harry Potter", collection: 1241 },
    { id: "lotr", title: "The Lord of the Rings", collection: 119 },
    { id: "hobbit", title: "The Hobbit", collection: 121938 },
    { id: "star-wars", title: "Star Wars", collection: 10 },
    { id: "bond", title: "James Bond", collection: 645 },
    { id: "dune", title: "Dune", collection: 726871 },
    { id: "matrix", title: "The Matrix", collection: 2344 },
    { id: "pirates", title: "Pirates of the Caribbean", collection: 295 },
    { id: "indiana-jones", title: "Indiana Jones", collection: 84 },
    { id: "jurassic", title: "Jurassic Park", collection: 328 },
    { id: "godfather", title: "The Godfather", collection: 230 },
    { id: "bttf", title: "Back to the Future", collection: 264 },
    { id: "toy-story", title: "Toy Story", collection: 10194 },
    { id: "shrek", title: "Shrek", collection: 2150 },
    { id: "despicable-me", title: "Despicable Me", collection: 86066 },
    { id: "httyd", title: "How to Train Your Dragon", collection: 89137 },
    { id: "mission-impossible", title: "Mission: Impossible", collection: 87359 },
    { id: "john-wick", title: "John Wick", collection: 404609 },
    { id: "hunger-games", title: "The Hunger Games", collection: 131635 },
    { id: "rocky", title: "Rocky", collection: 1575 },
    { id: "alien", title: "Alien", collection: 8091 },
    { id: "apes", title: "Planet of the Apes", collection: 173710 },
    { id: "spider-man", title: "Spider-Man (Raimi)", collection: 556 },
    { id: "dark-knight", title: "The Dark Knight", collection: 263 },
    { id: "fast-furious", title: "Fast & Furious", collection: 9485 },
    { id: "ice-age", title: "Ice Age", collection: 8354 },
    { id: "kung-fu-panda", title: "Kung Fu Panda", collection: 77816 },
    { id: "transformers", title: "Transformers", collection: 8650 },
    { id: "x-men", title: "X-Men", collection: 748 },
    { id: "conjuring", title: "The Conjuring", collection: 313086 },

    // Not one TMDB collection - these need a query
    {
        id: "mcu", title: "Marvel Cinematic Universe",
        discover: { with_keywords: 180547, "vote_count.gte": 200, sort_by: "primary_release_date.asc" }
    },
    {
        id: "pixar", title: "Pixar",
        discover: { with_companies: 3, "vote_count.gte": 100, sort_by: "primary_release_date.asc" }
    },
    {
        id: "ghibli", title: "Studio Ghibli",
        discover: { with_companies: 10342, "vote_count.gte": 50, sort_by: "primary_release_date.asc" }
    },
    {
        id: "dc", title: "DC Universe",
        discover: { with_keywords: 9715, "vote_count.gte": 200, sort_by: "primary_release_date.asc" }
    }
];

const byId = new Map(FRANCHISES.map(f => [f.id, f]));

function catalogue() {
    return FRANCHISES.map(f => ({ id: f.id, title: f.title }));
}

function franchise(id) {
    return byId.get(id) || null;
}

module.exports = { FRANCHISES, catalogue, franchise };
