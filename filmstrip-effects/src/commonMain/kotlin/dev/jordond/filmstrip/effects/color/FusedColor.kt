package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec

/**
 * One entry of a chain after its color runs have been folded: the effect a backend lowers, and the
 * effects the caller wrote that it stands for.
 *
 * @property spec What is lowered. The authored effect itself outside a run, and one effect carrying
 * the whole run otherwise.
 * @property sources The authored effects, in chain order, so a refusal or a degradation is reported
 * against the names the caller used rather than the one the fold made up.
 */
@InternalFilmstripApi
@Poko
public class FoldedSpec(
  public val spec: EffectSpec,
  public val sources: List<EffectSpec>,
)

/**
 * Folds every run of consecutive color matrix effects in an ordered chain into one effect.
 *
 * A backend clamps to the encoding's range as it writes, some once for the whole chain and some
 * around each effect, so where a run stops being several matrices decides what a channel pushed past
 * white and pulled back again comes out as. It stops here, ahead of all four: a run made only of
 * [Brightness] folds to one carrying the product of the factors, and any other run folds to one
 * [ColorMatrix] carrying the product of the matrices.
 *
 * A product past the largest 32-bit float is held at it, the way [then] holds a matrix entry, since that is the
 * widest number any backend takes. A brightness that large already pushes every channel that is not black past white,
 * so the frame comes out the same.
 *
 * An export that keeps an HDR grade folds the same way, since a backend applies the folded matrix
 * in the same encoded domain there, with the format's peak as the ceiling instead of white.
 *
 * @return one entry per effect a backend lowers, in chain order, each naming the authored effects
 * it stands for.
 */
@InternalFilmstripApi
public fun List<EffectSpec>.fusedColorMatrices(): List<FoldedSpec> {
  val folded = mutableListOf<FoldedSpec>()
  // Each effect's matrix is worked out once here, and that one serves both to decide the run and
  // to fold it, since a hue rotation and a sepia each cost trig to build.
  val pending = mutableListOf<Pair<EffectSpec, ColorMatrix>>()

  fun flush() {
    if (pending.isEmpty()) return
    val sources = pending.map { it.first }
    val spec =
      when {
        sources.size == 1 -> {
          sources.single()
        }
        sources.all { it is Brightness } -> {
          Brightness(sources.fold(1f) { scale, spec -> scale heldTimes (spec as Brightness).scale })
        }
        else -> {
          pending.fold(ColorMatrix.Identity) { combined, (_, matrix) -> combined.then(matrix) }
        }
      }
    folded += FoldedSpec(spec, sources)
    pending.clear()
  }

  forEach { spec ->
    val matrix = colorMatrixOf(spec)
    if (matrix != null) {
      pending += spec to matrix
    } else {
      flush()
      folded += FoldedSpec(spec, listOf(spec))
    }
  }
  flush()
  return folded
}
