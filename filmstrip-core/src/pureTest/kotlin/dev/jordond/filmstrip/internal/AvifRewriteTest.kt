package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.random.Random
import kotlin.test.Test

/**
 * Rewrites the sequence headers of hand-built AVIFs to state their `nclx`, comparing each result byte for byte with
 * the file built from scratch around the rewritten headers, so every box size, item location and `obu_size` is checked.
 */
class AvifRewriteTest {
  private val bt709Full = Cicp(1, 13, 1, true)
  private val silent = SequenceShape()

  private fun signalOf(nclx: Cicp) = CodestreamSignal(nclx.primaries, nclx.transfer, nclx.matrix, nclx.fullRange)

  // An AVIF laid out like a TestHeic, whose av1C carries configObus and whose coded items hold inBand then a frame.
  private fun avif(
    inBand: ByteArray = silent.obu(),
    configObus: ByteArray = ByteArray(0),
    nclx: Cicp? = bt709Full,
    grid: Boolean = false,
    large: Boolean = false,
    baseOffset: Boolean = false,
    ilocVersion: Int = 1,
    codedInIdat: Boolean = false,
    splitExtents: Boolean = false,
    mono: Boolean = false,
    unsubsampled: Boolean = false,
  ): TestHeic =
    TestHeic(
      emptyList(),
      nclx,
      grid = grid,
      large = large,
      baseOffset = baseOffset,
      ilocVersion = ilocVersion,
      itemType = "av01",
      codedInIdat = codedInIdat,
      splitExtents = splitExtents,
      configuration = box("av1C", av1cFields(mono, unsubsampled), configObus),
      coded = inBand + STAND_IN_FRAME,
    )

  // The av1C marker and version, then profile 0 at level 13 with 4:2:0 samples, or profile 1 with 4:4:4 ones.
  private fun av1cFields(
    mono: Boolean,
    unsubsampled: Boolean,
  ): ByteArray =
    if (unsubsampled) {
      byteArrayOf(0x81.toByte(), 0x2D, 0, 0)
    } else {
      byteArrayOf(0x81.toByte(), 0x0D, if (mono) 0x1C else 0x0C, 0)
    }

  private fun rewritten(file: TestHeic): ByteArray = heifWithExplicitCodestreamColour(file.bytes())

  @Test
  fun `an in band sequence header is rewritten to state the nclx`() {
    for (nclx in listOf(bt709Full, Cicp(12, 13, 6, true), Cicp(9, 16, 9, false))) {
      val expected = silent.stating(signalOf(nclx)).obu()
      rewritten(avif(nclx = nclx)) shouldBe avif(expected, nclx = nclx).bytes()
    }
  }

  @Test
  fun `the rewritten file reads back with the nclx as its codestream signal`() {
    val file = avif(nclx = Cicp(12, 13, 6, true)).bytes()
    pictureYcbcrCoding(file)?.codestream shouldBe silent.signal
    pictureYcbcrCoding(heifWithExplicitCodestreamColour(file))?.codestream shouldBe CodestreamSignal(12, 13, 6, true)
  }

  @Test
  fun `config obus in the av1C and in band headers are both rewritten`() {
    val stated = silent.stating(signalOf(bt709Full))
    rewritten(avif(configObus = silent.obu())) shouldBe avif(stated.obu(), configObus = stated.obu()).bytes()
    rewritten(avif(ByteArray(0), configObus = silent.obu())) shouldBe
      avif(ByteArray(0), configObus = stated.obu()).bytes()
  }

  @Test
  fun `an av1C without a header in band still gives the codestream signal`() {
    val file = avif(ByteArray(0), configObus = SequenceShape(description = Triple(9, 16, 9)).obu()).bytes()
    pictureYcbcrCoding(file)?.codestream shouldBe CodestreamSignal(9, 16, 9, true)
  }

  @Test
  fun `every layout moves its boxes and item locations with the header`() {
    val stated = silent.stating(signalOf(bt709Full)).obu()
    val layouts =
      listOf<(ByteArray) -> TestHeic>(
        { avif(it, grid = true) },
        { avif(it, large = true, baseOffset = true) },
        { avif(it, ilocVersion = 0) },
        { avif(it, ilocVersion = 2, codedInIdat = true) },
        { avif(it, splitExtents = true) },
      )
    for (layout in layouts) rewritten(layout(silent.obu())) shouldBe layout(stated).bytes()
  }

  @Test
  fun `the obu size keeps its width or widens when the header outgrows it`() {
    val target = signalOf(bt709Full)
    rewritten(avif(silent.obu(sizeBytes = 4))) shouldBe avif(silent.stating(target).obu(sizeBytes = 4)).bytes()
    val near = SequenceShape(padding = 120)
    near.payload().size shouldBe 126
    rewritten(avif(near.obu())) shouldBe avif(near.stating(target).obu(sizeBytes = 2)).bytes()
    val extended = silent.obu(extension = true)
    rewritten(avif(extended)) shouldBe avif(silent.stating(target).obu(extension = true)).bytes()
  }

