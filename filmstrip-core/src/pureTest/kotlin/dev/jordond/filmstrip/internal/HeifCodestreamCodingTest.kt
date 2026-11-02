package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.YcbcrCoding
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Reads the codec and codestream signal of hand-built AVIFs and HEICs with and without an `nclx`, and the matrix and
 * range each lands with.
 */
class HeifCodestreamCodingTest {
  private val silentSps = SpsShape().nal()
  private val bt709LimitedSps = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, false))).nal()

  private fun avif(
    header: SequenceShape,
    nclx: Cicp? = null,
    grid: Boolean = false,
    profile: Boolean = false,
  ): ByteArray =
    TestHeic(
      emptyList(),
      nclx,
      profile = profile,
      grid = grid,
      itemType = "av01",
      configuration = box("av1C", if (header.profile == 1) AV1C_444 else AV1C_420),
      coded = header.obu() + STAND_IN_FRAME,
    ).bytes()

  // A coding of 8-bit samples, 4:2:0 unless chromaFormat says otherwise, as every file here is.
  private fun coding(
    matrix: Int,
    fullRange: Boolean,
    codec: CodecKind,
    codestream: CodestreamSignal?,
    fromNclx: Boolean = true,
    chromaFormat: Int = 1,
    nclx: Cicp? = null,
  ) = YcbcrCoding(
    matrix,
    fullRange,
    codec,
    codestream,
    fromNclx,
    bitDepth = 8,
    chromaFormat = chromaFormat,
    primaries = nclx?.primaries ?: codestream?.primaries ?: 2,
    transfer = nclx?.transfer ?: codestream?.transfer ?: 2,
  )

  private fun heic(
    sps: ByteArray,
    grid: Boolean = false,
    profile: Boolean = false,
  ): ByteArray = TestHeic(listOf(sps), nclx = null, profile = profile, grid = grid).bytes()

  @Test
  fun `a heic without an nclx lands with its codestream's own matrix and range`() {
    val silent = CodestreamSignal(null, null, null, null)
    val bt709 = CodestreamSignal(1, 13, 1, false)
    for (grid in listOf(false, true)) {
      for (profile in listOf(false, true)) {
        pictureYcbcrCoding(heic(silentSps, grid, profile)) shouldBe
          coding(2, false, CodecKind.Hevc, silent, fromNclx = false)
        pictureYcbcrCoding(heic(bt709LimitedSps, grid, profile)) shouldBe
          coding(1, false, CodecKind.Hevc, bt709, fromNclx = false)
      }
    }
    val full = SpsShape(vui = fullVui(CodestreamSignal(null, null, null, true))).nal()
    pictureYcbcrCoding(heic(full)) shouldBe
      coding(2, true, CodecKind.Hevc, CodestreamSignal(null, null, null, true), fromNclx = false)
  }

  @Test
  fun `an avif without an nclx lands with its codestream's own matrix and range`() {
    for (grid in listOf(false, true)) {
      for (profile in listOf(false, true)) {
        val limited = SequenceShape(fullRange = false)
        pictureYcbcrCoding(avif(limited, grid = grid, profile = profile)) shouldBe
          coding(2, false, CodecKind.Av1, limited.signal, fromNclx = false)
        val bt709 = SequenceShape(description = Triple(1, 13, 1), fullRange = false)
        pictureYcbcrCoding(avif(bt709, grid = grid, profile = profile)) shouldBe
          coding(1, false, CodecKind.Av1, CodestreamSignal(1, 13, 1, false), fromNclx = false)
      }
    }
  }

  @Test
  fun `an nclx still gives the matrix and range with the codestream beside it`() {
    val header = SequenceShape(description = Triple(1, 13, 1), fullRange = false)
    pictureYcbcrCoding(avif(header, Cicp(1, 13, 6, true))) shouldBe
      coding(6, true, CodecKind.Av1, CodestreamSignal(1, 13, 1, false), nclx = Cicp(1, 13, 6, true))
    val heic = TestHeic(listOf(bt709LimitedSps), nclx = Cicp(1, 13, 12, true)).bytes()
    pictureYcbcrCoding(heic) shouldBe
      coding(12, true, CodecKind.Hevc, CodestreamSignal(1, 13, 1, false), nclx = Cicp(1, 13, 12, true))
  }

  @Test
  fun `a 4 4 4 identity avif reads its nclx and has it stated in its codestream`() {
    val header = SequenceShape(profile = 1, fullRange = true)
    val file = avif(header, Cicp(1, 13, 0, true))
    val signal = CodestreamSignal(null, null, null, true)
    val coding = coding(0, true, CodecKind.Av1, signal, chromaFormat = 3, nclx = Cicp(1, 13, 0, true))
    pictureYcbcrCoding(file) shouldBe coding
    pictureColour(file).shouldBeNull()
    val stated = heifWithExplicitColour(file).handed
    pictureYcbcrCoding(stated) shouldBe coding.copy(codestream = CodestreamSignal(1, 13, 0, true))
    heifWithExplicitColour(stated).handed shouldBeSameInstanceAs stated
  }

  @Test
  fun `without an nclx a codestream that does not read still gives its codec but no depth or layout`() {
    for (grid in listOf(false, true)) {
      val hevc = pictureYcbcrCoding(heic(byteArrayOf(0x42, 0x01, 0x01), grid))
      hevc shouldBe YcbcrCoding(2, false, CodecKind.Hevc, null, fromNclx = false)
      hevc?.colourKnown shouldBe false
      val av1 = pictureYcbcrCoding(avif(SequenceShape(profile = 3), grid = grid))
      av1 shouldBe YcbcrCoding(2, false, CodecKind.Av1, null, fromNclx = false)
      av1?.colourKnown shouldBe false
    }
    val other = TestHeic(listOf(silentSps), nclx = null, itemType = "jpeg").bytes()
    pictureYcbcrCoding(other).shouldBeNull()
  }

  @Test
  fun `the colour is known from an nclx or a codestream that reads whether silent or not`() {
    val read =
      listOf(
        heic(silentSps),
        heic(bt709LimitedSps),
        avif(SequenceShape(fullRange = false)),
        avif(SequenceShape(profile = 3), Cicp(1, 13, 6, false)),
        TestHeic(listOf(byteArrayOf(0x42, 0x01, 0x01)), Cicp(9, 16, 9, true)).bytes(),
      )
    for (file in read) pictureYcbcrCoding(file)?.colourKnown shouldBe true
    coding(2, false, CodecKind.Av1, null, fromNclx = true).colourKnown shouldBe true
  }

  @Test
  fun `a truncated file without an nclx reads its whole codestream signal or none`() {
    val file = heic(bt709LimitedSps)
    for (length in file.indices) {
      val codestream = pictureYcbcrCoding(file.copyOf(length))?.codestream ?: continue
      codestream shouldBe CodestreamSignal(1, 13, 1, false)
    }
  }

  private companion object {
    // The av1C marker and version, then profile 0 with 4:2:0 samples or profile 1 with 4:4:4 ones, each at 8 bits.
    val AV1C_420 = byteArrayOf(0x81.toByte(), 0x0D, 0x0C, 0)
    val AV1C_444 = byteArrayOf(0x81.toByte(), 0x2D, 0, 0)
  }
}
