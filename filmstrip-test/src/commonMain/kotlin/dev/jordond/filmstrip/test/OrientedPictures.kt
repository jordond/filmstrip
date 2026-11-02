package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.HeifStills.int32
import dev.jordond.filmstrip.test.HeifStills.uInt16

/**
 * The picture an orientation test draws, and the tags that tell a decoder to turn or mirror a picture.
 *
 * The picture is [SIZE] as stored, painted in four quadrants of distinct colours with a one texel [MARKER] in its
 * top-left corner, so each of the eight orientations displays it differently and a quarter turn swaps its width and
 * height.
 *
 * The tagging functions write a tag into bytes a test already has rather than encoding anything. A JPEG, PNG or WebP
 * gets an Exif block whose IFD0 holds only the orientation, tag 274, big-endian. A HEIF file gets `irot` and `imir`
 * properties on its primary item. [tiff] writes a whole file of the picture, since a TIFF keeps the tag in the same
 * IFD as its samples.
 */
@InternalFilmstripApi
public object OrientedPictures {
  /**
   * The picture's width and height as stored, before any orientation is applied.
   */
  public val SIZE: Size = Size(6, 4)

  /**
   * The packed ARGB colour of the stored picture's top-left texel.
   */
  public const val MARKER: Int = 0xFFF0F0F0.toInt()

  /**
   * The packed ARGB colours of the stored picture's quadrants, top-left, top-right, bottom-left then bottom-right.
   *
   * The top-left quadrant holds [MARKER] in its corner texel.
   */
  public val QUADRANTS: List<Int> =
    listOf(0xFFE02020.toInt(), 0xFF20C020.toInt(), 0xFF2040E0.toInt(), 0xFFE0E020.toInt())

  /**
   * The stored picture's texels as packed ARGB, row by row from the top-left.
   */
  public fun samples(): IntArray =
    IntArray(SIZE.width * SIZE.height) { index ->
      val right = index % SIZE.width >= SIZE.width / 2
      val bottom = index / SIZE.width >= SIZE.height / 2
      if (index == 0) MARKER else QUADRANTS[(if (bottom) 2 else 0) + (if (right) 1 else 0)]
    }

  /**
   * The stored picture as an untagged eight-bit RGBA PNG, as the bytes of the file.
   *
   * It is written the way [OverlayPixels.pngOf] writes one, unfiltered rows in stored deflate blocks.
   */
  public fun png(): ByteArray {
    val texels = samples()
    val rows = (0 until SIZE.height).fold(ByteArray(0)) { bytes, row -> bytes + rowOf(texels, row) }
    val header = int32(SIZE.width) + int32(SIZE.height) + byteArrayOf(EIGHT_BITS, RGBA, 0, 0, 0)
    return PNG_SIGNATURE +
      OverlayPixels.chunk("IHDR", header) +
      OverlayPixels.chunk("IDAT", OverlayPixels.zlibStored(rows)) +
      OverlayPixels.chunk("IEND", ByteArray(0))
  }

  /**
   * The stored picture as a big-endian, uncompressed TIFF, as the bytes of the file.
   *
   * IFD0 describes one strip of eight-bit RGBA samples with unassociated alpha. An [orientation] is written as tag 274
   * exactly as given, so a value outside 1 to 8 is written too.
   *
   * @param orientation The orientation to tag, or null for no tag at all.
   * @throws IllegalArgumentException When [orientation] does not fit an unsigned 16-bit value.
   */
  public fun tiff(orientation: Int? = null): ByteArray {
    orientation?.let(::requireOrientation)
    val texels = samples()
    val strip = texels.fold(ByteArray(0)) { bytes, texel -> bytes + rgba(texel) }

    fun entries(stripOffset: Int) =
      listOfNotNull(
        TiffEntry(IMAGE_WIDTH, SHORT, listOf(SIZE.width)),
        TiffEntry(IMAGE_LENGTH, SHORT, listOf(SIZE.height)),
        TiffEntry(BITS_PER_SAMPLE, SHORT, List(RGBA_CHANNELS) { EIGHT_BITS.toInt() }),
        TiffEntry(COMPRESSION, SHORT, listOf(UNCOMPRESSED)),
        TiffEntry(PHOTOMETRIC, SHORT, listOf(PHOTOMETRIC_RGB)),
        TiffEntry(STRIP_OFFSETS, LONG, listOf(stripOffset)),
        orientation?.let { TiffEntry(ORIENTATION, SHORT, listOf(it)) },
        TiffEntry(SAMPLES_PER_PIXEL, SHORT, listOf(RGBA_CHANNELS)),
        TiffEntry(ROWS_PER_STRIP, SHORT, listOf(SIZE.height)),
        TiffEntry(STRIP_BYTE_COUNTS, LONG, listOf(strip.size)),
        TiffEntry(PLANAR_CONFIGURATION, SHORT, listOf(CHUNKY)),
        TiffEntry(EXTRA_SAMPLES, SHORT, listOf(UNASSOCIATED_ALPHA)),
      )

    val stripOffset = TIFF_HEADER.size + ifd(entries(0)).size
    return TIFF_HEADER + ifd(entries(stripOffset)) + strip
  }

