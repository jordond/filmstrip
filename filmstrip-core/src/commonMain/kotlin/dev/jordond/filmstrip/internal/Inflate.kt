/*
 * This file is adapted from puff.c in zlib's contrib/puff, altered and ported to Kotlin. The original notice follows.
 *
 * puff.h
 * Copyright (C) 2002-2013 Mark Adler, all rights reserved
 * version 2.3, 21 Jan 2013
 *
 * This software is provided 'as-is', without any express or implied
 * warranty.  In no event will the author be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 *
 * Mark Adler    madler@alumni.caltech.edu
 */

package dev.jordond.filmstrip.internal

/**
 * Inflates the zlib stream in [bytes] between [start] and [end], or returns null when it is malformed.
 *
 * Output past [limit] bytes counts as malformed. When [sizePrefixed] is set the output opens with its own length as a
 * big-endian u32. Once those four bytes are out that length becomes the limit, and the stream must inflate to exactly
 * that many bytes.
 *
 * The output starts small and doubles as it fills, so the inflated size need not be known up front.
 *
 * A preset dictionary, a bad Adler-32 checksum, and a stream that runs past [end] all return null.
 */
internal fun inflateZlib(
  bytes: ByteArray,
  start: Int,
  end: Int,
  limit: Int,
  sizePrefixed: Boolean = false,
): ByteArray? =
  try {
    val stream = ZlibStream(bytes, minOf(end, bytes.size))
    stream.checkHeader(start)
    val buffer = ByteArray(minOf(INITIAL_CAPACITY, limit))
    val reader = Inflater(stream, start + 2, buffer, limit, sizePrefixed = sizePrefixed)
    val output = reader.inflate()
    if (stream.u32(reader.position) != reader.adler) broken()
    output
  } catch (_: BrokenStream) {
    null
  }

/**
 * Inflates a PNG's image data, the zlib stream that every `IDAT` chunk's data in [bytes] makes when run together, and
 * returns its first [imageSize] bytes, or null when libpng's sequential reader refuses it.
 *
 * [chunkEnds] holds where each chunk's data ends in [bytes]. libpng reads each chunk [LIBPNG_IDAT_READ_SIZE] bytes at a
 * time and asks zlib for nothing past the last byte of the image, so it only sees what zlib meets on the way to that
 * byte and on through the rest of that read. A fault there, such as a bad Adler-32 checksum in the same read, is
 * refused, and so is a stream that ends before the image does. Once the read is used up, or zlib has a byte past the
 * image to write, libpng goes on only to check the stream ends and takes any fault it meets after that as a warning.
 * Decompressed data past the image is read, checked and dropped that way. That check stops at the end of a read that
 * wrote nothing, and data that runs out while it still has bytes to write is refused.
 *
 * The image is inflated straight into one array of [imageSize] bytes. Past it only the last 32 KB are kept, as far back
 * as a deflate distance reaches.
 */
internal fun inflatePngImage(
  bytes: ByteArray,
  chunkEnds: IntArray,
  imageSize: Int,
): ByteArray? {
  val reads = LibpngReads(readEndsOf(chunkEnds), imageSize)
  val stream = ZlibStream(bytes, bytes.size, reads)
  val image = ByteArray(imageSize)
  val inflater = Inflater(stream, 2, image, imageSize, exact = true, reads = reads)
  val accepted =
    try {
      stream.checkHeader(0)
      inflater.inflate()
      stream.u32(inflater.position) == inflater.adler || reads.checking
    } catch (_: BrokenStream) {
      reads.checking
    } catch (stopped: ReadStopped) {
      stopped.accepted
    }
  return image.takeIf { accepted }
}

private fun ZlibStream.checkHeader(start: Int) {
  val method = u8(start)
  val flags = u8(start + 1)
  val presetDictionary = flags and 0x20 != 0
  if (method and 0x0F != 8 || method shr 4 > 7 || ((method shl 8) or flags) % 31 != 0 || presetDictionary) broken()
}

private fun readEndsOf(chunkEnds: IntArray): IntArray {
  val ends = mutableListOf<Int>()
  var start = 0
  for (end in chunkEnds) {
    var at = start
    while (at < end) {
      at = minOf(at + LIBPNG_IDAT_READ_SIZE, end)
      ends += at
    }
    start = end
  }
  return ends.toIntArray()
}

