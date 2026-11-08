package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DisplaySizeTest {
  @Test
  fun `hands back the coded size for an upright square-pixel track`() {
    displaySizeOf(Size(1920, 1080), rotationDegrees = 0, pixelAspectRatio = 1f) shouldBe Size(1920, 1080)
    displaySizeOf(Size(1920, 1080), rotationDegrees = 180, pixelAspectRatio = 1f) shouldBe Size(1920, 1080)
  }

  @Test
  fun `turns the frame for a quarter turn`() {
    displaySizeOf(Size(1920, 1080), rotationDegrees = 90, pixelAspectRatio = 1f) shouldBe Size(1080, 1920)
    displaySizeOf(Size(1920, 1080), rotationDegrees = 270, pixelAspectRatio = 1f) shouldBe Size(1080, 1920)
  }

  // Anamorphic HDV, which stores 1440 wide pixels and plays back at 1920.
  @Test
  fun `stretches a wide pixel out`() {
    displaySizeOf(Size(1440, 1080), rotationDegrees = 0, pixelAspectRatio = 4f / 3f) shouldBe Size(1920, 1080)
  }

  // NTSC DV, whose pixels are taller than they are wide. The frame grows to 528 high rather than
  // shrinking to 655 wide, so the correction keeps all 720 stored columns.
  @Test
  fun `grows a frame with tall pixels rather than narrowing it`() {
    displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = 10f / 11f) shouldBe Size(720, 528)
  }

  // The growth belongs to the stored frame, so it lands on the coded side whichever way the
  // container then turns it.
  @Test
  fun `grows before it turns`() {
    displaySizeOf(Size(720, 480), rotationDegrees = 90, pixelAspectRatio = 4f / 3f) shouldBe Size(480, 960)
    displaySizeOf(Size(720, 480), rotationDegrees = 180, pixelAspectRatio = 4f / 3f) shouldBe Size(960, 480)
    displaySizeOf(Size(720, 480), rotationDegrees = 90, pixelAspectRatio = 10f / 11f) shouldBe Size(528, 720)
  }

  @Test
  fun `reads a ratio that is not a positive number as square`() {
    displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = 0f) shouldBe Size(720, 480)
    displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = -2f) shouldBe Size(720, 480)
    displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = Float.NaN) shouldBe Size(720, 480)
    displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = Float.POSITIVE_INFINITY) shouldBe
      Size(720, 480)
  }

  // A ratio this far out grows a frame by orders of magnitude instead of correcting one, so it is
  // read as square. A track that reported no size at all keeps reporting none.
  @Test
  fun `reads an implausible ratio as square`() {
    displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = 0.0001f) shouldBe Size(720, 480)
    displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = 500f) shouldBe Size(720, 480)
    displaySizeOf(Size(0, 0), rotationDegrees = 0, pixelAspectRatio = 2f) shouldBe Size(0, 0)
  }

  // A browser only ever answers with a frame it has already turned, so the turn has to come back
  // out of it. Round tripping is what says the two directions are the same rotation.
  @Test
  fun `turns a displayed frame back to the one it was stored at`() {
    for (rotation in listOf(0, 90, 180, 270)) {
      val coded = Size(1920, 1080)
      val display = displaySizeOf(coded, rotation, pixelAspectRatio = 1f)

      withClue("a turn of $rotation degrees") { codedSizeOf(display, rotation) shouldBe coded }
    }
  }
}