  /**
   * [jpeg] with an APP1 Exif segment tagging [orientation], as the bytes of the file.
   *
   * The segment goes straight after the start of image marker, or after an APP0 segment when one comes first. Any
   * Exif segment [jpeg] already had ahead of its scan is dropped. Every other byte is kept as it was.
   *
   * @param orientation The value of tag 274, written as given, so it may sit outside 1 to 8.
   * @throws IllegalArgumentException When [jpeg] does not start with a start of image marker, or [orientation] does
   * not fit an unsigned 16-bit value.
   */
  public fun taggedJpeg(
    jpeg: ByteArray,
    orientation: Int,
  ): ByteArray {
    requireOrientation(orientation)
    require(jpeg.size > JPEG_MARKER_BYTES && jpeg.u16(0) == SOI) { "A JPEG starts with a start of image marker." }
    val segments = mutableListOf<IntRange>()
    var offset = JPEG_MARKER_BYTES
    while (offset + JPEG_MARKER_BYTES < jpeg.size && jpeg.u16(offset) != SOS && jpeg.u16(offset) != EOI) {
      require(jpeg.u8(offset) == MARKER_PREFIX) { "A JPEG segment starts with 0xFF, but offset $offset does not." }
      val end = offset + JPEG_MARKER_BYTES + jpeg.u16(offset + JPEG_MARKER_BYTES)
      segments += offset until end
      offset = end
    }
    val app0 = segments.firstOrNull()?.takeIf { jpeg.u16(it.first) == APP0 }
    val exif = EXIF_HEADER + exifTiff(orientation)
    val app1 = uInt16(APP1) + uInt16(JPEG_MARKER_BYTES + exif.size) + exif
    val kept =
      segments.filter { it != app0 && !jpeg.isExifSegment(it) }.fold(ByteArray(0)) { bytes, segment ->
        bytes + jpeg.copyOfRange(segment.first, segment.last + 1)
      }
    return jpeg.copyOfRange(0, JPEG_MARKER_BYTES) +
      (app0?.let { jpeg.copyOfRange(it.first, it.last + 1) } ?: ByteArray(0)) +
      app1 + kept + jpeg.copyOfRange(offset, jpeg.size)
  }

  /**
   * [png] with an `eXIf` chunk tagging [orientation] straight ahead of its first `IDAT`, as the bytes of the file.
   *
   * Any `eXIf` chunk [png] already had is dropped. Every other chunk is kept as it was, in its order.
   *
   * @param orientation The value of tag 274, written as given, so it may sit outside 1 to 8.
   * @throws IllegalArgumentException When [png] has no PNG signature or no `IDAT` chunk, or [orientation] does not
   * fit an unsigned 16-bit value.
   */
  public fun taggedPng(
    png: ByteArray,
    orientation: Int,
  ): ByteArray {
    requireOrientation(orientation)
    require(png.size > PNG_SIGNATURE.size && png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
      "A PNG starts with the PNG signature."
    }
    var out = PNG_SIGNATURE
    var offset = PNG_SIGNATURE.size
    var tagged = false
    while (offset < png.size) {
      val type = png.decodeToString(offset + 4, offset + 8)
      val end = offset + png.u32(offset) + PNG_CHUNK_OVERHEAD
      if (type == "IDAT" && !tagged) {
        out += OverlayPixels.chunk("eXIf", exifTiff(orientation))
        tagged = true
      }
      if (type != "eXIf") out += png.copyOfRange(offset, end)
      offset = end
    }
    require(tagged) { "A PNG needs an IDAT chunk to put eXIf ahead of." }
    return out
  }

