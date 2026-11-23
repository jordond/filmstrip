package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The smallest frame read off the lower bounds a MediaCodec encoder publishes.
 */
class EncoderFloorTest {
  @Test
  fun `a bound on the alignment is the floor`() {
    encoderFloor(96, 96, 16) shouldBe Size(96, 96)
    encoderFloor(128, 96, 2) shouldBe Size(128, 96)
  }

  @Test
  fun `a bound off the alignment rounds up to it and never down under the bound`() {
    encoderFloor(2, 2, 16) shouldBe Size(16, 16)
    encoderFloor(98, 130, 16) shouldBe Size(112, 144)
    encoderFloor(1, 1, 2) shouldBe Size(2, 2)
  }

  @Test
  fun `no alignment is taken as one and a bound of nothing as one step`() {
    encoderFloor(17, 33, 0) shouldBe Size(17, 33)
    encoderFloor(0, 0, 16) shouldBe Size(16, 16)
  }
}
