package dev.jordond.filmstrip.internal

import kotlin.math.abs

/**
 * The samples a pixel of each PNG colour type stores, keyed by colour type.
 */
internal val PNG_SAMPLES_PER_PIXEL = mapOf(0 to 1, 2 to 3, 3 to 1, 4 to 2, 6 to 4)

/**
 * Every colour type and bit depth pair PNG allows.
 */
internal val PNG_FORMATS =
  listOf(0 to 1, 0 to 2, 0 to 4, 0 to 8, 0 to 16, 2 to 8, 2 to 16, 3 to 1, 3 to 2, 3 to 4, 3 to 8, 4 to 8, 4 to 16) +
    listOf(6 to 8, 6 to 16)

/**
 * Writes a PNG of [samples], each pixel's samples at [depth] in the order [colourType] stores them, row by row.
 *
 * [filter] picks the filter of each stored row, counted across every Adam7 pass when [interlaced]. The compressed image
 * data is split into `IDAT` chunks of at most [imageDataChunk] bytes.
 */
internal fun encodePng(
  width: Int,
  height: Int,
  colourType: Int,
  depth: Int,
  samples: IntArray,
  interlaced: Boolean = false,
  filter: (row: Int) -> Int = { 0 },
  palette: ByteArray? = null,
  transparency: ByteArray? = null,
  imageDataChunk: Int = Int.MAX_VALUE,
  zlib: (ByteArray) -> ByteArray = ::storedZlibBlocks,
): ByteArray {
  val imageData = pngImageData(width, height, colourType, depth, samples, interlaced, filter)
  return pngFile(width, height, colourType, depth, zlib(imageData), interlaced, palette, transparency, imageDataChunk)
}

/**
 * Wraps already filtered and compressed [compressed] image data in a PNG with the given header and chunks.
 */
internal fun pngFile(
  width: Int,
  height: Int,
  colourType: Int,
  depth: Int,
  compressed: ByteArray,
  interlaced: Boolean = false,
  palette: ByteArray? = null,
  transparency: ByteArray? = null,
  imageDataChunk: Int = Int.MAX_VALUE,
  extraChunks: List<ByteArray> = emptyList(),
): ByteArray =
  bytes {
    raw(PNG_SIGNATURE)
    pngChunk("IHDR", pngHeader(width, height, depth, colourType, if (interlaced) 1 else 0))
    extraChunks.forEach { raw(it) }
    palette?.let { pngChunk("PLTE", it) }
    transparency?.let { pngChunk("tRNS", it) }
    for (start in compressed.indices step imageDataChunk) {
      pngChunk("IDAT", compressed.copyOfRange(start, minOf(compressed.size, start + imageDataChunk)))
    }
    if (compressed.isEmpty()) pngChunk("IDAT", compressed)
    pngChunk("IEND", ByteArray(0))
  }

internal fun pngHeader(
  width: Int,
  height: Int,
  depth: Int,
  colourType: Int,
  interlace: Int,
): ByteArray =
  bytes {
    u32(width.toLong())
    u32(height.toLong())
    u8(depth)
    u8(colourType)
    u8(0)
    u8(0)
    u8(interlace)
  }

/**
 * The filtered image data of a PNG before compression, each stored row led by its filter type.
 */
internal fun pngImageData(
  width: Int,
  height: Int,
  colourType: Int,
  depth: Int,
  samples: IntArray,
  interlaced: Boolean,
  filter: (row: Int) -> Int,
): ByteArray {
  val channels = PNG_SAMPLES_PER_PIXEL.getValue(colourType)
  val stride = maxOf(1, channels * depth / 8)
  val passes = if (interlaced) TEST_ADAM7 else listOf(intArrayOf(0, 0, 1, 1))
  var storedRow = 0
  return bytes {
    for ((x0, y0, xStep, yStep) in passes) {
      val columns = (x0 until width step xStep).toList()
      val rows = (y0 until height step yStep).toList()
      if (columns.isEmpty() || rows.isEmpty()) continue
      var above: ByteArray? = null
      for (y in rows) {
        val values = columns.flatMap { x -> (0 until channels).map { samples[(y * width + x) * channels + it] } }
        val line = packRow(values, depth)
        val type = filter(storedRow++)
        u8(type)
        raw(filterRow(type, line, above, stride))
        above = line
      }
    }
  }
}