  /**
   * [webp] with an `EXIF` chunk tagging [orientation], as the bytes of the file.
   *
   * A simple WebP, `VP8` or `VP8L`, becomes an extended one led by a `VP8X` chunk, its canvas the bitstream's size and
   * its alpha flag set when the `VP8L` header or an `ALPH` chunk says the picture has alpha. An extended one keeps its
   * `VP8X` with the Exif flag set. Either way the `EXIF` chunk goes after the image data and ahead of any `XMP ` or
   * unknown chunk, any it already had is dropped, and every other chunk is kept as it was, in its order.
   *
   * @param orientation The value of tag 274, written as given, so it may sit outside 1 to 8.
   * @throws IllegalArgumentException When [webp] is not a RIFF WebP led by a `VP8`, `VP8L` or `VP8X` chunk, or
   * [orientation] does not fit an unsigned 16-bit value.
   */
  public fun taggedWebp(
    webp: ByteArray,
    orientation: Int,
  ): ByteArray {
    requireOrientation(orientation)
    val riff = webp.size > RIFF_HEADER_BYTES && webp.decodeToString(0, 4) == "RIFF"
    require(riff && webp.decodeToString(8, RIFF_HEADER_BYTES) == "WEBP") {
      "A WebP starts with a RIFF header naming WEBP."
    }
    val chunks = mutableListOf<RiffChunk>()
    var offset = RIFF_HEADER_BYTES
    while (offset + RIFF_CHUNK_HEADER_BYTES <= webp.size) {
      val size = webp.u32LittleEndian(offset + 4)
      val payload = webp.copyOfRange(offset + RIFF_CHUNK_HEADER_BYTES, offset + RIFF_CHUNK_HEADER_BYTES + size)
      chunks += RiffChunk(webp.decodeToString(offset, offset + 4), payload)
      offset += RIFF_CHUNK_HEADER_BYTES + size + size % 2
    }
    val first = chunks.firstOrNull()?.fourCc
    require(first == "VP8 " || first == "VP8L" || first == "VP8X") {
      "A WebP leads with VP8, VP8L or VP8X, not $first."
    }

    val kept = chunks.filter { it.fourCc != "EXIF" && it.fourCc != "VP8X" }
    val extended = chunks.first().takeIf { it.fourCc == "VP8X" }
    val vp8x = extended?.payload?.copyOf() ?: simpleCanvas(chunks)
    vp8x[0] = (vp8x[0].toInt() or VP8X_EXIF).toByte()
    val exifAt = kept.indexOfFirst { it.fourCc !in IMAGE_CHUNKS }.takeIf { it >= 0 } ?: kept.size
    val ordered = kept.take(exifAt) + RiffChunk("EXIF", exifTiff(orientation)) + kept.drop(exifAt)
    val body =
      (listOf(RiffChunk("VP8X", vp8x)) + ordered).fold("WEBP".encodeToByteArray()) { bytes, chunk ->
        bytes + chunk.bytes()
      }
    return "RIFF".encodeToByteArray() + int32LittleEndian(body.size) + body
  }

  /**
   * [heif] with `irot` and `imir` properties on its primary item, as the bytes of the file.
   *
   * The properties are appended to `ipco`, `irot` ahead of `imir`, and associated as essential at the end of the
   * primary item's `ipma` entry, so a reader applies the turn before the mirror. Every enclosing box grows to fit, and
   * each `iloc` extent that pointed past `meta` is moved by the bytes `meta` grew. Every other byte is kept as it was.
   *
   * @param quarterTurns The `irot` angle in anticlockwise quarter turns, from 0 to 3, or null for no `irot`.
   * @param mirrorAxis The `imir` axis, 0 to exchange the top and bottom rows and 1 to exchange the left and right
   * columns, as libheif, libavif and ffmpeg apply it, or null for no `imir`.
   * @throws IllegalArgumentException When both are null or out of range, when [heif] has no `meta` with `pitm`, `iloc`,
   * `ipco` and an `ipma` entry for the primary item, when that entry names a property past the end of `ipco`, or when
   * the primary item already carries `irot` or `imir`.
   */
  public fun taggedHeif(
    heif: ByteArray,
    quarterTurns: Int?,
    mirrorAxis: Int?,
  ): ByteArray = HeifOrientation.tagged(heif, quarterTurns, mirrorAxis)

