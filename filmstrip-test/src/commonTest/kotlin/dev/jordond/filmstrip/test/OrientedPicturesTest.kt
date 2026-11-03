package dev.jordond.filmstrip.test

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The orientation picture and the tags [OrientedPictures] writes, read back byte by byte.
 *
 * Each tagged file is walked to the tag it carries and the orientation in it, and every byte the tag did not need is
 * checked to be where it was.
 */
class OrientedPicturesTest {
  @Test
  fun samplesPaintFourDistinctQuadrantsAndTheMarker() {
    val samples = OrientedPictures.samples()
    val width = OrientedPictures.SIZE.width
    val middles = listOf(1 to 1, 4 to 0, 1 to 3, 4 to 2).map { (x, y) -> samples[y * width + x] }

    samples.size shouldBe OrientedPictures.SIZE.width * OrientedPictures.SIZE.height
    samples[0] shouldBe OrientedPictures.MARKER
    middles shouldBe OrientedPictures.QUADRANTS
    (OrientedPictures.QUADRANTS + OrientedPictures.MARKER).toSet().size shouldBe 5
  }

  @Test
  fun pngHoldsTheSamplesRowByRow() {
    val chunks = pngChunks(OrientedPictures.png())
    val header = chunks.first().data
    val rows = storedInflate(chunks.single { it.type == "IDAT" }.data)

    chunks.map { it.type } shouldBe listOf("IHDR", "IDAT", "IEND")
    header.int32At(0) shouldBe OrientedPictures.SIZE.width
    header.int32At(4) shouldBe OrientedPictures.SIZE.height
    header.copyOfRange(8, 13) shouldBe byteArrayOf(8, 6, 0, 0, 0)
    rows shouldBe storedRows()
    chunks.forEach { withClue("${it.type} crc") { it.crc shouldBe tableCrc32(it.type.encodeToByteArray() + it.data) } }
  }

  @Test
  fun taggedPngPutsEXifStraightAheadOfIdat() {
    val png = OrientedPictures.png()
    for (orientation in ORIENTATIONS) {
      val tagged = OrientedPictures.taggedPng(png, orientation)
      val chunks = pngChunks(tagged)
      val exif = chunks.single { it.type == "eXIf" }

      withClue("$orientation chunks") { chunks.map { it.type } shouldBe listOf("IHDR", "eXIf", "IDAT", "IEND") }
      withClue("$orientation tag") { tiffOrientation(exif.data) shouldBe orientation }
      withClue("$orientation crc") { exif.crc shouldBe tableCrc32("eXIf".encodeToByteArray() + exif.data) }
      withClue("$orientation other chunks") { tagged.withoutPngChunk("eXIf") shouldBe png }
    }
  }

  @Test
  fun taggedPngReplacesAnEXifItAlreadyHad() {
    val retagged = OrientedPictures.taggedPng(OrientedPictures.taggedPng(OrientedPictures.png(), 3), 6)
    val exif = pngChunks(retagged).filter { it.type == "eXIf" }

    exif.size shouldBe 1
    tiffOrientation(exif.single().data) shouldBe 6
  }

  @Test
  fun taggedJpegPutsExifAfterApp0() {
    val jpeg = jpegOf(app0 = true)
    for (orientation in ORIENTATIONS) {
      val tagged = OrientedPictures.taggedJpeg(jpeg, orientation)
      val segments = jpegSegments(tagged)
      val app1 = segments[1]

      withClue("$orientation order") {
        segments.map { tagged.uInt16At(it.first) } shouldBe listOf(APP0, APP1, DQT, SOF0)
      }
      withClue("$orientation header") { tagged.copyOfRange(app1.first + 4, app1.first + 10) shouldBe EXIF_HEADER }
      withClue("$orientation tag") {
        tiffOrientation(tagged.copyOfRange(app1.first + 10, app1.last + 1)) shouldBe orientation
      }
      withClue("$orientation other bytes") { tagged.without(app1) shouldBe jpeg }
    }
  }