/**
 * A zlib stream of [data] in stored blocks of at most 65535 bytes.
 */
internal fun storedZlibBlocks(data: ByteArray): ByteArray =
  bytes {
    u8(0x78)
    u8(0x01)
    val starts = if (data.isEmpty()) listOf(0) else data.indices.step(MAX_STORED_BLOCK).toList()
    for ((index, start) in starts.withIndex()) {
      val block = data.copyOfRange(start, minOf(data.size, start + MAX_STORED_BLOCK))
      u8(if (index == starts.lastIndex) 1 else 0)
      u16(block.size, little = true)
      u16(block.size.inv() and 0xFFFF, little = true)
      raw(block)
    }
    u32(adler32Of(data))
  }

/**
 * Recomputes the CRC of every chunk in [png], so a test can change a chunk's bytes and still have it read.
 */
internal fun withFixedCrcs(png: ByteArray): ByteArray {
  val fixed = png.copyOf()
  var pos = 8
  while (pos + 8 <= fixed.size) {
    val length = ByteView(fixed, fixed.size).u32(pos)
    val end = pos + 8 + length
    if (length > Int.MAX_VALUE || end + 4 > fixed.size) break
    val crc = crc32Of(fixed.copyOfRange(pos + 4, end.toInt()))
    for (index in 0 until 4) fixed[end.toInt() + index] = (crc ushr (24 - index * 8)).toByte()
    pos = end.toInt() + 4
  }
  return fixed
}

private fun packRow(
  values: List<Int>,
  depth: Int,
): ByteArray =
  when (depth) {
    16 -> ByteArray(values.size * 2) { (values[it / 2] ushr if (it % 2 == 0) 8 else 0).toByte() }
    8 -> ByteArray(values.size) { values[it].toByte() }
    else -> packBits(values, depth)
  }

private fun packBits(
  values: List<Int>,
  depth: Int,
): ByteArray {
  val row = ByteArray((values.size * depth + 7) / 8)
  for ((index, value) in values.withIndex()) {
    val bit = index * depth
    row[bit / 8] = (row[bit / 8].toInt() or (value shl (8 - depth - bit % 8))).toByte()
  }
  return row
}

private fun filterRow(
  type: Int,
  line: ByteArray,
  above: ByteArray?,
  stride: Int,
): ByteArray =
  ByteArray(line.size) { i ->
    val a = if (i >= stride) line[i - stride].toInt() and 0xFF else 0
    val b = above?.let { it[i].toInt() and 0xFF } ?: 0
    val c = if (above != null && i >= stride) above[i - stride].toInt() and 0xFF else 0
    val predicted =
      when (type) {
        0 -> 0
        1 -> a
        2 -> b
        3 -> (a + b) / 2
        else -> paethPredictor(a, b, c)
      }
    (line[i] - predicted).toByte()
  }

// The Paeth predictor as the PNG spec writes it out.
private fun paethPredictor(
  a: Int,
  b: Int,
  c: Int,
): Int {
  val p = a + b - c
  val pa = abs(p - a)
  val pb = abs(p - b)
  val pc = abs(p - c)
  return when {
    pa <= pb && pa <= pc -> a
    pb <= pc -> b
    else -> c
  }
}

private const val MAX_STORED_BLOCK = 65535

private val TEST_ADAM7 =
  listOf(
    intArrayOf(0, 0, 8, 8),
    intArrayOf(4, 0, 8, 8),
    intArrayOf(0, 4, 4, 8),
    intArrayOf(2, 0, 4, 4),
    intArrayOf(0, 2, 2, 4),
    intArrayOf(1, 0, 2, 2),
    intArrayOf(0, 1, 1, 2),
  )
