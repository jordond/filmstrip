package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test

class NightVisionLoweringTest {
  @Test
  fun `a second look on the same frame reads the tables the first drew`() {
    val first = nightVisionFragment(NightVision(intensity = 0.3f, seed = 4), FRAME).sidecars
    val second = nightVisionFragment(NightVision(intensity = 0.8f, seed = 9), FRAME).sidecars

    second shouldHaveSize 2
    second.indices.forEach { second[it] shouldBeSameInstanceAs first[it] }
  }

  @Test
  fun `a look on another frame size draws its own vignette and keeps the phosphor table`() {
    val first = nightVisionFragment(NightVision(), FRAME).sidecars
    val other = nightVisionFragment(NightVision(), Size(FRAME.height, FRAME.width)).sidecars

    other[0] shouldBeSameInstanceAs first[0]
    other[1] shouldNotBeSameInstanceAs first[1]
  }

  private companion object {
    val FRAME = Size(64, 36)
  }
}
