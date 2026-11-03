package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test

/**
 * Rewrites the sequence header of every test AVIF to state its `nclx`, then reads the result back through core's own
 * reader, walks its boxes and compares its item data with the original's bit for bit.
 */
class AvifCodestreamRewriteTest {
  private val pictures = ColorPictures.AVIFS + ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA

  @Test
  fun everyAvifWithAnNclxStatesItInItsSequenceHeader() {
    for (picture in pictures) {
      val original = picture.bytes()
      val rewritten = heifWithExplicitCodestreamColor(original).handed
      val nclx = picture.nclx
      if (nclx == null) {
        val own = CodestreamSignal(2, 2, UNSPECIFIED_MATRIX_DECODES_AS, true)
        withClue("$picture states its own signal") { pictureYcbcrCoding(rewritten)?.codestream shouldBe own }
        continue
      }
      val coding = pictureYcbcrCoding(rewritten)
      withClue("$picture codestream signal") { coding?.codestream shouldBe nclx.signal() }
      withClue("$picture nclx") {
        listOf(coding?.matrix, coding?.fullRange) shouldBe listOf(nclx.matrix, nclx.fullRange)
      }
      withClue("$picture item data") { itemData(rewritten).single() shouldBe stated(picture.codestream(), nclx) }
      withClue("$picture boxes") { heifBoxes(rewritten).map { it.type } shouldBe listOf("ftyp", "meta", "mdat") }
    }
  }

  @Test
  fun aGridRewritesEveryTileAndLeavesItsDescriptor() {
    for (picture in pictures.filter { it.nclx != null }) {
      val original = picture.gridBytes()
      val rewritten = heifWithExplicitCodestreamColor(original).handed
      rewritten shouldNotBeSameInstanceAs original
      withClue("$picture") { pictureYcbcrCoding(rewritten)?.codestream shouldBe picture.nclx?.signal() }
      val before = itemData(original)
      val after = itemData(rewritten)
      withClue("$picture grid descriptor") { after.first() shouldBe before.first() }
      for (tile in after.drop(1)) withClue("$picture") { tile shouldBe stated(picture.codestream(), picture.nclx!!) }
    }
  }

  @Test
  fun everyIdentityAvifStatesMatrixZeroAndKeepsItsFrame() {
    for (picture in AvifColorPictures.NCLX_IDENTITY_AVIFS) {
      val nclx = checkNotNull(picture.nclx)
      val frame = picture.codestream().let { it.copyOfRange(2 + it.uInt8At(1), it.size) }
      for ((original, tiled) in listOf(picture.bytes() to false, picture.gridBytes() to true)) {
        val rewritten = heifWithExplicitCodestreamColor(original).handed
        val coding = pictureYcbcrCoding(rewritten)
        val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
        withClue("$picture codestream signal") { coding?.codestream shouldBe stated }
        withClue("$picture nclx") { listOf(coding?.matrix, coding?.fullRange) shouldBe listOf(0, true) }
        val coded = if (tiled) itemData(rewritten).drop(1) else itemData(rewritten)
        if (tiled) withClue("$picture grid") { itemData(rewritten).first() shouldBe itemData(original).first() }
        for (data in coded) withClue("$picture") { data.copyOfRange(data.size - frame.size, data.size) shouldBe frame }
        withClue("$picture boxes") { heifBoxes(rewritten).map { it.type } shouldBe listOf("ftyp", "meta", "mdat") }
      }
    }
  }

  @Test
  fun aSecondRewriteChangesNothing() {
    for (picture in pictures.filter { it.nclx != null }) {
      val once = heifWithExplicitCodestreamColor(picture.bytes()).handed
      withClue("$picture") { heifWithExplicitCodestreamColor(once).handed shouldBeSameInstanceAs once }
    }
  }

  // The codestream with its sequence header stating nclx, built bit by bit from the libaom header every test AVIF
  // shares. It is a reduced still picture header whose color description flag sits at bit 32, followed by
  // color_range, the chroma sample position, separate_uv_delta_q and film_grain_params_present up to bit 38. Every bit
  // around the new description and range is the original's, and the frame after the header is copied as it was.
  private fun stated(
    codestream: ByteArray,
    nclx: CicpTag,
  ): ByteArray {
    withClue("one byte size of 5") { listOf(codestream.uInt8At(0), codestream.uInt8At(1)) shouldBe listOf(0x0A, 5) }
    val bits = (0 until TRAILING_BITS).map { codestream.uInt8At(2 + it / 8) shr (7 - it % 8) and 1 }
    withClue("trailing bits at bit 38") { (codestream.uInt8At(6) and 0x03) shouldBe 0x02 }
    withClue("no color description of its own") { bits[DESCRIPTION_FLAG] shouldBe 0 }
    val matrix = if (nclx.matrix == 2) UNSPECIFIED_MATRIX_DECODES_AS else nclx.matrix
    val codes = listOf(nclx.primaries, nclx.transfer, matrix)
    val description = listOf(1) + codes.flatMap { code -> (7 downTo 0).map { code shr it and 1 } }
    val range = listOf(if (nclx.fullRange) 1 else 0)
    val written = bits.take(DESCRIPTION_FLAG) + description + range + bits.drop(DESCRIPTION_FLAG + 2) + listOf(1)
    val aligned = written + List((8 - written.size % 8) % 8) { 0 }
    val payload = aligned.chunked(8).map { byte -> byte.fold(0) { value, bit -> value shl 1 or bit }.toByte() }
    return byteArrayOf(0x0A, payload.size.toByte()) + payload.toByteArray() + codestream.copyOfRange(7, codestream.size)
  }

  // Every item's data in iloc order, read through the version 0 iloc every HeifStills file carries.
  private fun itemData(file: ByteArray): List<ByteArray> {
    val iloc = file.childrenOf(heifBoxes(file).named("meta"), fullBox = true).named("iloc")
    return List(file.uInt16At(iloc.start + 6)) { index ->
      val entry = iloc.start + 8 + index * ILOC_ENTRY_BYTES
      val offset = file.int32At(entry + 6)
      file.copyOfRange(offset, offset + file.int32At(entry + 10))
    }
  }

  private fun CicpTag.signal(): CodestreamSignal =
    CodestreamSignal(primaries, transfer, if (matrix == 2) UNSPECIFIED_MATRIX_DECODES_AS else matrix, fullRange)

  private companion object {
    const val DESCRIPTION_FLAG = 32
    const val TRAILING_BITS = 38
    const val ILOC_ENTRY_BYTES = 14
  }
}
