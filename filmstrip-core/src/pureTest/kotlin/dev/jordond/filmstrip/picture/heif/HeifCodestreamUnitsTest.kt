package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.ChromaFormat
import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.fixtures.av1c
import dev.jordond.filmstrip.picture.fixtures.avif
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.hvcc
import dev.jordond.filmstrip.picture.heif.HeifSampleLayoutTest.Av1Layout
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test

/**
 * Reads the bit depth and chroma layout of hand-built AVIFs and HEICs carrying several sequence parameter sets or
 * sequence headers, in their `hvcC` or `av1C` and at the start of their item data, and checks that an identity `nclx`
 * is refused and left unrewritten exactly when those units disagree.
 */
class HeifCodestreamUnitsTest {
  private val identity = Cicp(9, 16, 0, true)
  private val stated = CodestreamSignal(9, 16, 0, true)
  private val sps444 = SpsShape(chromaFormat = 3).nal()

  // An hvcC naming 4:4:4 or chromaFormat and 8 + depthMinus8 bits around configured, its lengths as wide as
  // lengthSizeMinusOne gives.
  private fun hvcc(
    configured: List<ByteArray>,
    depthMinus8: Int = 0,
    chromaFormat: Int = ChromaFormat.YUV444,
    lengthSizeMinusOne: Int = 3,
  ): ByteArray {
    val hvcc = hvccOf(VPS_NAL, *configured.toTypedArray(), PPS_NAL, lengthSizeMinusOne = lengthSizeMinusOne)
    hvcc[HVCC_PAYLOAD + 16] = (0xFC or chromaFormat).toByte()
    hvcc[HVCC_PAYLOAD + 17] = (0xF8 or depthMinus8).toByte()
    return hvcc
  }

  // The inBand units and then a slice, each behind a four byte length.
  private fun hevcData(inBand: List<ByteArray>): ByteArray =
    bytes { for (unit in inBand + TestHeic.SLICE) u32(unit.size.toLong()).raw(unit) }

  // A HEIC whose hvcC names 4:4:4 and 8 + depthMinus8 bits around configured, and whose coded items hold the inBand
  // units and then a slice.
  private fun heic(
    configured: List<ByteArray>,
    inBand: List<ByteArray> = emptyList(),
    depthMinus8: Int = 0,
    itemType: String = "hvc1",
    grid: Boolean = false,
    lengthSizeMinusOne: Int = 3,
    ilocBox: ByteArray? = null,
    tiles: Map<Int, TestTile> = emptyMap(),
  ): ByteArray =
    TestHeic(
      emptyList(),
      identity,
      grid = grid,
      itemType = itemType,
      ilocBox = ilocBox,
      configuration = hvcc(configured, depthMinus8, lengthSizeMinusOne = lengthSizeMinusOne),
      coded = hevcData(inBand),
      tiles = tiles,
    ).bytes()

  private fun av1c(
    layout: Av1Layout,
    configured: ByteArray,
  ): ByteArray = box("av1C", byteArrayOf(0x81.toByte(), layout.profileLevel, layout.fields, 0), configured)

  // An AVIF whose av1C states layout around configured, and whose coded items hold inBand and then a frame.
  private fun avif(
    layout: Av1Layout,
    configured: ByteArray,
    inBand: ByteArray,
    grid: Boolean = false,
    splitExtents: Boolean = false,
    ilocBox: ByteArray? = null,
    tiles: Map<Int, TestTile> = emptyMap(),
  ): ByteArray =
    TestHeic(
      emptyList(),
      identity,
      grid = grid,
      itemType = "av01",
      splitExtents = splitExtents,
      ilocBox = ilocBox,
      configuration = av1c(layout, configured),
      coded = inBand + STAND_IN_FRAME,
      tiles = tiles,
    ).bytes()

  private fun assertRefused(file: ByteArray) {
    val coding = pictureYcbcrCoding(file)
    coding?.bitDepth.shouldBeNull()
    coding?.chromaFormat.shouldBeNull()
    coding?.identityNot444 shouldBe true
    heifWithExplicitCodestreamColor(file).handed shouldBeSameInstanceAs file
  }

  private fun assertRead(
    file: ByteArray,
    bitDepth: Int,
    chromaFormat: Int,
  ) {
    val coding = pictureYcbcrCoding(file)
    coding?.bitDepth shouldBe bitDepth
    coding?.chromaFormat shouldBe chromaFormat
    coding?.identityNot444 shouldBe (chromaFormat != ChromaFormat.YUV444)
  }

