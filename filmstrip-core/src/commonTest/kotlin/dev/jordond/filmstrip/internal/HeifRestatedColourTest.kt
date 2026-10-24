package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.heifWithRestatedColour
import dev.jordond.filmstrip.media.ycbcrReading
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Rewrites hand-built AVIFs and HEICs through [heifWithRestatedColour], comparing each result byte for byte with the
 * file built from scratch around units stating the restated matrix and primaries beside the `nclx` or codestream's own
 * transfer and range.
 */
class HeifRestatedColourTest {
  private fun CodestreamSignal.description() = Triple(primaries!!, transfer!!, matrix!!)

  private val silentHeader = SequenceShape(fullRange = false)

  private fun paired(matrix: Int): Int = PAIRED.getValue(matrix)

  // The restated colour for a matrix a decoder cannot read, and any other matrix kept beside its paired primaries.
  private val restated: (Int, Boolean, YcbcrReading) -> RestatedColour =
    { matrix, _, _ -> RESTATED[matrix] ?: RestatedColour(matrix, paired(matrix)) }

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
  fun `an avif's nclx is stated with the primaries paired to its matrix`() {
    val stated =
      mapOf(
        Cicp(12, 13, 9, false) to CodestreamSignal(9, 13, 9, false),
        Cicp(9, 16, 1, true) to CodestreamSignal(1, 16, 1, true),
        Cicp(1, 13, 5, false) to CodestreamSignal(5, 13, 5, false),
        Cicp(1, 13, 2, true) to CodestreamSignal(6, 13, 6, true),
        Cicp(9, 16, 12, false) to CodestreamSignal(9, 16, 9, false),
      )
    for (grid in listOf(false, true)) {
      for ((nclx, signal) in stated) {
        val expected = avif(silentHeader.stating(signal), nclx, grid)
        heifWithRestatedColour(avif(silentHeader, nclx, grid), keepingMatrix(::paired)) shouldBe expected
        pictureYcbcrCoding(expected)?.codestream shouldBe signal
      }
    }
  }

  @Test
  fun `an avif without an nclx states its own signal with the paired primaries`() {
    val stated =
      mapOf(
        silentHeader to CodestreamSignal(6, 2, 6, false),
        SequenceShape(description = Triple(1, 13, 9), fullRange = true) to CodestreamSignal(9, 13, 9, true),
        SequenceShape(description = Triple(9, 1, 5), fullRange = false) to CodestreamSignal(5, 1, 5, false),
        SequenceShape(description = Triple(12, 13, 1), fullRange = false) to CodestreamSignal(1, 13, 1, false),
      )
    for (grid in listOf(false, true)) {
      for ((header, signal) in stated) {
        heifWithRestatedColour(avif(header, null, grid), keepingMatrix(::paired)) shouldBe
          avif(header.stating(signal), null, grid)
      }
      val agreeing = avif(SequenceShape(description = Triple(9, 16, 9)), null, grid)
      heifWithRestatedColour(agreeing, keepingMatrix(::paired)) shouldBeSameInstanceAs agreeing
    }
  }

  @Test
  fun `a heic's nclx is stated with the primaries paired to its matrix`() {
    val silent = SpsShape().nal()
    val stated =
      mapOf(
        Cicp(12, 13, 9, false) to CodestreamSignal(9, 13, 9, false),
        Cicp(9, 16, 1, true) to CodestreamSignal(1, 16, 1, true),
        Cicp(1, 13, 5, true) to CodestreamSignal(5, 13, 5, true),
        Cicp(1, 13, 2, false) to CodestreamSignal(6, 13, 6, false),
      )
    for (grid in listOf(false, true)) {
      for ((nclx, signal) in stated) {
        val expected = TestHeic(listOf(spsStating(silent, signal)!!), nclx, grid = grid).bytes()
        heifWithRestatedColour(TestHeic(listOf(silent), nclx, grid = grid).bytes(), keepingMatrix(::paired)) shouldBe
          expected
        pictureYcbcrCoding(expected)?.codestream shouldBe signal
      }
    }
  }

