package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size

/**
 * Follows a clip's chain to say where the frame entering each effect is opaque, the way [placementOf] follows its
 * canvases.
 *
 * Call [before] for an effect, then [advance] past it, in chain order.
 */
internal class OpaqueTracker {
  private var open: NormalizedRect? = null
  private var barred = false

  /**
   * Where the frame entering [spec] is opaque as a fraction of it. A canvas an effect reads is built before that effect,
   * so it is the placed picture of that canvas. Null when an earlier effect already read a canvas with bars, since the
   * clear part has moved or softened by then.
   */
  fun before(spec: EffectSpec): NormalizedRect? =
    when {
      barred -> null
      spec.readsCanvas() -> open ?: NormalizedRect.Full
      else -> NormalizedRect.Full
    }

  /**
   * Moves past [spec], which takes a frame of [size] to one of [next].
   */
  fun advance(
    spec: EffectSpec,
    size: Size,
    next: Size,
  ) {
    val pending = open
    when {
      spec.makesCanvas() -> {
        val placed = placeIn(size, next, (spec as Crop).fit)
        open = pending?.placedIn(placed) ?: placed
      }
      pending == null -> {}
      spec.readsCanvas() -> {
        if (!pending.covers(size)) barred = true
        open = null
      }
      spec is Flip -> {
        open = pending.flipped(spec.axis)
      }
    }
  }
}
