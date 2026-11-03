package dev.jordond.filmstrip.effects.overlay

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

      OverlayPicture(bitmap, STORED, null, null).shownSize shouldBe STORED
      for (orientation in PictureOrientation.entries) {
        val picture = OverlayPicture(bitmap, STORED, null, null, orientation = orientation)
        withClue("$orientation") { picture.shownSize shouldBe orientation.orientedSize(STORED) }
      }
    }

  @Test
  fun aClapCutsTheBitmapToTheTexelsItKeeps() =
    runTest {
      val bitmap = bitmapOf(pngOf(STORED, texels = TEXELS))
      val crop = PictureCrop(1, 1, Size(3, 2))

      val cut = bitmap.clapCropped(crop).shouldNotBeNull()
      Size(cut.width, cut.height) shouldBe crop.size
      val expected =
        (0 until crop.size.height).flatMap { row ->
          (0 until crop.size.width).map { column -> TEXELS[(crop.top + row) * STORED.width + crop.left + column] }
        }
      codesOf(cut).shouldNotBeNull() shouldBe expected.reduce(ByteArray::plus)
    }

  @Test
  fun noClapOrOneKeepingTheWholePictureLeavesTheBitmapAsItIs() =
    runTest {
      val bitmap = bitmapOf(pngOf(STORED))

      bitmap.clapCropped(null) shouldBeSameInstanceAs bitmap
      bitmap.clapCropped(PictureCrop(0, 0, STORED)) shouldBeSameInstanceAs bitmap
    }

  @Test
  fun aClapTheBitmapDoesNotHoldIsRefusedWithCoresSentence() =
    runTest {
      val bitmap = bitmapOf(pngOf(STORED))
      val crop = PictureCrop(4, 2, Size(3, 3))

      bitmap.clapCropped(crop).shouldBeNull()
      clapRefused(bitmap, crop).shouldBeInstanceOf<OverlayDecode.Refused>().message shouldBe crop.refusalFor(STORED)
    }

  private companion object {
    val STORED = Size(6, 4)

    // Four distinct sixteen-bit samples a texel, so a texel moved apart from its own samples shows.
    val SAMPLES = ShortArray(STORED.width * STORED.height * 4) { (it * 257 + 3).toShort() }

    // Opaque texels, each with its own red and green, so a cut one texel off shows.
    val TEXELS =
      List(STORED.width * STORED.height) { byteArrayOf((it * 10).toByte(), (250 - it * 10).toByte(), 0x40, -1) }
  }
}
