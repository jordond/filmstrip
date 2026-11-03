package dev.jordond.filmstrip.still

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.geometry.outputHeightRefusal
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * The numbers all four encoders read out of a [StillSpec]. Each backend consumes these rather than
 * working the same answer out again, so this is where the answer is pinned.
 */
class StillSpecTest {
  @Test
  fun aNullHeightKeepsTheFrameAsItIs() {
    stillSizeOf(Size(1920, 1080), null) shouldBe Size(1920, 1080)
  }

  @Test
  fun aHeightThatIsNotPositiveIsRefusedAsAnInvalidComposition() {
    outputHeightRefusal(0).shouldBeInstanceOf<FilmstripError.InvalidComposition>()
    outputHeightRefusal(-720).shouldBeInstanceOf<FilmstripError.InvalidComposition>()
  }

  @Test
  fun aNullOrPositiveHeightIsNotRefused() {
    outputHeightRefusal(null).shouldBeNull()
    outputHeightRefusal(1).shouldBeNull()
    outputHeightRefusal(720).shouldBeNull()
  }

  @Test
  fun aHeightInTheMiddleOfTheRangeScalesTheWidthWithIt() {
    // Half height is half width, and a third of the height is a third of the width. An endpoint
    // agrees under any reading, so the middle is what says the scale is proportional.
    stillSizeOf(Size(1920, 1080), 540) shouldBe Size(960, 540)
    stillSizeOf(Size(1920, 1080), 360) shouldBe Size(640, 360)
    stillSizeOf(Size(1920, 1080), 720) shouldBe Size(1280, 720)
  }

  @Test
  fun aWidthThatDoesNotDivideEvenlyRoundsToTheNearestPixel() {
    // 101 x 1000 / 1001 is 100.9, which is 101 rounded and 100 truncated.
    stillSizeOf(Size(101, 1001), 1000) shouldBe Size(101, 1000)
  }

  @Test
  fun aVeryWideFrameNeverRoundsASideAwayEntirely() {
    stillSizeOf(Size(2, 4000), 1) shouldBe Size(1, 1)
  }

  @Test
  fun aFrameWithNoAreaComesBackUntouched() {
    stillSizeOf(Size(0, 0), 720) shouldBe Size(0, 0)
  }

  @Test
  fun aHeightComfortablyUnderTheCeilingIsUntouched() {
    stillSizeOf(Size(1920, 1080), 2000) shouldBe Size(3556, 2000)
  }

  @Test
  fun aHeightJustUnderTheCeilingIsUntouched() {
    stillSizeOf(Size(1920, 1080), 2159) shouldBe Size(3838, 2159)
  }

  @Test
  fun aHeightJustOverTheCeilingIsScaledDownToIt() {
    stillSizeOf(Size(1920, 1080), 2161) shouldBe Size(3840, 2160)
  }

  @Test
  fun aHeightFarOverTheCeilingIsScaledDownToIt() {
    // 1920x1080 asked for at 20000 tall would be 35556x20000, well past what any target can hold
    // in memory. The aspect survives the clamp rather than each side being coerced on its own.
    stillSizeOf(Size(1920, 1080), 20000) shouldBe Size(3840, 2160)
  }

  @Test
  fun aPortraitSourceOverTheCeilingIsScaledByItsOwnAspect() {
    // A landscape ceiling on a portrait source proves the fit follows the source's aspect rather
    // than coercing width and height against the ceiling's own shape.
    stillSizeOf(Size(1080, 1920), 20000) shouldBe Size(1215, 2160)
  }

  @Test
  fun aSourceOverTheCeilingWithNoRequestedHeightIsStillHeldToIt() {
    // A 4032x3024 phone photo, with no heightPx asked for at all.
    stillSizeOf(Size(4032, 3024), null) shouldBe Size(2880, 2160)
  }

  @Test
  fun aSourceOverTheCeilingAskedForAtItsOwnHeightIsStillHeldToIt() {
    // Asking for exactly the source's own height takes the other early-return path, and must land
    // on the same clamp as a null height rather than slip past it.
    stillSizeOf(Size(4032, 3024), 3024) shouldBe Size(2880, 2160)
  }

  @Test
  fun qualityIsClampedRatherThanRefused() {
    StillSpec(quality = 1000).qualityPercent shouldBe 100
    StillSpec(quality = -20).qualityPercent shouldBe 0
    StillSpec(quality = 55).qualityPercent shouldBe 55
  }

  @Test
  fun theFractionFollowsTheClampedPercentage() {
    StillSpec(quality = 1000).qualityFraction shouldBe 1.0
    StillSpec(quality = -20).qualityFraction shouldBe 0.0
    StillSpec(quality = 55).qualityFraction shouldBe 0.55
  }

  @Test
  fun theDefaultSpecIsAJpegAtNinety() {
    val spec = StillSpec()

    spec.format shouldBe StillFormat.Jpeg
    spec.qualityPercent shouldBe 90
    spec.heightPx.shouldBeNull()
  }

  @Test
  fun everyFormatNamesAMediaTypeAndAnExtension() {
    StillFormat.Png.mimeType shouldBe "image/png"
    StillFormat.Jpeg.mimeType shouldBe "image/jpeg"
    StillFormat.Webp.mimeType shouldBe "image/webp"

    StillFormat.Png.fileExtension shouldBe "png"
    StillFormat.Jpeg.fileExtension shouldBe "jpg"
    StillFormat.Webp.fileExtension shouldBe "webp"
  }

  @Test
  fun aRefusalNamesTheFormatAndTheTargetThatRefusedIt() {
    val error = unsupportedStillFormat(StillFormat.Webp, "The JDK's ImageIO")

    error.format shouldBe StillFormat.Webp
    withClue(error.message) { error.message.contains("Webp").shouldBeTrue() }
    withClue(error.message) { error.message.contains("The JDK's ImageIO").shouldBeTrue() }
  }
}
