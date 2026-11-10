package dev.jordond.filmstrip.effects.spatial

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Darken the frame towards its edges.
 *
 * The falloff is measured from the centre of the frame the vignette runs on, the clip's own at clip scope and the
 * composed frame at composition scope. It is round on screen whatever the frame's shape, and a distance of `1f` is a
 * corner. The frame is left as it was inside `radius - softness` and darkened by [strength] past `radius + softness`,
 * along a smooth curve between the two.
 *
 * Every channel's code is multiplied by the same gain, so a pixel keeps its hue. On a kept HDR grade the gain lands on
 * the signal the grade's light encodes to, the same domain a color effect runs in.
 *
 * @property strength How much of the codes the darkest part takes away, from `0f` to `1f`. At `0f` the frame is left
 * as it was.
 * @property radius Where the falloff is half way, from `0f` to `1.5f`.
 * @property softness How far either side of [radius] the falloff reaches, from `0f` to `1f`. At `0f` the edge is hard.
 * @throws IllegalArgumentException when a value is outside its range.
 */
@Poko
public class Vignette(
  public val strength: Float = DEFAULT_STRENGTH,
  public val radius: Float = DEFAULT_RADIUS,
  public val softness: Float = DEFAULT_SOFTNESS,
) : EffectSpec {
  init {
    require(strength in 0f..1f) { "A vignette's strength runs from 0 to 1, not $strength." }
    require(radius in 0f..MAX_RADIUS) { "A vignette's radius runs from 0 to $MAX_RADIUS, not $radius." }
    require(softness in 0f..1f) { "A vignette's softness runs from 0 to 1, not $softness." }
  }

  override val id: String get() = EffectIds.VIGNETTE

  override val stage: EffectStage get() = EffectStage.Spatial

  override val isInert: Boolean get() = strength == 0f

  private companion object {
    const val MAX_RADIUS = 1.5f
  }
}

/**
 * Darken the frame towards its edges by [strength], with the falloff half way at [radius] and reaching [softness]
 * either side of it.
 */
public fun EffectsBuilder.vignette(
  strength: Float = DEFAULT_STRENGTH,
  radius: Float = DEFAULT_RADIUS,
  softness: Float = DEFAULT_SOFTNESS,
): EffectsBuilder = add(Vignette(strength, radius, softness))

private const val DEFAULT_STRENGTH = 0.5f
private const val DEFAULT_RADIUS = 0.75f
private const val DEFAULT_SOFTNESS = 0.45f
