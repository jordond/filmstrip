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

package dev.jordond.filmstrip.picture.png

// Inflates into output, growing it up to limit. An exact or size prefixed inflater must end on exactly limit bytes.
// With reads it follows libpng's calls into zlib, and what comes past limit is checked and dropped rather than refused.
// Input is held in bitBuffer. It is taken early only from bytes libpng's current read already holds, and past those a
// byte at a time when a code or extra bits need it, since how far the stream is read decides what libpng's reads
// refuse. position is how far the stream has been read as far as that goes, whole bytes still in bitBuffer not counted.
internal class Inflater(
  private val input: ZlibStream,
  private var fetched: Int,
  private var output: ByteArray,
  private var limit: Int,
  private val sizePrefixed: Boolean = false,
  private val exact: Boolean = false,
  private val reads: LibpngReads? = null,
) {
  private var bitBuffer = 0
  private var bitCount = 0
  private var size = 0

  // How many bytes were dropped past limit, up to WINDOW_SIZE, and where the next one goes in the window.
  private var past = 0
  private var windowAt = 0
  private var window: ByteArray? = null
  private var adlerLow = 1
  private var adlerHigh = 0
  private var adlerFrom = 0

  // Output below this size can be written without any of the checks emit makes.
  private var fastEnd = 0

  val position: Int get() = fetched - (bitCount ushr 3)

  val adler: Long
    get() {
      sumOutput()
      return (adlerHigh.toLong() shl 16) or adlerLow.toLong()
    }

  init {
    updateFastEnd()
  }

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

  private fun refill() {
    val bytes = input.bytes
    val readable = minOf(input.end, reads?.quietEnd ?: input.end)
    var at = fetched
    while (bitCount <= 24 && at < readable) {
      bitBuffer = bitBuffer or ((bytes[at++].toInt() and 0xFF) shl bitCount)
      bitCount += 8
    }
    fetched = at
  }

  private fun bits(count: Int): Int {
    if (bitCount < count) {
      refill()
      while (bitCount < count) {
        bitBuffer = bitBuffer or (input.u8(fetched++) shl bitCount)
        bitCount += 8
      }
    }
    val value = bitBuffer and ((1 shl count) - 1)
    bitBuffer = bitBuffer ushr count
    bitCount -= count
    return value
  }

  private fun updateFastEnd() {
    fastEnd = if (sizePrefixed && size < SIZE_PREFIX) 0 else minOf(output.size, limit - 1)
  }

  // Adds the output not yet summed to the Adler-32 checksum, in runs short enough that neither sum overflows.
  private fun sumOutput() {
    var low = adlerLow
    var high = adlerHigh
    var at = adlerFrom
    while (at < size) {
      val end = minOf(size, at + ADLER_RUN)
      while (at + 4 <= end) {
        low += output[at].toInt() and 0xFF
        high += low
        low += output[at + 1].toInt() and 0xFF
        high += low
        low += output[at + 2].toInt() and 0xFF
        high += low
        low += output[at + 3].toInt() and 0xFF
        high += low
        at += 4
      }
      while (at < end) {
        low += output[at++].toInt() and 0xFF
        high += low
      }
      low %= ADLER_MODULUS
      high %= ADLER_MODULUS
    }
    adlerLow = low
    adlerHigh = high
    adlerFrom = size
  }

  private fun emit(byte: Int) {
    reads?.emitting(size, position)
    if (size == limit) {
      if (reads == null) broken()
      sumOutput()
      adlerLow += byte
      if (adlerLow >= ADLER_MODULUS) adlerLow -= ADLER_MODULUS
      adlerHigh += adlerLow
      if (adlerHigh >= ADLER_MODULUS) adlerHigh -= ADLER_MODULUS
      val window = window ?: ByteArray(WINDOW_SIZE).also { window = it }
      window[windowAt] = byte.toByte()
      windowAt = (windowAt + 1) and WINDOW_MASK
      if (past < WINDOW_SIZE) past++
      return
    }
    if (size == output.size) output = output.copyOf(if (size >= limit - size) limit else size * 2)
    output[size++] = byte.toByte()
    reads?.emitted(size)
    if (sizePrefixed && size == SIZE_PREFIX) {
      val declared = (0 until SIZE_PREFIX).fold(0L) { value, at -> (value shl 8) or (output[at].toLong() and 0xFF) }
      if (declared < SIZE_PREFIX || declared > limit) broken()
      limit = declared.toInt()
    }
    updateFastEnd()
  }

  // The byte written distance bytes back. Past the limit only the last WINDOW_SIZE bytes are kept, which is as far
  // back as a deflate distance reaches.
  private fun written(distance: Int): Int {
    if (past < distance) {
      val at = size - distance + past
      if (at < 0) broken()
      return output[at].toInt() and 0xFF
    }
    return checkNotNull(window)[(windowAt - distance) and WINDOW_MASK].toInt() and 0xFF
  }

  private fun stored() {
    fetched = position
    bitBuffer = 0
    bitCount = 0
    val length = input.u16le(fetched)
    if (length != input.u16le(fetched + 2).inv() and 0xFFFF) broken()
    fetched += 4
    repeat(length) {
      emit(input.u8(fetched))
      fetched++
    }
  }

  private fun dynamic() {
    val literalCount = bits(5) + 257
    val distanceCount = bits(5) + 1
    val codeLengthCount = bits(4) + 4
    if (literalCount > 286 || distanceCount > 30) broken()
    val codeLengths = IntArray(CODE_LENGTH_ORDER.size)
    for (index in 0 until codeLengthCount) codeLengths[CODE_LENGTH_ORDER[index]] = bits(3)
    val codeLengthCode = Huffman(codeLengths, CODE_LENGTH_TABLE_BITS)
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
    codes(
      Huffman(lengths.copyOfRange(0, literalCount), LITERAL_TABLE_BITS),
      Huffman(lengths.copyOfRange(literalCount, lengths.size), DISTANCE_TABLE_BITS),
    )
  }

  private fun codes(
    literals: Huffman,
    distances: Huffman,
  ) {
    while (true) {
      fastRun(literals, distances)
      val symbol = decode(literals)
      if (symbol < END_OF_BLOCK) {
        if (size < fastEnd) output[size++] = symbol.toByte() else emit(symbol)
        continue
      }
      if (symbol == END_OF_BLOCK) return
      val lengthIndex = symbol - END_OF_BLOCK - 1
      if (lengthIndex >= LENGTH_BASE.size) broken()
      val length = LENGTH_BASE[lengthIndex] + bits(LENGTH_EXTRA[lengthIndex])
      val distanceIndex = decode(distances)
      if (distanceIndex >= DISTANCE_BASE.size) broken()
      val distance = DISTANCE_BASE[distanceIndex] + bits(DISTANCE_EXTRA[distanceIndex])
      copy(length, distance)
    }
  }

  // Decodes literals and matches for as long as the bits in hand or bytes taken early settle the next code and the
  // output has room, keeping everything it touches in locals. A code it cannot settle is handed back unread, for decode
  // and the checks of emit and written to take.
  private fun fastRun(
    literals: Huffman,
    distances: Huffman,
  ) {
    val literalTable = literals.table ?: return
    val distanceTable = distances.table ?: return
    val literalMask = literals.tableMask
    val distanceMask = distances.tableMask
    val bytes = input.bytes
    val readable = minOf(input.end, reads?.quietEnd ?: input.end)
    val out = output
    val end = fastEnd
    var buffer = bitBuffer
    var count = bitCount
    var taken = fetched
    var at = size
    var startBuffer = buffer
    var startCount = count
    var startTaken = taken
    var handedBack = false
    while (at < end) {
      if (count < 16) {
        if (taken + 1 >= readable) break
        val pair = (bytes[taken].toInt() and 0xFF) or ((bytes[taken + 1].toInt() and 0xFF) shl 8)
        buffer = buffer or (pair shl count)
        count += 16
        taken += 2
      }
      var entry = literalTable[buffer and literalMask]
      if (entry == 0) {
        entry = literals.longCode(buffer, count)
        if (entry == 0) break
      }
      val length = entry and 15
      val symbol = entry ushr 4
      if (symbol < END_OF_BLOCK) {
        buffer = buffer ushr length
        count -= length
        out[at++] = symbol.toByte()
        continue
      }
      startBuffer = buffer
      startCount = count
      startTaken = taken
      buffer = buffer ushr length
      count -= length
      val lengthIndex = symbol - END_OF_BLOCK - 1
      if (symbol == END_OF_BLOCK || lengthIndex >= LENGTH_BASE.size) {
        handedBack = true
        break
      }
      val lengthExtra = LENGTH_EXTRA[lengthIndex]
      if (count < 16 && taken + 1 < readable) {
        val pair = (bytes[taken].toInt() and 0xFF) or ((bytes[taken + 1].toInt() and 0xFF) shl 8)
        buffer = buffer or (pair shl count)
        count += 16
        taken += 2
      }
      if (count < lengthExtra) {
        handedBack = true
        break
      }
      val matchLength = LENGTH_BASE[lengthIndex] + (buffer and ((1 shl lengthExtra) - 1))
      buffer = buffer ushr lengthExtra
      count -= lengthExtra
      var distanceEntry = distanceTable[buffer and distanceMask]
      if (distanceEntry == 0) distanceEntry = distances.longCode(buffer, count)
      val distanceLength = distanceEntry and 15
      val distanceIndex = distanceEntry ushr 4
      if (distanceLength == 0 || distanceLength > count || distanceIndex >= DISTANCE_BASE.size) {
        handedBack = true
        break
      }
      buffer = buffer ushr distanceLength
      count -= distanceLength
      if (count < 16 && taken + 1 < readable) {
        val pair = (bytes[taken].toInt() and 0xFF) or ((bytes[taken + 1].toInt() and 0xFF) shl 8)
        buffer = buffer or (pair shl count)
        count += 16
        taken += 2
      }
      val distanceExtra = DISTANCE_EXTRA[distanceIndex]
      if (count < distanceExtra) {
        handedBack = true
        break
      }
      val distance = DISTANCE_BASE[distanceIndex] + (buffer and ((1 shl distanceExtra) - 1))
      buffer = buffer ushr distanceExtra
      count -= distanceExtra
      if (distance > at || matchLength > end - at) {
        handedBack = true
        break
      }
      var from = at - distance
      val matchEnd = at + matchLength
      while (at < matchEnd) out[at++] = out[from++]
    }
    if (handedBack) {
      buffer = startBuffer
      count = startCount
      taken = startTaken
    }
    bitBuffer = buffer
    bitCount = count
    fetched = taken
    size = at
  }

  private fun copy(
    length: Int,
    distance: Int,
  ) {
    if (distance <= size && length <= fastEnd - size) {
      var to = size
      var from = to - distance
      val end = to + length
      while (to < end) output[to++] = output[from++]
      size = end
      return
    }
    repeat(length) { emit(written(distance)) }
  }

  // Reads a code through the lookup table where the bits already held settle it and takes a byte only when they
  // do not, which is when the bit by bit walk would take one too. A code the table has no entry for is walked.
  private fun decode(huffman: Huffman): Int {
    val table = huffman.table
    if (table != null) {
      if (bitCount < MAX_CODE_LENGTH) refill()
      while (true) {
        val entry = table[bitBuffer and huffman.tableMask]
        val length = entry and 15
        if (length == 0) break
        if (length <= bitCount) {
          bitBuffer = bitBuffer ushr length
          bitCount -= length
          return entry ushr 4
        }
        bitBuffer = bitBuffer or (input.u8(fetched++) shl bitCount)
        bitCount += 8
      }
    }
    return walk(huffman)
  }

  private fun walk(huffman: Huffman): Int {
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

private fun deflateBases(
  first: Int,
  extraBits: IntArray,
): IntArray {
  var base = first
  return IntArray(extraBits.size) { index -> base.also { base += 1 shl extraBits[index] } }
}

private const val WINDOW_SIZE = 32768
private const val WINDOW_MASK = WINDOW_SIZE - 1
private const val ADLER_MODULUS = 65521

// The most bytes summed before reducing, so that neither Adler-32 sum passes Int.MAX_VALUE.
private const val ADLER_RUN = 3800
private const val LITERAL_TABLE_BITS = 10
private const val DISTANCE_TABLE_BITS = 8
private const val CODE_LENGTH_TABLE_BITS = 7
private const val SIZE_PREFIX = 4
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
    LITERAL_TABLE_BITS,
  )
private val FIXED_DISTANCES = Huffman(IntArray(30) { 5 }, DISTANCE_TABLE_BITS)
