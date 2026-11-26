package com.kmk.turkishtv

import android.content.Context

/**
 * What to watch tonight.
 *
 * Every other row on the home screen asks the viewer to browse. This one
 * answers. Four picks at most, each with a plain reason for being there,
 * because a recommendation nobody understands is just another poster.
 *
 * It is built entirely from things the app already knows: the watch
 * history, the series catalogue that is already in memory, and the
 * recommendation endpoint that "Because you watched" uses. No new server
 * work, which also means it is right even when the connection is poor.
 *
 * Order matters and is deliberate. Picking up where you left off beats
 * anything clever, so it leads. A brand new episode of something being
 * followed beats a recommendation. A recommendation beats nothing.
 */
object Tonight {

    private const val MAX_PICKS = 4

    /**
     * @param kind     series | enseries | movie, as the rest of the app uses
     * @param reason   the line shown under the title, in plain words
     * @param videoId  set when this should resume a specific episode
     */
    data class Pick(
        val kind: String,
        val id: String,
        val name: String,
        val reason: String,
        val image: String?,
        val poster: String?,
        val videoId: String? = null,
        val history: WatchState.HistoryEntry? = null,
        val suggestion: Api.Suggestion? = null
    )

    /**
     * Blocking: call it off the main thread. Only the recommendation step
     * touches the network, and it is allowed to fail silently — three
     * picks are better than a spinner.
     */
    fun pick(ctx: Context, catalogue: List<Api.Series>): List<Pick> {
        val out = LinkedHashMap<String, Pick>()
        val history = WatchState.history(ctx)

        // 1. carry on with whatever is part-watched
        history.firstOrNull { !it.isFinished && it.positionMs > Config.RESUME_THRESHOLD_MS }
            ?.let { h ->
                val mins = (h.positionMs / 60000).toInt()
                out[h.videoId] = Pick(
                    kind = if (h.isMovie) "movie" else if (h.isEnSeries) "enseries" else "series",
                    id = h.seriesId.ifEmpty { h.videoId },
                    name = h.seriesName,
                    reason = if (h.isMovie) "You stopped $mins minutes in"
                             else "You stopped $mins minutes into S${h.season} E${h.episode}",
                    image = h.thumb ?: h.backdrop,
                    poster = h.poster,
                    videoId = h.videoId,
                    history = h
                )
            }

        // 2. a new episode of something being followed
        val following = history.map { it.seriesId }.distinct().toSet()
        catalogue.filter { it.id in following && it.airedWithinDays(Ui.NEW_WINDOW_DAYS) }
            .sortedByDescending { it.lastAired ?: "" }
            .firstOrNull { it.id !in out }
            ?.let { s ->
                out[s.id] = Pick(
                    kind = "series", id = s.id, name = s.name,
                    reason = "New episode  ·  " + (Ui.airedLabel(s.lastAired) ?: "this week"),
                    image = s.background, poster = s.poster
                )
            }

        // 3. something like the last film actually finished
        history.firstOrNull { it.isMovie && it.isFinished }?.let { last ->
            val imdb = last.videoId.removePrefix("movie:")
            val (title, films) = try { Api.similar(imdb) } catch (e: Exception) { Pair(null, emptyList()) }
            films.firstOrNull { "m:${it.tmdbId}" !in out }?.let { f ->
                out["m:${f.tmdbId}"] = Pick(
                    kind = "movie", id = f.tmdbId.toString(), name = f.name,
                    reason = "Because you finished ${title ?: last.seriesName}",
                    image = f.background, poster = f.poster,
                    suggestion = f
                )
            }
        }

        // 4. a series in the library that has never been opened
        if (out.size < MAX_PICKS) {
            catalogue.filter { it.id !in following && it.id !in out }
                .sortedByDescending { it.rating?.toFloatOrNull() ?: 0f }
                .firstOrNull()
                ?.let { s ->
                    out[s.id] = Pick(
                        kind = "series", id = s.id, name = s.name,
                        reason = buildString {
                            append("Highly rated and you have not started it")
                            s.rating?.let { append("  ·  ").append(it) }
                        },
                        image = s.background, poster = s.poster
                    )
                }
        }

        return out.values.take(MAX_PICKS).toList()
    }
}