  /**
   * [heif] with [properties] on its primary item, as the bytes of the file.
   *
   * The properties are appended to `ipco` in the order given and associated as essential at the end of the primary
   * item's `ipma` entry, so a reader applies them after every property the item already lists, in that order. Every
   * enclosing box grows and every `iloc` extent past `meta` moves, as [taggedHeif] does.
   *
   * @throws IllegalArgumentException When [heif] has no `meta` with `pitm`, `iloc`, `ipco` and an `ipma` entry for
   * the primary item, or when that entry names a property past the end of `ipco`.
   */
  public fun heifWithProperties(
    heif: ByteArray,
    properties: List<ByteArray>,
  ): ByteArray = HeifOrientation.withProperties(heif, properties)

  /**
   * An `irot` property turning a picture [quarterTurns] quarter turns anticlockwise, from 0 to 3.
   */
  public fun quarterTurn(quarterTurns: Int): ByteArray = HeifStills.box("irot", byteArrayOf(quarterTurns.toByte()))

  /**
   * A `clap` property keeping a [width] by [height] clean aperture, moved from the picture's centre by
   * [horizontalOffset] and [verticalOffset] over [offsetDenominator]. The width and height are whole texels.
   */
  public fun cleanAperture(
    width: Int,
    height: Int,
    horizontalOffset: Int = 0,
    verticalOffset: Int = 0,
    offsetDenominator: Int = 1,
  ): ByteArray =
    HeifStills.box(
      "clap",
      listOf(width, 1, height, 1, horizontalOffset, offsetDenominator, verticalOffset, offsetDenominator)
        .fold(ByteArray(0)) { bytes, field -> bytes + HeifStills.int32(field) },
    )

  private fun rowOf(
    texels: IntArray,
    row: Int,
  ): ByteArray =
    (0 until SIZE.width).fold(byteArrayOf(NO_FILTER)) { bytes, column ->
      bytes + rgba(texels[row * SIZE.width + column])
    }

  private fun rgba(texel: Int): ByteArray =
    byteArrayOf(
      (texel shr RED_SHIFT).toByte(),
      (texel shr GREEN_SHIFT).toByte(),
      texel.toByte(),
      (texel ushr ALPHA_SHIFT).toByte(),
    )

  private fun requireOrientation(orientation: Int) {
    require(orientation in 0..UINT16_MAX) { "Tag 274 is an unsigned 16-bit value, not $orientation." }
  }

  // A big-endian TIFF holding IFD0 with tag 274 alone, as eXIf, an Exif APP1 and a WebP EXIF chunk all carry it.
  private fun exifTiff(orientation: Int): ByteArray =
    TIFF_HEADER + ifd(listOf(TiffEntry(ORIENTATION, SHORT, listOf(orientation))))

  // IFD0 at offset 8, its entries in tag order, followed by the values too long to sit in an entry.
  private fun ifd(entries: List<TiffEntry>): ByteArray {
    val sorted = entries.sortedBy { it.tag }
    var outside = ByteArray(0)
    val tableEnd = TIFF_HEADER.size + 2 + IFD_ENTRY_BYTES * sorted.size + 4
    val table =
      sorted.fold(uInt16(sorted.size)) { bytes, entry ->
        val value =
          entry.values.fold(ByteArray(0)) { values, it -> values + if (entry.type == SHORT) uInt16(it) else int32(it) }
        val field =
          if (value.size <= 4) {
            value + ByteArray(4 - value.size)
          } else {
            int32(tableEnd + outside.size).also { outside += value }
          }
        bytes + uInt16(entry.tag) + uInt16(entry.type) + int32(entry.values.size) + field
      }
    return table + int32(0) + outside
  }

  private fun simpleCanvas(chunks: List<RiffChunk>): ByteArray {
    val bitstream = chunks.first()
    val (width, height, alpha) =
      if (bitstream.fourCc == "VP8L") {
        val header = bitstream.payload.u32LittleEndian(1)
        Triple(
          (header and VP8L_SIDE_MASK) + 1,
          (header ushr VP8L_SIDE_BITS and VP8L_SIDE_MASK) + 1,
          header ushr VP8L_ALPHA_BIT and 1 == 1,
        )
      } else {
        val payload = bitstream.payload
        Triple(
          payload.u16LittleEndian(VP8_WIDTH_OFFSET) and VP8_SIDE_MASK,
          payload.u16LittleEndian(VP8_WIDTH_OFFSET + 2) and VP8_SIDE_MASK,
          false,
        )
      }
    val flags = if (alpha || chunks.any { it.fourCc == "ALPH" }) VP8X_ALPHA else 0
    return byteArrayOf(flags.toByte(), 0, 0, 0) + int24LittleEndian(width - 1) + int24LittleEndian(height - 1)
  }