  @Test
  fun `a heic without an nclx states its own signal with the paired primaries`() {
    val stated =
      mapOf(
        CodestreamSignal(null, null, null, null) to CodestreamSignal(6, 2, 6, false),
        CodestreamSignal(1, 13, 9, false) to CodestreamSignal(9, 13, 9, false),
        CodestreamSignal(9, 1, 5, true) to CodestreamSignal(5, 1, 5, true),
        CodestreamSignal(12, 13, 1, false) to CodestreamSignal(1, 13, 1, false),
      )
    for (grid in listOf(false, true)) {
      for ((own, signal) in stated) {
        val sps = if (own.matrix == null) SpsShape().nal() else SpsShape(vui = minimalVui(own)).nal()
        val expected = TestHeic(listOf(spsStating(sps, signal)!!), nclx = null, grid = grid).bytes()
        val file = TestHeic(listOf(sps), nclx = null, grid = grid).bytes()
        heifWithRestatedColour(file, keepingMatrix(::paired)) shouldBe expected
      }
    }
  }

  @Test
  fun `a codestream's chromaticity derived matrix reaches restate as its named matrix`() {
    val named =
      mapOf(
        CodestreamSignal(1, 13, 12, false) to CodestreamSignal(1, 13, 1, false),
        CodestreamSignal(9, 16, 12, true) to CodestreamSignal(9, 16, 9, true),
        CodestreamSignal(9, 14, 13, false) to CodestreamSignal(9, 14, 10, false),
      )
    for (grid in listOf(false, true)) {
      for ((own, signal) in named) {
        val header = SequenceShape(description = own.description(), fullRange = own.fullRange == true)
        val handed = mutableListOf<Int>()
        val pick = { matrix: Int -> paired(matrix).also { handed += matrix } }
        heifWithRestatedColour(avif(header, null, grid), keepingMatrix(pick)) shouldBe
          avif(header.stating(signal), null, grid)
        val sps = SpsShape(vui = minimalVui(own)).nal()
        heifWithRestatedColour(TestHeic(listOf(sps), nclx = null, grid = grid).bytes(), keepingMatrix(pick)) shouldBe
          TestHeic(listOf(spsStating(sps, signal)!!), nclx = null, grid = grid).bytes()
        handed.toSet() shouldBe setOf(signal.matrix)
      }
    }
  }

  @Test
  fun `a derived matrix resolves to its named matrix and every other matrix is given back`() {
    resolvedMatrix(12, 1, 13) shouldBe 1
    resolvedMatrix(13, 9, 14) shouldBe 10
    resolvedMatrix(12, 12, 13) shouldBe 12
    resolvedMatrix(13, 12, 13) shouldBe 13
    resolvedMatrix(13, 9, 16) shouldBe 13
    resolvedMatrix(12, 2, 1) shouldBe 12
    for (matrix in (-1..256) - setOf(12, 13)) resolvedMatrix(matrix, 9, 14) shouldBe matrix
  }

  @Test
  fun `a chromaticity derived matrix no named matrix matches reaches restate as it is`() {
    val nclxs = listOf(Cicp(5, 6, 12, false), Cicp(6, 6, 12, true), Cicp(1, 13, 13, false))
    for (grid in listOf(false, true)) {
      for (nclx in nclxs) {
        val handed = mutableListOf<Int>()
        val pick = { matrix: Int -> EBU_3213.also { handed += matrix } }
        val signal = CodestreamSignal(EBU_3213, nclx.transfer, nclx.matrix, nclx.fullRange)
        heifWithRestatedColour(avif(silentHeader, nclx, grid), keepingMatrix(pick)) shouldBe
          avif(silentHeader.stating(signal), nclx, grid)
        val sps = SpsShape().nal()
        heifWithRestatedColour(TestHeic(listOf(sps), nclx, grid = grid).bytes(), keepingMatrix(pick)) shouldBe
          TestHeic(listOf(spsStating(sps, signal)!!), nclx, grid = grid).bytes()
        handed.toSet() shouldBe setOf(nclx.matrix)
      }
      val own = CodestreamSignal(6, 6, 12, false)
      val header = SequenceShape(description = own.description(), fullRange = false)
      val handed = mutableListOf<Int>()
      val pick = keepingMatrix { matrix -> EBU_3213.also { handed += matrix } }
      heifWithRestatedColour(avif(header, null, grid), pick) shouldBe
        avif(header.stating(own.copy(primaries = EBU_3213)), null, grid)
      handed.toSet() shouldBe setOf(12)
    }
  }