  // Whether every unit the rewrite walks in the rewritten file states the identity nclx.
  private fun assertRewritten(file: ByteArray) {
    val rewritten = heifWithExplicitCodestreamColor(file).handed
    rewritten shouldNotBeSameInstanceAs file
    val view = ByteView(rewritten, rewritten.size)
    val coded = heifCodedItems(view) ?: error("no coded items")
    val units = view.codestreamUnits(coded.codec, coded.items, coded.locations, coded.idat)
    units.map { view.signalOf(it) } shouldBe List(units.size) { stated }
  }

  @Test
  fun `a second parameter set in the hvcC differing in layout or depth gives neither`() {
    for (grid in listOf(false, true)) {
      for (second in listOf(SpsShape(chromaFormat = 1).nal(), SpsShape(chromaFormat = 3, bitDepthMinus8 = 2).nal())) {
        assertRefused(heic(listOf(sps444, second), grid = grid))
      }
      val agreeing = heic(listOf(sps444, SpsShape(spsId = 1, chromaFormat = 3).nal()), grid = grid)
      assertRead(agreeing, 8, 3)
      assertRewritten(agreeing)
    }
  }

  @Test
  fun `a parameter set in band differing from the hvcC one in layout or depth gives neither`() {
    val differing = listOf(SpsShape(chromaFormat = 2).nal(), SpsShape(chromaFormat = 3, bitDepthMinus8 = 2).nal())
    for (grid in listOf(false, true)) {
      for (inBand in differing) {
        assertRefused(heic(listOf(sps444), listOf(VPS_NAL, inBand, PPS_NAL), grid = grid))
        assertRefused(heic(listOf(sps444), listOf(VPS_NAL, sps444, inBand, PPS_NAL), grid = grid))
      }
      val ten = SpsShape(chromaFormat = 3, bitDepthMinus8 = 2).nal()
      assertRead(heic(listOf(ten), listOf(VPS_NAL, ten, PPS_NAL), depthMinus8 = 2, grid = grid), 10, 3)
      val agreeing = heic(listOf(sps444), listOf(VPS_NAL, sps444, PPS_NAL), grid = grid)
      assertRead(agreeing, 8, 3)
      assertRewritten(agreeing)
    }
  }

  @Test
  fun `an hev1 item with its parameter set only in band is read and rewritten`() {
    val silent = CodestreamSignal(null, null, null, null)
    for (grid in listOf(false, true)) {
      val file = heic(emptyList(), listOf(VPS_NAL, sps444, PPS_NAL), itemType = "hev1", grid = grid)
      assertRead(file, 8, 3)
      pictureYcbcrCoding(file)?.codestream shouldBe silent
      assertRewritten(file)
      assertRefused(heic(emptyList(), itemType = "hev1", grid = grid))
    }
  }

  @Test
  fun `a first parameter set in the hvcC that does not parse is not covered by one in band`() {
    for (grid in listOf(false, true)) {
      val file = heic(listOf(byteArrayOf(0x42, 0x01, 0x01)), listOf(VPS_NAL, sps444, PPS_NAL), grid = grid)
      pictureYcbcrCoding(file)?.codestream.shouldBeNull()
      assertRefused(file)
    }
  }

  @Test
  fun `config obus alone give the depth and layout only when the av1C agrees`() {
    for (grid in listOf(false, true)) {
      for (layout in Av1Layout.entries) {
        assertRead(avif(layout, layout.header.obu(), ByteArray(0), grid), layout.bitDepth, layout.chromaFormat)
      }
      assertRewritten(avif(Av1Layout.Yuv444, Av1Layout.Yuv444.header.obu(), ByteArray(0), grid))
      val disagreeing =
        listOf(
          Av1Layout.Yuv444 to Av1Layout.Yuv420,
          Av1Layout.Yuv444 to Av1Layout.Yuv444Twelve,
          Av1Layout.Yuv444Twelve to Av1Layout.Yuv444,
          Av1Layout.Yuv420 to Av1Layout.Yuv420Ten,
        )
      for ((layout, header) in disagreeing) assertRefused(avif(layout, header.header.obu(), ByteArray(0), grid))
    }
  }

  @Test
  fun `a second sequence header differing in layout or depth gives neither`() {
    val good = Av1Layout.Yuv444.header.obu()
    for (grid in listOf(false, true)) {
      for (other in listOf(Av1Layout.Yuv420.header.obu(), Av1Layout.Yuv444Twelve.header.obu())) {
        assertRefused(avif(Av1Layout.Yuv444, good + other, ByteArray(0), grid))
        assertRefused(avif(Av1Layout.Yuv444, ByteArray(0), good + other, grid))
        assertRefused(avif(Av1Layout.Yuv444, good, other, grid))
      }
      val agreeing = avif(Av1Layout.Yuv444, good + good, good + good, grid)
      assertRead(agreeing, 8, 3)
      assertRewritten(agreeing)
    }
  }

