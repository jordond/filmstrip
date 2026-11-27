package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.test.MovieBoxes.ascii
import dev.jordond.filmstrip.test.MovieBoxes.box
import dev.jordond.filmstrip.test.MovieBoxes.fullBox
import dev.jordond.filmstrip.test.MovieBoxes.u32
import kotlin.time.Duration

// Builders the movie length tests share, each laying out the boxes of a small MP4 field by field.

internal fun movieFile(
  children: ByteArray,
  ahead: ByteArray = ByteArray(0),
): ByteArray = FTYP + ahead + box("moov", children)

internal fun editList(vararg entries: Pair<Long, Long>): ByteArray {
  val rows =
    entries.fold(ByteArray(0)) { all, (duration, mediaTime) ->
      all + u32(duration) + u32(mediaTime) +
        u32(RATE_ONE)
    }
  return box("edts", fullBox("elst", 0, u32(entries.size.toLong()) + rows))
}

internal suspend fun durationOf(file: ByteArray): Duration? =
  movieDurationOf(file.size.toLong()) { start, count ->
    file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + count))
  }

internal val FTYP = box("ftyp", ascii("isom") + u32(0) + ascii("isom"))

internal const val RATE_ONE = 0x00010000L
