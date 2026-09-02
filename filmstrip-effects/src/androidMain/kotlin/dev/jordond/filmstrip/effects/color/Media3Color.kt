package dev.jordond.filmstrip.effects.color

import androidx.media3.effect.RgbMatrix

/**
 * Hands media3 a colour matrix to multiply the frame by.
 *
 * `DefaultShaderProgram` asks every [RgbMatrix] in the chain for its matrix on each draw and
 * multiplies the answers into one uniform before clamping, so a run of these costs one pass and
 * clamps once. The columns are worked out when the effect is built rather than per frame, and the
 * same array is handed back every time.
 */
internal class ColorMatrixEffect(
  matrix: ColorMatrix,
) : RgbMatrix {
  private val columns: FloatArray = matrix.toColumnMajor4x4()

  override fun getMatrix(
    presentationTimeUs: Long,
    useHdr: Boolean,
  ): FloatArray = columns
}
