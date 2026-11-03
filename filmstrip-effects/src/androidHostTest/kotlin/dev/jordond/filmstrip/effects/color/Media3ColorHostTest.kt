package dev.jordond.filmstrip.effects.color

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.math.abs
import kotlin.test.Test

/**
 * The matrix [ColorMatrixEffect] hands media3 on each draw.
 *
 * media3 multiplies a pixel's `(r, g, b, 1)` by the column-major matrix it is handed, so the pixel the effect writes
 * for a color inside the frame's range is the one [transform] gives.
 */
class Media3ColorHostTest {
  @Test
  fun everyDrawIsHandedTheSameColumnsWhateverTheTimeOrTheSignal() {
    val effect = ColorMatrixEffect(MIX)
    val first = effect.getMatrix(0L, false)

    first shouldBe MIX.toColumnMajor4x4()
    effect.getMatrix(1_500_000L, false) shouldBeSameInstanceAs first
    effect.getMatrix(1_500_000L, true) shouldBeSameInstanceAs first
  }

  @Test
  fun aPixelMultipliedByTheColumnsLandsWhereTheSharedTransformPutsIt() {
    val columns = ColorMatrixEffect(MIX).getMatrix(0L, false)
    for (pixel in PIXELS) {
      val written = FloatArray(3) { row -> (0 until 4).map { columns[it * 4 + row] * pixel[it] }.sum() }

      val expected = MIX.transform(pixel[0], pixel[1], pixel[2])
      written.indices.forEach { channel ->
        withClue("${pixel.toList()} channel $channel") {
          (abs(written[channel] - expected[channel]) < TOLERANCE) shouldBe true
        }
      }
    }
  }

  private companion object {
    // Every entry off its identity value, a bias on each row, and nothing that carries these pixels out of range.
    val MIX =
      ColorMatrix(
        rr = 0.7f,
        rg = 0.2f,
        rb = 0.05f,
        rBias = 0.02f,
        gr = 0.1f,
        gg = 0.8f,
        gb = 0.05f,
        gBias = -0.01f,
        br = 0.05f,
        bg = 0.15f,
        bb = 0.75f,
        bBias = 0.03f,
      )
    val PIXELS = listOf(floatArrayOf(0.25f, 0.5f, 0.75f, 1f), floatArrayOf(0.6f, 0.4f, 0.1f, 1f))
    const val TOLERANCE = 1e-6f
  }
}