  @Test
  fun `a first header in band that does not parse is not covered by the av1C one`() {
    val good = Av1Layout.Yuv444.header
    val bad = obu(SEQUENCE_HEADER, good.payload().copyOf(2))
    for (grid in listOf(false, true)) {
      val file = avif(Av1Layout.Yuv444, good.obu(), bad + good.obu(), grid)
      pictureYcbcrCoding(file)?.codestream.shouldBeNull()
      assertRefused(file)
      pictureYcbcrCoding(avif(Av1Layout.Yuv444, good.obu(), ByteArray(0), grid))?.codestream shouldBe good.signal
    }
  }

  @Test
  fun `a third tile whose own hvcC differs in layout or depth gives neither`() {
    val differing =
      listOf(
        hvcc(listOf(SpsShape(chromaFormat = 1).nal()), chromaFormat = ChromaFormat.YUV420),
        hvcc(listOf(SpsShape(chromaFormat = 3, bitDepthMinus8 = 2).nal()), depthMinus8 = 2),
      )
    for (own in differing) assertRefused(heic(listOf(sps444), grid = true, tiles = mapOf(4 to TestTile(own))))
    val agreeing = heic(listOf(sps444), grid = true, tiles = mapOf(4 to TestTile(hvcc(listOf(sps444)))))
    assertRead(agreeing, 8, 3)
    assertRewritten(agreeing)
  }

  @Test
  fun `a third tile whose parameter set in band differs in layout or depth gives neither`() {
    for (inBand in listOf(SpsShape(chromaFormat = 2).nal(), SpsShape(chromaFormat = 3, bitDepthMinus8 = 2).nal())) {
      val tile = TestTile(coded = hevcData(listOf(VPS_NAL, inBand, PPS_NAL)))
      assertRefused(heic(listOf(sps444), grid = true, tiles = mapOf(4 to tile)))
    }
    val agreeing = TestTile(coded = hevcData(listOf(VPS_NAL, sps444, PPS_NAL)))
    assertRead(heic(listOf(sps444), grid = true, tiles = mapOf(4 to agreeing)), 8, 3)
  }

  @Test
  fun `a third tile whose own av1C or header in band differs in layout or depth gives neither`() {
    val good = Av1Layout.Yuv444.header.obu()
    val tiles =
      listOf(
        TestTile(av1c(Av1Layout.Yuv420, Av1Layout.Yuv420.header.obu())),
        TestTile(av1c(Av1Layout.Yuv444Twelve, ByteArray(0))),
        TestTile(coded = Av1Layout.Yuv420.header.obu() + STAND_IN_FRAME),
        TestTile(coded = Av1Layout.Yuv444Twelve.header.obu() + STAND_IN_FRAME),
      )
    for (tile in tiles) assertRefused(avif(Av1Layout.Yuv444, good, good, grid = true, tiles = mapOf(4 to tile)))
    val agreeing = TestTile(av1c(Av1Layout.Yuv444, good), coded = good + good + STAND_IN_FRAME)
    val file = avif(Av1Layout.Yuv444, good, good, grid = true, tiles = mapOf(4 to agreeing))
    assertRead(file, 8, 3)
    assertRewritten(file)
  }

  @Test
  fun `an hvcC giving three byte lengths leaves the depth and layout unknown`() {
    for (grid in listOf(false, true)) {
      val file = heic(listOf(sps444), grid = grid, lengthSizeMinusOne = 2)
      pictureYcbcrCoding(file)?.codestream shouldBe spsSignal(sps444)
      assertRefused(file)
      assertRead(heic(listOf(sps444), grid = grid), 8, 3)
    }
  }

  @Test
  fun `an av1 item of several extents leading with an unsized obu leaves the depth and layout unknown`() {
    val unsized = Av1Layout.Yuv444.header.obu(sizeBytes = 0)
    val sized = Av1Layout.Yuv444.header.obu()
    for (grid in listOf(false, true)) {
      assertRefused(avif(Av1Layout.Yuv444, sized, unsized, grid, splitExtents = true))
      assertRead(avif(Av1Layout.Yuv444, sized, sized, grid, splitExtents = true), 8, 3)
    }
  }

  @Test
  fun `an iloc that does not read leaves the depth and layout unknown`() {
    val unreadable = fullBox("iloc", 3, 0, ByteArray(4))
    val header = Av1Layout.Yuv444.header.obu()
    for (grid in listOf(false, true)) {
      val hevc = heic(listOf(sps444), grid = grid, ilocBox = unreadable)
      pictureYcbcrCoding(hevc)?.codestream shouldBe spsSignal(sps444)
      assertRefused(hevc)
      assertRefused(avif(Av1Layout.Yuv444, header, header, grid, ilocBox = unreadable))
    }
  }

  private companion object {
    const val HVCC_PAYLOAD = 8
  }
}
