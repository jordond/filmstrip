package dev.jordond.filmstrip.effects

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import kotlin.test.Test

/**
 * An image moved by [atOrigin] so its extent starts at the coordinate origin.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreImageTest {
  // Core Image widens a fractional extent out to whole pixels, so the size is the placed image's own.
  @Test
  fun `an image whose extent sits elsewhere is moved onto the origin at its own size`() {
    for ((x, y) in listOf(12.0 to 34.0, -7.5 to 20.0, 0.0 to -16.0)) {
      val placed = square().imageByApplyingTransform(CGAffineTransformMakeTranslation(x, y))

      withClue("from $x $y") { extentOf(placed.atOrigin()) shouldBe listOf(0.0, 0.0) + extentOf(placed).drop(2) }
    }
  }

  @Test
  fun `an image already at the origin is handed back as it is`() {
    val image = square()

    image.atOrigin() shouldBeSameInstanceAs image
  }

  private fun square(): CIImage =
    CIImage.imageWithColor(CIColor.colorWithRed(1.0, 0.0, 0.0)).imageByCroppingToRect(CGRectMake(0.0, 0.0, SIDE, SIDE))

  private fun extentOf(image: CIImage): List<Double> =
    image.extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }

  private companion object {
    const val SIDE = 24.0
  }
}