// Follows libpng's calls into zlib over a PNG's image data. ROWS covers every call that still writes the image, LAST
// the rest of the call that wrote its last byte, and CHECKING the calls libpng makes after it to see the stream end.
private class LibpngReads(
  private val readEnds: IntArray,
  private val imageSize: Int,
) {
  private var read = 0
  private var phase = ROWS
  private var writtenInCall = 0
  private var written = 0L

  val checking: Boolean get() = phase == CHECKING

  fun pull(at: Int) {
    if (read < readEnds.size && at < readEnds[read]) return
    when (phase) {
      ROWS -> {
        nextRead()
      }
      LAST -> {
        phase = CHECKING
        nextRead()
      }
      else -> {
        if (written == 0L) throw ReadStopped(accepted = true)
        nextRead()
        writtenInCall = 0
      }
    }
  }

  fun emitting(
    count: Long,
    position: Int,
  ) {
    if (count < imageSize) return
    if (phase == LAST || writtenInCall == LIBPNG_CHECK_BUFFER) {
      phase = CHECKING
      if (position == readEnds[read]) nextRead()
      writtenInCall = 0
    }
    writtenInCall++
    written++
  }

  fun emitted(count: Long) {
    if (count == imageSize.toLong()) phase = LAST
  }

  private fun nextRead() {
    read++
    if (read >= readEnds.size) throw ReadStopped(accepted = false)
  }
}

private class ReadStopped(
  val accepted: Boolean,
) : Exception()

private class ZlibStream(
  private val bytes: ByteArray,
  val end: Int,
  private val reads: LibpngReads? = null,
) {
  fun u8(at: Int): Int {
    reads?.pull(at)
    if (at !in 0 until end) broken()
    return bytes[at].toInt() and 0xFF
  }

  fun u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

  fun u32(at: Int): Long =
    (((u8(at) shl 8) or u8(at + 1)).toLong() shl 16) or ((u8(at + 2) shl 8) or u8(at + 3)).toLong()
}

private class Huffman(
  lengths: IntArray,
) {
  val counts = IntArray(MAX_CODE_LENGTH + 1)
  val symbols = IntArray(lengths.size)

  init {
    for (length in lengths) counts[length]++
    val offsets = IntArray(MAX_CODE_LENGTH + 1)
    for (length in 1 until MAX_CODE_LENGTH) offsets[length + 1] = offsets[length] + counts[length]
    for ((symbol, length) in lengths.withIndex()) {
      if (length != 0) symbols[offsets[length]++] = symbol
    }
  }
}

