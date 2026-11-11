package dev.jordond.filmstrip.transform.internal.encode

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.ExportSpec
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The key frame spacing a transcode writes when its spec names none.
 */
@InternalFilmstripApi
public val DEFAULT_KEY_FRAME_INTERVAL: Duration = 1.seconds

/**
 * How many frames apart an encoder writing [frameRate] places its key frames for [spec].
 *
 * It is [ExportSpec.keyFrameInterval] counted in frames, with [DEFAULT_KEY_FRAME_INTERVAL] standing in for no
 * interval. An interval that ends between two frames rounds to the nearer one, and half a frame rounds up. The count
 * is never below one, so an interval shorter than a frame makes every frame a key frame.
 *
 * The planner is the only caller, and every backend reads the answer off the plan's output format.
 */
@InternalFilmstripApi
public fun keyFrameIntervalOf(
  spec: ExportSpec,
  frameRate: Int,
): Int {
  val interval = spec.keyFrameInterval ?: DEFAULT_KEY_FRAME_INTERVAL
  val frames = (interval / 1.seconds * frameRate).roundToLong()
  return frames.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}