  @Test
  fun `an unsized header runs to the end of a single extent item`() {
    val unsized = silent.obu(sizeBytes = 0)
    val file = avif(ByteArray(0), configObus = unsized).bytes()
    val expected = avif(ByteArray(0), silent.stating(signalOf(bt709Full)).obu(0)).bytes()
    heifWithExplicitCodestreamColour(file) shouldBe expected
  }

  @Test
  fun `an unspecified matrix is written as bt601 and a wide code point as unspecified`() {
    val unspecified = Cicp(2, 2, 2, true)
    rewritten(avif(nclx = unspecified)) shouldBe
      avif(silent.stating(CodestreamSignal(2, 2, 6, true)).obu(), nclx = unspecified).bytes()
    val wide = Cicp(300, 13, 1, false)
    rewritten(avif(nclx = wide)) shouldBe
      avif(silent.stating(CodestreamSignal(2, 13, 1, false)).obu(), nclx = wide).bytes()
  }

  @Test
  fun `a monochrome header states the nclx too`() {
    val mono = SequenceShape(mono = true, fullRange = false)
    rewritten(avif(mono.obu(), mono = true)) shouldBe
      avif(mono.stating(signalOf(bt709Full)).obu(), mono = true).bytes()
  }

  @Test
  fun `an identity nclx on unsubsampled samples is stated as 0`() {
    val own = SequenceShape(profile = 1)
    for (nclx in listOf(Cicp(1, 13, 0, true), Cicp(9, 16, 0, false), Cicp(2, 2, 0, true))) {
      val stated = own.stating(signalOf(nclx)).obu()
      val result = rewritten(avif(own.obu(), configObus = own.obu(), nclx = nclx, unsubsampled = true))
      result shouldBe avif(stated, configObus = stated, nclx = nclx, unsubsampled = true).bytes()
      pictureYcbcrCoding(result)?.codestream shouldBe signalOf(nclx)
      heifWithExplicitCodestreamColour(result) shouldBeSameInstanceAs result
      rewritten(avif(own.obu(), nclx = nclx, grid = true, unsubsampled = true)) shouldBe
        avif(stated, nclx = nclx, grid = true, unsubsampled = true).bytes()
    }
  }

  @Test
  fun `any other nclx matrix on unsubsampled samples is stated as on subsampled ones`() {
    val own = SequenceShape(profile = 1, description = Triple(1, 13, 0))
    val stated = mapOf(Cicp(1, 13, 1, false) to 1, Cicp(9, 16, 9, true) to 9, Cicp(1, 13, 2, true) to 6)
    for ((nclx, matrix) in stated) {
      val expected = own.stating(CodestreamSignal(nclx.primaries, nclx.transfer, matrix, nclx.fullRange)).obu()
      rewritten(avif(own.obu(), nclx = nclx, unsubsampled = true)) shouldBe
        avif(expected, nclx = nclx, unsubsampled = true).bytes()
    }
  }

  @Test
  fun `a header already naming identity takes the nclx primaries and transfer`() {
    val srgb = SequenceShape(profile = 1, description = Triple(1, 13, 0))
    for (nclx in listOf(Cicp(2, 2, 0, true), Cicp(9, 16, 0, true), Cicp(2, 2, 0, false))) {
      val stated = srgb.stating(signalOf(nclx)).obu()
      rewritten(avif(srgb.obu(), configObus = srgb.obu(), nclx = nclx, unsubsampled = true)) shouldBe
        avif(stated, configObus = stated, nclx = nclx, unsubsampled = true).bytes()
    }
  }

  @Test
  fun `an identity nclx that AV1 cannot signal is left alone`() {
    val unsignalled =
      listOf(
        avif(nclx = Cicp(9, 16, 0, true)),
        avif(SequenceShape(profile = 1).obu(), nclx = Cicp(1, 13, 0, false), unsubsampled = true),
        avif(SequenceShape(mono = true).obu(), nclx = Cicp(2, 2, 0, true), mono = true),
      ).map { it.bytes() }
    for (file in unsignalled) heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
  }

  @Test
  fun `without an nclx a silent header states its own range with BT601`() {
    for (fullRange in listOf(true, false)) {
      val own = SequenceShape(fullRange = fullRange)
      val expected = own.stating(CodestreamSignal(2, 2, 6, fullRange)).obu()
      rewritten(avif(own.obu(), nclx = null)) shouldBe avif(expected, nclx = null).bytes()
      rewritten(avif(own.obu(), nclx = null, grid = true)) shouldBe avif(expected, nclx = null, grid = true).bytes()
    }
  }

  @Test
  fun `without an nclx an unspecified matrix is stated as BT601 and the rest kept`() {
    val unspecified = SequenceShape(description = Triple(1, 13, 2), fullRange = false)
    val expected = unspecified.stating(CodestreamSignal(1, 13, 6, false)).obu()
    val result = rewritten(avif(unspecified.obu(), nclx = null))
    result shouldBe avif(expected, nclx = null).bytes()
    pictureYcbcrCoding(result)?.codestream shouldBe CodestreamSignal(1, 13, 6, false)
    heifWithExplicitCodestreamColour(result) shouldBeSameInstanceAs result
  }

