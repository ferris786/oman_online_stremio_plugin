/**
 * Curated discovery lists ("The Reel").
 *
 * Each recipe is a named TMDB /discover/movie query. Keeping them on the
 * server means the set can be tuned without shipping an app build, and the
 * app only ever asks for "the catalogue" and "list N".
 *
 * Every keyword and company id below was resolved against TMDB's search
 * endpoints and checked to return real films - guessed ids silently return
 * an empty row, which would make the spin feel broken.
 *
 * Quality floors matter: a niche keyword needs a low vote_count or the list
 * is empty, while a whole decade needs a high one or it fills with obscure
 * titles nobody wants.
 */

const KW = {
    timeLoop: 10854, heist: 10051, revenge: 9748, roadTrip: 7312, submarine: 339,
    bodySwap: 33721, conArtist: 10453, postApoc: 359337, oneNight: 162914,
    dystopia: 4565, spaceTravel: 3801, serialKiller: 10714, courtroom: 33519,
    trueStory: 9672, comingOfAge: 10683, vampire: 3133, zombie: 12377,
    kungFu: 780, spy: 470, treasure: 6956, survival: 10349, boxing: 209476,
    chess: 316, amnesia: 1453, island: 2041, desert: 18034, prison: 378,
    journalism: 917, ai: 310, coldWar: 2106
};

const CO = { ghibli: 10342, pixar: 3, blumhouse: 3172, aardman: 297 };

// TMDB genre ids (movies)
const G = {
    action: 28, adventure: 12, animation: 16, comedy: 35, crime: 80,
    documentary: 99, drama: 18, family: 10751, fantasy: 14, history: 36,
    horror: 27, music: 10402, mystery: 9648, romance: 10749, sciFi: 878,
    thriller: 53, war: 10752, western: 37
};

function kw(id, votes = 80) {
    return { with_keywords: id, "vote_count.gte": votes, sort_by: "vote_average.desc" };
}
function years(from, to, votes = 400) {
    return {
        "primary_release_date.gte": `${from}-01-01`,
        "primary_release_date.lte": `${to}-12-31`,
        "vote_count.gte": votes,
        sort_by: "vote_average.desc"
    };
}