  @Test
  fun `a second pass over a stated nclx changes nothing`() {
    for (grid in listOf(false, true)) {
      for (nclx in listOf(Cicp(12, 13, 9, false), Cicp(9, 16, 12, true), Cicp(1, 13, 2, false))) {
        val avif = heifWithRestatedColour(avif(silentHeader, nclx, grid), keepingMatrix(::paired))
        heifWithRestatedColour(avif, keepingMatrix(::paired)) shouldBeSameInstanceAs avif
        val heic =
          heifWithRestatedColour(TestHeic(listOf(SpsShape().nal()), nclx, grid = grid).bytes(), keepingMatrix(::paired))
        heifWithRestatedColour(heic, keepingMatrix(::paired)) shouldBeSameInstanceAs heic
      }
    }
  }

  @Test
  fun `a primaries code point outside 0 to 255 throws`() {
    val own = CodestreamSignal(1, 13, 9, true)
    val header = SequenceShape(description = own.description(), fullRange = true)
    val sps = SpsShape(vui = minimalVui(own)).nal()
    val files =
      listOf(
        avif(silentHeader, Cicp(12, 13, 9, false), grid = false),
        avif(header, null, grid = false),
        TestHeic(listOf(sps), nclx = null).bytes(),
      )
    for (answer in listOf(-1, 256, 300)) {
      for (file in files) {
        assertFailsWith<IllegalArgumentException> { heifWithRestatedColour(file, keepingMatrix { answer }) }
      }
    }
  }

  @Test
  fun `without primaries the nclx keeps its own`() {
    val nclx = Cicp(12, 13, 9, false)
    heifWithExplicitCodestreamColour(avif(silentHeader, nclx, grid = false)) shouldBe
      avif(silentHeader.stating(CodestreamSignal(12, 13, 9, false)), nclx, grid = false)
  }

  @Test
  fun `a restated matrix is stated in every unit and the nclx is left as it was`() {
    val silent = SpsShape().nal()
    for (grid in listOf(false, true)) {
      for (nclx in listOf(Cicp(9, 16, 10, false), Cicp(9, 16, 14, true), Cicp(1, 13, 8, true), Cicp(1, 13, 5, false))) {
        val to = RESTATED.getValue(nclx.matrix)
        val signal = CodestreamSignal(to.primaries, nclx.transfer, to.matrix, nclx.fullRange)
        val avif = heifWithRestatedColour(avif(silentHeader, nclx, grid), restated)
        avif shouldBe avif(silentHeader.stating(signal), nclx, grid)
        pictureYcbcrCoding(avif)?.matrix shouldBe nclx.matrix
        pictureYcbcrCoding(avif)?.codestream shouldBe signal
        val heic = heifWithRestatedColour(TestHeic(listOf(silent), nclx, grid = grid).bytes(), restated)
        heic shouldBe TestHeic(listOf(spsStating(silent, signal)!!), nclx, grid = grid).bytes()
        pictureYcbcrCoding(heic)?.matrix shouldBe nclx.matrix
        pictureYcbcrCoding(heic)?.codestream shouldBe signal
        heifWithRestatedColour(avif, restated) shouldBeSameInstanceAs avif
        heifWithRestatedColour(heic, restated) shouldBeSameInstanceAs heic
      }
    }
  }

  @Test
  fun `a restated range is stated in every unit and the nclx keeps its own`() {
    val silent = SpsShape().nal()
    for (grid in listOf(false, true)) {
      for (nclx in listOf(Cicp(1, 13, 8, false), Cicp(9, 16, 9, true))) {
        val restate = { _: Int, full: Boolean, _: YcbcrReading -> RestatedColour(6, 1, fullRange = !full) }
        val signal = CodestreamSignal(1, nclx.transfer, 6, !nclx.fullRange)
        heifWithRestatedColour(avif(silentHeader, nclx, grid), restate) shouldBe
          avif(silentHeader.stating(signal), nclx, grid)
        heifWithRestatedColour(TestHeic(listOf(silent), nclx, grid = grid).bytes(), restate) shouldBe
          TestHeic(listOf(spsStating(silent, signal)!!), nclx, grid = grid).bytes()
      }
    }
  }

