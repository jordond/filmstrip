package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.picture.PictureContainer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The delay a frame of an animated picture in [container] shows for, given the [stored] delay its file holds.
 *
 * A GIF that stores 10 ms or less shows for 100 ms. An APNG or a WebP replaces only a stored zero with 100 ms and plays
 * any positive delay as it is stored. Every reader passes every delay through here and nothing else changes one.
 *
 * @throws IllegalArgumentException when [stored] is negative or [container] is not a GIF, an APNG or a WebP.
 */
@InternalFilmstripApi
public fun shownFrameDelay(
  container: PictureContainer,
  stored: Duration,
): Duration {
  require(stored >= Duration.ZERO) { "A stored delay is not negative, and $stored is." }
  return when (container) {
    PictureContainer.Gif -> if (stored <= GIF_UNSPECIFIED) DEFAULT_DELAY else stored
    PictureContainer.Png, PictureContainer.Webp -> if (stored == Duration.ZERO) DEFAULT_DELAY else stored
    else -> throw IllegalArgumentException("$container does not animate.")
  }
}

/**
 * How many times an animated picture in [container] plays, given the loop [stored] in its file.
 *
 * A GIF with no loop block, which is a null [stored], plays once, and one that stores zero plays forever. A GIF stores
 * how many times it repeats, so one that stores N plays N + 1 times. An APNG or a WebP stores the number of plays
 * itself, zero for forever.
 *
 * @throws IllegalArgumentException when [stored] is negative, is null for a container other than a GIF, or [container]
 * is not a GIF, an APNG or a WebP.
 */
@InternalFilmstripApi
public fun framePlays(
  container: PictureContainer,
  stored: Int?,
): FramePlays {
  require(stored == null || stored >= 0) { "A stored loop count is not negative, and $stored is." }
  return when (container) {
    PictureContainer.Gif -> {
      when (stored) {
        null -> FramePlays.Once
        0 -> FramePlays.Forever
        else -> FramePlays.Times(stored + 1)
      }
    }
    PictureContainer.Png, PictureContainer.Webp -> {
      requireNotNull(stored) { "$container always stores its plays." }
      if (stored == 0) FramePlays.Forever else FramePlays.Times(stored)
    }
    else -> {
      throw IllegalArgumentException("$container does not animate.")
    }
  }
}

private val GIF_UNSPECIFIED = 10.milliseconds
private val DEFAULT_DELAY = 100.milliseconds
