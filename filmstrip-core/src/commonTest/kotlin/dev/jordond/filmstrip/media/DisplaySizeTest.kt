package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplaySizeTest {
  @Test
  fun `hands back the coded size for an upright square-pixel track`() {
    assertEquals(Size(1920, 1080), displaySizeOf(Size(1920, 1080), rotationDegrees = 0, pixelAspectRatio = 1f))
    assertEquals(Size(1920, 1080), displaySizeOf(Size(1920, 1080), rotationDegrees = 180, pixelAspectRatio = 1f))
  }

  @Test
  fun `turns the frame for a quarter turn`() {
    assertEquals(Size(1080, 1920), displaySizeOf(Size(1920, 1080), rotationDegrees = 90, pixelAspectRatio = 1f))
    assertEquals(Size(1080, 1920), displaySizeOf(Size(1920, 1080), rotationDegrees = 270, pixelAspectRatio = 1f))
  }

  // Anamorphic HDV, which stores 1440 wide pixels and plays back at 1920.
  @Test
  fun `stretches a wide pixel out`() {
    assertEquals(Size(1920, 1080), displaySizeOf(Size(1440, 1080), rotationDegrees = 0, pixelAspectRatio = 4f / 3f))
  }

  // NTSC DV, whose pixels are taller than they are wide. The frame grows to 528 high rather than
  // shrinking to 655 wide, so the correction keeps all 720 stored columns.
  @Test
  fun `grows a frame with tall pixels rather than narrowing it`() {
    assertEquals(Size(720, 528), displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = 10f / 11f))
  }

  // The growth belongs to the stored frame, so it lands on the coded side whichever way the
  // container then turns it.
  @Test
  fun `grows before it turns`() {
    assertEquals(Size(480, 960), displaySizeOf(Size(720, 480), rotationDegrees = 90, pixelAspectRatio = 4f / 3f))
    assertEquals(Size(960, 480), displaySizeOf(Size(720, 480), rotationDegrees = 180, pixelAspectRatio = 4f / 3f))
    assertEquals(Size(528, 720), displaySizeOf(Size(720, 480), rotationDegrees = 90, pixelAspectRatio = 10f / 11f))
  }

  @Test
  fun `reads a ratio that is not a positive number as square`() {
    assertEquals(Size(720, 480), displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = 0f))
    assertEquals(Size(720, 480), displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = -2f))
    assertEquals(Size(720, 480), displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = Float.NaN))
    assertEquals(
      Size(720, 480),
      displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = Float.POSITIVE_INFINITY),
    )
  }

  // A ratio this far out grows a frame by orders of magnitude instead of correcting one, so it is
  // read as square. A track that reported no size at all keeps reporting none.
  @Test
  fun `reads an implausible ratio as square`() {
    assertEquals(Size(720, 480), displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = 0.0001f))
    assertEquals(Size(720, 480), displaySizeOf(Size(720, 480), rotationDegrees = 0, pixelAspectRatio = 500f))
    assertEquals(Size(0, 0), displaySizeOf(Size(0, 0), rotationDegrees = 0, pixelAspectRatio = 2f))
  }
}
