package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock

/**
 * What a [TapeLook] draws on one frame, which every renderer except ffmpeg's expressions reads.
 *
 * @property tick The tick the pattern is drawn at.
 * @property params The strengths the look has on that tick.
 */
@InternalFilmstripApi
public class TapePictureFrame private constructor(
  public val tick: Int,
  public val params: TapePictureParams,
) {
  @InternalFilmstripApi
  public companion object {
    /**
     * The frame [look] draws at [frameSeconds] of composition time, in a scope that covers [span].
     *
     * A [TapeClock.Wall] look in a preview that has a [clock] draws its pattern and its rolling band at the clock's
     * reading, which has no composition time to anchor to. Its settle still runs on the frame's own time, so the look
     * is at the strength the export draws there, and a rolling band that settles is drawn as it is once it has, gone,
     * with a band that does not settle rolling from tick 0. Every other look draws all of it at the frame's own time,
     * a rolling band counted from the start of [span].
     */
    public fun of(
      look: TapeLook,
      frameSeconds: Double,
      span: TimeRange,
      clock: PreviewClock?,
    ): TapePictureFrame {
      val wall = look.clock == TapeClock.Wall && clock != null
      val tick = TapePictureSignal.tickAt(TapePictureSignal.tapeSeconds(look, frameSeconds, clock))
      val level =
        TapePictureRamp.of(look, span)?.levelAt(TapePictureSignal.tickAt(frameSeconds)) ?: look.intensity.toDouble()
      return TapePictureFrame(tick, look.pictureParams(level, span.start.takeUnless { wall }))
    }
  }
}
