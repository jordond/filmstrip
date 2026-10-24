package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The samples a PNG stores, decoded with no colour conversion.
 *
 * [rgba] holds four unsigned 16-bit samples per pixel, red, green, blue then alpha, row by row from the top left. Read
 * one with `toInt() and 0xFFFF`. A sample stored in fewer than 16 bits is scaled to the full range, so an 8-bit value
 * comes back multiplied by 257 and a 2-bit value by 21845. Grey fills red, green and blue alike, a palette index takes
 * its `PLTE` entry, and alpha is opaque unless the PNG stores alpha or a `tRNS` chunk makes the pixel transparent.
 *
 * @property width the picture's width in pixels.
 * @property height the picture's height in pixels.
 * @property rgba the samples of every pixel.
 * @property sixteenBit whether the PNG stores 16-bit samples, so a sample carries more than 8 bits.
 */
@InternalFilmstripApi
public class PngSamples internal constructor(
  public val width: Int,
  public val height: Int,
  public val rgba: ShortArray,
  public val sixteenBit: Boolean,
)

/**
 * Decodes the samples the PNG in [bytes] stores, or returns null when it is malformed, unsupported or too large.
 *
 * Reads every colour type and bit depth PNG allows, interlaced or not. A palette is expanded through `PLTE`, and a
 * `tRNS` chunk gives a palette entry its alpha or makes a grey or RGB key colour transparent. Colour chunks play no
 * part, since [pictureColour] reads those.
 *
 * `IHDR`, every `IDAT` and a palette picture's `PLTE` must pass their CRC, and the file must reach `IEND`. A `PLTE` in
 * an RGB or RGBA picture is skipped. One in a grey picture is skipped the way libpng skips it, unless it fails its CRC
 * or is a second one ahead of the image data. A `tRNS` that fails its CRC, comes after the image data or does not fit
 * the colour type is skipped. A palette index past the end of `PLTE` returns null. A header whose inflated rows and
 * samples together need more than [MAX_PNG_DECODE_BYTES] returns null before either is allocated. The rows are
 * allocated once at the size the header gives them and inflated the way libpng's sequential reader inflates them, so
 * image data that inflates to less returns null, decompressed data past the rows is dropped and a bad Adler-32 checksum
 * is refused only where libpng refuses it, as [inflatePngImage] describes. Never throws.
 */
@InternalFilmstripApi
public fun pngSamples(bytes: ByteArray): PngSamples? = pngSamples(bytes, MAX_PNG_DECODE_BYTES)

/**
 * Decodes the samples the PNG in [bytes] stores the way the one-argument [pngSamples] does, holding its inflated rows
 * and decoded samples to [maxBytes] instead of [MAX_PNG_DECODE_BYTES].
 */
@InternalFilmstripApi
public fun pngSamples(
  bytes: ByteArray,
  maxBytes: Long,
): PngSamples? {
  val png = ByteView(bytes, bytes.size)
  if (!png.matches(0, PNG_SIGNATURE)) return null
  return try {
    png.readPng(maxBytes)
  } catch (_: MalformedPicture) {
    null
  }
}

/**
 * A PNG's `IHDR` fields and the sizes that follow from them.
 *
 * [decodeBytes] is what a decode holds at once, the inflated rows of every pass plus four 16-bit samples a pixel.
 */
internal class PngHeader(
  val width: Int,
  val height: Int,
  val depth: Int,
  val colourType: Int,
  val interlaced: Boolean,
) {
  val samplesPerPixel = SAMPLES_PER_PIXEL.getValue(colourType)
  val bitsPerPixel = depth * samplesPerPixel

  // The distance back to the byte a filter predicts from, a whole pixel or one byte when pixels are smaller.
  val filterStride = maxOf(1, bitsPerPixel / 8)

  fun rowBytes(pixels: Int): Int = ((pixels.toLong() * bitsPerPixel + 7) / 8).toInt()

  // The reduced images the pixels are stored in, one for a plain PNG and seven for Adam7, some of them empty.
  val passes: List<PngPass> =
    if (!interlaced) {
      listOf(PngPass(0, 0, 1, 1, width, height))
    } else {
      ADAM7.map { (x, y, xStep, yStep) ->
        PngPass(x, y, xStep, yStep, cover(width, x, xStep), cover(height, y, yStep))
      }
    }

  val imageDataSize: Long =
    passes.sumOf { if (it.width == 0 || it.height == 0) 0L else it.height * (1L + rowBytes(it.width)) }

  // The inflated rows and the decoded samples, the two buffers a decode holds at once.
  val decodeBytes: Long = imageDataSize + width.toLong() * height * RGBA16_BYTES

  private fun cover(
    size: Int,
    start: Int,
    step: Int,
  ): Int = if (size > start) (size - start + step - 1) / step else 0
}

