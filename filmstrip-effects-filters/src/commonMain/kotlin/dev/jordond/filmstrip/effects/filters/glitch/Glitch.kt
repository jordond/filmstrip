package dev.jordond.filmstrip.effects.filters.glitch

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the frame look like a broken digital signal.
 *
 * Red and blue sit a little apart from green all the time. Now and then a burst tears through the picture, cutting it
 * into horizontal slices that slide sideways, with thin rows torn further and the colours pulled wider apart. The
 * pattern is a function of the frame's composition time and its height as a fraction, so a seek or a smaller preview
 * shows the same bursts.
 *
 * With [blocks] above `0f` a burst also breaks runs of square blocks along some rows, the way a damaged digital stream
 * does. A broken block either shows a block from a few blocks away or smears the row above it down through itself. The
 * blocks are cut before the slices slide, so they ride with their slice.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean. A
 * clip overlay under a composition [Glitch] is part of the clip's frame by then, and is torn with it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name. An Apple device
 * that cannot compile a Core Image Metal kernel refuses it by name too.
 *
 * @property intensity How strong the look is, from `0f` which leaves the frame alone to `1f`.
 * @property blocks How many blocks a burst breaks, from `0f` for none to `1f`. Blocks only break inside a burst, so a
 * glitch at [intensity] `0f` breaks none.
 * @property seed Picks the pattern of bursts. Equal seeds draw the same glitches.
 * @throws IllegalArgumentException when [intensity] or [blocks] is outside `0f..1f`.
 */
@Poko
public class Glitch(
  public val intensity: Float = 1f,
  public val blocks: Float = 0f,
  public val seed: Int = DEFAULT_SEED,
) : EffectSpec {
  init {
    require(intensity in 0f..1f) { "A glitch intensity has to be within 0 to 1, not $intensity." }
    require(blocks in 0f..1f) { "A glitch's blocks have to be within 0 to 1, not $blocks." }
  }

  override val id: EffectId get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = intensity == 0f

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public val ID: EffectId = EffectId("filmstrip.filters.glitch")

    /**
     * The seed a [Glitch] uses unless it is given one.
     */
    public const val DEFAULT_SEED: Int = 2001

    /**
     * A strong glitch whose bursts break blocks across the picture, like a corrupt digital file.
     */
    public val Corrupt: Glitch = Glitch(intensity = CORRUPT_INTENSITY, blocks = 1f)
  }
}

/**
 * Adds a [Glitch] look at [intensity], from `0f` to `1f`, breaking [blocks] of the picture in its bursts, with its
 * bursts drawn from [seed].
 */
public fun EffectsBuilder.glitch(
  intensity: Float = 1f,
  blocks: Float = 0f,
  seed: Int = Glitch.DEFAULT_SEED,
): EffectsBuilder = add(Glitch(intensity, blocks, seed))

private const val CORRUPT_INTENSITY = 0.8f
