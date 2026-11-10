package dev.jordond.filmstrip.transform.internal.geometry

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FillBlendTest {
  @Test
  fun `clear shows the fill and opaque shows the source`() {
    blendOverFill(source = 200, alpha = 0, fill = 40) shouldBe 40
    blendOverFill(source = 200, alpha = 255, fill = 40) shouldBe 200
  }

  // An endpoint agrees under a multiplicative and an additive reading, so the middle is what pins the rule.
  @Test
  fun `half alpha lands between the source and the fill`() {
    blendOverFill(source = 200, alpha = 128, fill = 40) shouldBe 120
    blendOverFill(source = 0, alpha = 64, fill = 255) shouldBe 191
  }

  @Test
  fun `alpha outside the code range clamps to it`() {
    blendOverFill(source = 200, alpha = 300, fill = 40) shouldBe 200
    blendOverFill(source = 200, alpha = -5, fill = 40) shouldBe 40
  }
}
