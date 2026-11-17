package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import kotlin.time.Duration

/**
 * A look that makes the frame read as played back from a tape, which [Vhs], [Betamax] and [Hi8] are.
 *
 * Every format draws the same kinds of damage through one renderer, and differs in how strong each kind is.
 *
 * @property intensity How strong the look is, from `0f` which leaves the frame alone to `1f`.
 * @property seed Picks the pattern of noise and events. Equal seeds draw the same tape.
 * @property tracking The band of mistracked tape that runs through the picture.
 * @property clock What the pattern moves with in a preview. An export draws the same tape for either.
 * @property settle How the look eases to its intensity from another strength, or null when it is the same strength
 * throughout.
 */
public interface TapeLook : EffectSpec {
  public val intensity: Float
  public val seed: Int
  public val tracking: Tracking
  public val clock: TapeClock
  public val settle: TapeSettle?

  /**
   * The strengths this look draws with when it is at [level], which every renderer reads.
   *
   * @param level The strength the look has, from 0 to 1. A look that settles is at its [intensity] after it has.
   * @param scopeStart The composition time of the first frame of the scope the look is in, which a rolling band counts
   * from. A look drawn from a preview's own clock has no composition time to anchor to, and takes null.
   */
  @InternalFilmstripApi
  public fun pictureParams(
    level: Double,
    scopeStart: Duration?,
  ): TapePictureParams

  public companion object {
    /**
     * The seed a tape look uses unless it is given one.
     */
    public const val DEFAULT_SEED: Int = 1984
  }
}