  @Test
  fun `a restated matrix is stated in every unit without an nclx`() {
    val owns =
      listOf(
        CodestreamSignal(9, 16, 10, false),
        CodestreamSignal(9, 16, 14, true),
        CodestreamSignal(1, 13, 8, true),
        CodestreamSignal(1, 13, 5, false),
      )
    for (grid in listOf(false, true)) {
      for (own in owns) {
        val to = RESTATED.getValue(own.matrix!!)
        val signal = own.copy(primaries = to.primaries, matrix = to.matrix)
        val header = SequenceShape(description = own.description(), fullRange = own.fullRange == true)
        val avif = heifWithRestatedColour(avif(header, null, grid), restated)
        avif shouldBe avif(header.stating(signal), null, grid)
        pictureYcbcrCoding(avif)?.codestream shouldBe signal
        val sps = SpsShape(vui = minimalVui(own)).nal()
        val heic = heifWithRestatedColour(TestHeic(listOf(sps), nclx = null, grid = grid).bytes(), restated)
        heic shouldBe TestHeic(listOf(spsStating(sps, signal)!!), nclx = null, grid = grid).bytes()
        pictureYcbcrCoding(heic)?.codestream shouldBe signal
      }
    }
  }

  @Test
  fun `restate is handed the range the picture lands with`() {
    for (fullRange in listOf(false, true)) {
      val handed = mutableListOf<Pair<Int, Boolean>>()
      val restate = { matrix: Int, full: Boolean, _: YcbcrReading ->
        RestatedColour(matrix, 1).also { handed += matrix to full }
      }
      val nclx = Cicp(1, 13, 1, fullRange)
      heifWithRestatedColour(avif(silentHeader, nclx, grid = false), restate)
      heifWithRestatedColour(TestHeic(listOf(SpsShape().nal()), nclx).bytes(), restate)
      val own = CodestreamSignal(1, 13, 1, fullRange)
      val header = SequenceShape(description = own.description(), fullRange = fullRange)
      heifWithRestatedColour(avif(header, null, grid = false), restate)
      heifWithRestatedColour(TestHeic(listOf(SpsShape(vui = minimalVui(own)).nal()), nclx = null).bytes(), restate)
      handed shouldBe List(4) { 1 to fullRange }
    }
    val handed = mutableListOf<Pair<Int, Boolean>>()
    heifWithRestatedColour(TestHeic(listOf(SpsShape().nal()), nclx = null).bytes()) { matrix, full, _ ->
      RestatedColour(matrix, 6).also { handed += matrix to full }
    }
    handed shouldBe listOf(6 to false)
  }

  @Test
  fun `restate is handed the reading of the picture's own matrix primaries and transfer`() {
    val pictures =
      listOf(
        Cicp(9, 14, 13, false) to ycbcrReading(13, 9, 14),
        Cicp(5, 1, 12, true) to ycbcrReading(12, 5, 1),
        Cicp(1, 13, 2, false) to ycbcrReading(6, 1, 13),
      )
    for ((nclx, reading) in pictures) {
      val handed = mutableListOf<YcbcrReading>()
      val restate = { matrix: Int, _: Boolean, given: YcbcrReading ->
        RestatedColour(matrix, 1).also { handed += given }
      }
      heifWithRestatedColour(avif(silentHeader, nclx, grid = false), restate)
      heifWithRestatedColour(TestHeic(listOf(SpsShape().nal()), nclx).bytes(), restate)
      val own = CodestreamSignal(nclx.primaries, nclx.transfer, nclx.matrix, nclx.fullRange)
      val header = SequenceShape(description = own.description(), fullRange = nclx.fullRange)
      heifWithRestatedColour(avif(header, null, grid = false), restate)
      handed shouldBe List(3) { reading }
    }
  }

  @Test
  fun `restating the handed matrix with the stated primaries matches no restate`() {
    val nclxs = listOf(Cicp(12, 13, 9, false), Cicp(9, 16, 12, true), Cicp(1, 13, 2, true), Cicp(1, 13, 5, false), null)
    for (grid in listOf(false, true)) {
      for (nclx in nclxs) {
        val keep = { matrix: Int, _: Boolean, _: YcbcrReading -> RestatedColour(matrix, nclx?.primaries ?: 2) }
        val files =
          listOf(avif(silentHeader, nclx, grid), TestHeic(listOf(SpsShape().nal()), nclx, grid = grid).bytes())
        for (file in files) {
          heifWithRestatedColour(file, keep) shouldBe heifWithExplicitCodestreamColour(file)
        }
      }
    }
  }

