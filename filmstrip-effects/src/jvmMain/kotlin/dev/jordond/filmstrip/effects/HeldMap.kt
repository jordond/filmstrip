package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.FilterNode

/**
 * Moves a map's frames one second before the time they were drawn at, so a filter syncing a clip against the map
 * already holds it when the clip's first frame lands before zero.
 *
 * `remap` reading a clip frame with no map frame yet crashes ffmpeg, and `blend` hands that frame on as it came, so
 * every map either of them reads goes through this.
 */
@InternalFilmstripApi
public val AHEAD_OF_THE_CLIP: FilterNode = FilterNode("setpts", "expr" to "PTS-1/TB")
