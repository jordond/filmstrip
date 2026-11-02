package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.heifWithRestatedColour
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Rewrites hand-built AVIFs and HEICs through [heifWithRestatedColour] with a restate that states its colour in the
 * `nclx` too, comparing each result byte for byte with the file built from scratch around that `nclx` and units
 * stating the same matrix, primaries and range beside the transfer each `nclx` already held.
 */
class HeifRestatedNclxTest {
  private val silentHeader = SequenceShape(fullRange = false)

  private fun avif(
    header: SequenceShape,
    nclx: Cicp?,
    grid: Boolean,
  ): ByteArray =
    TestHeic(
      emptyList(),
      nclx,
      grid = grid,
      itemType = "av01",
      configuration = box("av1C", byteArrayOf(0x81.toByte(), 0x0D, 0x0C, 0), header.obu()),
      coded = header.obu() + STAND_IN_FRAME,
    ).bytes()

  @Test
  fun `a restated nclx states the matrix primaries and range in place and keeps its transfer`() {
    val silent = SpsShape().nal()
    for (grid in listOf(false, true)) {
      for (nclx in listOf(Cicp(9, 16, 10, false), Cicp(1, 13, 8, true))) {
        val restate = { _: Int, _: Boolean, _: YcbcrReading -> RestatedColour(9, 9, fullRange = true, nclx = true) }
        val signal = CodestreamSignal(9, nclx.transfer, 9, true)
        val stated = Cicp(9, nclx.transfer, 9, true)
        val avif = heifWithRestatedColour(avif(silentHeader, nclx, grid), restate).restated
        avif shouldBe avif(silentHeader.stating(signal), stated, grid)
        pictureYcbcrCoding(avif)?.matrix shouldBe 9
        val heic = heifWithRestatedColour(TestHeic(listOf(silent), nclx, grid = grid).bytes(), restate).restated
        heic shouldBe TestHeic(listOf(spsStating(silent, signal)!!), stated, grid = grid).bytes()
        pictureYcbcrCoding(heic)?.fullRange shouldBe true
      }
    }
  }

  @Test
  fun `a restated nclx adds none to a file without one`() {
    val silent = SpsShape().nal()
    val restate = { _: Int, _: Boolean, _: YcbcrReading -> RestatedColour(9, 9, fullRange = true, nclx = true) }
    val signal = CodestreamSignal(9, 2, 9, true)
    for (grid in listOf(false, true)) {
      heifWithRestatedColour(avif(silentHeader, null, grid), restate).restated shouldBe
        avif(silentHeader.stating(signal), null, grid)
      val heic = heifWithRestatedColour(TestHeic(listOf(silent), nclx = null, grid = grid).bytes(), restate).restated
      heic shouldBe TestHeic(listOf(spsStating(silent, signal)!!), nclx = null, grid = grid).bytes()
      pictureYcbcrCoding(heic)?.codestream shouldBe signal
    }
  }

  @Test
  fun `a restated nclx leaves a thumbnail's own nclx as it was`() {
    val silent = SpsShape().nal()
    val thumbnail = Cicp(12, 13, 1, false)
    val restate = { _: Int, _: Boolean, _: YcbcrReading -> RestatedColour(9, 9, fullRange = true, nclx = true) }
    for (grid in listOf(false, true)) {
      val file = TestHeic(listOf(silent), Cicp(1, 13, 8, true), grid = grid, thumbnailNclx = thumbnail).bytes()
      val signal = CodestreamSignal(9, 13, 9, true)
      val stated = Cicp(9, 13, 9, true)
      heifWithRestatedColour(file, restate).restated shouldBe
        TestHeic(listOf(spsStating(silent, signal)!!), stated, grid = grid, thumbnailNclx = thumbnail).bytes()
    }
  }

  @Test
  fun `a restated nclx is stated in both nclx on the primary each keeping its transfer`() {
    val silent = SpsShape().nal()
    val restate = { _: Int, _: Boolean, _: YcbcrReading -> RestatedColour(9, 9, fullRange = true, nclx = true) }
    for (grid in listOf(false, true)) {
      val file = TestHeic(listOf(silent), Cicp(1, 13, 8, true), grid = grid, secondNclx = Cicp(1, 16, 5, false))
      val signal = CodestreamSignal(9, 13, 9, true)
      val restated = heifWithRestatedColour(file.bytes(), restate).restated
      restated shouldBe
        TestHeic(
          listOf(spsStating(silent, signal)!!),
          Cicp(9, 13, 9, true),
          grid = grid,
          secondNclx = Cicp(9, 16, 9, true),
        ).bytes()
      pictureYcbcrCoding(restated)?.matrix shouldBe 9
    }
  }

  @Test
  fun `a restated nclx and units already stating it give the bytes themselves`() {
    val silent = SpsShape().nal()
    for (grid in listOf(false, true)) {
      for (transfer in listOf(16, 13)) {
        val restate = { _: Int, _: Boolean, _: YcbcrReading -> RestatedColour(9, 9, fullRange = true, nclx = true) }
        val signal = CodestreamSignal(9, transfer, 9, true)
        val stated = Cicp(9, transfer, 9, true)
        val avif = avif(silentHeader.stating(signal), stated, grid)
        heifWithRestatedColour(avif, restate).restated shouldBeSameInstanceAs avif
        val heic = TestHeic(listOf(spsStating(silent, signal)!!), stated, grid = grid).bytes()
        heifWithRestatedColour(heic, restate).restated shouldBeSameInstanceAs heic
        val unitsOnly = TestHeic(listOf(silent), stated, grid = grid).bytes()
        heifWithRestatedColour(unitsOnly, restate).restated shouldBe heic
      }
    }
  }
}
