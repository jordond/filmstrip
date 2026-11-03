package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.picture.fixtures.avif
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Rewrites hand-built HEICs and AVIFs landing with the identity matrix, checking it is stated as 0 on 4:4:4 samples and
 * that every other layout is given back as it was, with or without an `nclx` and with or without paired primaries.
 */
class HeifIdentityRewriteTest {
  // A HEIC whose hvcC names chromaFormat around sps, a silent SPS of that layout unless one is given.
  private fun heic(
    chromaFormat: Int,
    nclx: Cicp?,
    grid: Boolean,
    sps: ByteArray = SpsShape(chromaFormat = chromaFormat).nal(),
  ): ByteArray {
    val hvcc = hvccOf(VPS_NAL, sps, PPS_NAL)
    hvcc[HVCC_PAYLOAD + 16] = (0xFC or chromaFormat).toByte()
    return TestHeic(listOf(sps), nclx, grid = grid, configuration = hvcc).bytes()
  }

  private fun avif(
    header: SequenceShape,
    av1c: ByteArray,
    nclx: Cicp?,
    grid: Boolean,
  ): ByteArray =
    TestHeic(
      emptyList(),
      nclx,
      grid = grid,
      itemType = "av01",
      configuration = box("av1C", av1c),
      coded = header.obu() + STAND_IN_FRAME,
    ).bytes()

  @Test
  fun `a heic's identity nclx on 4 4 4 samples is stated as 0`() {
    val sps = SpsShape(chromaFormat = 3).nal()
    for (grid in listOf(false, true)) {
      for (nclx in IDENTITY_NCLX) {
        val signal = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
        val expected = heic(3, nclx, grid, spsStating(sps, signal)!!)
        val result = heifWithExplicitCodestreamColor(heic(3, nclx, grid)).handed
        result shouldBe expected
        pictureYcbcrCoding(result)?.codestream shouldBe signal
        heifWithExplicitCodestreamColor(result).handed shouldBeSameInstanceAs result
        heifWithRestatedColor(heic(3, nclx, grid), keepingMatrix { BT2020 }).restated shouldBe
          heic(3, nclx, grid, spsStating(sps, signal.copy(primaries = BT2020))!!)
      }
    }
  }

  @Test
  fun `a heic's identity nclx on any other layout is left alone`() {
    for (grid in listOf(false, true)) {
      for (chromaFormat in 0..2) {
        for (nclx in IDENTITY_NCLX) {
          val file = heic(chromaFormat, nclx, grid)
          heifWithExplicitCodestreamColor(file).handed shouldBeSameInstanceAs file
          heifWithRestatedColor(file, keepingMatrix { BT2020 }).unrestated shouldBeSameInstanceAs file
        }
      }
    }
  }

  @Test
  fun `a heic whose hvcC says 4 4 4 around a subsampled parameter set is refused and left alone`() {
    for (grid in listOf(false, true)) {
      for (chromaFormat in 0..2) {
        for (nclx in IDENTITY_NCLX) {
          val file = heic(3, nclx, grid, SpsShape(chromaFormat = chromaFormat).nal())
          pictureYcbcrCoding(file)?.identityNot444 shouldBe true
          heifWithExplicitCodestreamColor(file).handed shouldBeSameInstanceAs file
          heifWithRestatedColor(file, keepingMatrix { BT2020 }).unrestated shouldBeSameInstanceAs file
        }
      }
    }
  }

  @Test
  fun `a codestream naming identity without an nclx is restated only on 4 4 4 samples`() {
    val own = CodestreamSignal(9, 16, 0, false)
    for (grid in listOf(false, true)) {
      val stated = SpsShape(chromaFormat = 3, vui = minimalVui(own)).nal()
      heifWithRestatedColor(heic(3, null, grid, stated), keepingMatrix { BT709 }).restated shouldBe
        heic(3, null, grid, spsStating(stated, own.copy(primaries = BT709))!!)
      for (chromaFormat in 1..2) {
        val file = heic(chromaFormat, null, grid, SpsShape(chromaFormat = chromaFormat, vui = minimalVui(own)).nal())
        heifWithRestatedColor(file, keepingMatrix { BT709 }).unrestated shouldBeSameInstanceAs file
      }
      val header = SequenceShape(description = Triple(9, 16, 0), fullRange = false)
      val subsampled = avif(header, AV1C_420, null, grid)
      heifWithRestatedColor(subsampled, keepingMatrix { BT709 }).unrestated shouldBeSameInstanceAs subsampled
    }
  }

  @Test
  fun `an avif's identity nclx on a layout that is not known is left alone`() {
    for (grid in listOf(false, true)) {
      for (av1c in listOf(VERTICAL_AV1C, SHORT_AV1C)) {
        for (nclx in IDENTITY_NCLX) {
          val file = avif(SequenceShape(profile = 1), av1c, nclx, grid)
          heifWithExplicitCodestreamColor(file).handed shouldBeSameInstanceAs file
          heifWithRestatedColor(file, keepingMatrix { BT2020 }).unrestated shouldBeSameInstanceAs file
        }
      }
    }
  }

  @Test
  fun `a restated identity matrix is stated only on 4 4 4 samples`() {
    val nclx = Cicp(9, 16, 9, false)
    val toIdentity = { _: Int, _: Boolean, _: YcbcrReading -> RestatedColor(0, BT2020) }
    val sps = SpsShape(chromaFormat = 3).nal()
    for (grid in listOf(false, true)) {
      heifWithRestatedColor(heic(3, nclx, grid), toIdentity).restated shouldBe
        heic(3, nclx, grid, spsStating(sps, CodestreamSignal(9, 16, 0, false))!!)
      for (chromaFormat in 0..2) {
        val file = heic(chromaFormat, nclx, grid)
        heifWithRestatedColor(file, toIdentity).unrestated shouldBeSameInstanceAs file
      }
      val subsampled = avif(SequenceShape(), AV1C_420, nclx, grid)
      heifWithRestatedColor(subsampled, toIdentity).unrestated shouldBeSameInstanceAs subsampled
    }
  }

  @Test
  fun `restate is not called for a picture refused for identity`() {
    for (grid in listOf(false, true)) {
      for (nclx in IDENTITY_NCLX) {
        val file = heic(1, nclx, grid)
        heifWithRestatedColor(file) { _, _, _ -> error("restate was called") }.unrestated shouldBeSameInstanceAs file
      }
    }
  }

  private companion object {
    const val HVCC_PAYLOAD = 8
    const val BT709 = 1
    const val BT2020 = 9

    val IDENTITY_NCLX = listOf(Cicp(1, 13, 0, true), Cicp(9, 16, 0, false), Cicp(2, 2, 0, true))

    // The av1C marker and version, then profile 0 with 4:2:0 samples at 8 bits.
    val AV1C_420 = byteArrayOf(0x81.toByte(), 0x0D, 0x0C, 0)

    // An av1C naming chroma subsampled vertically alone, which AV1 has no layout for, and one cut off after its
    // profile byte.
    val VERTICAL_AV1C = byteArrayOf(0x81.toByte(), 0x4D, 0x64, 0)
    val SHORT_AV1C = byteArrayOf(0x81.toByte(), 0x4D)
  }
}
