package dev.jordond.filmstrip.still

import dev.drewhamilton.poko.Poko

/**
 * What an encoded still should look like.
 *
 * @property format The image format to encode in.
 * @property quality How much of the image a lossy [format] keeps, from 0 to 100. A value outside
 *   that range is clamped rather than refused, and [StillFormat.Png] ignores it.
 * @property heightPx The height to encode at, in pixels. The width follows from the frame's aspect.
 *   Null keeps the frame's own height, and a height of zero or less fails the still with
 *   [dev.jordond.filmstrip.FilmstripError.InvalidComposition]. A height that would scale the frame
 *   past the largest still filmstrip encodes is held to that size instead, so an upscale can grow a
 *   frame only so far.
 */
@Poko
public class StillSpec(
  public val format: StillFormat = StillFormat.Jpeg,
  public val quality: Int = DEFAULT_QUALITY,
  public val heightPx: Int? = null,
)

private const val DEFAULT_QUALITY = 90