/**
 * One reduced image of a PNG's pixels, starting at column [x] and row [y] and stepping [xStep] and [yStep] between
 * them.
 *
 * A plain PNG has one pass covering every pixel. Adam7 has seven, some of them empty.
 */
internal data class PngPass(
  val x: Int,
  val y: Int,
  val xStep: Int,
  val yStep: Int,
  val width: Int,
  val height: Int,
)

private fun ByteView.readPng(maxBytes: Long): PngSamples? {
  val headerChunk = pngChunk(PNG_SIGNATURE.size)
  if (headerChunk.type != PNG_IHDR || headerChunk.size != PNG_IHDR_SIZE || !crcHolds(headerChunk)) return null
  val header = readPngHeader(headerChunk.start, maxBytes) ?: return null
  var palette: PngChunk? = null
  var greyPalette = false
  var transparency: PngChunk? = null
  val imageData = mutableListOf<PngChunk>()
  var imageDataEnded = false
  var chunk = pngChunk(headerChunk.next)
  while (chunk.type != PNG_IEND) {
    when {
      chunk.type == PNG_IDAT -> {
        if (imageDataEnded || !crcHolds(chunk)) return null
        imageData += chunk
      }
      chunk.type == PNG_PLTE && header.colourType == PALETTE -> {
        if (palette != null || imageData.isNotEmpty() || !crcHolds(chunk)) return null
        if (chunk.size == 0 || chunk.size % 3 != 0 || chunk.size > MAX_PALETTE_ENTRIES * 3) return null
        palette = chunk
      }
      chunk.type == PNG_PLTE && header.isGrey -> {
        if (greyPalette || !crcHolds(chunk)) return null
        greyPalette = imageData.isEmpty()
      }
      chunk.type == PNG_PLTE -> {
        // An RGB picture's palette is only a suggestion, so it is skipped however broken.
      }
      chunk.type == PNG_TRNS -> {
        if (transparency == null && imageData.isEmpty() && fits(chunk, header, palette) && crcHolds(chunk)) {
          transparency = chunk
        }
      }
      chunk.isCritical -> {
        return null
      }
    }
    if (imageData.isNotEmpty() && chunk.type != PNG_IDAT) imageDataEnded = true
    chunk = pngChunk(chunk.next)
  }
  if (imageData.isEmpty() || (header.colourType == PALETTE && palette == null)) return null
  val stored = inflateImageData(imageData, header) ?: return null
  val pixels = PixelReader(this, header, palette, transparency)
  val rgba = ShortArray(header.width * header.height * 4)
  var offset = 0
  for (pass in header.passes) {
    if (pass.width == 0 || pass.height == 0) continue
    val rowBytes = header.rowBytes(pass.width)
    if (!unfilter(stored, offset, rowBytes, pass.height, header.filterStride)) return null
    for (row in 0 until pass.height) {
      val line = offset + row * (rowBytes + 1) + 1
      val y = pass.y + row * pass.yStep
      for (column in 0 until pass.width) {
        val at = (y * header.width + pass.x + column * pass.xStep) * 4
        if (!pixels.read(stored, line, column, rgba, at)) return null
      }
    }
    offset += pass.height * (rowBytes + 1)
  }
  return PngSamples(header.width, header.height, rgba, header.depth == 16)
}

/**
 * Reads the `IHDR` fields at [start], or returns null when PNG does not allow them or decoding needs more than
 * [maxBytes].
 *
 * The pixel count is held to [maxBytes] before the passes are sized, so their sums cannot overflow. Throws
 * [MalformedPicture] when the fields run past the end of the view.
 */
internal fun ByteView.readPngHeader(
  start: Int,
  maxBytes: Long,
): PngHeader? {
  val width = u32(start)
  val height = u32(start + 4)
  if (!allowsPngHeader(start) || width * height > maxBytes / RGBA16_BYTES) return null
  val header = PngHeader(width.toInt(), height.toInt(), u8(start + 8), u8(start + 9), u8(start + 12) == 1)
  return header.takeIf { it.decodeBytes <= maxBytes }
}

