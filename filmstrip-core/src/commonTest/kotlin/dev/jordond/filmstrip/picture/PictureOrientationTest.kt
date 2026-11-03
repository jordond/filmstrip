package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PictureOrientationTest {
  // Each picture is written out the way EXIF describes its orientation showing the stored one, a b c over d e f, so
  // every texel of every orientation is pinned and not only the corners a turn and a mirror can share.
  @Test
  fun everyOrientationShowsTheStoredTexelsWhereExifPutsThem() {
    for ((orientation, shown) in SHOWN) {
      val size = orientation.orientedSize(STORED)
      val read =
        (0 until size.height).joinToString("/") { y ->
          (0 until size.width).joinToString("") { x ->
            val source = orientation.sourceOf(x, y, size)
            STORED_TEXELS[source.y][source.x].toString()
          }
        }

      withClue(orientation.name) { read shouldBe shown }
    }
  }

  @Test
  fun onlyTheOrientationsThatTurnByAQuarterSwapTheSides() {
    val swapping = PictureOrientation.entries.filter { it.swapsAxes }

    swapping.map { it.exif } shouldBe (5..8).toList()
    for (orientation in PictureOrientation.entries) {
      val expected = if (orientation.swapsAxes) Size(2, 3) else STORED
      withClue(orientation.name) { orientation.orientedSize(STORED) shouldBe expected }
    }
  }

  @Test
  fun everyExifValueNamesItsOwnOrientationAndAnyOtherIsNormal() {
    (1..8).map { PictureOrientation.ofExif(it).exif } shouldBe (1..8).toList()
    PictureOrientation.ofExif(0) shouldBe PictureOrientation.Normal
    PictureOrientation.ofExif(9) shouldBe PictureOrientation.Normal
    PictureOrientation.ofExif(-1) shouldBe PictureOrientation.Normal
  }

  @Test
  fun packedTexelsAreLaidOutTheWayTheOrientationShowsThem() {
    val argb = IntArray(6) { STORED_TEXELS[it / 3][it % 3].code }
    for ((orientation, shown) in SHOWN) {
      val expected = shown.replace("/", "").map { it.code }.toIntArray()

      withClue(orientation.name) { orientedSamples(orientation, argb, STORED) shouldBe expected }
    }
  }

  // Every channel of a texel carries its own value, so a reorder that split a texel or moved its channels apart reads
  // back wrong.
  @Test
  fun sixteenBitSamplesMoveFourAtATime() {
    val rgba = ShortArray(24) { (STORED_TEXELS[it / 12][(it / 4) % 3].code * 100 + it % 4).toShort() }
    for ((orientation, shown) in SHOWN) {
      val expected =
        shown
          .replace("/", "")
          .flatMap { texel -> (0 until 4).map { channel -> (texel.code * 100 + channel).toShort() } }
          .toShortArray()

      withClue(orientation.name) { orientedSamples(orientation, rgba, STORED) shouldBe expected }
    }
  }

  @Test
  fun samplesThatDoNotFillTheStoredSizeAreRefused() {
    assertFailsWith<IllegalArgumentException> { orientedSamples(PictureOrientation.Rotate90, IntArray(5), STORED) }
    assertFailsWith<IllegalArgumentException> { orientedSamples(PictureOrientation.Rotate90, ShortArray(23), STORED) }
  }

  @Test
  fun theNormalOrientationHandsBackACopy() {
    val argb = IntArray(6) { it }
    val shown = orientedSamples(PictureOrientation.Normal, argb, STORED)
    shown[0] = 99

    argb[0] shouldBe 0
  }

  private companion object {
    val STORED = Size(3, 2)
    val STORED_TEXELS = listOf("abc", "def")

    val SHOWN =
      mapOf(
        PictureOrientation.Normal to "abc/def",
        PictureOrientation.FlipHorizontal to "cba/fed",
        PictureOrientation.Rotate180 to "fed/cba",
        PictureOrientation.FlipVertical to "def/abc",
        PictureOrientation.Transpose to "ad/be/cf",
        PictureOrientation.Rotate90 to "da/eb/fc",
        PictureOrientation.Transverse to "fc/eb/da",
        PictureOrientation.Rotate270 to "cf/be/ad",
      )
  }
}
