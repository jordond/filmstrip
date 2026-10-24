package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Rewrites the sequence header of every test AVIF to state its `nclx`, then reads the result back through core's own
 * reader, walks its boxes and compares its item data with the original's bit for bit.
 */
class AvifCodestreamRewriteTest {
  private val pictures = ColourPictures.AVIFS + ColourPictures.NCLX_BT709_FULL_EDGE_CHROMA

  @Test
  fun everyAvifWithAnNclxStatesItInItsSequenceHeader() {
    for (picture in pictures) {
      val original = picture.bytes()
      val rewritten = heifWithExplicitCodestreamColour(original)
      val nclx = picture.nclx
      if (nclx == null) {
        val own = CodestreamSignal(2, 2, UNSPECIFIED_MATRIX_DECODES_AS, true)
        assertEquals(own, pictureYcbcrCoding(rewritten)?.codestream, "$picture states its own signal")
        continue
      }
      val coding = pictureYcbcrCoding(rewritten)
      assertEquals(nclx.signal(), coding?.codestream, "$picture codestream signal")
      assertEquals(listOf(nclx.matrix, nclx.fullRange), listOf(coding?.matrix, coding?.fullRange), "$picture nclx")
      assertContentEquals(stated(picture.codestream(), nclx), itemData(rewritten).single(), "$picture item data")
      assertEquals(listOf("ftyp", "meta", "mdat"), heifBoxes(rewritten).map { it.type }, "$picture boxes")
    }
  }

  @Test
  fun aGridRewritesEveryTileAndLeavesItsDescriptor() {
    for (picture in pictures.filter { it.nclx != null }) {
      val original = picture.gridBytes()
      val rewritten = heifWithExplicitCodestreamColour(original)
      assertNotSame(original, rewritten)
      assertEquals(picture.nclx?.signal(), pictureYcbcrCoding(rewritten)?.codestream, "$picture")
      val before = itemData(original)
      val after = itemData(rewritten)
      assertContentEquals(before.first(), after.first(), "$picture grid descriptor")
      for (tile in after.drop(1)) assertContentEquals(stated(picture.codestream(), picture.nclx!!), tile, "$picture")
    }
  }

  @Test
  fun everyIdentityAvifStatesMatrixZeroAndKeepsItsFrame() {
    for (picture in AvifColourPictures.NCLX_IDENTITY_AVIFS) {
      val nclx = checkNotNull(picture.nclx)
      val frame = picture.codestream().let { it.copyOfRange(2 + it.uInt8At(1), it.size) }
      for ((original, tiled) in listOf(picture.bytes() to false, picture.gridBytes() to true)) {
        val rewritten = heifWithExplicitCodestreamColour(original)
        val coding = pictureYcbcrCoding(rewritten)
        val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
        assertEquals(stated, coding?.codestream, "$picture codestream signal")
        assertEquals(listOf(0, true), listOf(coding?.matrix, coding?.fullRange), "$picture nclx")
        val coded = if (tiled) itemData(rewritten).drop(1) else itemData(rewritten)
        if (tiled) assertContentEquals(itemData(original).first(), itemData(rewritten).first(), "$picture grid")
        for (data in coded) assertContentEquals(frame, data.copyOfRange(data.size - frame.size, data.size), "$picture")
        assertEquals(listOf("ftyp", "meta", "mdat"), heifBoxes(rewritten).map { it.type }, "$picture boxes")
      }
    }
  }

  @Test
  fun aSecondRewriteChangesNothing() {
    for (picture in pictures.filter { it.nclx != null }) {
      val once = heifWithExplicitCodestreamColour(picture.bytes())
      assertSame(once, heifWithExplicitCodestreamColour(once), "$picture")
    }
  }

  // The codestream with its sequence header stating nclx, built bit by bit from the libaom header every test AVIF
  // shares. It is a reduced still picture header whose colour description flag sits at bit 32, followed by
  // color_range, the chroma sample position, separate_uv_delta_q and film_grain_params_present up to bit 38. Every bit
  // around the new description and range is the original's, and the frame after the header is copied as it was.
  private fun stated(
    codestream: ByteArray,
    nclx: CicpTag,
  ): ByteArray {
    assertEquals(listOf(0x0A, 5), listOf(codestream.uInt8At(0), codestream.uInt8At(1)), "one byte size of 5")
    val bits = (0 until TRAILING_BITS).map { codestream.uInt8At(2 + it / 8) shr (7 - it % 8) and 1 }
    assertEquals(0x02, codestream.uInt8At(6) and 0x03, "trailing bits at bit 38")
    assertEquals(0, bits[DESCRIPTION_FLAG], "no colour description of its own")
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
