package dev.jordond.filmstrip.effects.filters.hi8

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effects.filters.tape.TapeAudioFigures
import dev.jordond.filmstrip.effects.filters.tape.TapeLook
import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapeStock
import dev.jordond.filmstrip.effects.filters.tape.audioFiguresOf
import dev.jordond.filmstrip.effects.filters.tape.pictureStock
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs

/**
 * Makes the frame look like it was played back from a Hi8 camcorder tape.
 *
 * Draws the damage a [Vhs] does with sharper luma under chroma that is just as soft, heavier grain, little colour
 * bleed and more dropouts, each one shorter. The pattern is a function of the frame's composition time and its height
 * as a fraction, so a seek or a smaller preview shows the same tape. With [TapeClock.Wall] a preview draws the pattern
 * from its own clock instead.
 *
 * The effect runs over the fill and under any overlay at the same scope, and an HDR source tone maps to SDR first, the
 * way a [Vhs] does. Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by
 * name, and so does an Apple device that cannot compile a Core Image Metal kernel.
 *
 * @property intensity How strong the look is, from `0f` which leaves the frame alone to `1f`.
 * @property tape The tape speed, which sets how soft the picture is.
 * @property seed Picks the pattern of noise and events. Equal seeds draw the same tape.
 * @property tracking The band of mistracked tape that runs through the picture.
 * @property clock What the pattern moves with in a preview. An export draws the same tape for either.
 * @throws IllegalArgumentException when [intensity] is outside `0f..1f`.
 */
@Poko
public class Hi8(
  override val intensity: Float = 1f,
  public val tape: Hi8Speed = Hi8Speed.SP,
  override val seed: Int = TapeLook.DEFAULT_SEED,
  override val tracking: Tracking = Tracking.Events(),
  override val clock: TapeClock = TapeClock.Composition,
) : TapeLook {
  init {
    require(intensity in 0f..1f) { "A Hi8 intensity has to be within 0 to 1, not $intensity." }
  }

  override val id: EffectId get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = intensity == 0f

  override val redrawsWhileHeld: Boolean get() = clock == TapeClock.Wall

  @InternalFilmstripApi
  override fun pictureParams(): TapePictureParams =
    TapePictureParams.at(intensity.toDouble(), tape.pictureStock, tracking)

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public val ID: EffectId = EffectId("filmstrip.filters.hi8")
  }
}

/**
 * How fast a Hi8 tape ran, and so how much picture detail it kept. The sound rides the picture heads, so it is the same
 * at both speeds.
 */
public enum class Hi8Speed(
  override val label: String,
) : TapeStock {
  /**
   * Standard play, the sharper of the two.
   */
  SP("SP"),

  /**
   * Long play, the softer of the two.
   */
  LP("LP"),
  ;

  @InternalFilmstripApi
  override fun audioFigures(): TapeAudioFigures = audioFiguresOf(this)
}
