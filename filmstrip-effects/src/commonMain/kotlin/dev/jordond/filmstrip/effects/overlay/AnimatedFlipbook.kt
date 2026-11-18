package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.AnimatedPicture
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.AtlasGrid
import dev.jordond.filmstrip.picture.animated.animatedPeakRefusal
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.animated.atlasGrid
import dev.jordond.filmstrip.picture.pictureColor

/**
 * What an overlay's picture draws once it is read as a GIF, an APNG or a WebP.
 */
internal sealed interface AnimatedFlipbookRead {
  /**
   * The picture is not animated, so it is drawn the way a still is.
   */
  data object Still : AnimatedFlipbookRead

  /**
   * The picture is animated, and [flipbook] says which frame each output frame draws.
   *
   * @property picture The frames and the timeline as the file stores them.
   * @property grid Where each frame sits in the atlas a backend writes them into.
   * @property flipbook The cell of [grid] each output frame shows, which is [picture]'s timeline over the atlas.
   * @property color How the file describes the color of its frames, or null when it is untagged and read as sRGB.
   */
  class Drawn(
    val picture: AnimatedPicture,
    val grid: AtlasGrid,
    val flipbook: Flipbook,
    val color: PictureColor?,
  ) : AnimatedFlipbookRead

  /**
   * The picture cannot be drawn, and [message] says why in one sentence.
   */
  class Refused(
    val message: String,
  ) : AnimatedFlipbookRead
}

/**
 * Reads [bytes] as the file of an overlay's picture and lays its frames out as the cells of one atlas.
 *
 * A file with one frame reads as [AnimatedFlipbookRead.Still]. A file whose peak, counted for what [held] says the
 * backend keeps, passes [ceiling] bytes is refused, and so is one whose frames do not fit an atlas whose longest side
 * is [maxSide]. A null [maxSide] puts no limit on the atlas, for a caller that reads before it knows the side and asks
 * [AnimatedFlipbookRead.Drawn.refusalFor] once it does. An animated file on an overlay with a [sheet] is refused too,
 * since a sheet and an animation are two readings of one picture.
 *
 * A backend that draws no atlas reads a frame by its index, which [Flipbook.frameOnSlot] and [Flipbook.frameAt] give.
 *
 * @param sheet Whether the overlay the picture is drawn for has a sprite sheet.
 */
internal fun animatedRead(
  bytes: ByteArray,
  held: AnimatedCopies,
  sheet: Boolean = false,
  maxSide: Int? = null,
  ceiling: Long = PICTURE_PEAK_CEILING,
): AnimatedFlipbookRead {
  val picture =
    when (val read = animatedPictureOf(bytes, ceiling)) {
      AnimatedRead.Still -> return AnimatedFlipbookRead.Still
      is AnimatedRead.Refused -> return AnimatedFlipbookRead.Refused(read.message)
      is AnimatedRead.Animated -> read.picture
    }
  animatedPeakRefusal(picture, held, ceiling, bytes.size.toLong())?.let { return AnimatedFlipbookRead.Refused(it) }
  val grid =
    atlasGrid(picture.frames.size, picture.shown, null)
      ?: return AnimatedFlipbookRead.Refused(animatedAtlasTooWide(picture.frames.size, maxSide))
  val drawn = AnimatedFlipbookRead.Drawn(picture, grid, grid.flipbook(picture.timeline), pictureColor(bytes))
  return drawn.refusalFor(sheet, maxSide)?.let { AnimatedFlipbookRead.Refused(it) } ?: drawn
}

/**
 * Why this animated picture cannot be drawn for an overlay that has a sprite sheet when [sheet] is true, or whose
 * backend draws atlases no longer than [maxSide] on a side, or null when it can.
 */
internal fun AnimatedFlipbookRead.Drawn.refusalFor(
  sheet: Boolean,
  maxSide: Int?,
): String? =
  when {
    sheet -> SHEET_ON_ANIMATED
    atlasGrid(grid.cells.size, grid.cell, maxSide) == null -> animatedAtlasTooWide(grid.cells.size, maxSide)
    else -> null
  }

internal const val SHEET_ON_ANIMATED: String =
  "A sprite sheet cannot be laid over an animated picture, since the picture already plays its own frames. Drop the " +
    "sheet or give the overlay a still picture."

internal fun animatedAtlasTooWide(
  frameCount: Int,
  maxSide: Int?,
): String =
  "The $frameCount frames of this animated picture do not fit one atlas whose longest side is ${maxSide ?: "unset"} " +
    "pixels. Use fewer frames or a smaller picture."

/**
 * Why an animated picture cannot be drawn when the composition resolved no frame rate to count its frames on.
 */
@InternalFilmstripApi
public const val ANIMATED_NO_GRID: String =
  "An animated picture is drawn one frame per output frame, and this composition resolved no frame rate to count " +
    "them on. Give the export a frame rate."

/**
 * Why an animated picture cannot be drawn when the span it is sampled over has no end, for a backend that samples
 * every output frame ahead of drawing.
 */
@InternalFilmstripApi
public const val ANIMATED_NO_END: String =
  "An animated picture is drawn one frame per output frame, so it needs the span it is drawn over to end. Give the " +
    "composition an end, or give the overlay a visible range that ends."
