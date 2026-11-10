package dev.jordond.filmstrip.effects.filters.vhs

import dev.drewhamilton.poko.Poko
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The band of mistracked tape that a [Vhs] runs through the picture.
 *
 * Inside the band rows slide sideways, streaky noise rises, colour fades and the picture mixes toward black and white
 * snow. [Vhs.intensity] scales the slide, the noise and the snow, and at `0f` no band runs at all.
 */
public sealed interface Tracking {
  /**
   * No tracking band, the tape is tracked perfectly.
   */
  public data object Off : Tracking

  /**
   * Bands that turn up now and then, each drifting down the frame and fading in and out over four seconds.
   *
   * @property chance The chance a band runs in any four second window, from `0f` to `1f`. [Vhs.intensity] scales it by
   * its square.
   * @property height How tall the band is, as a fraction of the frame height from `0.02f` to `0.5f`.
   * @property shift The largest sideways slide inside the band, as a fraction of the frame width from `0f` to `0.1f`.
   * @property snow How far the middle of the band mixes toward snow, from `0f` which keeps the picture to `1f`.
   * @throws IllegalArgumentException when a value is outside its range.
   */
  @Poko
  public class Events(
    public val chance: Float = DEFAULT_CHANCE,
    public val height: Float = DEFAULT_HEIGHT,
    public val shift: Float = DEFAULT_SHIFT,
    public val snow: Float = DEFAULT_SNOW,
  ) : Tracking {
    init {
      require(chance in 0f..1f) { "A tracking chance has to be within 0 to 1, not $chance." }
      requireBand(height, shift, snow)
    }
  }

  /**
   * One band that is always there, rolling from the top of the frame to the bottom every [period], the look of a tape
   * that needs tracking.
   *
   * @property period How long the band takes to roll through the frame once.
   * @property height How tall the band is, as a fraction of the frame height from `0.02f` to `0.5f`.
   * @property shift The largest sideways slide inside the band, as a fraction of the frame width from `0f` to `0.1f`.
   * @property snow How far the middle of the band mixes toward snow, from `0f` which keeps the picture to `1f`.
   * @throws IllegalArgumentException when [period] is not positive and finite, or a value is outside its range.
   */
  @Poko
  public class Rolling(
    public val period: Duration = DEFAULT_PERIOD,
    public val height: Float = DEFAULT_HEIGHT,
    public val shift: Float = DEFAULT_SHIFT,
    public val snow: Float = DEFAULT_SNOW,
  ) : Tracking {
    init {
      require(period.isPositive() && period.isFinite()) { "A tracking period has to be positive, not $period." }
      requireBand(height, shift, snow)
    }
  }

  public companion object {
    /**
     * The chance an [Events] band runs in a window, unless it is given one.
     */
    public const val DEFAULT_CHANCE: Float = 0.2f

    /**
     * The height of a band, unless it is given one.
     */
    public const val DEFAULT_HEIGHT: Float = 0.07f

    /**
     * The largest slide inside a band, unless it is given one.
     */
    public const val DEFAULT_SHIFT: Float = 0.02f

    /**
     * How far a band mixes toward snow, unless it is given a value.
     */
    public const val DEFAULT_SNOW: Float = 0.6f

    /**
     * How long a [Rolling] band takes to cross the frame, unless it is given a period.
     */
    public val DEFAULT_PERIOD: Duration = 6.seconds
  }
}

private fun requireBand(
  height: Float,
  shift: Float,
  snow: Float,
) {
  require(height in MIN_HEIGHT..MAX_HEIGHT) { "A tracking height has to be within 0.02 to 0.5, not $height." }
  require(shift in 0f..MAX_SHIFT) { "A tracking shift has to be within 0 to 0.1, not $shift." }
  require(snow in 0f..1f) { "A tracking snow has to be within 0 to 1, not $snow." }
}

private const val MIN_HEIGHT = 0.02f
private const val MAX_HEIGHT = 0.5f
private const val MAX_SHIFT = 0.1f
