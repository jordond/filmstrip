package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.MeasuredFrame
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The frame an encoder is handed between its smallest and its largest.
 */
class FittedBetweenTest {
  @Test
  fun `a frame under the floor scales up by one factor until both sides reach it`() {
    val floor = Size(16, 16)

    MeasuredFrame(Size(10, 40)).fittedBetween(floor, Size(1920, 1080), 2) shouldBe Size(16, 64)
    MeasuredFrame(Size(40, 10)).fittedBetween(floor, Size(1920, 1080), 2) shouldBe Size(64, 16)
    MeasuredFrame(Size(6, 10)).fittedBetween(floor, null, 2) shouldBe Size(16, 26)
  }

  @Test
  fun `a frame on the floor or a step above it or midway to the ceiling comes back as fittedTo leaves it`() {
    val floor = Size(16, 16)
    val ceiling = Size(1920, 1080)

    listOf(Size(16, 16), Size(18, 18), Size(968, 548), Size(4000, 3000)).forEach { size ->
      MeasuredFrame(size).fittedBetween(floor, ceiling, 2) shouldBe MeasuredFrame(size).fittedTo(ceiling, 2)
    }
    MeasuredFrame(Size(10, 4)).fittedBetween(null, null, 16) shouldBe MeasuredFrame(Size(10, 4)).fittedTo(null, 16)
  }

  @Test
  fun `a lifted side rounds up to the alignment and never back under the floor`() {
    MeasuredFrame(Size(12, 20)).fittedBetween(Size(16, 16), null, 16) shouldBe Size(16, 32)
    MeasuredFrame(Size(20, 50)).fittedBetween(Size(32, 32), null, 16) shouldBe Size(32, 80)
  }

  @Test
  fun `a frame whose lift runs past the ceiling fits nowhere`() {
    MeasuredFrame(Size(2, 4000)).fittedBetween(Size(16, 16), Size(1920, 1080), 2).shouldBeNull()
    MeasuredFrame(Size(4000, 20)).fittedBetween(Size(16, 16), Size(1920, 1080), 2).shouldBeNull()
  }
}