  @Test
  fun `a restated matrix outside 0 to 255 throws`() {
    val files =
      listOf(
        avif(silentHeader, Cicp(9, 16, 10, false), grid = false),
        TestHeic(listOf(SpsShape(vui = minimalVui(CodestreamSignal(9, 16, 10, false))).nal()), nclx = null).bytes(),
      )
    for (answer in listOf(-1, 256)) {
      for (file in files) {
        assertFailsWith<IllegalArgumentException> {
          heifWithRestatedColour(file) { _, _, _ -> RestatedColour(answer, 9) }
        }
      }
    }
  }

  @Test
  fun `restate is not called for a picture whose colour does not read`() {
    val unreadable = TestHeic(listOf(byteArrayOf(0x42, 0x01, 0x01)), nclx = null).bytes()
    pictureYcbcrCoding(unreadable)?.colourKnown shouldBe false
    heifWithRestatedColour(unreadable) { _, _, _ -> error("restate was called") } shouldBeSameInstanceAs unreadable
  }

  @Test
  fun `restate is not called for a file with a moov box`() {
    val sps = SpsShape().nal()
    for (grid in listOf(false, true)) {
      for (nclx in listOf(Cicp(9, 16, 10, false), null)) {
        val file = TestHeic(listOf(sps), nclx, grid = grid, trailing = "moov").bytes()
        heifWithRestatedColour(file) { _, _, _ -> error("restate was called") } shouldBeSameInstanceAs file
      }
    }
  }

  @Test
  fun `restate is not called for a picture with a unit that does not parse`() {
    for (grid in listOf(false, true)) {
      val file = TestHeic(listOf(byteArrayOf(0x42, 0x01, 0x01)), Cicp(9, 16, 10, false), grid = grid).bytes()
      pictureYcbcrCoding(file)?.matrix shouldBe 10
      heifWithRestatedColour(file) { _, _, _ -> error("restate was called") } shouldBeSameInstanceAs file
    }
  }

  @Test
  fun `restate is not called for a picture without a unit`() {
    for (grid in listOf(false, true)) {
      for (nclx in listOf(Cicp(9, 16, 10, false), null)) {
        val file = TestHeic(emptyList(), nclx, grid = grid).bytes()
        heifWithRestatedColour(file) { _, _, _ -> error("restate was called") } shouldBeSameInstanceAs file
      }
    }
  }

  @Test
  fun `restate is not called for an nclx matrix past 255`() {
    for (grid in listOf(false, true)) {
      for (matrix in listOf(256, 300)) {
        val nclx = Cicp(1, 13, matrix, true)
        val files =
          listOf(avif(silentHeader, nclx, grid), TestHeic(listOf(SpsShape().nal()), nclx, grid = grid).bytes())
        for (file in files) {
          pictureYcbcrCoding(file)?.colourKnown shouldBe true
          heifWithRestatedColour(file) { _, _, _ -> error("restate was called") } shouldBeSameInstanceAs file
        }
      }
    }
  }

  private companion object {
    // The primaries each matrix is read beside, BT.709 with BT.709, BT.601 with its own primaries and both BT.2020
    // matrices with BT.2020.
    val PAIRED = mapOf(1 to 1, 5 to 5, 6 to 6, 9 to 9, 10 to 9)

    // A matrix a decoder cannot read restated as one beside the primaries it reads it with, from both ends and the
    // middle of the named matrices.
    val RESTATED =
      mapOf(
        10 to RestatedColour(9, 9),
        14 to RestatedColour(9, 9),
        8 to RestatedColour(6, 6),
        5 to RestatedColour(1, 1),
      )

    // EBU Tech 3213 primaries, which no matrix here is paired with.
    const val EBU_3213 = 22
  }
}

// A restate that keeps the matrix it is handed and states the primaries pick gives for it.
internal fun keepingMatrix(pick: (matrix: Int) -> Int): (Int, Boolean, YcbcrReading) -> RestatedColour =
  { matrix, _, _ -> RestatedColour(matrix, pick(matrix)) }
