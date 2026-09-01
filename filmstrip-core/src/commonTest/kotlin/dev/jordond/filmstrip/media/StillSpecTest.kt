package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The numbers all four encoders read out of a [StillSpec]. Each backend consumes these rather than
 * working the same answer out again, so this is where the answer is pinned.
 */
class StillSpecTest {
  @Test
  fun aNullHeightKeepsTheFrameAsItIs() {
    assertEquals(Size(1920, 1080), stillSizeOf(Size(1920, 1080), null))
  }

  @Test
  fun aHeightThatIsNotPositiveKeepsTheFrameAsItIs() {
    assertEquals(Size(1920, 1080), stillSizeOf(Size(1920, 1080), 0))
    assertEquals(Size(1920, 1080), stillSizeOf(Size(1920, 1080), -720))
  }

  @Test
  fun aHeightInTheMiddleOfTheRangeScalesTheWidthWithIt() {
    // Half height is half width, and a third of the height is a third of the width. An endpoint
    // agrees under any reading, so the middle is what says the scale is proportional.
    assertEquals(Size(960, 540), stillSizeOf(Size(1920, 1080), 540))
    assertEquals(Size(640, 360), stillSizeOf(Size(1920, 1080), 360))
    assertEquals(Size(1280, 720), stillSizeOf(Size(1920, 1080), 720))
  }

  @Test
  fun aWidthThatDoesNotDivideEvenlyRoundsToTheNearestPixel() {
    // 101 x 1000 / 1001 is 100.9, which is 101 rounded and 100 truncated.
    assertEquals(Size(101, 1000), stillSizeOf(Size(101, 1001), 1000))
  }

  @Test
  fun aVeryWideFrameNeverRoundsASideAwayEntirely() {
    assertEquals(Size(1, 1), stillSizeOf(Size(2, 4000), 1))
  }

  @Test
  fun aFrameWithNoAreaComesBackUntouched() {
    assertEquals(Size(0, 0), stillSizeOf(Size(0, 0), 720))
  }

  @Test
  fun aHeightComfortablyUnderTheCeilingIsUntouched() {
    assertEquals(Size(3556, 2000), stillSizeOf(Size(1920, 1080), 2000))
  }

  @Test
  fun aHeightJustUnderTheCeilingIsUntouched() {
    assertEquals(Size(3838, 2159), stillSizeOf(Size(1920, 1080), 2159))
  }

  @Test
  fun aHeightJustOverTheCeilingIsScaledDownToIt() {
    assertEquals(Size(3840, 2160), stillSizeOf(Size(1920, 1080), 2161))
  }

  @Test
  fun aHeightFarOverTheCeilingIsScaledDownToIt() {
    // 1920x1080 asked for at 20000 tall would be 35556x20000, well past what any target can hold
    // in memory. The aspect survives the clamp rather than each side being coerced on its own.
    assertEquals(Size(3840, 2160), stillSizeOf(Size(1920, 1080), 20000))
  }

  @Test
  fun aPortraitSourceOverTheCeilingIsScaledByItsOwnAspect() {
    // A landscape ceiling on a portrait source proves the fit follows the source's aspect rather
    // than coercing width and height against the ceiling's own shape.
    assertEquals(Size(1215, 2160), stillSizeOf(Size(1080, 1920), 20000))
  }

  @Test
  fun aSourceOverTheCeilingWithNoRequestedHeightIsStillHeldToIt() {
    // A 4032x3024 phone photo, with no heightPx asked for at all.
    assertEquals(Size(2880, 2160), stillSizeOf(Size(4032, 3024), null))
  }

  @Test
  fun aSourceOverTheCeilingAskedForAtItsOwnHeightIsStillHeldToIt() {
    // Asking for exactly the source's own height takes the other early-return path, and must land
    // on the same clamp as a null height rather than slip past it.
    assertEquals(Size(2880, 2160), stillSizeOf(Size(4032, 3024), 3024))
  }

  @Test
  fun qualityIsClampedRatherThanRefused() {
    assertEquals(100, StillSpec(quality = 1000).qualityPercent)
    assertEquals(0, StillSpec(quality = -20).qualityPercent)
    assertEquals(55, StillSpec(quality = 55).qualityPercent)
  }

  @Test
  fun theFractionFollowsTheClampedPercentage() {
    assertEquals(1.0, StillSpec(quality = 1000).qualityFraction)
    assertEquals(0.0, StillSpec(quality = -20).qualityFraction)
    assertEquals(0.55, StillSpec(quality = 55).qualityFraction)
  }

  @Test
  fun theDefaultSpecIsAJpegAtNinety() {
    val spec = StillSpec()

    assertEquals(StillFormat.Jpeg, spec.format)
    assertEquals(90, spec.qualityPercent)
    assertEquals(null, spec.heightPx)
  }

  @Test
  fun everyFormatNamesAMediaTypeAndAnExtension() {
    assertEquals("image/png", StillFormat.Png.mimeType)
    assertEquals("image/jpeg", StillFormat.Jpeg.mimeType)
    assertEquals("image/webp", StillFormat.Webp.mimeType)

    assertEquals("png", StillFormat.Png.fileExtension)
    assertEquals("jpg", StillFormat.Jpeg.fileExtension)
    assertEquals("webp", StillFormat.Webp.fileExtension)
  }

  @Test
  fun aRefusalNamesTheFormatAndTheTargetThatRefusedIt() {
    val error = unsupportedStillFormat(StillFormat.Webp, "The JDK's ImageIO")

    assertEquals(StillFormat.Webp, error.format)
    assertTrue(error.message.contains("Webp"), error.message)
    assertTrue(error.message.contains("The JDK's ImageIO"), error.message)
  }
}
