package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.fixtures.avif
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Reads the bit depth and chroma layout of hand-built AVIFs and HEICs from their sequence header or parameter set, only
 * when their `av1C` or `hvcC` agrees, and whether each names the identity matrix on samples not known to be 4:4:4,
 * through an `nclx` or through its codestream alone.
 */
class HeifSampleLayoutTest {
  // An AVIF whose av1C has profile and fields for its depth and layout, and whose item data leads with header.
  private fun avif(
    layout: Av1Layout,
    header: SequenceShape = layout.header,
    nclx: Cicp? = null,
    grid: Boolean = false,
  ): ByteArray =
    TestHeic(
      emptyList(),
      nclx,
      grid = grid,
      itemType = "av01",
      configuration = box("av1C", byteArrayOf(0x81.toByte(), layout.profileLevel, layout.fields, 0)),
      coded = header.obu() + STAND_IN_FRAME,
    ).bytes()

  // A HEIC whose hvcC names chromaFormat and a luma depth of 8 + depthMinus8 around sps.
  private fun heic(
    chromaFormat: Int,
    depthMinus8: Int = 0,
    sps: ByteArray = SpsShape(chromaFormat = chromaFormat, bitDepthMinus8 = depthMinus8).nal(),
    nclx: Cicp? = null,
    grid: Boolean = false,
  ): ByteArray {
    val hvcc = hvccOf(VPS_NAL, sps, PPS_NAL)
    hvcc[HVCC_PAYLOAD + 16] = (0xFC or chromaFormat).toByte()
    hvcc[HVCC_PAYLOAD + 17] = (0xF8 or depthMinus8).toByte()
    return TestHeic(listOf(sps), nclx, grid = grid, configuration = hvcc).bytes()
  }

  @Test
  fun `an avif's depth and layout come from its sequence header when its av1C agrees`() {
    for (grid in listOf(false, true)) {
      for (layout in Av1Layout.entries) {
        val coding = pictureYcbcrCoding(avif(layout, grid = grid))
        coding?.bitDepth shouldBe layout.bitDepth
        coding?.chromaFormat shouldBe layout.chromaFormat
      }
      val vertical = TestHeic(emptyList(), grid = grid, itemType = "av01", configuration = VERTICAL_AV1C)
      pictureYcbcrCoding(vertical.bytes())?.chromaFormat.shouldBeNull()
    }
  }

  @Test
  fun `a heic's depth and layout come from its parameter set when its hvcC agrees`() {
    for (grid in listOf(false, true)) {
      for (chromaFormat in 0..3) {
        for ((depthMinus8, depth) in listOf(0 to 8, 2 to 10, 4 to 12)) {
          val coding = pictureYcbcrCoding(heic(chromaFormat, depthMinus8, grid = grid))
          coding?.bitDepth shouldBe depth
          coding?.chromaFormat shouldBe chromaFormat
        }
      }
    }
  }

  @Test
  fun `a heic whose hvcC disagrees with its parameter set gives neither and an identity nclx on it is refused`() {
    val nclx = Cicp(9, 16, 0, true)
    for (grid in listOf(false, true)) {
      val disagreeing =
        listOf(
          heic(3, sps = SpsShape(chromaFormat = 1).nal(), nclx = nclx, grid = grid),
          heic(3, sps = SpsShape(chromaFormat = 2).nal(), nclx = nclx, grid = grid),
          heic(3, sps = SpsShape(chromaFormat = 3, bitDepthMinus8 = 2).nal(), nclx = nclx, grid = grid),
          heic(1, sps = SpsShape(chromaFormat = 3).nal(), nclx = nclx, grid = grid),
          heic(3, sps = byteArrayOf(0x42, 0x01, 0x01), nclx = nclx, grid = grid),
        )
      for (file in disagreeing) {
        val coding = pictureYcbcrCoding(file)
        coding?.chromaFormat.shouldBeNull()
        coding?.bitDepth.shouldBeNull()
        coding?.identityNot444 shouldBe true
        heifWithExplicitCodestreamColor(file).handed shouldBeSameInstanceAs file
      }
    }
  }

  @Test
  fun `an avif whose av1C disagrees with its sequence header gives neither and an identity nclx on it is refused`() {
    val nclx = Cicp(9, 16, 0, true)
    for (grid in listOf(false, true)) {
      val disagreeing =
        listOf(
          avif(Av1Layout.Yuv444, header = Av1Layout.Yuv420.header, nclx = nclx, grid = grid),
          avif(Av1Layout.Yuv444, header = Av1Layout.Yuv422.header, nclx = nclx, grid = grid),
          avif(Av1Layout.Yuv444Twelve, header = Av1Layout.Yuv444.header, nclx = nclx, grid = grid),
          avif(Av1Layout.Yuv420, header = Av1Layout.Yuv444.header, nclx = nclx, grid = grid),
          avif(Av1Layout.Yuv420Ten, header = Av1Layout.Yuv420.header, nclx = nclx, grid = grid),
        )
      for (file in disagreeing) {
        val coding = pictureYcbcrCoding(file)
        coding?.chromaFormat.shouldBeNull()
        coding?.bitDepth.shouldBeNull()
        coding?.identityNot444 shouldBe true
        heifWithExplicitCodestreamColor(file).handed shouldBeSameInstanceAs file
      }
    }
  }

  @Test
  fun `an hvcC cut off between its layout and its depth gives neither`() {
    val cut = ByteArray(HVCC_DEPTH_AT).also { it[0] = 1 }.also { it[HVCC_DEPTH_AT - 1] = 0xFF.toByte() }
    for (grid in listOf(false, true)) {
      val sps = SpsShape(chromaFormat = 3).nal()
      val file = TestHeic(listOf(sps), Cicp(9, 16, 0, true), grid = grid, configuration = box("hvcC", cut)).bytes()
      val coding = pictureYcbcrCoding(file)
      coding?.chromaFormat.shouldBeNull()
      coding?.bitDepth.shouldBeNull()
      coding?.identityNot444 shouldBe true
    }
  }