  @Test
  fun taggedJpegPutsExifStraightAfterSoiWithoutApp0() {
    val jpeg = jpegOf(app0 = false)
    val tagged = OrientedPictures.taggedJpeg(jpeg, 5)
    val app1 = jpegSegments(tagged).first()

    app1.first shouldBe 2
    tagged.uInt16At(app1.first) shouldBe APP1
    tiffOrientation(tagged.copyOfRange(app1.first + 10, app1.last + 1)) shouldBe 5
    tagged.without(app1) shouldBe jpeg
  }

  @Test
  fun taggedJpegReplacesAnExifItAlreadyHad() {
    val retagged = OrientedPictures.taggedJpeg(OrientedPictures.taggedJpeg(jpegOf(app0 = true), 2), 7)
    val exif = jpegSegments(retagged).filter { retagged.uInt16At(it.first) == APP1 }

    exif.size shouldBe 1
    tiffOrientation(retagged.copyOfRange(exif.single().first + 10, exif.single().last + 1)) shouldBe 7
  }

  @Test
  fun taggedLosslessWebpBecomesExtendedWithItsCanvasAndAlpha() {
    val webp = webpOf(riffChunk("VP8L", vp8lPayload()))
    for (orientation in ORIENTATIONS) {
      val tagged = OrientedPictures.taggedWebp(webp, orientation)
      val chunks = riffChunks(tagged)

      withClue("$orientation chunks") { chunks.map { it.first } shouldBe listOf("VP8X", "VP8L", "EXIF") }
      withClue("$orientation RIFF size") { tagged.int32LittleEndianAt(4) shouldBe tagged.size - 8 }
      withClue("$orientation VP8X") { chunks[0].second shouldBe vp8x(VP8X_ALPHA or VP8X_EXIF) }
      withClue("$orientation VP8L") { chunks[1].second shouldBe vp8lPayload() }
      withClue("$orientation tag") { tiffOrientation(chunks[2].second) shouldBe orientation }
    }
  }

  @Test
  fun taggedLossyWebpBecomesExtendedWithItsCanvas() {
    val tagged = OrientedPictures.taggedWebp(webpOf(riffChunk("VP8 ", VP8_PAYLOAD)), 8)
    val chunks = riffChunks(tagged)

    chunks.map { it.first } shouldBe listOf("VP8X", "VP8 ", "EXIF")
    chunks[0].second shouldBe vp8x(VP8X_EXIF)
    chunks[1].second shouldBe VP8_PAYLOAD
    tiffOrientation(chunks[2].second) shouldBe 8
  }

  @Test
  fun taggedExtendedWebpKeepsItsVp8xAndReplacesItsExif() {
    val webp =
      webpOf(
        riffChunk("VP8X", vp8x(VP8X_ALPHA or VP8X_EXIF)) + riffChunk("VP8L", vp8lPayload()) +
          riffChunk("EXIF", OrientedPictures.taggedWebp(webpOf(riffChunk("VP8L", vp8lPayload())), 3).exifChunk()),
      )
    val chunks = riffChunks(OrientedPictures.taggedWebp(webp, 4))

    chunks.map { it.first } shouldBe listOf("VP8X", "VP8L", "EXIF")
    chunks[0].second shouldBe vp8x(VP8X_ALPHA or VP8X_EXIF)
    tiffOrientation(chunks[2].second) shouldBe 4
  }

  @Test
  fun tiffHoldsTheSamplesBesideItsOrientation() {
    for (orientation in ORIENTATIONS) {
      val tiff = OrientedPictures.tiff(orientation)
      val tags = tiffEntries(tiff).map { tiff.uInt16At(it) }
      val stripOffset = tiffValue(tiff, STRIP_OFFSETS)
      val stripBytes = tiffValue(tiff, STRIP_BYTE_COUNTS)

      withClue("$orientation tag order") { tags shouldBe tags.sorted() }
      withClue("$orientation tag") { tiffOrientation(tiff) shouldBe orientation }
      withClue("$orientation width") { tiffValue(tiff, IMAGE_WIDTH) shouldBe OrientedPictures.SIZE.width }
      withClue("$orientation height") { tiffValue(tiff, IMAGE_LENGTH) shouldBe OrientedPictures.SIZE.height }
      withClue("$orientation strip end") { stripOffset + stripBytes shouldBe tiff.size }
      withClue("$orientation strip") { tiff.copyOfRange(stripOffset, stripOffset + stripBytes) shouldBe storedRgba() }
    }
  }

