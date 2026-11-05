package dev.jordond.filmstrip.effects.geometry

import dev.jordond.filmstrip.geometry.NormalizedRect
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A crop and a pan each name regions of the frame, and both refuse one that has no area or reaches outside it when
 * they are built, so no backend is handed a region it cannot sample.
 */
class RegionRefusalTest {
  @Test
  fun aCropRefusesARegionItCannotKeep() {
    BROKEN.forEach { rect -> assertFailsWith<IllegalArgumentException> { CropRect(rect) } }

    CropRect(MIDDLE).rect shouldBe MIDDLE
  }

  @Test
  fun aPanRefusesARegionAtEitherEnd() {
    BROKEN.forEach { rect ->
      assertFailsWith<IllegalArgumentException> { KenBurns(rect, MIDDLE) }
      assertFailsWith<IllegalArgumentException> { KenBurns(MIDDLE, rect) }
    }

    KenBurns(NormalizedRect.Full, MIDDLE).to shouldBe MIDDLE
  }

  private companion object {
    val MIDDLE = NormalizedRect(0.25f, 0.2f, 0.75f, 0.6f)

    val BROKEN =
      listOf(
        NormalizedRect(0.5f, 0f, 1.5f, 1f),
        NormalizedRect(-0.1f, 0f, 0.5f, 1f),
        NormalizedRect(0.5f, 0.5f, 0.5f, 0.5f),
        NormalizedRect(0.6f, 0f, 0.4f, 1f),
        NormalizedRect(Float.NaN, 0f, 1f, 1f),
        NormalizedRect(0f, 0f, Float.POSITIVE_INFINITY, 1f),
      )
  }
}