  @Test
  fun `an identity nclx is refused on subsampled and monochrome samples and taken on 4 4 4`() {
    for (nclx in listOf(Cicp(1, 13, 0, true), Cicp(9, 16, 0, false), Cicp(2, 2, 0, true))) {
      for (grid in listOf(false, true)) {
        for (layout in Av1Layout.entries) {
          val coding = pictureYcbcrCoding(avif(layout, nclx = nclx, grid = grid))
          coding?.identityNot444 shouldBe (layout.chromaFormat != 3)
        }
        for (chromaFormat in 0..3) {
          val coding = pictureYcbcrCoding(heic(chromaFormat, nclx = nclx, grid = grid))
          coding?.identityNot444 shouldBe (chromaFormat != 3)
        }
      }
    }
  }

  @Test
  fun `a codestream naming identity without an nclx is refused on 4 2 0 and 4 2 2 and taken on 4 4 4`() {
    val identity = Triple(9, 16, 0)
    for (layout in Av1Layout.entries.filter { !it.header.mono }) {
      val coding = pictureYcbcrCoding(avif(layout, layout.header.copy(description = identity)))
      coding?.fromNclx shouldBe false
      coding?.matrix shouldBe 0
      coding?.identityNot444 shouldBe (layout.chromaFormat != 3)
    }
    for (chromaFormat in 1..3) {
      val vui = minimalVui(CodestreamSignal(9, 16, 0, false))
      val coding = pictureYcbcrCoding(heic(chromaFormat, sps = SpsShape(chromaFormat = chromaFormat, vui = vui).nal()))
      coding?.fromNclx shouldBe false
      coding?.matrix shouldBe 0
      coding?.identityNot444 shouldBe (chromaFormat != 3)
    }
  }

  @Test
  fun `only the identity matrix on a known layout is refused`() {
    for (matrix in listOf(1, 5, 6, 9)) {
      pictureYcbcrCoding(avif(Av1Layout.Yuv420, nclx = Cicp(1, 13, matrix, true)))?.identityNot444 shouldBe false
      pictureYcbcrCoding(heic(2, nclx = Cicp(1, 13, matrix, true)))?.identityNot444 shouldBe false
    }
    val unknown = YcbcrCoding(0, true, CodecKind.Av1, null, bitDepth = null, chromaFormat = null)
    unknown.copy(chromaFormat = 3, bitDepth = 8).identityNot444 shouldBe false
    unknown.copy(chromaFormat = 3).identityNot444 shouldBe true
    unknown.copy(chromaFormat = 2, bitDepth = 8).identityNot444 shouldBe true
    unknown.copy(chromaFormat = 2).identityNot444 shouldBe true
    unknown.copy(codec = CodecKind.Other).identityNot444 shouldBe false
  }

  @Test
  fun `an identity nclx on a layout that is not known is refused`() {
    val nclx = Cicp(9, 16, 0, true)
    for (grid in listOf(false, true)) {
      for (configuration in listOf(VERTICAL_AV1C, SHORT_AV1C)) {
        val file = TestHeic(emptyList(), nclx, grid = grid, itemType = "av01", configuration = configuration).bytes()
        val coding = pictureYcbcrCoding(file)
        coding?.chromaFormat.shouldBeNull()
        coding?.identityNot444 shouldBe true
      }
      val unread = TestHeic(listOf(SpsShape().nal()), nclx, grid = grid, configuration = box("hvcC", byteArrayOf(1)))
      pictureYcbcrCoding(unread.bytes())?.identityNot444 shouldBe true
      val other = TestHeic(listOf(SpsShape().nal()), nclx, grid = grid, itemType = "jpeg").bytes()
      pictureYcbcrCoding(other)?.identityNot444 shouldBe false
    }
  }

  // An av1C's profile with level 13 and its byte of depth, monochrome and subsampling bits, with a sequence header of
  // the same profile, depth and layout.
  internal enum class Av1Layout(
    val profileLevel: Byte,
    val fields: Byte,
    val bitDepth: Int,
    val chromaFormat: Int,
    val header: SequenceShape,
  ) {
    Yuv420(0x0D, 0x0C, 8, 1, SequenceShape()),
    Yuv420Ten(0x0D, 0x4C, 10, 1, SequenceShape(highBitDepth = true)),
    Yuv422(0x4D, 0x08, 8, 2, SequenceShape(profile = 2)),
    Yuv444(0x2D, 0x00, 8, 3, SequenceShape(profile = 1)),
    Yuv444Twelve(0x4D, 0x60, 12, 3, SequenceShape(2, highBitDepth = true, twelveBit = true, subsampling = 0 to 0)),
    Monochrome(0x0D, 0x1C, 8, 0, SequenceShape(mono = true)),
  }

  private companion object {
    const val HVCC_PAYLOAD = 8

    // Where an hvcC payload holds its luma depth, just past the byte holding its chroma layout.
    const val HVCC_DEPTH_AT = 17

    // An av1C naming chroma subsampled vertically alone, which AV1 has no layout for.
    val VERTICAL_AV1C = box("av1C", byteArrayOf(0x81.toByte(), 0x4D, 0x64, 0))

    // An av1C cut off after its marker and profile byte, so its depth and layout do not read.
    val SHORT_AV1C = box("av1C", byteArrayOf(0x81.toByte(), 0x4D))
  }
}