const LISTS = [
    // ---- themes: the heart of the feature ----
    { id: "time-loop", title: "Caught in a Time Loop", subtitle: "The same day, again and again", params: kw(KW.timeLoop, 150) },
    { id: "heist", title: "One Last Job", subtitle: "Heists, crews and clean getaways", params: kw(KW.heist, 150) },
    { id: "heist-70s", title: "Heists of the Seventies", subtitle: "When the crew wore corduroy", params: { ...kw(KW.heist, 60), "primary_release_date.gte": "1970-01-01", "primary_release_date.lte": "1979-12-31" } },
    { id: "revenge", title: "Settling the Score", subtitle: "Revenge, served at every temperature", params: kw(KW.revenge, 200) },
    { id: "road-trip", title: "Somewhere Down the Road", subtitle: "Road trips and the people on them", params: kw(KW.roadTrip, 150) },
    { id: "one-night", title: "All in One Night", subtitle: "Stories that end before sunrise", params: kw(KW.oneNight, 80) },
    { id: "submarine", title: "Below the Surface", subtitle: "Submarines, pressure and silence", params: kw(KW.submarine, 60) },
    { id: "con-artist", title: "Trust Nobody", subtitle: "Grifters, marks and long cons", params: kw(KW.conArtist, 100) },
    { id: "body-swap", title: "Not Myself Today", subtitle: "Body swaps and borrowed lives", params: kw(KW.bodySwap, 60) },
    { id: "post-apoc", title: "After the End", subtitle: "What is left once it all falls over", params: kw(KW.postApoc, 100) },
    { id: "dystopia", title: "A Worse Tomorrow", subtitle: "Dystopias and the people inside them", params: kw(KW.dystopia, 300) },
    { id: "space", title: "Out There", subtitle: "Space travel, the long way", params: kw(KW.spaceTravel, 200) },
    { id: "ai", title: "The Machines Think", subtitle: "Artificial intelligence, mostly going wrong", params: kw(KW.ai, 150) },
    { id: "serial-killer", title: "On the Hunt", subtitle: "Investigators and the people they chase", params: kw(KW.serialKiller, 200) },
    { id: "courtroom", title: "Order in Court", subtitle: "Trials, verdicts and closing arguments", params: kw(KW.courtroom, 80) },
    { id: "true-story", title: "This Really Happened", subtitle: "Based on a true story", params: kw(KW.trueStory, 400) },
    { id: "coming-of-age", title: "Growing Up", subtitle: "Coming of age, awkwardly", params: kw(KW.comingOfAge, 200) },
    { id: "survival", title: "Stay Alive", subtitle: "Survival, against the odds", params: kw(KW.survival, 150) },
    { id: "prison", title: "Inside", subtitle: "Prison walls and what happens behind them", params: kw(KW.prison, 150) },
    { id: "spy", title: "Tradecraft", subtitle: "Spies, handlers and dead drops", params: kw(KW.spy, 150) },
    { id: "cold-war", title: "The Cold War", subtitle: "Two sides, no shooting", params: kw(KW.coldWar, 80) },
    { id: "treasure", title: "X Marks the Spot", subtitle: "Treasure hunts and maps", params: kw(KW.treasure, 80) },
    { id: "kung-fu", title: "Hands and Feet", subtitle: "Kung fu, properly done", params: kw(KW.kungFu, 60) },
    { id: "boxing", title: "In the Corner", subtitle: "Fighters and the rounds they take", params: kw(KW.boxing, 60) },
    { id: "island", title: "No Way Off", subtitle: "Islands, willing and otherwise", params: kw(KW.island, 100) },
    { id: "desert", title: "Nothing but Sand", subtitle: "Films that bake in the sun", params: kw(KW.desert, 100) },
    { id: "amnesia", title: "Who Am I?", subtitle: "Memory, missing", params: kw(KW.amnesia, 100) },
    { id: "journalism", title: "Hold the Front Page", subtitle: "Reporters chasing the story", params: kw(KW.journalism, 80) },
    { id: "vampire", title: "After Dark", subtitle: "Vampires, old and new", params: kw(KW.vampire, 100) },
    { id: "zombie", title: "The Dead Walk", subtitle: "Zombies and the living who run", params: kw(KW.zombie, 100) },
    { id: "chess", title: "Your Move", subtitle: "Chess, and thinking ahead", params: kw(KW.chess, 40) },

    // ---- a single great year ----
    { id: "best-1999", title: "The Class of 1999", subtitle: "A famously good year at the pictures", params: { primary_release_year: 1999, "vote_count.gte": 800, sort_by: "vote_average.desc" } },
    { id: "best-1994", title: "The Class of 1994", subtitle: "One year, an unfair number of classics", params: { primary_release_year: 1994, "vote_count.gte": 800, sort_by: "vote_average.desc" } },
    { id: "best-1982", title: "The Class of 1982", subtitle: "The year science fiction grew up", params: { primary_release_year: 1982, "vote_count.gte": 300, sort_by: "vote_average.desc" } },
    { id: "best-2007", title: "The Class of 2007", subtitle: "A quietly extraordinary year", params: { primary_release_year: 2007, "vote_count.gte": 800, sort_by: "vote_average.desc" } },

    // ---- decades ----
    { id: "seventies", title: "The Seventies", subtitle: "Paranoia, grain and long lenses", params: years(1970, 1979, 300) },
    { id: "eighties-action", title: "Eighties Action", subtitle: "One-liners and practical effects", params: { ...years(1980, 1989, 200), with_genres: G.action } },
    { id: "nineties-scifi", title: "Nineties Science Fiction", subtitle: "Before everything was a franchise", params: { ...years(1990, 1999, 200), with_genres: G.sciFi } },
    { id: "silent-era", title: "Before the Talkies", subtitle: "Cinema's first thirty years", params: years(1900, 1935, 100) },

    // ---- studios ----
    { id: "ghibli", title: "Studio Ghibli", subtitle: "The whole shelf", params: { with_companies: CO.ghibli, "vote_count.gte": 50, sort_by: "vote_average.desc" } },
    { id: "pixar", title: "Pixar", subtitle: "Everything from the lamp", params: { with_companies: CO.pixar, "vote_count.gte": 100, sort_by: "vote_average.desc" } },
    { id: "blumhouse", title: "Blumhouse", subtitle: "Small budgets, big frights", params: { with_companies: CO.blumhouse, "vote_count.gte": 100, sort_by: "vote_average.desc" } },
    { id: "aardman", title: "Aardman", subtitle: "Plasticine and very British jokes", params: { with_companies: CO.aardman, "vote_count.gte": 40, sort_by: "vote_average.desc" } },

    // ---- world cinema (the Turkish one bridges both halves of the app) ----
    { id: "turkish", title: "Turkish Cinema", subtitle: "The best of Türkiye on film", params: { with_original_language: "tr", "vote_count.gte": 80, sort_by: "vote_average.desc" } },
    { id: "korean", title: "Korean Cinema", subtitle: "Thrillers, and everything else", params: { with_original_language: "ko", "vote_count.gte": 200, sort_by: "vote_average.desc" } },
    { id: "japanese", title: "Japanese Cinema", subtitle: "Live action and animated", params: { with_original_language: "ja", "vote_count.gte": 200, sort_by: "vote_average.desc" } },
    { id: "french", title: "French Cinema", subtitle: "From the New Wave onward", params: { with_original_language: "fr", "vote_count.gte": 200, sort_by: "vote_average.desc" } },
    { id: "hindi", title: "Hindi Cinema", subtitle: "The highest rated from India", params: { with_original_language: "hi", "vote_count.gte": 150, sort_by: "vote_average.desc" } },
    { id: "spanish", title: "Spanish-Language Cinema", subtitle: "Spain and Latin America", params: { with_original_language: "es", "vote_count.gte": 200, sort_by: "vote_average.desc" } },
    { id: "arabic", title: "Arabic Cinema", subtitle: "Films from the Arab world", params: { with_original_language: "ar", "vote_count.gte": 20, sort_by: "vote_average.desc" } },

    // ---- by the clock ----
    { id: "short-night", title: "Home by Nine", subtitle: "Nothing over a hundred minutes", params: { "with_runtime.lte": 100, "with_runtime.gte": 70, "vote_count.gte": 800, sort_by: "vote_average.desc" } },
    { id: "long-haul", title: "Clear the Evening", subtitle: "Two and a half hours and up", params: { "with_runtime.gte": 150, "vote_count.gte": 600, sort_by: "vote_average.desc" } },

    // ---- hidden gems: good, and almost nobody has voted ----
    { id: "gems", title: "Hidden Gems", subtitle: "Highly rated, barely seen", params: { "vote_average.gte": 7.5, "vote_count.gte": 50, "vote_count.lte": 400, sort_by: "vote_average.desc" } },
    { id: "gems-horror", title: "Overlooked Horror", subtitle: "Frights that passed you by", params: { with_genres: G.horror, "vote_average.gte": 7, "vote_count.gte": 40, "vote_count.lte": 400, sort_by: "vote_average.desc" } },
    { id: "gems-doc", title: "Documentaries Worth Finding", subtitle: "True, and well told", params: { with_genres: G.documentary, "vote_average.gte": 7.5, "vote_count.gte": 30, sort_by: "vote_average.desc" } },

    // ---- genre crossings ----
    { id: "comedy-crime", title: "Crime, Played for Laughs", subtitle: "When the job goes comically wrong", params: { with_genres: `${G.comedy},${G.crime}`, "vote_count.gte": 200, sort_by: "vote_average.desc" } },
    { id: "scifi-horror", title: "Science Fiction That Bites", subtitle: "Where sci-fi meets horror", params: { with_genres: `${G.sciFi},${G.horror}`, "vote_count.gte": 150, sort_by: "vote_average.desc" } },
    { id: "animation-adult", title: "Animation, Not for Children", subtitle: "Drawn, but not for the kids", params: { with_genres: `${G.animation},${G.drama}`, "vote_count.gte": 150, sort_by: "vote_average.desc" } },
    { id: "westerns", title: "The Western", subtitle: "Horses, debts and bad men", params: { with_genres: G.western, "vote_count.gte": 200, sort_by: "vote_average.desc" } },
    { id: "war", title: "The War Film", subtitle: "What it costs", params: { with_genres: G.war, "vote_count.gte": 300, sort_by: "vote_average.desc" } },
    { id: "music-docs", title: "Turn It Up", subtitle: "Music on screen", params: { with_genres: G.music, "vote_count.gte": 100, sort_by: "vote_average.desc" } }
];

const byId = new Map(LISTS.map(l => [l.id, l]));

/** the spin wheel's contents: names only, no films */
function catalogue() {
    return LISTS.map(l => ({ id: l.id, title: l.title, subtitle: l.subtitle }));
}

function recipe(id) {
    return byId.get(id) || null;
}

module.exports = { LISTS, catalogue, recipe };
