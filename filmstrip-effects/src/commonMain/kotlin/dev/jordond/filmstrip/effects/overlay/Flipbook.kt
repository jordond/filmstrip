package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.animated.AtlasGrid
import kotlin.time.Duration

/**
 * The cell of a sprite sheet or of an animated picture's atlas each output frame shows.
 *
 * The cells are rectangles of the picture as it is shown, after its orientation and clean aperture, one per frame in
 * frame order. The [timeline] says which of them an output frame draws.
 *
 * @property timeline When each frame starts and how often the frames play.
 * @property cell The size every cell has.
 * @property cells The rectangle of each frame, in frame order.
 */
internal class Flipbook(
  val timeline: FrameTimeline,
  val cell: Size,
  val cells: List<PictureCrop>,
) {
  /**
   * The index of the frame shown on [slot] when the first frame starts at [drawnStart].
   */
  fun frameOnSlot(
    drawnStart: Duration,
    slot: Long,
    frameRate: Int,
  ): Int = timeline.frameOnSlot(drawnStart, slot, frameRate)

  /**
   * The index of the frame shown at [compositionTime], which is put on its output slot first.
   */
  fun frameAt(
    drawnStart: Duration,
    compositionTime: Duration,
    frameRate: Int,
  ): Int = timeline.frameAt(drawnStart, compositionTime, frameRate)

  /**
   * The cell shown on [slot] when the first frame starts at [drawnStart].
   */
  fun cellOnSlot(
    drawnStart: Duration,
    slot: Long,
    frameRate: Int,
  ): PictureCrop = cells[frameOnSlot(drawnStart, slot, frameRate)]

  /**
   * The cell shown at [compositionTime], which is put on its output slot first.
   */
  fun cellAt(
    drawnStart: Duration,
    compositionTime: Duration,
    frameRate: Int,
  ): PictureCrop = cells[frameAt(drawnStart, compositionTime, frameRate)]
}

/**
 * The flipbook that shows each cell of this atlas for as long as [timeline] says its frame lasts.
 */
internal fun AtlasGrid.flipbook(timeline: FrameTimeline): Flipbook = Flipbook(timeline, cell, cells)

/**
 * What a [SpriteSheet] draws once it is laid over a picture.
 */
internal sealed interface FlipbookRead {
  /**
   * The sheet holds one frame, so [cell] is drawn on every output frame.
   *
   * @property cell The rectangle of the one frame.
   */
  class Still(
    val cell: PictureCrop,
  ) : FlipbookRead

  /**
   * The sheet holds several frames, and [flipbook] says which one each output frame draws.
   */
  class Drawn(
    val flipbook: Flipbook,
  ) : FlipbookRead

  /**
   * The sheet cannot be laid over the picture, and [message] says why in one sentence.
   */
  class Refused(
    val message: String,
  ) : FlipbookRead
}

/**
 * Lays this sheet's grid over a picture [shown] at its size after orientation and clean aperture.
 *
 * A side the grid does not divide evenly is refused, never rounded.
 */
internal fun SpriteSheet.flipbookOver(shown: Size): FlipbookRead {
  if (shown.width <= 0 || shown.height <= 0 || shown.width % columns != 0 || shown.height % rows != 0) {
    return FlipbookRead.Refused(sheetNotDivisible(this, shown))
  }
  val cell = Size(shown.width / columns, shown.height / rows)
  val cells =
    List(frameCount) { index -> PictureCrop((index % columns) * cell.width, (index / columns) * cell.height, cell) }
  return if (frameCount == 1) {
    FlipbookRead.Still(cells[0])
  } else {
    FlipbookRead.Drawn(Flipbook(timeline, cell, cells))
  }
}

/**
 * Why a flipbook cannot be driven, which is [refusal] when the planner resolved no output rate to count it on, or null
 * when it did.
 *
 * @param refusal The sentence for the kind of flipbook, the sprite sheet's by default and [ANIMATED_NO_GRID] for an
 * animated picture.
 */
internal fun flipbookRefusal(
  attributes: Attributes,
  refusal: String = FLIPBOOK_NO_GRID,
): String? = if (attributes.gridRate == null) refusal else null

internal fun sheetNotDivisible(
  sheet: SpriteSheet,
  shown: Size,
): String =
  "A sprite sheet of ${sheet.columns} columns and ${sheet.rows} rows needs a picture whose width divides by " +
    "${sheet.columns} and whose height divides by ${sheet.rows}, and this one is shown at " +
    "${shown.width}x${shown.height}."

internal const val FLIPBOOK_NO_GRID =
  "A sprite sheet is drawn one cell per output frame, and this composition resolved no frame rate to count them " +
    "on. Give the export a frame rate."