/**
 * Whether PNG allows the `IHDR` fields at [start]. Throws [MalformedPicture] when they run past the end of the view.
 */
internal fun ByteView.allowsPngHeader(start: Int): Boolean =
  u32(start) in 1..Int.MAX_VALUE &&
    u32(start + 4) in 1..Int.MAX_VALUE &&
    u8(start + 8) in (DEPTHS[u8(start + 9)] ?: emptySet()) &&
    u8(start + 10) == 0 &&
    u8(start + 11) == 0 &&
    u8(start + 12) <= 1

private val PngHeader.isGrey: Boolean get() = colourType == GREY || colourType == GREY_ALPHA

private fun fits(
  chunk: PngChunk,
  header: PngHeader,
  palette: PngChunk?,
): Boolean =
  when (header.colourType) {
    GREY -> chunk.size == 2
    RGB -> chunk.size == 6
    PALETTE -> palette != null && chunk.size in 1..header.paletteEntries(palette)
    else -> false
  }

// The PLTE entries an index can reach, those past 2^depth being dropped the way libpng truncates them.
private fun PngHeader.paletteEntries(palette: PngChunk): Int = minOf(palette.size / 3, 1 shl depth)

private fun ByteView.inflateImageData(
  chunks: List<PngChunk>,
  header: PngHeader,
): ByteArray? {
  val joined = ByteArray(chunks.sumOf { it.size })
  val ends = IntArray(chunks.size)
  var at = 0
  for ((index, chunk) in chunks.withIndex()) {
    at = copyInto(joined, at, chunk.start, chunk.end)
    ends[index] = at
  }
  return inflatePngImage(joined, ends, header.imageDataSize.toInt())
}

// Undoes each row's filter in place, or returns false for a filter type PNG does not define.
private fun unfilter(
  data: ByteArray,
  start: Int,
  rowBytes: Int,
  rows: Int,
  stride: Int,
): Boolean {
  for (row in 0 until rows) {
    val line = start + row * (rowBytes + 1) + 1
    val above = if (row == 0) -1 else line - rowBytes - 1
    val filter = data[line - 1].toInt()
    if (filter !in 0..4) return false
    if (filter == 0) continue
    for (i in 0 until rowBytes) {
      val a = if (i >= stride) data[line + i - stride].unsigned else 0
      val b = if (above >= 0) data[above + i].unsigned else 0
      val c = if (above >= 0 && i >= stride) data[above + i - stride].unsigned else 0
      val predicted =
        when (filter) {
          SUB -> a
          UP -> b
          AVERAGE -> (a + b) ushr 1
          else -> paeth(a, b, c)
        }
      data[line + i] = (data[line + i] + predicted).toByte()
    }
  }
  return true
}

// PNG's Paeth predictor, which breaks ties toward a, then b, then c.
private fun paeth(
  a: Int,
  b: Int,
  c: Int,
): Int {
  val estimate = a + b - c
  val fromA = abs(estimate - a)
  val fromB = abs(estimate - b)
  val fromC = abs(estimate - c)
  return when {
    fromA <= fromB && fromA <= fromC -> a
    fromB <= fromC -> b
    else -> c
  }
}