  @Test
  fun untaggedTiffCarriesNoOrientation() {
    tiffOrientation(OrientedPictures.tiff()).shouldBeNull()
  }

  @Test
  fun taggedExtendedWebpPutsExifAheadOfXmpAndUnknownChunks() {
    val webp =
      webpOf(
        riffChunk("VP8X", vp8x(VP8X_ALPHA)) + riffChunk("ICCP", ByteArray(3)) + riffChunk("ALPH", ByteArray(2)) +
          riffChunk("VP8 ", VP8_PAYLOAD) + riffChunk("XMP ", ByteArray(5)) + riffChunk("UNKN", ByteArray(1)),
      )
    val chunks = riffChunks(OrientedPictures.taggedWebp(webp, 6))

    chunks.map { it.first } shouldBe listOf("VP8X", "ICCP", "ALPH", "VP8 ", "EXIF", "XMP ", "UNKN")
    tiffOrientation(chunks[4].second) shouldBe 6
  }

  @Test
  fun taggedLosslessWebpWithoutAlphaLeavesTheAlphaFlagClear() {
    val tagged = OrientedPictures.taggedWebp(webpOf(riffChunk("VP8L", vp8lPayload(alpha = false))), 2)

    riffChunks(tagged).first().second shouldBe vp8x(VP8X_EXIF)
  }

  @Test
  fun taggedLossyWebpWithAnAlphChunkSetsTheAlphaFlag() {
    val webp = webpOf(riffChunk("VP8 ", VP8_PAYLOAD) + riffChunk("ALPH", ByteArray(2)))
    val chunks = riffChunks(OrientedPictures.taggedWebp(webp, 2))

    chunks.map { it.first } shouldBe listOf("VP8X", "VP8 ", "ALPH", "EXIF")
    chunks.first().second shouldBe vp8x(VP8X_ALPHA or VP8X_EXIF)
  }

  @Test
  fun everyTagTakesAnyUnsignedSixteenBitOrientation() {
    val png = OrientedPictures.png()
    val jpeg = jpegOf(app0 = true)
    val webp = webpOf(riffChunk("VP8L", vp8lPayload()))
    val exif = pngChunks(OrientedPictures.taggedPng(png, 65535)).single { it.type == "eXIf" }

    tiffOrientation(OrientedPictures.tiff(65535)) shouldBe 65535
    tiffOrientation(exif.data) shouldBe 65535
    (OrientedPictures.taggedJpeg(jpeg, 65535).size > jpeg.size) shouldBe true
    tiffOrientation(OrientedPictures.taggedWebp(webp, 65535).exifChunk()) shouldBe 65535
    for (orientation in listOf(-1, 65536)) {
      assertFailsWith<IllegalArgumentException>("tiff $orientation") { OrientedPictures.tiff(orientation) }
      assertFailsWith<IllegalArgumentException>("png $orientation") { OrientedPictures.taggedPng(png, orientation) }
      assertFailsWith<IllegalArgumentException>("jpeg $orientation") { OrientedPictures.taggedJpeg(jpeg, orientation) }
      assertFailsWith<IllegalArgumentException>("webp $orientation") { OrientedPictures.taggedWebp(webp, orientation) }
    }
  }

