package dev.jordond.filmstrip.effects.filters.vhs

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the frame look like it was played back from a VHS tape.
 *
 * Softens luma and smears chroma along each line, wobbles the lines, bends the bottom of the frame like a head switch,
 * and adds streaky noise, dropouts and a [Tracking] band. The pattern is a function of the frame's composition time
 * and its height as a fraction, so a seek or a smaller preview shows the same tape.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean. A
 * clip overlay under a composition [Vhs] is part of the clip's frame by then, and is burned in with it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name. An Apple device
 * that cannot compile a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, refuses it by name
 * too.
 *
 * @property intensity How strong the look is, from `0f` which leaves the frame alone to `1f`.
 * @property tape The tape speed, which sets how soft the picture is.
 * @property seed Picks the pattern of noise and events. Equal seeds draw the same tape.
 * @property tracking The band of mistracked tape that runs through the picture.
 * @throws IllegalArgumentException when [intensity] is outside `0f..1f`.
 */
@Poko
public class Vhs(
  public val intensity: Float = 1f,
  public val tape: TapeSpeed = TapeSpeed.EP,
  public val seed: Int = DEFAULT_SEED,
  public val tracking: Tracking = Tracking.Events(),
) : EffectSpec {
  init {
    require(intensity in 0f..1f) { "A VHS intensity has to be within 0 to 1, not $intensity." }
  }

  override val id: String get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = intensity == 0f

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public const val ID: String = "filmstrip.filters.vhs"

    /**
     * The seed a [Vhs] uses unless it is given one.
     */
    public const val DEFAULT_SEED: Int = 1984
  }
}

/**
 * How long a tape was stretched, and so how much picture detail it kept.
 *
 * @property lumaMHz The luma bandwidth, in megahertz.
 * @property chromaMHz The chroma bandwidth, in megahertz.
 */
public enum class TapeSpeed(
  public val lumaMHz: Double,
  public val chromaMHz: Double,
) {
  /**
   * Standard play, the sharpest of the three.
   */
  SP(lumaMHz = 2.4, chromaMHz = 0.32),

  /**
   * Long play, between the other two.
   */
  LP(lumaMHz = 1.9, chromaMHz = 0.30),

  /**
   * Extended play, the softest of the three.
   */
  EP(lumaMHz = 1.4, chromaMHz = 0.28),
}