  private fun ByteArray.isExifSegment(segment: IntRange): Boolean =
    u16(segment.first) == APP1 &&
      segment.last + 1 - segment.first >= JPEG_SEGMENT_HEADER_BYTES + EXIF_HEADER.size &&
      EXIF_HEADER.indices.all { this[segment.first + JPEG_SEGMENT_HEADER_BYTES + it] == EXIF_HEADER[it] }

  private fun ByteArray.u16LittleEndian(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

  private fun ByteArray.u32LittleEndian(offset: Int): Int =
    u16LittleEndian(offset) or (u16LittleEndian(offset + 2) shl 16)

  private fun int24LittleEndian(value: Int): ByteArray =
    byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte())

  private fun int32LittleEndian(value: Int): ByteArray =
    int24LittleEndian(value) + byteArrayOf((value ushr 24).toByte())

  private class TiffEntry(
    val tag: Int,
    val type: Int,
    val values: List<Int>,
  )

  private class RiffChunk(
    val fourCc: String,
    val payload: ByteArray,
  ) {
    fun bytes(): ByteArray =
      fourCc.encodeToByteArray() + int32LittleEndian(payload.size) + payload + ByteArray(payload.size % 2)
  }

  private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  private val TIFF_HEADER = "MM".encodeToByteArray() + byteArrayOf(0, 42, 0, 0, 0, 8)
  private val EXIF_HEADER = "Exif".encodeToByteArray() + byteArrayOf(0, 0)

  // The extended WebP chunks that come ahead of EXIF, the colour profile, animation and image data.
  private val IMAGE_CHUNKS = setOf("ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L")

  private const val SOI = 0xFFD8
  private const val EOI = 0xFFD9
  private const val SOS = 0xFFDA
  private const val APP0 = 0xFFE0
  private const val APP1 = 0xFFE1
  private const val MARKER_PREFIX = 0xFF
  private const val JPEG_MARKER_BYTES = 2
  private const val JPEG_SEGMENT_HEADER_BYTES = 4

  // A chunk's length, type and checksum around its data.
  private const val PNG_CHUNK_OVERHEAD = 12
  private const val NO_FILTER: Byte = 0
  private const val RGBA: Byte = 6
  private const val EIGHT_BITS: Byte = 8
  private const val RGBA_CHANNELS = 4

  private const val RIFF_HEADER_BYTES = 12
  private const val RIFF_CHUNK_HEADER_BYTES = 8
  private const val VP8X_EXIF = 0x08
  private const val VP8X_ALPHA = 0x10
  private const val VP8L_SIDE_BITS = 14
  private const val VP8L_SIDE_MASK = 0x3FFF
  private const val VP8L_ALPHA_BIT = 28
  private const val VP8_WIDTH_OFFSET = 6
  private const val VP8_SIDE_MASK = 0x3FFF

  private const val SHORT = 3
  private const val LONG = 4
  private const val IFD_ENTRY_BYTES = 12
  private const val IMAGE_WIDTH = 256
  private const val IMAGE_LENGTH = 257
  private const val BITS_PER_SAMPLE = 258
  private const val COMPRESSION = 259
  private const val PHOTOMETRIC = 262
  private const val STRIP_OFFSETS = 273
  private const val ORIENTATION = 274
  private const val SAMPLES_PER_PIXEL = 277
  private const val ROWS_PER_STRIP = 278
  private const val STRIP_BYTE_COUNTS = 279
  private const val PLANAR_CONFIGURATION = 284
  private const val EXTRA_SAMPLES = 338
  private const val UNCOMPRESSED = 1
  private const val PHOTOMETRIC_RGB = 2
  private const val CHUNKY = 1
  private const val UNASSOCIATED_ALPHA = 2

  private const val UINT16_MAX = 0xFFFF
  private const val RED_SHIFT = 16
  private const val GREEN_SHIFT = 8
  private const val ALPHA_SHIFT = 24
}
