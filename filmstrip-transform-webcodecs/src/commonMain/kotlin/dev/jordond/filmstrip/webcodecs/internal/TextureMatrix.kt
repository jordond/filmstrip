package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.effect.WebGlPass
import kotlin.time.Duration

/**
 * The texture matrix one chain samples through, as the nine floats of a column-major GL `mat3`.
 *
 * A pass with a fixed matrix is multiplied into its neighbours once, at lowering. A pass whose matrix moves with time
 * keeps its place between them and is evaluated on every frame, so the product [at] gives is the chain's in order
 * whichever passes move. A chain with no moving pass answers the same array at every time.
 *
 * @property varies Whether any pass in the chain moves with time, so the matrix has to be uploaded per frame rather
 * than once per clip.
 */
internal class TextureMatrix private constructor(
  private val fixed: FloatArray?,
  private val factors: List<(Duration) -> FloatArray>,
) {
  val varies: Boolean get() = fixed == null

  /**
   * This chain with [matrix] in front of it, so a point goes through [matrix] first and then through the chain.
   */
  fun then(matrix: FloatArray): TextureMatrix =
    if (fixed !=
      null
    ) {
      TextureMatrix(multiply(fixed, matrix), emptyList())
    } else {
      TextureMatrix(null, factors + { matrix })
    }

  /**
   * The chain's matrix on the frame drawn at [compositionTime].
   *
   * A chain that does not vary hands back the same array every time, which the caller must not write to.
   */
  fun at(compositionTime: Duration): FloatArray =
    fixed ?: factors.fold(IDENTITY.copyOf()) { combined, factor -> multiply(combined, factor(compositionTime)) }

  companion object {
    /**
     * Folds [passes] in chain order with the first one outermost, so the last pass maps the output first.
     *
     * Each run of fixed passes is multiplied into one factor here, and a moving pass stays a factor of its own.
     */
    fun of(passes: List<WebGlPass>): TextureMatrix {
      val factors = mutableListOf<(Duration) -> FloatArray>()
      var run = IDENTITY.copyOf()
      for (pass in passes) {
        val timed = pass.textureMatrixAt
        if (timed == null) {
          run = multiply(run, pass.uniforms[TEX_MATRIX_UNIFORM] ?: IDENTITY)
          continue
        }
        val before = run
        factors += { before }
        factors += timed
        run = IDENTITY.copyOf()
      }
      if (factors.isEmpty()) return TextureMatrix(run, emptyList())
      val after = run
      factors += { after }
      return TextureMatrix(null, factors)
    }
  }
}

private fun multiply(
  left: FloatArray,
  right: FloatArray,
): FloatArray {
  val out = FloatArray(9)
  for (c in 0 until 3) {
    for (r in 0 until 3) {
      var sum = 0f
      for (k in 0 until 3) sum += left[k * 3 + r] * right[c * 3 + k]
      out[c * 3 + r] = sum
    }
  }
  return out
}

private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
