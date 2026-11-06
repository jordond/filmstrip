package dev.jordond.filmstrip.geometry

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The refusal for an output height that is not positive, or null when [heightPx] can be used.
 *
 * Every `heightPx` on the public API reads the same way. Null keeps the frame's own height and a positive value asks
 * for that height. Zero or a negative value is refused with the [FilmstripError.InvalidComposition] this returns, which
 * the export planner, [dev.jordond.filmstrip.still.FrameRenderer] and the still encoder all hand back as their failure.
 */
@InternalFilmstripApi
public fun outputHeightRefusal(heightPx: Int?): FilmstripError.InvalidComposition? =
  if (heightPx != null && heightPx <= 0) {
    FilmstripError.InvalidComposition(
      "The output height is $heightPx px, and a height has to be positive. Leave it null to keep the " +
        "frame's own height.",
    )
  } else {
    null
  }

/**
 * The height an export or preview scales its frame to, or null when it keeps [naturalHeight].
 *
 * A `heightPx` is a maximum. One at or above [naturalHeight] keeps the natural frame, so a 720p source asked for
 * 1080 stays at 720. A composition framed by a still, one whose primary track holds no video clip, takes [heightPx] as
 * it is. The export planner, its copy check and the player's preview all read the height from here.
 */
@InternalFilmstripApi
public fun effectiveHeight(
  heightPx: Int?,
  naturalHeight: Int,
  framedByStill: Boolean,
): Int? =
  when {
    heightPx == null -> null
    framedByStill -> heightPx
    heightPx >= naturalHeight -> null
    else -> heightPx
  }
