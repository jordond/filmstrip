package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.TestIccProfiles
import dev.jordond.filmstrip.media.YcbcrCoding
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Picks an AVIF or HEIC primary item's colour description out of its `prof` and `nclx` properties and those of its
 * tiles, reads its stored channels from `pixi`, `av1C` or `hvcC`, and reads the YCbCr matrix and range its `nclx`
 * records.
 */
class EmbeddedColourHeifTest {
  private val p3 = TestIccProfiles.displayP3
  private val grey = TestIccProfiles.grey22
  private val displayP3 = Cicp(12, 13, 6, true)
  private val linearBt2020 = Cicp(9, 8, 6, true)

  private fun untyped(
    matrix: Int,
    fullRange: Boolean,
    primaries: Int,
    transfer: Int,
  ) = YcbcrCoding(matrix, fullRange, CodecKind.Other, codestream = null, primaries = primaries, transfer = transfer)

  private fun codec(
    itemInfo: ByteArray?,
    fileType: ByteArray = ftyp("mif1"),
    tile: List<ByteArray>? = null,
  ): CodecKind? = pictureYcbcrCoding(heifItems(listOf(nclx(1, 13)), tile, fileType, itemInfo))?.codec

  @Test
  fun `heif descriptions follow the primary profile and its nclx then a tile profile and its nclx`() {
    val primaryProfile = colr("prof", p3)
    val primaryNclx = nclx(12, 13)
    val tileProfile = colr("prof", TestIccProfiles.gamma22)
    val tileNclx = nclx(9, 8)
    pictureColour(heifItems(listOf(primaryNclx, primaryProfile))).summary() shouldBe p3.toList()
    pictureColour(heifItems(listOf(primaryNclx), tile = listOf(tileProfile))) shouldBe displayP3
    pictureColour(heifItems(listOf(primaryNclx), tile = listOf(tileNclx))) shouldBe displayP3
    pictureColour(heifItems(listOf(primaryProfile), tile = listOf(tileProfile))).summary() shouldBe p3.toList()
    pictureColour(heifItems(listOf(primaryProfile), tile = listOf(tileNclx))).summary() shouldBe p3.toList()
    val tileOnly = heifItems(emptyList(), tile = listOf(tileNclx, tileProfile))
    pictureColour(tileOnly).summary() shouldBe TestIccProfiles.gamma22.toList()
    pictureColour(heifItems(emptyList(), tile = listOf(tileNclx))) shouldBe linearBt2020
  }

  @Test
  fun `an nclx with the iPhone's display p3 code points is read`() {
    pictureColour(heifItems(listOf(nclx(12, 13, matrix = 6, fullRange = true), pixi(3)))) shouldBe displayP3
  }

  @Test
  fun `an nclx not understood defers to the next description`() {
    pictureColour(heifItems(listOf(box("colr", asciiBytes("nclx") + ByteArray(3)), nclx(9, 8)))) shouldBe linearBt2020
  }

  @Test
  fun `an nclx whose code points are not supported lands as untagged instead of deferring`() {
    pictureColour(heifItems(listOf(nclx(23, 13)), tile = listOf(nclx(9, 8)))).shouldBeNull()
    pictureColour(heifItems(listOf(nclx(9, 10)), tile = listOf(colr("prof", p3)))).shouldBeNull()
    pictureColour(heifItems(listOf(nclx(9, 19)))).shouldBeNull()
  }

  @Test
  fun `an nclx whose matrix is unspecified still describes the colour`() {
    pictureColour(heifItems(listOf(nclx(9, 16, matrix = 2)))) shouldBe Cicp(9, 16, 2, true)
    pictureColour(heifItems(listOf(nclx(9, 16, matrix = 2)), tile = listOf(colr("prof", p3)))) shouldBe
      Cicp(9, 16, 2, true)
  }

  @Test
  fun `an nclx whose primaries or transfer is unspecified defers to the next description`() {
    pictureColour(heifItems(listOf(nclx(2, 13)), tile = listOf(nclx(9, 8)))) shouldBe linearBt2020
    pictureColour(heifItems(listOf(nclx(2, 13)), tile = listOf(colr("prof", p3)))).summary() shouldBe p3.toList()
    pictureColour(heifItems(listOf(nclx(2, 2, matrix = 6)), tile = listOf(colr("prof", p3)))).summary() shouldBe
      p3.toList()
    pictureColour(heifItems(listOf(nclx(9, 2)), tile = listOf(nclx(12, 13)))) shouldBe displayP3
    pictureColour(heifItems(listOf(nclx(2, 13)))).shouldBeNull()
  }

