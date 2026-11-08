package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TexelAffineTest {
  // Every shown texel of a picture wider than it is tall, the middle ones included, so a map that only agreed on the
  // corners a turn and a mirror share would fail.
  @Test
  fun everyShownTexelCentreLandsOnTheCentreOfTheStoredTexelSourceOfNames() {
    for (orientation in PictureOrientation.entries) {
      val map = orientation.texelAffine(STORED)
      val shown = orientation.orientedSize(STORED)
      for (y in 0 until shown.height) {
        for (x in 0 until shown.width) {
          val source = orientation.sourceOf(x, y, shown)
          val centreX = x + HALF
          val centreY = y + HALF
          val readX = centreX * map.acrossX + centreY * map.downX + map.offsetX
          val readY = centreX * map.acrossY + centreY * map.downY + map.offsetY

          withClue("$orientation at $x, $y") { readX shouldBe source.x + HALF }
          withClue("$orientation at $x, $y") { readY shouldBe source.y + HALF }
        }
      }
    }
  }

  @Test
  fun aPictureShownAsStoredMapsOntoItself() {
    PictureOrientation.Normal.texelAffine(STORED) shouldBe TexelAffine(1, 0, 0, 1, 0.0, 0.0)
  }

  private companion object {
    const val HALF = 0.5
    val STORED = Size(5, 3)
  }
}