private class PixelReader(
  png: ByteView,
  private val header: PngHeader,
  palette: PngChunk?,
  transparency: PngChunk?,
) {
  private val depth = header.depth
  private val maxSample = (1 shl depth) - 1
  private val scale = FULL_SCALE / ((1 shl depth) - 1)
  private val colours = palette?.let { png.bytes.copyOfRange(it.start, it.end) }
  private val alphas =
    if (header.colourType == PALETTE) transparency?.let { png.bytes.copyOfRange(it.start, it.end) } else null
  private val key =
    if (header.colourType == PALETTE || transparency == null) {
      null
    } else {
      IntArray(transparency.size / 2) { png.u16(transparency.start + it * 2) and maxSample }
    }

  // Writes the pixel at column into rgba, or returns false for a palette index past the end of the palette.
  fun read(
    data: ByteArray,
    line: Int,
    column: Int,
    rgba: ShortArray,
    at: Int,
  ): Boolean {
    val first = column * header.samplesPerPixel
    when (header.colourType) {
      GREY -> {
        val grey = sample(data, line, first)
        rgba.put(at, grey, grey, grey, if (key?.get(0) == grey) 0 else maxSample, scale)
      }
      RGB -> {
        val red = sample(data, line, first)
        val green = sample(data, line, first + 1)
        val blue = sample(data, line, first + 2)
        val keyed = key != null && key[0] == red && key[1] == green && key[2] == blue
        rgba.put(at, red, green, blue, if (keyed) 0 else maxSample, scale)
      }
      PALETTE -> {
        val colours = colours ?: return false
        val index = sample(data, line, first)
        if (index * 3 >= colours.size) return false
        val alpha = alphas?.getOrNull(index)?.unsigned ?: 0xFF
        rgba.put(
          at,
          colours[index * 3].unsigned,
          colours[index * 3 + 1].unsigned,
          colours[index * 3 + 2].unsigned,
          alpha,
          EIGHT_BIT_SCALE,
        )
      }
      GREY_ALPHA -> {
        val grey = sample(data, line, first)
        rgba.put(at, grey, grey, grey, sample(data, line, first + 1), scale)
      }
      else -> {
        val red = sample(data, line, first)
        val green = sample(data, line, first + 1)
        val blue = sample(data, line, first + 2)
        rgba.put(at, red, green, blue, sample(data, line, first + 3), scale)
      }
    }
    return true
  }

  private fun sample(
    data: ByteArray,
    line: Int,
    index: Int,
  ): Int =
    when (depth) {
      16 -> (data[line + index * 2].unsigned shl 8) or data[line + index * 2 + 1].unsigned
      8 -> data[line + index].unsigned
      else -> (data[line + index * depth / 8].unsigned ushr (8 - depth - index * depth % 8)) and maxSample
    }
}

private fun ShortArray.put(
  at: Int,
  red: Int,
  green: Int,
  blue: Int,
  alpha: Int,
  scale: Int,
) {
  this[at] = (red * scale).toShort()
  this[at + 1] = (green * scale).toShort()
  this[at + 2] = (blue * scale).toShort()
  this[at + 3] = (alpha * scale).toShort()
}

private val Byte.unsigned: Int get() = toInt() and 0xFF

private fun abs(value: Int): Int = if (value < 0) -value else value

private const val RGBA16_BYTES = 8

/**
 * The longest side of the largest overlay picture [MAX_PNG_DECODE_BYTES] is sized to hold.
 */
internal const val MAX_PNG_OVERLAY_SIDE = 4096L

private const val GREY = 0
private const val RGB = 2
private const val PALETTE = 3
private const val GREY_ALPHA = 4
private const val RGBA = 6
private const val SUB = 1
private const val UP = 2
private const val AVERAGE = 3
private const val MAX_PALETTE_ENTRIES = 256
private const val FULL_SCALE = 65535
private const val EIGHT_BIT_SCALE = 257

private val SAMPLES_PER_PIXEL = mapOf(GREY to 1, RGB to 3, PALETTE to 1, GREY_ALPHA to 2, RGBA to 4)

private val DEPTHS =
  mapOf(
    GREY to setOf(1, 2, 4, 8, 16),
    RGB to setOf(8, 16),
    PALETTE to setOf(1, 2, 4, 8),
    GREY_ALPHA to setOf(8, 16),
    RGBA to setOf(8, 16),
  )

// Each Adam7 pass's first column, first row, column step and row step.
private val ADAM7 =
  listOf(
    listOf(0, 0, 8, 8),
    listOf(4, 0, 8, 8),
    listOf(0, 4, 4, 8),
    listOf(2, 0, 4, 4),
    listOf(0, 2, 2, 4),
    listOf(1, 0, 2, 2),
    listOf(0, 1, 1, 2),
  )

/**
 * The most bytes [pngSamples] holds for one picture's inflated rows and decoded samples together.
 *
 * It is exactly what a [MAX_PNG_OVERLAY_SIDE] square 16-bit RGBA picture needs in whichever layout costs more, each
 * stored row's filter byte and 8 bytes a pixel inflated, plus 8 bytes a pixel of samples. Adam7 stores 7680 shorter
 * rows for 4096 lines, so the interlaced picture is the larger by 3584 filter bytes and the plain one fits with that to
 * spare. Any picture needing no more fits, so a plain 8-bit RGBA one holding 12 bytes a pixel can be 4096 by 5461. A
 * header asking for more returns null before anything is allocated for it.
 */
internal val MAX_PNG_DECODE_BYTES: Long =
  listOf(false, true).maxOf { interlaced ->
    val side = MAX_PNG_OVERLAY_SIDE.toInt()
    PngHeader(side, side, depth = 16, colourType = RGBA, interlaced = interlaced).decodeBytes
  }
