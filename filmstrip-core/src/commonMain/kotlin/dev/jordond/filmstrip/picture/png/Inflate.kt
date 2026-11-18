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
internal class LibpngReads(
  private val readEnds: IntArray,
  private val imageSize: Int,
) {
  private var read = 0
  private var phase = ROWS
  private var writtenInCall = 0
  private var written = false

  val checking: Boolean get() = phase == CHECKING

  // Bytes before this one are inside the read libpng is in, so taking them early changes nothing it sees.
  val quietEnd: Int get() = if (read < readEnds.size) readEnds[read] else 0

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
        if (!written) throw ReadStopped(accepted = true)
        nextRead()
        writtenInCall = 0
      }
    }
  }

  fun emitting(
    count: Int,
    position: Int,
  ) {
    if (count < imageSize) return
    if (phase == LAST || writtenInCall == LIBPNG_CHECK_BUFFER) {
      phase = CHECKING
      if (position == readEnds[read]) nextRead()
      writtenInCall = 0
    }
    writtenInCall++
    written = true
  }

  fun emitted(count: Int) {
    if (count == imageSize) phase = LAST
  }

  private fun nextRead() {
    read++
    if (read >= readEnds.size) throw ReadStopped(accepted = false)
  }
}

private class ReadStopped(
  val accepted: Boolean,
) : Exception()

internal class ZlibStream(
  val bytes: ByteArray,
  val end: Int,
  private val reads: LibpngReads? = null,
) {
  fun u8(at: Int): Int {
    reads?.pull(at)
    if (at < 0 || at >= end) broken()
    return bytes[at].toInt() and 0xFF
  }

  fun u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

  fun u32(at: Int): Long =
    (((u8(at) shl 8) or u8(at + 1)).toLong() shl 16) or ((u8(at + 2) shl 8) or u8(at + 3)).toLong()
}

private class BrokenStream : Exception()

internal fun broken(): Nothing = throw BrokenStream()

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
private const val INITIAL_CAPACITY = 1024
