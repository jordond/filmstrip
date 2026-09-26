package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectStage

/**
 * The composition effects that run before a colour fill is painted in, in chain order.
 *
 * That is every composition effect outside [EffectStage.Composite]. A backend runs these over the
 * picture laid on the output frame, then paints a colour fill into the bars and gaps, then runs
 * [effectsOverFill]. Together the two lists are [ResolvedComposition.compositionEffects].
 *
 * @throws IllegalArgumentException when the effects are out of canonical order, with anything
 * outside [EffectStage.Composite] after a Composite effect.
 */
@InternalFilmstripApi
public val ResolvedComposition.effectsUnderFill: List<ResolvedEffect>
  get() = compositionEffects.splitAtFill().first

/**
 * The composition effects that run after a colour fill is painted in, in chain order.
 *
 * That is every composition effect in [EffectStage.Composite], so an overlay draws over the bars and
 * gaps as well as the picture. A backend runs these after [effectsUnderFill] and the fill.
 *
 * @throws IllegalArgumentException when the effects are out of canonical order, with anything
 * outside [EffectStage.Composite] after a Composite effect.
 */
@InternalFilmstripApi
public val ResolvedComposition.effectsOverFill: List<ResolvedEffect>
  get() = compositionEffects.splitAtFill().second

/**
 * The composition effects that run before a colour fill is painted in, in chain order.
 *
 * The same split [ResolvedComposition.effectsUnderFill] makes, read off a negotiated composition
 * for a backend that lowers one directly.
 */
@InternalFilmstripApi
public val NegotiatedComposition.effectsUnderFill: List<ResolvedEffect>
  get() = compositionEffects.splitAtFill().first

/**
 * The composition effects that run after a colour fill is painted in, in chain order.
 *
 * The same split [ResolvedComposition.effectsOverFill] makes, read off a negotiated composition for
 * a backend that lowers one directly.
 */
@InternalFilmstripApi
public val NegotiatedComposition.effectsOverFill: List<ResolvedEffect>
  get() = compositionEffects.splitAtFill().second

private fun List<ResolvedEffect>.splitAtFill(): Pair<List<ResolvedEffect>, List<ResolvedEffect>> {
  val fill = indexOfFirst { it.stage == EffectStage.Composite }.takeIf { it >= 0 } ?: size
  val over = drop(fill)
  require(over.all { it.stage == EffectStage.Composite }) {
    "Composition effects must be in canonical order with the Composite stage last, got ${map { it.stage }}"
  }
  return take(fill) to over
}
