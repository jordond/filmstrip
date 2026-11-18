package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.picture.FrameTimeline
import kotlin.time.Duration

/**
 * A picture laid out as a uniform grid of cells, each cell one frame of an animation.
 *
 * Frames read left to right, then top to bottom. Every cell is the same size, and each frame lasts its own duration.
 * Durations are used as written, so a frame that lasts 5 ms is kept as 5 ms. Which frame an output frame shows is
 * settled by snapping the start of each frame to the nearest output frame, so a duration that no frame rate divides
 * evenly does not drift.
 *
 * Two sheets are equal when their grids, durations and plays are equal.
 *
 * @property columns How many cells sit side by side in the picture.
 * @property rows How many cells sit one above the other in the picture.
 * @property plays How many times the frames play through before the last one holds.
 * @throws IllegalArgumentException when [columns] or [rows] is below one, [frameDurations] is empty or longer than the
 * grid has cells, or a duration is zero, negative or infinite.
 */
public class SpriteSheet(
  public val columns: Int,
  public val rows: Int,
  frameDurations: List<Duration>,
  public val plays: FramePlays = FramePlays.Forever,
) {
  /**
   * How long each frame lasts, in frame order.
   *
   * The list is a copy of the one the sheet was built from.
   */
  public val frameDurations: List<Duration> = frameDurations.toList()

  /**
   * How many cells hold a frame, which is the number of durations.
   */
  public val frameCount: Int get() = this.frameDurations.size

  init {
    require(columns >= 1) { "A sprite sheet has at least one column, and $columns is not one." }
    require(rows >= 1) { "A sprite sheet has at least one row, and $rows is not one." }
    require(this.frameDurations.isNotEmpty()) { "A sprite sheet needs a frame, and its list of durations is empty." }
    require(this.frameDurations.size <= cellCount(columns, rows)) {
      "A $columns by $rows sprite sheet holds ${columns.toLong() * rows} frames, and ${this.frameDurations.size} " +
        "durations were given."
    }
    this.frameDurations.forEachIndexed { index, duration ->
      require(duration.isFinite() && duration > Duration.ZERO) {
        "Frame $index of a sprite sheet lasts $duration, and a frame lasts a positive, finite time."
      }
    }
  }

  /**
   * Builds a sheet whose [frameCount] frames each last [frameDuration].
   *
   * @param frameCount How many cells hold a frame, from one up to every cell of the grid.
   * @throws IllegalArgumentException when [columns] or [rows] is below one, [frameCount] is outside the grid, or
   * [frameDuration] is zero, negative or infinite.
   */
  public constructor(
    columns: Int,
    rows: Int,
    frameDuration: Duration,
    frameCount: Int = cellCount(columns, rows).toInt(),
    plays: FramePlays = FramePlays.Forever,
  ) : this(columns, rows, uniformDurations(columns, rows, frameDuration, frameCount), plays)

  internal val timeline: FrameTimeline =
    if (this.frameDurations.all { it == this.frameDurations[0] }) {
      FrameTimeline.uniform(this.frameDurations.size, this.frameDurations[0], plays)
    } else {
      FrameTimeline(this.frameDurations, plays)
    }

  override fun equals(other: Any?): Boolean =
    this === other ||
      (
        other is SpriteSheet &&
          columns == other.columns &&
          rows == other.rows &&
          plays == other.plays &&
          frameDurations == other.frameDurations
      )

  override fun hashCode(): Int {
    var result = columns
    result = 31 * result + rows
    result = 31 * result + frameDurations.hashCode()
    result = 31 * result + plays.hashCode()
    return result
  }

  override fun toString(): String =
    "SpriteSheet(columns=$columns, rows=$rows, frameDurations=$frameDurations, plays=$plays)"
}

private fun cellCount(
  columns: Int,
  rows: Int,
): Long = columns.toLong() * rows

private fun uniformDurations(
  columns: Int,
  rows: Int,
  frameDuration: Duration,
  frameCount: Int,
): List<Duration> {
  require(columns >= 1 && rows >= 1) { "A sprite sheet has at least one column and one row, not $columns by $rows." }
  require(frameCount in 1..cellCount(columns, rows)) {
    "A $columns by $rows sprite sheet holds frames 1 to ${cellCount(columns, rows)}, and $frameCount was asked for."
  }
  return List(frameCount) { frameDuration }
}
