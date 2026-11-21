package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.picture.ByteRangeRead
import dev.jordond.filmstrip.picture.PictureWalk
import dev.jordond.filmstrip.picture.RangedPicture
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.walkPicture

/**
 * Where the furthest reaching last sample of any track of an MP4 or QuickTime file of [size] bytes ends, as a byte
 * offset into the file, read off each track's sample table.
 *
 * A track's last sample sits at the end of its last chunk, so the walk reads the last chunk offset from `stco` or
 * `co64`, the samples that chunk holds from the last `stsc` entry and their sizes from the tail of `stsz` or `stz2`.
 * A track with no samples, and one whose `dref` points at another file, are skipped.
 *
 * Null for any other container, a file whose tracks are all skipped, such as a fragmented one, and a truncated or
 * malformed `moov`.
 */
internal suspend fun lastSampleEndOf(
  size: Long,
  read: ByteRangeRead,
): Long? = walkPicture(RangedPicture(size, read)) { inMovie { start, end -> lastSampleEnd(start, end) } }

private suspend fun PictureWalk.lastSampleEnd(
  start: Long,
  end: Long,
): Long? {
  var furthest: Long? = null
  forEachChild(start, end) { type, trackStart, trackEnd ->
    if (type == TRAK) {
      forEachChild(trackStart, trackEnd) { trackType, mediaStart, mediaEnd ->
        if (trackType == MDIA) {
          forEachChild(mediaStart, mediaEnd) { mediaType, infoStart, infoEnd ->
            if (mediaType == MINF) {
              mediaLastSampleEnd(infoStart, infoEnd)?.let { furthest = maxOf(furthest ?: it, it) }
            }
          }
        }
      }
    }
  }
  return furthest
}

// Where the last sample of the track whose `minf` runs from start to end ends, or null when the track holds no samples
// or keeps them in another file.
private suspend fun PictureWalk.mediaLastSampleEnd(
  start: Long,
  end: Long,
): Long? {
  var external = false
  var sizes: Int? = null
  var sizesAt = 0L to 0L
  var runsAt: Pair<Long, Long>? = null
  var chunks: Int? = null
  var chunksAt = 0L to 0L
  forEachChild(start, end) { type, childStart, childEnd ->
    when (type) {
      DINF -> {
        external = referencesOtherFile(childStart, childEnd)
      }
      STBL -> {
        forEachChild(childStart, childEnd) { tableType, tableStart, tableEnd ->
          when (tableType) {
            STSZ, STZ2 -> {
              sizes = tableType
              sizesAt = tableStart to tableEnd
            }
            STSC -> {
              runsAt = tableStart to tableEnd
            }
            STCO, CO64 -> {
              chunks = tableType
              chunksAt = tableStart to tableEnd
            }
          }
        }
      }
    }
  }
  if (external) return null
  val sizeType = sizes ?: return null
  val chunkType = chunks ?: return null
  val (runStart, runEnd) = runsAt ?: return null
  val chunkCount = u32(chunksAt.first + 4, limit = chunksAt.second)
  val runCount = u32(runStart + 4, limit = runEnd)
  if (chunkCount == 0L || runCount == 0L) return null
  val lastChunk =
    if (chunkType == CO64) {
      u64(chunksAt.first + 8 + (chunkCount - 1) * 8, limit = chunksAt.second)
    } else {
      u32(chunksAt.first + 8 + (chunkCount - 1) * 4, limit = chunksAt.second)
    }
  val perChunk = u32(runStart + 8 + (runCount - 1) * STSC_ENTRY + 4, limit = runEnd)
  val lastChunkBytes = lastSamplesSize(sizeType, sizesAt.first, sizesAt.second, perChunk) ?: return null
  if (lastChunk < 0 || lastChunk > Long.MAX_VALUE - lastChunkBytes) malformed()
  return lastChunk + lastChunkBytes
}

// How many bytes the last count samples an `stsz` or `stz2` lists take up, or null when it lists none.
private suspend fun PictureWalk.lastSamplesSize(
  type: Int,
  start: Long,
  end: Long,
  count: Long,
): Long? {
  val samples = u32(start + 8, limit = end)
  if (samples == 0L || count == 0L) return null
  val taken = minOf(count, samples)
  val first = samples - taken
  if (type == STSZ) {
    val uniform = u32(start + 4, limit = end)
    if (uniform != 0L) return if (uniform > Long.MAX_VALUE / taken) malformed() else uniform * taken
    return sumOf(block(start + 12 + first * 4, taken * 4), taken) { view, at -> view.u32(at * 4) }
  }
  return when (u8(start + 7, limit = end)) {
    4 -> {
      val view = block(start + 12 + first / 2, (samples + 1) / 2 - first / 2)
      sumOf(view, taken) { bytes, at ->
        val index = first + at
        val packed = bytes.u8((index / 2 - first / 2).toInt())
        (if (index % 2 == 0L) packed shr 4 else packed and 0xF).toLong()
      }
    }
    8 -> {
      sumOf(block(start + 12 + first, taken), taken) { view, at -> view.u8(at).toLong() }
    }
    16 -> {
      sumOf(block(start + 12 + first * 2, taken * 2), taken) { view, at -> view.u16(at * 2).toLong() }
    }
    else -> {
      malformed()
    }
  }
}

private inline fun sumOf(
  view: ByteView,
  count: Long,
  entry: (ByteView, Int) -> Long,
): Long {
  var total = 0L
  for (at in 0 until count.toInt()) total += entry(view, at)
  return total
}

// Whether any entry of the `dref` under the `dinf` between start and end leaves its flag for media in this file clear.
private suspend fun PictureWalk.referencesOtherFile(
  start: Long,
  end: Long,
): Boolean {
  var other = false
  forEachChild(start, end) { type, refStart, refEnd ->
    if (type == DREF) {
      forEachChild(refStart + DREF_HEADER, refEnd) { _, entryStart, entryEnd ->
        if (u32(entryStart, limit = entryEnd) and SELF_CONTAINED == 0L) other = true
      }
    }
  }
  return other
}

private val STSZ = fourcc("stsz")
private val STZ2 = fourcc("stz2")
private val STSC = fourcc("stsc")
private val STCO = fourcc("stco")
private val CO64 = fourcc("co64")
private val DINF = fourcc("dinf")
private val DREF = fourcc("dref")

private const val SELF_CONTAINED = 1L

// A `dref` opens on its version, flags and entry count, and every `stsc` entry is three 32-bit fields.
private const val DREF_HEADER = 8L
private const val STSC_ENTRY = 12L
