package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.PngChromaticities
import dev.jordond.filmstrip.color.PngGamma
import dev.jordond.filmstrip.color.TestIccProfiles
import dev.jordond.filmstrip.picture.fixtures.BT2020_CHRM
import dev.jordond.filmstrip.picture.fixtures.P3_CHRM
import dev.jordond.filmstrip.picture.fixtures.REC709_CHRM
import dev.jordond.filmstrip.picture.fixtures.app2
import dev.jordond.filmstrip.picture.fixtures.badCrc
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.chrmChunk
import dev.jordond.filmstrip.picture.fixtures.cicpChunk
import dev.jordond.filmstrip.picture.fixtures.extraSamples
import dev.jordond.filmstrip.picture.fixtures.gamaChunk
import dev.jordond.filmstrip.picture.fixtures.iccp
import dev.jordond.filmstrip.picture.fixtures.iccpChunk
import dev.jordond.filmstrip.picture.fixtures.jpegSegments
import dev.jordond.filmstrip.picture.fixtures.photometric
import dev.jordond.filmstrip.picture.fixtures.png
import dev.jordond.filmstrip.picture.fixtures.pngChunk
import dev.jordond.filmstrip.picture.fixtures.samplesPerPixel
import dev.jordond.filmstrip.picture.fixtures.srgbChunk
import dev.jordond.filmstrip.picture.fixtures.storedZlib
import dev.jordond.filmstrip.picture.fixtures.summary
import dev.jordond.filmstrip.picture.fixtures.tiffWith
import dev.jordond.filmstrip.picture.fixtures.webp
import dev.jordond.filmstrip.picture.png.pngChunk
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Picks a picture's color description out of synthetic PNG, JPEG, TIFF and WebP containers carrying several at once,
 * and drops one that is for a different number of channels than the picture stores.
 */
class EmbeddedColorTest {
  private val p3 = TestIccProfiles.displayP3
  private val grey = TestIccProfiles.grey22
  private val cmyk = TestIccProfiles.profile("CMYK", "Lab ", emptyList())
  private val linearBt2020 = Cicp(9, 8, 0, true)
  private val halfGamma = PngGamma(50000, null)
  private val brokenRgb = TestIccProfiles.profile("RGB ", "XYZ ", emptyList())

  @Test
  fun `png descriptions follow cicp then iccp then srgb then gamma whatever their order in the file`() {
    val cicp = cicpChunk(9, 8)
    val iccp = iccpChunk(p3)
    val srgb = srgbChunk()
    val gama = gamaChunk(50000)
    val pairs =
      listOf(
        Triple(cicp, iccp, linearBt2020),
        Triple(cicp, srgb, linearBt2020),
        Triple(cicp, gama, linearBt2020),
        Triple(iccp, srgb, p3.toList()),
        Triple(iccp, gama, p3.toList()),
        Triple(srgb, gama, null),
      )
    for ((first, second, wanted) in pairs) {
      pictureColor(png(first, second)).summary() shouldBe wanted
      pictureColor(png(second, first)).summary() shouldBe wanted
    }
    pictureColor(png(gama)) shouldBe halfGamma
  }

