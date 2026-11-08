package dev.jordond.filmstrip.picture.png

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.Icc
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.PngChromaticities
import dev.jordond.filmstrip.color.PngGamma
import dev.jordond.filmstrip.color.TestIccProfiles
import dev.jordond.filmstrip.picture.embeddedColor
import dev.jordond.filmstrip.picture.fixtures.P3_CHRM
import dev.jordond.filmstrip.picture.fixtures.badCrc
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.chrmChunk
import dev.jordond.filmstrip.picture.fixtures.cicpChunk
import dev.jordond.filmstrip.picture.fixtures.gamaChunk
import dev.jordond.filmstrip.picture.fixtures.iccpChunk
import dev.jordond.filmstrip.picture.fixtures.jpeg
import dev.jordond.filmstrip.picture.fixtures.png
import dev.jordond.filmstrip.picture.fixtures.pngChunk
import dev.jordond.filmstrip.picture.fixtures.srgbChunk
import dev.jordond.filmstrip.picture.pictureColorFromDecodedPng
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * The color descriptions a PNG's chunks carry, in their order of precedence, and the narrower set read from a PNG a
 * decoder wrote.
 */
class PngColorTest {
  private val p3 = TestIccProfiles.displayP3

  @Test
  fun `every color chunk is read in its order of precedence whatever order it sits in`() {
    val picture = png(chrmChunk(P3_CHRM), gamaChunk(50000), srgbChunk(), iccpChunk(p3), cicpChunk(9, 16))

    val descriptions = descriptionsOf(picture)

    descriptions.size shouldBe 4
    descriptions[0] shouldBe Cicp(9, 16, 0, fullRange = true)
    descriptions[1].shouldBeInstanceOf<Icc>().profile shouldBe p3
    descriptions[2] shouldBe Cicp(1, 13, 0, fullRange = true)
    descriptions[3] shouldBe PngGamma(50000, chromaticities(P3_CHRM))
  }

  // A decoder writes sRGB, gAMA and cHRM from what it guessed about the source, so only cICP and iCCP are its own.
  @Test
  fun `a png a decoder wrote is read for its cICP and iCCP only`() {
    val guessed = png(gamaChunk(50000), chrmChunk(P3_CHRM))

    descriptionsOf(guessed) shouldBe listOf(PngGamma(50000, chromaticities(P3_CHRM)))
    pictureColorFromDecodedPng(guessed, 3).shouldBeNull()
    val profiled = pictureColorFromDecodedPng(png(gamaChunk(50000), iccpChunk(p3)), 3).shouldBeInstanceOf<Icc>()
    profiled.profile shouldBe p3
    pictureColorFromDecodedPng(png(srgbChunk(), cicpChunk(12, 13)), 3) shouldBe Cicp(12, 13, 0, fullRange = true)
  }

  @Test
  fun `the color type sets how many color channels are stored`() {
    val channels = listOf(0, 2, 3, 4, 6).map { embeddedColor(png(colorType = it)).shouldNotBeNull().channels }

    channels shouldBe listOf(1, 3, 3, 1, 3)
  }

  // PNG stores RGB samples only, and a narrow-range one would need expanding before its curve could run.
  @Test
  fun `a cICP naming a matrix or a narrow range is not understood`() {
    descriptionsOf(png(cicpChunk(9, 16, matrix = 1))).shouldBeEmpty()
    descriptionsOf(png(cicpChunk(9, 16, fullRange = 0))).shouldBeEmpty()
    descriptionsOf(png(cicpChunk(9, 16, fullRange = 0), gamaChunk(50000))) shouldBe listOf(PngGamma(50000, null))
  }

  @Test
  fun `the first chunk of a kind wins and one whose CRC fails is passed over`() {
    descriptionsOf(png(gamaChunk(50000), gamaChunk(80000))) shouldBe listOf(PngGamma(50000, null))
    descriptionsOf(png(badCrc(gamaChunk(50000)), gamaChunk(80000))) shouldBe listOf(PngGamma(80000, null))
  }

  @Test
  fun `a chunk behind the image data is not read`() {
    descriptionsOf(png(afterImageData = gamaChunk(50000))).shouldBeEmpty()
  }

  @Test
  fun `a gamma of zero and chromaticities that bound no gamut are left out`() {
    descriptionsOf(png(gamaChunk(0))).shouldBeEmpty()
    descriptionsOf(png(chrmChunk(IntArray(8)))).shouldBeEmpty()
    descriptionsOf(png(gamaChunk(0), chrmChunk(P3_CHRM))) shouldBe listOf(PngGamma(null, chromaticities(P3_CHRM)))
    descriptionsOf(png(gamaChunk(50000), chrmChunk(IntArray(8)))) shouldBe listOf(PngGamma(50000, null))
  }

  @Test
  fun `an sRGB chunk with an intent PNG does not define is left out`() {
    descriptionsOf(png(srgbChunk(intent = 3))) shouldBe listOf(Cicp(1, 13, 0, fullRange = true))
    descriptionsOf(png(srgbChunk(intent = 4))).shouldBeEmpty()
  }

  @Test
  fun `an iCCP too short to hold a profile header is left out`() {
    descriptionsOf(png(iccpChunk(p3.copyOfRange(0, 100)))).shouldBeEmpty()
  }

  @Test
  fun `another format and a png that does not open on its header read as nothing`() {
    val headless =
      bytes {
        raw(PNG_SIGNATURE)
        pngChunk("IEND", ByteArray(0))
      }

    embeddedColor(headless).shouldBeNull()
    pictureColorFromDecodedPng(headless, 3).shouldBeNull()
    pictureColorFromDecodedPng(jpeg(p3), 3).shouldBeNull()
    pictureColorFromDecodedPng(png(cicpChunk(9, 16)).copyOfRange(0, 20), 3).shouldBeNull()
  }

  private fun descriptionsOf(picture: ByteArray): List<PictureColor> =
    embeddedColor(picture).shouldNotBeNull().descriptions

  private fun chromaticities(values: IntArray) =
    PngChromaticities(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
}
