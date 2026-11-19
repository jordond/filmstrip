package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * How the browser lays a decoded overlay picture's samples out, cuts its bitmap to the `clap` core reads, and sizes
 * the picture it shows.
 */
class WebOverlayPictureTest {
  @Test
  fun samplesAreLaidOutTheWayCoreOrientsThemAtTheSizeTheyAreShown() {
    for (orientation in PictureOrientation.entries) {
      val (size, laid) = orientation.laidOut(STORED, SAMPLES)

      withClue("$orientation") { size shouldBe orientation.orientedSize(STORED) }
      withClue("$orientation") { laid shouldBe orientedSamples(orientation, SAMPLES, STORED) }
    }
    PictureOrientation.Normal.laidOut(STORED, SAMPLES).second shouldBeSameInstanceAs SAMPLES
  }

  @Test
  fun aPictureIsShownAtItsUploadedSizeLaidOutByItsTurn() =
    runTest {
      val bitmap = bitmapOf(pngOf(STORED))

      OverlayPicture(OverlaySource.Image(bitmap), STORED, null).shownSize shouldBe STORED
      for (orientation in PictureOrientation.entries) {
        val picture = OverlayPicture(OverlaySource.Image(bitmap), STORED, null, orientation = orientation)
        withClue("$orientation") { picture.shownSize shouldBe orientation.orientedSize(STORED) }
      }
    }

  // Texels at every alpha, read from a rectangle in the middle and at both far corners as well as the whole, so a read
  // that drops the offset or premultiplies shows.
  @Test
  fun theCodesOfARectangleAreTheTexelsStoredThere() =
    runTest {
      val bitmap = bitmapOf(pngOf(WIDE, texels = WIDE_TEXELS))
      val rects =
        listOf(
          PictureCrop(5, 3, Size(41, 29)),
          PictureCrop(0, 0, Size(7, 4)),
          PictureCrop(WIDE.width - 9, WIDE.height - 6, Size(9, 6)),
          PictureCrop(0, 0, WIDE),
        )

      for (rect in rects) {
        val expected =
          (0 until rect.size.height).flatMap { row ->
            (0 until rect.size.width).map { column -> WIDE_TEXELS[(rect.top + row) * WIDE.width + rect.left + column] }
          }
        withClue("${rect.left}, ${rect.top}, ${rect.size}") {
          codesOf(bitmap, rect).shouldNotBeNull() shouldBe expected.reduce(ByteArray::plus)
        }
      }
      codesOf(bitmap).shouldNotBeNull() shouldBe WIDE_TEXELS.reduce(ByteArray::plus)
    }

  @Test
  fun noClapOrOneKeepingTheWholePictureCutsNothing() =
    runTest {
      val bitmap = bitmapOf(pngOf(STORED))
      val crop = PictureCrop(1, 1, Size(3, 2))

      bitmap.clapCut(null).shouldBeNull()
      bitmap.clapCut(PictureCrop(0, 0, STORED)).shouldBeNull()
      bitmap.clapCut(crop) shouldBe crop
    }

  @Test
  fun aClapTheBitmapDoesNotHoldIsRefusedWithCoresSentence() =
    runTest {
      val bitmap = bitmapOf(pngOf(STORED))
      val crop = PictureCrop(4, 2, Size(3, 3))

      bitmap.clapCut(crop) shouldBe crop
      crop.liesWithin(STORED) shouldBe false
      clapRefused(bitmap, crop).shouldBeInstanceOf<OverlayDecode.Refused>().message shouldBe crop.refusalFor(STORED)
    }

  private companion object {
    val STORED = Size(6, 4)

    // Four distinct sixteen-bit samples a texel, so a texel moved apart from its own samples shows.
    val SAMPLES = ShortArray(STORED.width * STORED.height * 4) { (it * 257 + 3).toShort() }

    val WIDE = Size(67, 45)

    // Each texel with its own red, green and blue and alpha running through every code, so a texel read from the wrong
    // place or premultiplied shows.
    val WIDE_TEXELS =
      List(WIDE.width * WIDE.height) {
        byteArrayOf((it * 10).toByte(), (250 - it * 7).toByte(), (it * 3 + 64).toByte(), (it * 37 % 256).toByte())
      }
  }
}