  @Test
  fun `a png description not understood defers to the next`() {
    val shortCicp = bytes { pngChunk("cICP", byteArrayOf(9, 8, 0)) }
    pictureColor(png(shortCicp, gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(cicpChunk(9, 8, matrix = 1), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(cicpChunk(9, 8, fullRange = 2), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(cicpChunk(9, 8, fullRange = 0), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(iccp(storedZlib(p3).also { it[it.lastIndex]++ }), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(srgbChunk(intent = 4), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(gamaChunk(0))).shouldBeNull()
  }

  @Test
  fun `a narrow range png cicp defers to the next description and a full range one reads`() {
    pictureColor(png(cicpChunk(9, 8, fullRange = 0), iccpChunk(p3))).summary() shouldBe p3.toList()
    pictureColor(png(cicpChunk(9, 8, fullRange = 0), srgbChunk(), gamaChunk(50000))).shouldBeNull()
    pictureColor(png(cicpChunk(9, 8, fullRange = 1), iccpChunk(p3))) shouldBe linearBt2020
  }

  @Test
  fun `a cicp whose code points are not supported lands as untagged instead of deferring`() {
    pictureColor(png(cicpChunk(23, 13), iccpChunk(p3))).shouldBeNull()
    for (transfer in listOf(9, 10, 12, 19)) {
      pictureColor(png(cicpChunk(9, transfer), chrmChunk(BT2020_CHRM), gamaChunk(50000))).shouldBeNull()
    }
  }

  @Test
  fun `a cicp whose primaries or transfer is unspecified defers to the next description`() {
    pictureColor(png(cicpChunk(2, 13), iccpChunk(p3))).summary() shouldBe p3.toList()
    pictureColor(png(cicpChunk(9, 2), iccpChunk(p3))).summary() shouldBe p3.toList()
    pictureColor(png(cicpChunk(2, 2), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(cicpChunk(2, 16), chrmChunk(P3_CHRM))) shouldBe PngGamma(null, chromaticities(P3_CHRM))
    pictureColor(png(cicpChunk(2, 13), srgbChunk(), gamaChunk(50000))).shouldBeNull()
    pictureColor(png(cicpChunk(2, 13))).shouldBeNull()
  }

  @Test
  fun `an rgb profile that does not parse defers to the next description`() {
    pictureColor(png(iccpChunk(brokenRgb), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(iccpChunk(brokenRgb), chrmChunk(P3_CHRM))) shouldBe PngGamma(null, chromaticities(P3_CHRM))
    pictureColor(png(iccpChunk(brokenRgb))).shouldBeNull()
    pictureColorFromDecodedProfile(brokenRgb, 3).shouldBeNull()
  }

  @Test
  fun `a png color chunk whose crc is wrong is skipped`() {
    pictureColor(png(badCrc(cicpChunk(9, 8)), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(badCrc(iccpChunk(p3)), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(badCrc(srgbChunk()), gamaChunk(50000))) shouldBe halfGamma
    pictureColor(png(badCrc(gamaChunk(50000)))).shouldBeNull()
    pictureColor(png(badCrc(chrmChunk(P3_CHRM)))).shouldBeNull()
  }

  @Test
  fun `a png whose header is missing or fails its crc has no description`() {
    val tagged = png(cicpChunk(9, 8))
    val headerCrc = 8 + 8 + 13 + 3
    pictureColor(tagged.copyOf().also { it[headerCrc]++ }).shouldBeNull()
    pictureColor(tagged.copyOf().also { it[12] = 'X'.code.toByte() }).shouldBeNull()
  }

  @Test
  fun `the first of a repeated png chunk wins`() {
    pictureColor(png(cicpChunk(9, 8), cicpChunk(12, 13))) shouldBe linearBt2020
    pictureColor(png(gamaChunk(50000), gamaChunk(70000))) shouldBe halfGamma
  }

  @Test
  fun `png gamma is srgb only at its sRGB equivalence`() {
    pictureColor(png(gamaChunk(45455))).shouldBeNull()
    pictureColor(png(gamaChunk(45454))).shouldBeNull()
    pictureColor(png(gamaChunk(45456))).shouldBeNull()
    pictureColor(png(gamaChunk(45455), chrmChunk(REC709_CHRM))).shouldBeNull()
    pictureColor(png(chrmChunk(REC709_CHRM))).shouldBeNull()
    pictureColor(png(gamaChunk(45457))) shouldBe PngGamma(45457, null)
    pictureColor(png(gamaChunk(45455), chrmChunk(P3_CHRM))) shouldBe PngGamma(45455, chromaticities(P3_CHRM))
    pictureColor(png(chrmChunk(P3_CHRM))) shouldBe PngGamma(null, chromaticities(P3_CHRM))
  }

  @Test
  fun `chromaticities that span no color space are not understood`() {
    val flat = intArrayOf(31270, 32900, 30000, 60000, 30000, 60000, 15000, 6000)
    val noWhite = intArrayOf(31270, 0, 64000, 33000, 30000, 60000, 15000, 6000)
    pictureColor(png(chrmChunk(flat))).shouldBeNull()
    pictureColor(png(chrmChunk(noWhite), gamaChunk(50000))) shouldBe halfGamma
  }

  @Test
  fun `a png profile for other channels than the picture stores defers to the next description`() {
    pictureColor(png(iccpChunk(p3), colorType = 0)).shouldBeNull()
    pictureColor(png(iccpChunk(p3), colorType = 4)).shouldBeNull()
    pictureColor(png(iccpChunk(grey), colorType = 2)).shouldBeNull()
    pictureColor(png(iccpChunk(grey), colorType = 6)).shouldBeNull()
    pictureColor(png(iccpChunk(p3), gamaChunk(50000), colorType = 0)) shouldBe halfGamma
    pictureColor(png(iccpChunk(grey), colorType = 0)).summary() shouldBe grey.toList()
    pictureColor(png(iccpChunk(p3), colorType = 3)).summary() shouldBe p3.toList()
    pictureColor(png(cicpChunk(9, 8), colorType = 0)) shouldBe linearBt2020
    pictureColor(png(iccpChunk(p3), colorType = 5)).shouldBeNull()
  }

  @Test
  fun `a jpeg profile must be for as many channels as its frame has components`() {
    pictureColor(jpegSegments(app2(1, 1, p3), components = 3)).summary() shouldBe p3.toList()
    pictureColor(jpegSegments(app2(1, 1, p3), components = 1)).shouldBeNull()
    pictureColor(jpegSegments(app2(1, 1, grey), components = 1)).summary() shouldBe grey.toList()
    pictureColor(jpegSegments(app2(1, 1, grey), components = 3)).shouldBeNull()
    pictureColor(jpegSegments(app2(1, 1, cmyk), components = 4)).shouldBeNull()
    pictureColor(jpegSegments(app2(1, 1, p3))).summary() shouldBe p3.toList()
  }

  @Test
  fun `a tiff profile must be for its samples per pixel less its extra samples`() {
    for ((little, big) in listOf(true to false, false to false, true to true, false to true)) {
      fun read(
        profile: ByteArray,
        vararg entries: Triple<Int, Int, Int>,
      ) = pictureColor(tiffWith(profile, *entries, little = little, big = big)).summary()
      withClue("little $little big $big") {
        read(p3, samplesPerPixel(3)) shouldBe p3.toList()
        read(p3, samplesPerPixel(3, type = 4)) shouldBe p3.toList()
        read(p3, samplesPerPixel(4), extraSamples()) shouldBe p3.toList()
        read(p3, photometric(3)) shouldBe p3.toList()
        read(p3).shouldBeNull()
        read(p3, samplesPerPixel(1)).shouldBeNull()
        read(p3, samplesPerPixel(4)).shouldBeNull()
        read(p3, samplesPerPixel(4, type = 4)).shouldBeNull()
        read(grey, samplesPerPixel(2), extraSamples()) shouldBe grey.toList()
        read(grey, samplesPerPixel(3)).shouldBeNull()
      }
    }
  }

  @Test
  fun `a webp is always color`() {
    pictureColor(webp(p3)).summary() shouldBe p3.toList()
    pictureColor(webp(grey)).shouldBeNull()
  }

  @Test
  fun `a jpeg profile reads as rgb or grey only when its color space is one of them`() {
    jpegProfileIsRgbOrGrey(jpegSegments(app2(1, 1, p3), components = 1)) shouldBe true
    jpegProfileIsRgbOrGrey(jpegSegments(app2(1, 1, grey), components = 3)) shouldBe true
    jpegProfileIsRgbOrGrey(jpegSegments(app2(1, 1, cmyk), components = 4)) shouldBe false
    jpegProfileIsRgbOrGrey(jpegSegments(components = 3)) shouldBe false
    jpegProfileIsRgbOrGrey(png(iccpChunk(p3))) shouldBe false
    jpegProfileIsRgbOrGrey(ByteArray(0)) shouldBe false
  }

  @Test
  fun `a decoder's profile is kept only for the channels its decode stores`() {
    pictureColorFromDecodedProfile(p3, 3).summary() shouldBe p3.toList()
    pictureColorFromDecodedProfile(grey, 1).summary() shouldBe grey.toList()
    pictureColorFromDecodedProfile(p3, 1).shouldBeNull()
    pictureColorFromDecodedProfile(grey, 3).shouldBeNull()
    pictureColorFromDecodedProfile(cmyk, 4).shouldBeNull()
    pictureColorFromDecodedProfile(ByteArray(0), 3).shouldBeNull()
  }

  @Test
  fun `a decoded png reads only its cicp and its profile and never the chunks a decoder guesses`() {
    val p3Cicp = Cicp(12, 13, 0, true)
    val guessedP3 = arrayOf(cicpChunk(12, 13), chrmChunk(P3_CHRM), gamaChunk(45455))
    pictureColorFromDecodedPng(png(*guessedP3), 3) shouldBe p3Cicp
    pictureColor(png(*guessedP3)) shouldBe p3Cicp
    val log = png(cicpChunk(9, 10), chrmChunk(BT2020_CHRM))
    pictureColorFromDecodedPng(log, 3).shouldBeNull()
    pictureColor(log).shouldBeNull()
    pictureColorFromDecodedPng(png(cicpChunk(9, 16), chrmChunk(BT2020_CHRM)), 3) shouldBe Cicp(9, 16, 0, true)
    pictureColorFromDecodedPng(png(chrmChunk(P3_CHRM), gamaChunk(45455)), 3).shouldBeNull()
    pictureColorFromDecodedPng(png(srgbChunk(), chrmChunk(P3_CHRM), gamaChunk(50000)), 3).shouldBeNull()
    pictureColorFromDecodedPng(png(gamaChunk(50000)), 3).shouldBeNull()
    pictureColorFromDecodedPng(png(iccpChunk(p3), chrmChunk(P3_CHRM), gamaChunk(45455)), 3).summary() shouldBe
      p3.toList()
    pictureColorFromDecodedPng(png(cicpChunk(9, 8), iccpChunk(p3)), 3) shouldBe linearBt2020
    pictureColorFromDecodedPng(png(cicpChunk(2, 8), iccpChunk(p3)), 3).summary() shouldBe p3.toList()
    pictureColorFromDecodedPng(png(cicpChunk(9, 2), iccpChunk(p3)), 3).summary() shouldBe p3.toList()
    pictureColorFromDecodedPng(png(cicpChunk(9, 2)), 3).shouldBeNull()
    pictureColorFromDecodedPng(png(cicpChunk(2, 8)), 3).shouldBeNull()
    pictureColorFromDecodedPng(png(iccpChunk(brokenRgb), cicpChunk(9, 8, matrix = 1)), 3).shouldBeNull()
  }

  @Test
  fun `a decoded png's description must fit the source's channels whatever its own color type`() {
    pictureColorFromDecodedPng(png(iccpChunk(grey), colorType = 6), 1).summary() shouldBe grey.toList()
    pictureColorFromDecodedPng(png(iccpChunk(grey), colorType = 6), 3).shouldBeNull()
    pictureColorFromDecodedPng(png(iccpChunk(p3), colorType = 6), 1).shouldBeNull()
    pictureColorFromDecodedPng(png(cicpChunk(9, 8), colorType = 6), 1) shouldBe linearBt2020
  }

  @Test
  fun `a decoded png that is not a png or is malformed has no description`() {
    pictureColorFromDecodedPng(jpegSegments(app2(1, 1, p3), components = 3), 3).shouldBeNull()
    pictureColorFromDecodedPng(ByteArray(0), 3).shouldBeNull()
    val tagged = png(cicpChunk(9, 8))
    pictureColorFromDecodedPng(tagged.copyOf().also { it[12] = 'X'.code.toByte() }, 3).shouldBeNull()
    pictureColorFromDecodedPng(tagged.copyOf(20), 3).shouldBeNull()
  }

  private fun chromaticities(values: IntArray) =
    PngChromaticities(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
}