  @Test
  fun `an rgb profile that does not parse defers to the nclx`() {
    val brokenRgb = TestIccProfiles.profile("RGB ", "XYZ ", emptyList())
    pictureColour(heifItems(listOf(colr("prof", brokenRgb), nclx(9, 8)))) shouldBe linearBt2020
    pictureColour(heifItems(listOf(colr("prof", brokenRgb)), tile = listOf(colr("prof", p3)))).summary() shouldBe
      p3.toList()
  }

  @Test
  fun `the ycbcr coding is the nclx matrix and range whether or not its code points are supported`() {
    pictureYcbcrCoding(heifItems(listOf(nclx(12, 13, matrix = 6, fullRange = true)))) shouldBe untyped(6, true, 12, 13)
    pictureYcbcrCoding(heifItems(listOf(nclx(2, 2, matrix = 6, fullRange = true)))) shouldBe untyped(6, true, 2, 2)
    pictureYcbcrCoding(heifItems(listOf(nclx(2, 2, matrix = 1, fullRange = false)))) shouldBe untyped(1, false, 2, 2)
    pictureYcbcrCoding(heifItems(listOf(nclx(9, 16, matrix = 9, fullRange = false)))) shouldBe untyped(9, false, 9, 16)
    pictureColour(heifItems(listOf(nclx(2, 2, matrix = 6, fullRange = true)))).shouldBeNull()
  }

  @Test
  fun `the ycbcr coding reads past a profile that wins as the colour`() {
    val profileFirst = heifItems(listOf(colr("prof", p3), nclx(2, 2, matrix = 1, fullRange = false)))
    pictureColour(profileFirst).summary() shouldBe p3.toList()
    pictureYcbcrCoding(profileFirst) shouldBe untyped(1, false, 2, 2)
  }

  @Test
  fun `the ycbcr coding takes the primary item's nclx and then a tile's`() {
    val both = heifItems(listOf(nclx(1, 13, matrix = 1)), tile = listOf(nclx(1, 13, matrix = 5, fullRange = false)))
    pictureYcbcrCoding(both) shouldBe untyped(1, true, 1, 13)
    val tileOnly = heifItems(listOf(colr("prof", p3)), tile = listOf(nclx(2, 2, matrix = 5, fullRange = false)))
    pictureYcbcrCoding(tileOnly) shouldBe untyped(5, false, 2, 2)
  }

  @Test
  fun `a picture without an nclx or a codestream has no ycbcr coding`() {
    pictureYcbcrCoding(heifItems(listOf(colr("prof", p3), pixi(3)))).shouldBeNull()
    pictureYcbcrCoding(heifItems(listOf(box("colr", asciiBytes("nclx") + ByteArray(3))))).shouldBeNull()
    pictureYcbcrCoding(png(cicpChunk(9, 8))).shouldBeNull()
    pictureYcbcrCoding(ByteArray(0)).shouldBeNull()
    val tagged = heifItems(listOf(nclx(12, 13)))
    for (length in tagged.indices) {
      pictureYcbcrCoding(tagged.copyOf(length))?.let { it shouldBe untyped(6, true, 12, 13) }
    }
  }

  @Test
  fun `the codec is the primary item's type whatever the brands say`() {
    codec(iinf(1 to "av01"), ftyp("avif", "mif1", "miaf")) shouldBe CodecKind.Av1
    codec(iinf(1 to "av01"), ftyp("mif1", "avif", "miaf")) shouldBe CodecKind.Av1
    codec(iinf(1 to "av01"), ftyp("miaf", "mif1", "avif")) shouldBe CodecKind.Av1
    codec(iinf(1 to "av01"), ftyp("avis", "avif", "msf1", "miaf")) shouldBe CodecKind.Av1
    codec(iinf(1 to "hvc1"), ftyp("mif1")) shouldBe CodecKind.Hevc
    codec(iinf(1 to "hvc1"), ftyp("heic", "mif1", "heic")) shouldBe CodecKind.Hevc
    codec(iinf(1 to "hev1"), ftyp("mif1", "heix")) shouldBe CodecKind.Hevc
    codec(iinf(1 to "hvc1"), ftyp("avif", "mif1")) shouldBe CodecKind.Hevc
    codec(iinf(1 to "av01"), ftyp("heic", "mif1")) shouldBe CodecKind.Av1
  }

  @Test
  fun `a large ftyp still leaves the codec to the item and one that runs to the end hides the meta`() {
    codec(iinf(1 to "hvc1"), ftyp("heic", "mif1", size = BoxSize.Large)) shouldBe CodecKind.Hevc
    codec(iinf(1 to "av01"), ftyp("avif", "mif1", size = BoxSize.ToEnd)).shouldBeNull()
  }

