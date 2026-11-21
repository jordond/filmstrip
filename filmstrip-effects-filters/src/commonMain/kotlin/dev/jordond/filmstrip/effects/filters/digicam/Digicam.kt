package dev.jordond.filmstrip.effects.filters.digicam

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the frame look like it came off an early digital camera with a small CCD sensor.
 *
 * Edges get the bright and dark halo of a camera that sharpens too hard, highlights are pushed until they clip
 * channel by channel, and coloured noise crawls in the shadows. The halo's width and the noise's size are fractions of
 * the frame, and the noise reads the frame's composition time, so a seek or a smaller preview shows the same frame.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean.
 * `digicam { stamp() }` adds the camera's orange date stamp on top of it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name.
 *
 * @property sharpness How strong the halo around edges is, from `0f` which adds none to `1f`.
 * @property noise How strong the coloured noise in the shadows is, from `0f` which adds none to `1f`.
 * @property clip How far highlights are pushed into clipping, from `0f` which leaves them alone to `1f`.
 * @property seed Picks the noise pattern. Equal seeds draw the same noise.
 * @throws IllegalArgumentException when a control is outside `0f..1f`.
 */
@Poko
public class Digicam(
  public val sharpness: Float = 0.6f,
  public val noise: Float = 0.5f,
  public val clip: Float = 0.5f,
  public val seed: Int = DEFAULT_SEED,
) : EffectSpec {
  init {
    require(sharpness in 0f..1f) { "A digicam sharpness has to be within 0 to 1, not $sharpness." }
    require(noise in 0f..1f) { "A digicam noise has to be within 0 to 1, not $noise." }
    require(clip in 0f..1f) { "A digicam clip has to be within 0 to 1, not $clip." }
  }

  override val id: EffectId get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = sharpness == 0f && noise == 0f && clip == 0f

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public val ID: EffectId = EffectId("filmstrip.filters.digicam")

    /**
     * The seed a [Digicam] uses unless it is given one.
     */
    public const val DEFAULT_SEED: Int = 2001

    /**
     * A cheap compact camera pushed hard, with heavy halos, blown highlights and noisy shadows.
     */
    public val Ccd: Digicam = Digicam(sharpness = 0.8f, noise = 0.7f, clip = 0.7f)
  }
}