  @Test
  fun taggedJpegRefusesBytesWithoutAStartOfImage() {
    val jpeg = jpegOf(app0 = true)

    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedJpeg(jpeg.copyOfRange(2, jpeg.size), 1) }
    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedJpeg(jpeg.copyOfRange(0, 2), 1) }
  }

  @Test
  fun taggedPngRefusesBytesWithoutASignatureOrIdat() {
    val png = OrientedPictures.png()

    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedPng(png.withoutPngChunk("IDAT"), 1) }
    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedPng(png.copyOfRange(1, png.size), 1) }
  }

  @Test
  fun taggedWebpRefusesBytesThatAreNotARiffWebp() {
    val webp = webpOf(riffChunk("VP8L", vp8lPayload()))
    val rifx = "RIFX".encodeToByteArray() + webp.copyOfRange(4, webp.size)
    val wave = webp.copyOfRange(0, 8) + "WAVE".encodeToByteArray() + webp.copyOfRange(12, webp.size)
    val alphFirst = webpOf(riffChunk("ALPH", ByteArray(2)) + riffChunk("VP8 ", VP8_PAYLOAD))

    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedWebp(rifx, 1) }
    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedWebp(wave, 1) }
    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedWebp(alphFirst, 1) }
  }

  private fun storedRgba(): ByteArray =
    OrientedPictures.samples().fold(ByteArray(0)) { bytes, texel ->
      bytes + byteArrayOf((texel shr 16).toByte(), (texel shr 8).toByte(), texel.toByte(), (texel ushr 24).toByte())
    }

  private fun storedRows(): ByteArray {
    val rgba = storedRgba()
    val stride = OrientedPictures.SIZE.width * 4
    return (0 until OrientedPictures.SIZE.height).fold(ByteArray(0)) { bytes, row ->
      bytes + byteArrayOf(0) + rgba.copyOfRange(row * stride, (row + 1) * stride)
    }
  }

  private fun ByteArray.withoutPngChunk(type: String): ByteArray {
    var offset = 8
    var out = copyOfRange(0, offset)
    while (offset < size) {
      val end = offset + int32At(offset) + 12
      if (textAt(offset + 4) != type) out += copyOfRange(offset, end)
      offset = end
    }
    return out
  }

  private fun jpegOf(app0: Boolean): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
      (if (app0) segment(APP0, JFIF) else ByteArray(0)) +
      segment(DQT, ByteArray(65) { it.toByte() }) +
      segment(SOF0, byteArrayOf(8, 0, 4, 0, 6, 1, 1, 0x11, 0)) +
      segment(SOS, byteArrayOf(1, 1, 0, 0, 63, 0)) +
      byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0, 0x56, 0xFF.toByte(), 0xD9.toByte())

  private fun segment(
    marker: Int,
    payload: ByteArray,
  ): ByteArray = int16(marker) + int16(payload.size + 2) + payload

  // Every segment between SOI and SOS, as the range of bytes it covers.
  private fun jpegSegments(jpeg: ByteArray): List<IntRange> {
    val segments = mutableListOf<IntRange>()
    var offset = 2
    while (jpeg.uInt16At(offset) != SOS) {
      val end = offset + 2 + jpeg.uInt16At(offset + 2)
      segments += offset until end
      offset = end
    }
    return segments
  }

  private fun ByteArray.without(range: IntRange): ByteArray =
    copyOfRange(0, range.first) + copyOfRange(range.last + 1, size)

  // A VP8L header for a 6x4 picture, its alpha bit set when [alpha] is, followed by an odd number of bitstream bytes.
  private fun vp8lPayload(alpha: Boolean = true): ByteArray {
    val alphaBit = if (alpha) 1 shl 28 else 0
    val header = (OrientedPictures.SIZE.width - 1) or ((OrientedPictures.SIZE.height - 1) shl 14) or alphaBit
    return byteArrayOf(0x2F) + int32LittleEndian(header) + byteArrayOf(0x01, 0x02)
  }

  private fun vp8x(flags: Int): ByteArray =
    byteArrayOf(flags.toByte(), 0, 0, 0) +
      int32LittleEndian(OrientedPictures.SIZE.width - 1).copyOf(3) +
      int32LittleEndian(OrientedPictures.SIZE.height - 1).copyOf(3)

  private fun webpOf(chunks: ByteArray): ByteArray {
    val body = "WEBP".encodeToByteArray() + chunks
    return "RIFF".encodeToByteArray() + int32LittleEndian(body.size) + body
  }

  private fun riffChunk(
    fourCc: String,
    payload: ByteArray,
  ): ByteArray = fourCc.encodeToByteArray() + int32LittleEndian(payload.size) + payload + ByteArray(payload.size % 2)

  private fun riffChunks(webp: ByteArray): List<Pair<String, ByteArray>> {
    val chunks = mutableListOf<Pair<String, ByteArray>>()
    var offset = 12
    while (offset < webp.size) {
      val size = webp.int32LittleEndianAt(offset + 4)
      chunks += webp.textAt(offset) to webp.copyOfRange(offset + 8, offset + 8 + size)
      offset += 8 + size + size % 2
    }
    withClue("RIFF chunks end with the file") { offset shouldBe webp.size }
    return chunks
  }

  private fun ByteArray.exifChunk(): ByteArray = riffChunks(this).single { it.first == "EXIF" }.second

  // Where each IFD0 entry of a big-endian TIFF starts.
  private fun tiffEntries(tiff: ByteArray): List<Int> {
    tiff.decodeToString(0, 2) shouldBe "MM"
    tiff.uInt16At(2) shouldBe 42
    val ifd = tiff.int32At(4)
    withClue("IFD0 is the only IFD") { tiff.int32At(ifd + 2 + 12 * tiff.uInt16At(ifd)) shouldBe 0 }
    return (0 until tiff.uInt16At(ifd)).map { ifd + 2 + 12 * it }
  }

  // The first value of a SHORT or LONG entry held inside the entry itself.
  private fun tiffValue(
    tiff: ByteArray,
    tag: Int,
  ): Int {
    val entry = tiffEntries(tiff).single { tiff.uInt16At(it) == tag }
    return if (tiff.uInt16At(entry + 2) == SHORT) tiff.uInt16At(entry + 8) else tiff.int32At(entry + 8)
  }

  private fun tiffOrientation(tiff: ByteArray): Int? {
    val entry = tiffEntries(tiff).singleOrNull { tiff.uInt16At(it) == ORIENTATION } ?: return null
    withClue("orientation type") { tiff.uInt16At(entry + 2) shouldBe SHORT }
    withClue("orientation count") { tiff.int32At(entry + 4) shouldBe 1 }
    return tiff.uInt16At(entry + 8)
  }

  private fun ByteArray.int32LittleEndianAt(offset: Int): Int =
    uInt8At(offset) or (uInt8At(offset + 1) shl 8) or (uInt8At(offset + 2) shl 16) or (uInt8At(offset + 3) shl 24)

  private fun int32LittleEndian(value: Int): ByteArray =
    byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

  private fun int16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

  private companion object {
    // Every value tag 274 means something for, and two it does not.
    val ORIENTATIONS = (0..9).toList()

    val EXIF_HEADER = "Exif".encodeToByteArray() + byteArrayOf(0, 0)
    val JFIF = "JFIF".encodeToByteArray() + byteArrayOf(0, 1, 2, 0, 0, 1, 0, 1, 0, 0)

    // A VP8 key frame tag, its start code and a 6x4 frame size, followed by bitstream bytes.
    val VP8_PAYLOAD = byteArrayOf(0x10, 0x02, 0x00, 0x9D.toByte(), 0x01, 0x2A, 6, 0, 4, 0, 0x01, 0x02)

    const val APP0 = 0xFFE0
    const val APP1 = 0xFFE1
    const val DQT = 0xFFDB
    const val SOF0 = 0xFFC0
    const val SOS = 0xFFDA
    const val VP8X_EXIF = 0x08
    const val VP8X_ALPHA = 0x10
    const val SHORT = 3
    const val IMAGE_WIDTH = 256
    const val IMAGE_LENGTH = 257
    const val STRIP_OFFSETS = 273
    const val ORIENTATION = 274
    const val STRIP_BYTE_COUNTS = 279
  }
}
