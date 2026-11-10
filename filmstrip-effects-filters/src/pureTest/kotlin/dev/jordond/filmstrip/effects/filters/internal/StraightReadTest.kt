package dev.jordond.filmstrip.effects.filters.internal

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StraightReadTest {
  @Test
  fun `a read between four texels mixes them premultiplied and comes back straight`() {
    val texels =
      mapOf(
        (0 to 0) to doubleArrayOf(1.0, 0.0, 0.0, 1.0),
        (1 to 0) to doubleArrayOf(0.0, 1.0, 0.0, 0.5),
        (0 to 1) to doubleArrayOf(0.0, 0.0, 1.0, 0.25),
        (1 to 1) to doubleArrayOf(0.0, 0.0, 0.0, 0.0),
      )
    // A third of the way across and a quarter of the way down from the first texel's centre.
    val u = (0.5 + THIRD) / 2
    val v = (0.5 + QUARTER) / 2
    val weights = listOf((1 - THIRD) * (1 - QUARTER), THIRD * (1 - QUARTER), (1 - THIRD) * QUARTER, THIRD * QUARTER)
    val alphas = listOf(1.0, 0.5, 0.25, 0.0)
    val alpha = weights.zip(alphas).sumOf { (w, a) -> w * a }

    val read = straightRead(u, v, 2, 2) { column, row -> texels.getValue(column to row) }

    read[0] shouldBe (weights[0] / alpha plusOrMinus EPSILON)
    read[1] shouldBe (weights[1] * 0.5 / alpha plusOrMinus EPSILON)
    read[2] shouldBe (weights[2] * 0.25 / alpha plusOrMinus EPSILON)
    read[3] shouldBe (alpha plusOrMinus EPSILON)
  }

  @Test
  fun `a read among clear texels is clear black`() {
    straightReadAcross(0.4, 8) { DoubleArray(4) { 0.0 } }.toList() shouldBe listOf(0.0, 0.0, 0.0, 0.0)
  }

  @Test
  fun `a read past the edge holds the edge texel`() {
    val texel = { column: Int -> doubleArrayOf(column / 10.0, 0.5, 0.5) }

    straightReadAcross(0.0, 8, texel).toList() shouldBe texel(0).toList()
    straightReadAcross(1.0, 8, texel)[0] shouldBe (texel(7)[0] plusOrMinus EPSILON)
  }

  @Test
  fun `a texel with no alpha is opaque and the read gives no alpha back`() {
    val read = straightReadAcross((2 + THIRD + 0.5) / 8, 8) { column -> doubleArrayOf(column / 8.0, 0.0, 0.0) }

    read.size shouldBe 3
    read[0] shouldBe ((2 + THIRD) / 8 plusOrMinus EPSILON)
  }

  private companion object {
    const val THIRD = 1.0 / 3.0
    const val QUARTER = 0.25
    const val EPSILON = 1e-12
  }
}
