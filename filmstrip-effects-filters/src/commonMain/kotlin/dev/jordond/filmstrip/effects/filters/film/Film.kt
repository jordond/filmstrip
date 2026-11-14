package dev.jordond.filmstrip.effects.filters.film

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the frame look like it was shot on film.
 *
 * Halation glows warm around the brightest parts of the picture, and grain lands over the result, heaviest in the
 * midtones. The grain is a function of the frame's composition time and its place on a fixed raster, and the glow's
 * width a fraction of the frame width, so a seek or a smaller preview shows the same frame.
 *
 * The rest of the controls make it look projected from small gauge stock, and [Super8] sets them all. The frame weaves
 * in the gate, its brightness flickers, dust and scratches land on it, and a rounded gate darkens its edges. The gate
 * holds still, the rest change with the frame's composition time at 24 a second, and every random draw takes [seed].
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean. A
 * clip overlay under a composition [Film] is part of the clip's frame by then, and takes the look with it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name.
 *
 * @property grain How strong the grain is, from `0f` which adds none to `1f`.
 * @property halation How strong the glow is, from `0f` which adds none to `1f`.
 * @property weave How far the frame wanders in the gate, from `0f` which holds it still to `1f`.
 * @property flicker How much the frame's brightness flickers, from `0f` which holds it steady to `1f`.
 * @property dust How much dust and how many scratches land on the frame, from `0f` which adds none to `1f`.
 * @property gate How far the gate's rounded, dark edge closes in, from `0f` which shows the whole frame to `1f`.
 * @property seed The seed every random draw takes, so two clips can grain and weave apart.
 * @throws IllegalArgumentException when a control is outside `0f..1f`.
 */
@Poko
public class Film(
  public val grain: Float = 0.5f,
  public val halation: Float = 0.5f,
  public val weave: Float = 0f,
  public val flicker: Float = 0f,
  public val dust: Float = 0f,
  public val gate: Float = 0f,
  public val seed: Int = DEFAULT_SEED,
) : EffectSpec {
  init {
    require(grain in 0f..1f) { "A film grain has to be within 0 to 1, not $grain." }
    require(halation in 0f..1f) { "A film halation has to be within 0 to 1, not $halation." }
    require(weave in 0f..1f) { "A film weave has to be within 0 to 1, not $weave." }
    require(flicker in 0f..1f) { "A film flicker has to be within 0 to 1, not $flicker." }
    require(dust in 0f..1f) { "A film dust has to be within 0 to 1, not $dust." }
    require(gate in 0f..1f) { "A film gate has to be within 0 to 1, not $gate." }
  }

  override val id: EffectId get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean
    get() = grain == 0f && halation == 0f && weave == 0f && flicker == 0f && dust == 0f && gate == 0f

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public val ID: EffectId = EffectId("filmstrip.filters.film")

    /**
     * The seed a [Film] takes when none is given.
     */
    public const val DEFAULT_SEED: Int = 1895

    /**
     * Small gauge stock run through a worn projector, with every control set.
     */
    public val Super8: Film =
      Film(grain = 0.8f, halation = 0.4f, weave = 0.5f, flicker = 0.4f, dust = 0.4f, gate = 0.6f)
  }
}

/**
 * Adds a [Film] look with these controls, each from `0f` to `1f`, and every random draw taking [seed].
 */
public fun EffectsBuilder.film(
  grain: Float = 0.5f,
  halation: Float = 0.5f,
  weave: Float = 0f,
  flicker: Float = 0f,
  dust: Float = 0f,
  gate: Float = 0f,
  seed: Int = Film.DEFAULT_SEED,
): EffectsBuilder = add(Film(grain, halation, weave, flicker, dust, gate, seed))