// Inflates into output, growing it up to limit. An exact or size prefixed inflater must end on exactly limit bytes.
// With reads it follows libpng's calls into zlib, and what comes past limit is checked and dropped rather than refused.
private class Inflater(
  private val input: ZlibStream,
  var position: Int,
  private var output: ByteArray,
  private var limit: Int,
  private val sizePrefixed: Boolean = false,
  private val exact: Boolean = false,
  private val reads: LibpngReads? = null,
) {
  private var bitBuffer = 0
  private var bitCount = 0
  private var size = 0
  private var past = 0L
  private var window: ByteArray? = null
  private var adlerLow = 1L
  private var adlerHigh = 0L

  val adler: Long get() = (adlerHigh shl 16) or adlerLow

  fun inflate(): ByteArray {
    do {
      val last = bits(1) == 1
      when (bits(2)) {
        0 -> stored()
        1 -> codes(FIXED_LITERALS, FIXED_DISTANCES)
        2 -> dynamic()
        else -> broken()
      }
    } while (!last)
    if (sizePrefixed && size < SIZE_PREFIX) broken()
    if ((sizePrefixed || exact) && size != limit) broken()
    return if (size == output.size) output else output.copyOf(size)
  }

  private fun bits(count: Int): Int {
    while (bitCount < count) {
      bitBuffer = bitBuffer or (input.u8(position++) shl bitCount)
      bitCount += 8
    }
    val value = bitBuffer and ((1 shl count) - 1)
    bitBuffer = bitBuffer ushr count
    bitCount -= count
    return value
  }

  private fun emit(byte: Byte) {
    reads?.emitting(size + past, position)
    adlerLow = (adlerLow + (byte.toInt() and 0xFF)) % ADLER_MODULUS
    adlerHigh = (adlerHigh + adlerLow) % ADLER_MODULUS
    if (size == limit) {
      if (reads == null) broken()
      val window = window ?: ByteArray(WINDOW_SIZE).also { window = it }
      window[(past and WINDOW_MASK).toInt()] = byte
      past++
      return
    }
    if (size == output.size) output = output.copyOf(minOf(size * 2L, limit.toLong()).toInt())
    output[size++] = byte
    reads?.emitted(size.toLong())
    if (sizePrefixed && size == SIZE_PREFIX) {
      val declared = (0 until SIZE_PREFIX).fold(0L) { value, at -> (value shl 8) or (output[at].toLong() and 0xFF) }
      if (declared < SIZE_PREFIX || declared > limit) broken()
      limit = declared.toInt()
    }
  }

  // The byte written distance bytes back. Past the limit only the last WINDOW_SIZE bytes are kept, which is as far
  // back as a deflate distance reaches.
  private fun written(distance: Int): Byte {
    val at = size + past - distance
    if (at < 0) broken()
    return if (at < size) output[at.toInt()] else checkNotNull(window)[((at - size) and WINDOW_MASK).toInt()]
  }

  private fun stored() {
    bitBuffer = 0
    bitCount = 0
    val length = input.u16le(position)
    if (length != input.u16le(position + 2).inv() and 0xFFFF) broken()
    position += 4
    repeat(length) {
      emit(input.u8(position).toByte())
      position++
    }
  }

  private fun dynamic() {
    val literalCount = bits(5) + 257
    val distanceCount = bits(5) + 1
    val codeLengthCount = bits(4) + 4
    if (literalCount > 286 || distanceCount > 30) broken()
    val codeLengths = IntArray(CODE_LENGTH_ORDER.size)
    for (index in 0 until codeLengthCount) codeLengths[CODE_LENGTH_ORDER[index]] = bits(3)
    val codeLengthCode = Huffman(codeLengths)
    val lengths = IntArray(literalCount + distanceCount)
    var index = 0
    while (index < lengths.size) {
      val symbol = decode(codeLengthCode)
      if (symbol < 16) {
        lengths[index++] = symbol
        continue
      }
      if (symbol == 16 && index == 0) broken()
      val value = if (symbol == 16) lengths[index - 1] else 0
      val run =
        when (symbol) {
          16 -> 3 + bits(2)
          17 -> 3 + bits(3)
          else -> 11 + bits(7)
        }
      if (index + run > lengths.size) broken()
      repeat(run) { lengths[index++] = value }
    }
    if (lengths[END_OF_BLOCK] == 0) broken()
    codes(Huffman(lengths.copyOfRange(0, literalCount)), Huffman(lengths.copyOfRange(literalCount, lengths.size)))
  }

  private fun codes(
    literals: Huffman,
    distances: Huffman,
  ) {
    while (true) {
      val symbol = decode(literals)
      if (symbol < END_OF_BLOCK) {
        emit(symbol.toByte())
        continue
      }
      if (symbol == END_OF_BLOCK) return
      val lengthIndex = symbol - END_OF_BLOCK - 1
      if (lengthIndex >= LENGTH_BASE.size) broken()
      val length = LENGTH_BASE[lengthIndex] + bits(LENGTH_EXTRA[lengthIndex])
      val distanceIndex = decode(distances)
      if (distanceIndex >= DISTANCE_BASE.size) broken()
      val distance = DISTANCE_BASE[distanceIndex] + bits(DISTANCE_EXTRA[distanceIndex])
      repeat(length) { emit(written(distance)) }
    }
  }

  private fun decode(huffman: Huffman): Int {
    var code = 0
    var first = 0
    var index = 0
    for (length in 1..MAX_CODE_LENGTH) {
      code = code or bits(1)
      val count = huffman.counts[length]
      if (code - first < count) return huffman.symbols[index + code - first]
      index += count
      first = (first + count) shl 1
      code = code shl 1
    }
    broken()
  }
}

private class BrokenStream : Exception()

private fun broken(): Nothing = throw BrokenStream()

private fun deflateBases(
  first: Int,
  extraBits: IntArray,
): IntArray {
  var base = first
  return IntArray(extraBits.size) { index -> base.also { base += 1 shl extraBits[index] } }
}

/**
 * How many bytes of an `IDAT` chunk libpng hands zlib at a time.
 *
 * This is libpng's default `PNG_IDAT_READ_SIZE`, and [inflatePngImage] splits a PNG's image data the same way.
 */
internal const val LIBPNG_IDAT_READ_SIZE = 8192

// The buffer libpng inflates into once the image is out, PNG_INFLATE_BUF_SIZE.
private const val LIBPNG_CHECK_BUFFER = 1024
private const val ROWS = 0
private const val LAST = 1
private const val CHECKING = 2
private const val WINDOW_SIZE = 32768
private const val WINDOW_MASK = WINDOW_SIZE - 1L
private const val ADLER_MODULUS = 65521L
private const val INITIAL_CAPACITY = 1024
private const val SIZE_PREFIX = 4
private const val MAX_CODE_LENGTH = 15
private const val END_OF_BLOCK = 256
private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
private val LENGTH_EXTRA = IntArray(29) { if (it < 8 || it == 28) 0 else (it - 4) / 4 }
private val LENGTH_BASE = deflateBases(3, LENGTH_EXTRA).also { it[28] = 258 }
private val DISTANCE_EXTRA = IntArray(30) { maxOf(0, (it - 2) / 2) }
private val DISTANCE_BASE = deflateBases(1, DISTANCE_EXTRA)
private val FIXED_LITERALS =
  Huffman(
    IntArray(288) {
      when {
        it < 144 || it >= 280 -> 8
        it < 256 -> 9
        else -> 7
      }
    },
  )
private val FIXED_DISTANCES = Huffman(IntArray(30) { 5 })
