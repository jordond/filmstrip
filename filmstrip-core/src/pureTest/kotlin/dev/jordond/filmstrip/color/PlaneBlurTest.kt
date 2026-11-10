package dev.jordond.filmstrip.color

import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PlaneBlurTest {
  @Test
  fun `a flat picture between bars keeps its level up to and on its edge`() {
    val blurred = blurPlane(flat(LEVEL), CONTAIN, WIDTH, HEIGHT, MID_SIGMA)

    for (index in blurred.indices) {
      if (CONTAIN[index] > 0.0) {
        withClue("pixel ${index % WIDTH} ${index / WIDTH}") { blurred[index] shouldBe (LEVEL plusOrMinus EXACT) }
      }
    }
  }

  @Test
  fun `a bar laid over its fill keeps exactly the fill`() {
    val blurred = blurPlane(flat(LEVEL), CONTAIN, WIDTH, HEIGHT, MID_SIGMA)

    for (index in blurred.indices) {
      if (CONTAIN[index] == 0.0) {
        val laid = blurred[index] * CONTAIN[index] + FILL * (1.0 - CONTAIN[index])
        withClue("pixel ${index % WIDTH} ${index / WIDTH}") { laid shouldBe FILL }
      }
    }
  }

  @Test
  fun `an edge inside the picture blurs evenly along a mid row`() {
    val plane = DoubleArray(WIDTH * HEIGHT) { if (it % WIDTH < WIDTH / 2) LIGHT else DARK }
    val opaque = DoubleArray(WIDTH * HEIGHT) { 1.0 }
    val row =
      blurPlane(plane, opaque, WIDTH, HEIGHT, MID_SIGMA).copyOfRange(
        HEIGHT / 2 * WIDTH,
        (HEIGHT / 2 + 1) * WIDTH,
      )

    row.first() shouldBe (LIGHT plusOrMinus EXACT)
    row.last() shouldBe (DARK plusOrMinus EXACT)
    for (column in 1 until WIDTH) {
      withClue("column $column") { row[column] shouldBeLessThan row[column - 1] + EXACT }
    }
    for (offset in 0 until WIDTH / 2) {
      withClue("columns ${WIDTH / 2 - 1 - offset} and ${WIDTH / 2 + offset}") {
        row[WIDTH / 2 - 1 - offset] + row[WIDTH / 2 + offset] shouldBe (LIGHT + DARK plusOrMinus EXACT)
      }
    }
    LIGHT - row[WIDTH / 2 - 1] shouldBeGreaterThan SPREAD
  }

  @Test
  fun `a width the factor does not divide spreads the box between pixels`() {
    val width = WIDTH + 2
    val plan = BlurPlan.of(MID_SIGMA, width)
    val plane = DoubleArray(width * HEIGHT) { if (it % width < width / 2) LIGHT else DARK }
    val opaque = DoubleArray(width * HEIGHT) { 1.0 }
    val blurred = blurPlane(plane, opaque, width, HEIGHT, MID_SIGMA)

    withClue("the factor ${plan.factor} should leave part of a pixel over at $width") {
      width % plan.factor shouldBeGreaterThan 0
    }
    blurred.copyOfRange(0, width).toList() shouldBe
      blurred.copyOfRange(HEIGHT / 2 * width, (HEIGHT / 2 + 1) * width).toList()
    blurred[width / 2] shouldBeLessThan LIGHT
    blurred[width / 2] shouldBeGreaterThan DARK
  }

  private fun flat(level: Double) = DoubleArray(WIDTH * HEIGHT) { level }

  private companion object {
    // A sigma of 0.05 boxes these 320 pixels down by two, which divides them.
    const val WIDTH = 320
    const val HEIGHT = 90
    const val MID_SIGMA = 0.05
    const val LEVEL = 0.6
    const val FILL = 0.25
    const val LIGHT = 0.85
    const val DARK = 0.15
    const val EXACT = 1e-9
    const val SPREAD = 0.05

    // A picture contained between bars a fifth of the width wide, with half covered columns at its edges.
    val CONTAIN =
      DoubleArray(WIDTH * HEIGHT) {
        when (it % WIDTH) {
          in 0 until 63, in 257 until WIDTH -> 0.0
          63, 256 -> 0.5
          else -> 1.0
        }
      }
  }
}
