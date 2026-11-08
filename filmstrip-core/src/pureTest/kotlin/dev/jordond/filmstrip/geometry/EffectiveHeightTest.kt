package dev.jordond.filmstrip.geometry

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EffectiveHeightTest {
  @Test
  fun aHeightBelowTheNaturalOneScalesDown() {
    effectiveHeight(heightPx = 480, naturalHeight = 720, framedByStill = false) shouldBe 480
  }

  @Test
  fun aHeightEqualToTheNaturalOneKeepsTheFrame() {
    effectiveHeight(heightPx = 720, naturalHeight = 720, framedByStill = false).shouldBeNull()
  }

  @Test
  fun aHeightAboveTheNaturalOneNeverUpscales() {
    effectiveHeight(heightPx = 1080, naturalHeight = 720, framedByStill = false).shouldBeNull()
  }

  @Test
  fun aStillFramedCompositionTakesAHeightAboveItsOwn() {
    effectiveHeight(heightPx = 1080, naturalHeight = 720, framedByStill = true) shouldBe 1080
  }

  @Test
  fun noHeightKeepsTheFrame() {
    effectiveHeight(heightPx = null, naturalHeight = 720, framedByStill = true).shouldBeNull()
  }
}