  @Test
  fun `without an nclx a header that names its matrix is left alone`() {
    val named = listOf(Triple(1, 13, 1), Triple(9, 16, 9), Triple(1, 13, 0), Triple(1, 13, 12))
    for (description in named) {
      val file = avif(SequenceShape(description = description, fullRange = false).obu(), nclx = null).bytes()
      heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
    }
    val identity = SequenceShape(profile = 1, description = Triple(9, 16, 0), fullRange = false)
    val file = avif(identity.obu(), nclx = null, unsubsampled = true).bytes()
    heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
  }

  @Test
  fun `a chromaticity derived nclx matrix is stated as its named matrix`() {
    val named = mapOf(Cicp(1, 13, 12, false) to 1, Cicp(9, 16, 12, false) to 9, Cicp(9, 14, 13, false) to 10)
    for ((nclx, matrix) in named) {
      val expected = silent.stating(CodestreamSignal(nclx.primaries, nclx.transfer, matrix, false)).obu()
      rewritten(avif(nclx = nclx)) shouldBe avif(expected, nclx = nclx).bytes()
    }
    for (nclx in listOf(Cicp(5, 6, 12, false), Cicp(6, 6, 12, false), Cicp(1, 13, 13, false))) {
      rewritten(avif(nclx = nclx)) shouldBe avif(silent.stating(signalOf(nclx)).obu(), nclx = nclx).bytes()
    }
  }

  @Test
  fun `the bit depth comes from the sequence header when the av1C of the primary item or its first tile agrees`() {
    val twelve = SequenceShape(2, highBitDepth = true, twelveBit = true, subsampling = 0 to 0)
    val depths =
      listOf(
        Triple(byteArrayOf(0x0D, 0x0C), silent, 8),
        Triple(byteArrayOf(0x0D, 0x4C), SequenceShape(highBitDepth = true), 10),
        Triple(byteArrayOf(0x4D, 0x60), twelve, 12),
      )
    for ((_, header, depth) in depths) {
      for (grid in listOf(false, true)) {
        for ((fields, _, stated) in depths) {
          val av1c = box("av1C", byteArrayOf(0x81.toByte()) + fields + byteArrayOf(0))
          val file = TestHeic(emptyList(), grid = grid, itemType = "av01", configuration = av1c, coded = header.obu())
          val read = pictureYcbcrCoding(file.bytes())?.bitDepth
          if (stated == depth) read shouldBe depth else read.shouldBeNull()
        }
      }
    }
    pictureYcbcrCoding(avif(SequenceShape(profile = 1).obu(), unsubsampled = true).bytes())?.bitDepth shouldBe 8
    val bytes = avif().bytes()
    for (cut in bytes.indices) (pictureYcbcrCoding(bytes.copyOf(cut))?.bitDepth ?: 8) shouldBe 8
    pictureYcbcrCoding(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)).shouldBeNull()
  }

  @Test
  fun `files left as they were`() {
    val unchanged =
      listOf(
        avif(nclx = Cicp(1, 13, 0, true)),
        avif(nclx = Cicp(1, 13, 0, false)),
        avif(SequenceShape(description = Triple(1, 13, 1)).obu()),
        avif(SequenceShape(profile = 3).obu()),
        avif(ByteArray(0)),
      ).map { it.bytes() }
    for (file in unchanged) heifWithExplicitCodestreamColour(file) shouldBeSameInstanceAs file
    val splitUnsized = avif(silent.obu(sizeBytes = 0), splitExtents = true).bytes()
    heifWithExplicitCodestreamColour(splitUnsized) shouldBeSameInstanceAs splitUnsized
  }

  @Test
  fun `a second rewrite changes nothing`() {
    val once = rewritten(avif(configObus = silent.obu(), grid = true))
    heifWithExplicitCodestreamColour(once) shouldBeSameInstanceAs once
  }

  @Test
  fun `every truncation comes back as the input`() {
    val whole = avif(configObus = silent.obu(), grid = true, baseOffset = true).bytes()
    heifWithExplicitCodestreamColour(whole) shouldNotBe whole
    for (size in 0 until whole.size) {
      val truncated = whole.copyOf(size)
      heifWithExplicitCodestreamColour(truncated) shouldBeSameInstanceAs truncated
    }
  }

  @Test
  fun `seeded corruption comes back without throwing`() {
    val random = Random(59)
    val files =
      listOf(
        avif(configObus = silent.obu(), grid = true),
        avif(SequenceShape(reduced = false, timing = true, decoderModel = true).obu(), large = true, ilocVersion = 2),
      ).map { it.bytes() }
    repeat(FUZZ_ROUNDS) { round ->
      val file = files[round % files.size].copyOf()
      repeat(1 + random.nextInt(4)) { file[random.nextInt(file.size)] = random.nextInt(256).toByte() }
      shouldBeIntact(file, heifWithExplicitCodestreamColour(file))
    }
  }

  // The result is the file itself, or keeps its boxes and item data and states its nclx in every sequence header.
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
    for (data in listOf(STAND_IN_FRAME, TestHeic.EXIF, TestHeic.MIME)) {
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

  private companion object {
    const val FUZZ_ROUNDS = 4000
  }
}
