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

internal class Huffman(
  lengths: IntArray,
  tableBits: Int,
) {
  val counts = IntArray(MAX_CODE_LENGTH + 1)
  val symbols = IntArray(lengths.size)

  // Maps the next bits tableMask selects to a symbol shl 4 or its code length, 0 for a code longer than that or none.
  // It is null for an over-subscribed set of lengths, which only the bit by bit walk reads.
  val table: IntArray?

  // Selects the table entry, as many bits as the longest code has up to the table's width.
  val tableMask: Int

  init {
    for (length in lengths) counts[length]++
    val offsets = IntArray(MAX_CODE_LENGTH + 1)
    for (length in 1 until MAX_CODE_LENGTH) offsets[length + 1] = offsets[length] + counts[length]
    for ((symbol, length) in lengths.withIndex()) {
      if (length != 0) symbols[offsets[length]++] = symbol
    }
    val longest = (MAX_CODE_LENGTH downTo 1).firstOrNull { counts[it] != 0 } ?: 0
    tableMask = (1 shl minOf(longest, tableBits)) - 1
    table = lookupTable(tableBits)
  }

  // The table entry for a code the table does not hold, read from the bits in buffer the way the bit by bit walk reads
  // them, or 0 when fewer than MAX_CODE_LENGTH bits are held or no code matches them.
  fun longCode(
    buffer: Int,
    count: Int,
  ): Int {
    if (count < MAX_CODE_LENGTH) return 0
    var code = 0
    var first = 0
    var index = 0
    for (length in 1..MAX_CODE_LENGTH) {
      code = code or ((buffer ushr (length - 1)) and 1)
      val codes = counts[length]
      if (code - first < codes) return (symbols[index + code - first] shl 4) or length
      index += codes
      first = (first + codes) shl 1
      code = code shl 1
    }
    return 0
  }

  private fun lookupTable(tableBits: Int): IntArray? {
    val table = IntArray(tableMask + 1)
    var code = 0
    var index = 0
    for (length in 1..MAX_CODE_LENGTH) {
      repeat(counts[length]) {
        if (length <= tableBits) {
          val reversed = ((REVERSED[code and 0xFF] shl 8) or REVERSED[code ushr 8]) ushr (16 - length)
          val entry = (symbols[index] shl 4) or length
          var at = reversed
          while (at < table.size) {
            table[at] = entry
            at += 1 shl length
          }
        }
        index++
        code++
      }
      if (code > (1 shl length)) return null
      code = code shl 1
    }
    return table
  }
}

internal const val MAX_CODE_LENGTH = 15

// Each byte with its bits in reverse order.
private val REVERSED =
  IntArray(256) { byte -> (0 until 8).fold(0) { reversed, bit -> reversed or (((byte ushr bit) and 1) shl (7 - bit)) } }
