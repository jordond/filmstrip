package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.random.Random
import kotlin.test.Test

/**
 * Rewrites the sequence parameter sets of hand-built HEICs to state their `nclx`, comparing each result byte for byte
 * with the file built from scratch around the rewritten units, so every box size and item location is checked.
 */
class HeifRewriteTest {
  private val bt709Full = Cicp(1, 13, 1, true)
  private val silent = SpsShape().nal()
  private val agreeing = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, true))).nal()

  private fun stating(
    nclx: Cicp,
    shape: SpsShape = SpsShape(),
  ): ByteArray {
    val signal = CodestreamSignal(nclx.primaries, nclx.transfer, nclx.matrix, nclx.fullRange)
    return spsStating(shape.nal(), signal)!!
  }

  private fun stating(nal: ByteArray): ByteArray = spsStating(nal, CodestreamSignal(1, 13, 1, true))!!

  private fun rewritten(heic: TestHeic): ByteArray = heifWithExplicitCodestreamColour(heic.bytes())

  @Test
  fun `a silent parameter set is rewritten to state the nclx`() {
    rewritten(TestHeic(listOf(silent))) shouldBe TestHeic(listOf(stating(bt709Full))).bytes()
    val limited = Cicp(9, 16, 9, false)
    rewritten(TestHeic(listOf(silent), limited)) shouldBe TestHeic(listOf(stating(limited)), limited).bytes()
  }

  @Test
  fun `the rewritten file reads back with the nclx as its codestream signal`() {
    val coding = pictureYcbcrCoding(rewritten(TestHeic(listOf(silent), Cicp(12, 13, 6, true))))
    coding?.codestream shouldBe CodestreamSignal(12, 13, 6, true)
  }

  @Test
  fun `a parameter set that disagrees is replaced and one that agrees is kept`() {
    val disagreeing = SpsShape(vui = fullVui(CodestreamSignal(1, 13, 6, true))).nal()
    val expected = SpsShape(vui = fullVui(CodestreamSignal(1, 13, 1, true))).nal()
    rewritten(TestHeic(listOf(disagreeing))) shouldBe TestHeic(listOf(expected)).bytes()
    val file = TestHeic(listOf(agreeing)).bytes()
    heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
  }

  @Test
  fun `a parameter set that agrees on matrix and range but not primaries is rewritten`() {
    val p3 = SpsShape(vui = minimalVui(CodestreamSignal(12, 13, 1, true))).nal()
    rewritten(TestHeic(listOf(p3))) shouldBe TestHeic(listOf(stating(p3))).bytes()
  }

  @Test
  fun `an unspecified nclx matrix is written as BT601 while the nclx still reads as unspecified`() {
    val unspecified = Cicp(1, 13, 2, true)
    val result = rewritten(TestHeic(listOf(silent), unspecified))
    result shouldBe TestHeic(listOf(stating(Cicp(1, 13, 6, true))), unspecified).bytes()
    val coding = pictureYcbcrCoding(result)
    coding?.matrix shouldBe 2
    coding?.codestream shouldBe CodestreamSignal(1, 13, 6, true)
  }

  @Test
  fun `a parameter set already stating BT601 under an unspecified nclx matrix is kept`() {
    val file = TestHeic(listOf(stating(Cicp(1, 13, 6, false))), Cicp(1, 13, 2, false)).bytes()
    heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
  }

  @Test
  fun `every parameter set in the hvcC is rewritten`() {
    val second = SpsShape(spsId = 1)
    val expected = TestHeic(listOf(stating(bt709Full), stating(bt709Full, second))).bytes()
    rewritten(TestHeic(listOf(silent, second.nal()))) shouldBe expected
  }

  @Test
  fun `64-bit box sizes grow with the change`() {
    val expected = TestHeic(listOf(stating(bt709Full)), large = true).bytes()
    rewritten(TestHeic(listOf(silent), large = true)) shouldBe expected
  }

  @Test
  fun `base offsets and 32-bit item ids move with the change`() {
    val expected = TestHeic(listOf(stating(bt709Full)), baseOffset = true, ilocVersion = 2).bytes()
    rewritten(TestHeic(listOf(silent), baseOffset = true, ilocVersion = 2)) shouldBe expected
  }

  @Test
  fun `a parameter set repeated in the item data is rewritten with the item's length`() {
    val new = stating(bt709Full)
    rewritten(TestHeic(listOf(silent), inBand = silent)) shouldBe TestHeic(listOf(new), inBand = new).bytes()
    for (baseOffset in listOf(false, true)) {
      val expected = TestHeic(listOf(agreeing), inBand = new, baseOffset = baseOffset).bytes()
      rewritten(TestHeic(listOf(agreeing), inBand = silent, baseOffset = baseOffset)) shouldBe expected
    }
  }

  @Test
  fun `a grid rewrites the hvcC its tiles share and the parameter sets in every tile`() {
    val new = stating(bt709Full)
    rewritten(TestHeic(listOf(silent), grid = true)) shouldBe TestHeic(listOf(new), grid = true).bytes()
    val expected = TestHeic(listOf(new), inBand = new, grid = true, large = true).bytes()
    rewritten(TestHeic(listOf(silent), inBand = silent, grid = true, large = true)) shouldBe expected
  }

  @Test
  fun `hev1 items are rewritten too`() {
    val expected = TestHeic(listOf(stating(bt709Full)), itemType = "hev1").bytes()
    rewritten(TestHeic(listOf(silent), itemType = "hev1")) shouldBe expected
  }

  @Test
  fun `a profile beside the nclx makes no difference`() {
    val expected = TestHeic(listOf(stating(bt709Full)), profile = true).bytes()
    rewritten(TestHeic(listOf(silent), profile = true)) shouldBe expected
  }

  @Test
  fun `without an nclx a silent parameter set states limited range with BT601`() {
    val own = CodestreamSignal(2, 2, 6, false)
    val new = spsStating(silent, own)!!
    for (profile in listOf(false, true)) {
      rewritten(TestHeic(listOf(silent), nclx = null, profile = profile)) shouldBe
        TestHeic(listOf(new), nclx = null, profile = profile).bytes()
    }
    rewritten(TestHeic(listOf(silent), nclx = null, inBand = silent, grid = true)) shouldBe
      TestHeic(listOf(new), nclx = null, inBand = new, grid = true).bytes()
    pictureYcbcrCoding(rewritten(TestHeic(listOf(silent), nclx = null)))?.codestream shouldBe own
  }

  @Test
  fun `without an nclx a video signal type keeps its range and gains BT601`() {
    val fullRange = SpsShape(vui = fullVui(CodestreamSignal(null, null, null, true))).nal()
    val expected = SpsShape(vui = fullVui(CodestreamSignal(2, 2, 6, true))).nal()
    rewritten(TestHeic(listOf(fullRange), nclx = null)) shouldBe TestHeic(listOf(expected), nclx = null).bytes()
    val unspecified = SpsShape(vui = fullVui(CodestreamSignal(1, 13, 2, false))).nal()
    val stated = SpsShape(vui = fullVui(CodestreamSignal(1, 13, 6, false))).nal()
    rewritten(TestHeic(listOf(unspecified), nclx = null)) shouldBe TestHeic(listOf(stated), nclx = null).bytes()
  }

  @Test
  fun `without an nclx a parameter set that names its matrix is left alone`() {
    for (signal in listOf(CodestreamSignal(1, 13, 1, false), CodestreamSignal(9, 16, 9, true))) {
      val file = TestHeic(listOf(SpsShape(vui = fullVui(signal)).nal()), nclx = null).bytes()
      heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
    }
  }

  @Test
  fun `a chromaticity derived nclx matrix is stated as its named matrix`() {
    val bt709 = Cicp(1, 13, 12, true)
    rewritten(TestHeic(listOf(silent), bt709)) shouldBe TestHeic(listOf(stating(Cicp(1, 13, 1, true))), bt709).bytes()
    val p3 = Cicp(12, 13, 12, true)
    rewritten(TestHeic(listOf(silent), p3)) shouldBe TestHeic(listOf(stating(p3)), p3).bytes()
  }

  @Test
  fun `files that need no rewriting come back as themselves`() {
    val unchanged =
      listOf(
        TestHeic(listOf(agreeing), nclx = null),
        TestHeic(listOf(silent), itemType = "av01"),
        TestHeic(listOf(silent), trailing = "moov"),
        TestHeic(listOf(byteArrayOf(0x42, 0x01, 0x01))),
        TestHeic(listOf(silent), nclx = Cicp(1, 13, 256, true)),
      ).map { it.bytes() } + listOf(avif(PROFILE), ByteArray(0), asciiBytes("not a picture at all"))
    for (file in unchanged) heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
  }

  @Test
  fun `code points past 255 are written as unspecified`() {
    val wide = Cicp(300, 400, 1, true)
    val expected = TestHeic(listOf(stating(Cicp(2, 2, 1, true))), wide).bytes()
    rewritten(TestHeic(listOf(silent), wide)) shouldBe expected
  }

  @Test
  fun `mdat ahead of meta keeps its offsets while the boxes after it move`() {
    val new = stating(bt709Full)
    rewritten(TestHeic(listOf(silent), mdatFirst = true)) shouldBe TestHeic(listOf(new), mdatFirst = true).bytes()
    for (baseOffset in listOf(false, true)) {
      val expected = TestHeic(listOf(new), inBand = new, mdatFirst = true, baseOffset = baseOffset).bytes()
      rewritten(TestHeic(listOf(silent), inBand = silent, mdatFirst = true, baseOffset = baseOffset)) shouldBe expected
    }
  }

  @Test
  fun `in-band lengths of one and two bytes are rewritten at their own width`() {
    val new = stating(bt709Full)
    for (width in listOf(0, 1)) {
      val expected = TestHeic(listOf(new), inBand = new, lengthSizeMinusOne = width).bytes()
      rewritten(TestHeic(listOf(silent), inBand = silent, lengthSizeMinusOne = width)) shouldBe expected
    }
  }

  @Test
  fun `a three byte length size is not understood`() {
    val file = TestHeic(listOf(silent), inBand = silent, lengthSizeMinusOne = 2).bytes()
    heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
  }

  @Test
  fun `an in-band parameter set that outgrows its length field leaves the file as it was`() {
    val padded = { bytes: Int -> SpsShape(vui = paddedVui(bytes)).nal() }
    val growing = (0..MAX_ONE_BYTE).map(padded).first { it.size <= MAX_ONE_BYTE && stating(it).size > MAX_ONE_BYTE }
    val file = TestHeic(listOf(growing), inBand = growing, lengthSizeMinusOne = 0).bytes()
    heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
    val new = stating(growing)
    val fits = TestHeic(listOf(growing), inBand = growing, lengthSizeMinusOne = 1)
    rewritten(fits) shouldBe TestHeic(listOf(new), inBand = new, lengthSizeMinusOne = 1).bytes()
  }

  @Test
  fun `items in idat move with a parameter set rewritten inside them`() {
    val new = stating(bt709Full)
    for (openEnded in listOf(false, true)) {
      val expected = TestHeic(listOf(new), inBand = new, codedInIdat = true, openEnded = openEnded).bytes()
      rewritten(TestHeic(listOf(silent), inBand = silent, codedInIdat = true, openEnded = openEnded)) shouldBe expected
    }
    rewritten(TestHeic(listOf(silent), openEnded = true)) shouldBe TestHeic(listOf(new), openEnded = true).bytes()
  }

  @Test
  fun `iloc version 0 locations move with the change`() {
    val new = stating(bt709Full)
    val expected = TestHeic(listOf(new), inBand = new, ilocVersion = 0, baseOffset = true).bytes()
    rewritten(TestHeic(listOf(silent), inBand = silent, ilocVersion = 0, baseOffset = true)) shouldBe expected
  }

  @Test
  fun `an item split into extents moves its later extents and grows its first`() {
    val new = stating(bt709Full)
    rewritten(TestHeic(listOf(silent), splitExtents = true)) shouldBe TestHeic(listOf(new), splitExtents = true).bytes()
    val expected = TestHeic(listOf(new), inBand = new, grid = true, splitExtents = true).bytes()
    rewritten(TestHeic(listOf(silent), inBand = silent, grid = true, splitExtents = true)) shouldBe expected
  }

  @Test
  fun `a parameter set with an extension after its vui keeps it`() {
    val extended = SpsShape(vui = fullVui(signal = null, extension = true))
    val expected = TestHeic(listOf(stating(bt709Full, extended)), inBand = stating(bt709Full, extended)).bytes()
    rewritten(TestHeic(listOf(extended.nal()), inBand = extended.nal())) shouldBe expected
  }

  @Test
  fun `bytes after the last box that do not parse are kept behind the rewritten file`() {
    val new = stating(bt709Full)
    for (junk in listOf(bytes { u32(1000).ascii("junk").u32(0) }, byteArrayOf(1, 2, 3))) {
      val expected = TestHeic(listOf(new), inBand = new, junk = junk).bytes()
      rewritten(TestHeic(listOf(silent), inBand = silent, junk = junk)) shouldBe expected
    }
  }

  @Test
  fun `extent counts the iloc has no bytes for leave the file as it was`() {
    val items =
      bytes {
        u8(0).u8(0).u16(CRAFTED_ITEMS)
        repeat(CRAFTED_ITEMS) { u16(it + 1).u16(0).u16(0).u16(0xFFFF) }
      }
    val empty = fullBox("iloc", 1, 0, items)
    val oneExtent =
      bytes {
        u8(0x44).u8(0).u16(1)
        u16(1).u16(0).u16(0).u16(0xFFFF)
        u32(0).u32(8)
      }
    val short = fullBox("iloc", 1, 0, oneExtent)
    for (iloc in listOf(empty, short)) {
      val file = TestHeic(listOf(silent), ilocBox = iloc).bytes()
      heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
    }
  }

  @Test
  fun `every truncation comes back as the input`() {
    val whole = TestHeic(listOf(silent), inBand = silent, grid = true, baseOffset = true).bytes()
    heifWithExplicitCodestreamColour(whole) shouldNotBe whole
    for (size in 0 until whole.size) {
      val truncated = whole.copyOf(size)
      heifWithExplicitCodestreamColour(truncated) shouldBeSameInstanceAs truncated
    }
  }

  @Test
  fun `seeded corruption comes back without throwing`() {
    val random = Random(47)
    val files =
      listOf(
        TestHeic(listOf(silent), inBand = silent, grid = true),
        TestHeic(listOf(silent, agreeing), large = true, baseOffset = true, ilocVersion = 2),
      ).map { it.bytes() }
    repeat(FUZZ_ROUNDS) { round ->
      val file = files[round % files.size].copyOf()
      repeat(1 + random.nextInt(4)) { file[random.nextInt(file.size)] = random.nextInt(256).toByte() }
      shouldBeIntact(file, heifWithExplicitCodestreamColour(file))
    }
  }

  // The result is the file itself, or keeps its boxes and item data and states its nclx in every parameter set.
  private fun shouldBeIntact(
    file: ByteArray,
    result: ByteArray,
  ) {
    if (result === file) return
    val nclx = pictureYcbcrCoding(file)!!
    val codestream = pictureYcbcrCoding(result)?.codestream
    codestream?.matrix shouldBe if (nclx.matrix == 2) 6 else nclx.matrix
    codestream?.fullRange shouldBe nclx.fullRange
    topLevelTypes(result) shouldBe topLevelTypes(file)
    for (data in listOf(TestHeic.SLICE, TestHeic.EXIF, TestHeic.MIME)) {
      occurrences(result, data) shouldBe occurrences(file, data)
    }
    heifWithExplicitCodestreamColour(result) shouldBeSameInstanceAs result
  }

  private fun topLevelTypes(bytes: ByteArray): List<Int> {
    val walk = ByteView(bytes, bytes.size).boxes(0, bytes.size).iterator()
    val types = mutableListOf<Int>()
    while (true) types += deferring { if (walk.hasNext()) walk.next().type else null } ?: break
    return types
  }

  private fun occurrences(
    bytes: ByteArray,
    data: ByteArray,
  ): Int = (0..bytes.size - data.size).count { at -> data.indices.all { bytes[at + it] == data[it] } }

  // Video usability information with no video signal type and the given number of bytes after it before the stop bit.
  private fun paddedVui(bytes: Int): Bits.() -> Unit =
    {
      fullVui(signal = null, tail = false).invoke(this)
      repeat(bytes) { u(8, 0xA5) }
    }

  private companion object {
    const val FUZZ_ROUNDS = 4000
    const val MAX_ONE_BYTE = 255
    const val CRAFTED_ITEMS = 1000
  }
}