  @Test
  fun `a 32 bit item id names its item type too`() {
    val associations = ipma(listOf(1 to listOf(1)), version = 1)
    val file = heif(listOf(nclx(1, 13)), associations, version = 1, itemInfo = iinf(1 to "av01", version = 1))
    pictureYcbcrCoding(file)?.codec shouldBe CodecKind.Av1
  }

  @Test
  fun `a grid takes its tiles' codec`() {
    val tile = listOf(hvcc(chromaFormat = 1))
    codec(iinf(1 to "grid", 2 to "hvc1"), tile = tile) shouldBe CodecKind.Hevc
    codec(iinf(2 to "av01", 1 to "grid"), tile = listOf(av1c(monochrome = false))) shouldBe CodecKind.Av1
    codec(iinf(1 to "hvc1", 2 to "av01"), tile = tile) shouldBe CodecKind.Hevc
    codec(iinf(1 to "grid"), tile = tile) shouldBe CodecKind.Other
    codec(iinf(1 to "grid", 2 to "hvc1")) shouldBe CodecKind.Other
  }

  @Test
  fun `an item type that is not hevc or av1 is another codec`() {
    codec(iinf(1 to "jpeg")) shouldBe CodecKind.Other
    codec(iinf(2 to "hvc1")) shouldBe CodecKind.Other
    codec(null) shouldBe CodecKind.Other
    val oldEntry = fullBox("iinf", 0, 0, bytes { u16(1) }, fullBox("infe", 1, 0, bytes { u16(1).u16(0).u8(0).u8(0) }))
    codec(oldEntry) shouldBe CodecKind.Other
    codec(fullBox("iinf", 0, 0, bytes { u16(1) }, fullBox("infe", 2, 0, bytes { u16(1) }))) shouldBe CodecKind.Other
  }

  @Test
  fun `a truncated file reads its whole coding or none`() {
    val coded = heifItems(listOf(nclx(1, 13, matrix = 1, fullRange = false)), itemInfo = iinf(1 to "hvc1"))
    for (length in coded.indices) {
      pictureYcbcrCoding(coded.copyOf(length))?.let { it.matrix shouldBe 1 }
    }
    pictureYcbcrCoding(coded) shouldBe YcbcrCoding(1, false, CodecKind.Hevc, null, primaries = 1, transfer = 13)
  }

  @Test
  fun `the srgb code points read as untagged`() {
    pictureColour(heifItems(listOf(nclx(1, 13)))).shouldBeNull()
    pictureColour(heifItems(listOf(nclx(1, 13)), tile = listOf(nclx(9, 8)))).shouldBeNull()
  }

  @Test
  fun `a heif profile for other channels than its item stores defers to the next description`() {
    pictureColour(heifItems(listOf(colr("prof", p3), pixi(1)))).shouldBeNull()
    pictureColour(heifItems(listOf(colr("prof", p3), nclx(9, 8), pixi(1)))) shouldBe linearBt2020
    pictureColour(heifItems(listOf(colr("prof", grey), pixi(1)))).summary() shouldBe grey.toList()
    pictureColour(heifItems(listOf(colr("prof", p3), pixi(3)))).summary() shouldBe p3.toList()
    pictureColour(heifItems(listOf(colr("prof", p3), av1c(monochrome = true)))).shouldBeNull()
    pictureColour(heifItems(listOf(colr("prof", p3), av1c(monochrome = false)))).summary() shouldBe p3.toList()
    pictureColour(heifItems(listOf(colr("prof", p3), hvcc(chromaFormat = 0)))).shouldBeNull()
    pictureColour(heifItems(listOf(colr("prof", p3), hvcc(chromaFormat = 1)))).summary() shouldBe p3.toList()
    pictureColour(heifItems(listOf(colr("prof", grey)))).summary() shouldBe grey.toList()
  }

  @Test
  fun `the primary item's pixi wins over its codec and its tiles`() {
    val primaryPixi = heifItems(listOf(colr("prof", p3), av1c(monochrome = true), pixi(3)))
    pictureColour(primaryPixi).summary() shouldBe p3.toList()
    val tileCodec = heifItems(listOf(colr("prof", p3)), tile = listOf(av1c(monochrome = true)))
    pictureColour(tileCodec).shouldBeNull()
    val primaryOverTile = heifItems(listOf(colr("prof", p3), pixi(3)), tile = listOf(pixi(1)))
    pictureColour(primaryOverTile).summary() shouldBe p3.toList()
  }
}
