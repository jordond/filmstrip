package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.picture.ByteRangeRead
import dev.jordond.filmstrip.picture.PictureWalk
import dev.jordond.filmstrip.picture.RangedPicture
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.topLevelBox
import dev.jordond.filmstrip.picture.walkPicture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long an MP4 or QuickTime file of [size] bytes that [read] fetches ranges of plays for, read off the duration in
 * its `mvhd` box.
 *
 * That is the figure the muxer wrote for the file's longest track once its edit list is applied, so a track that
 * opens on an encoder delay counts only the samples it presents, and one an empty edit delays counts the delay too.
 * Only the box headers ahead of the `moov` box, the `moov` box's own children and the `tkhd` box of each track are
 * fetched.
 *
 * Null for any other container, a fragmented file, whose `mvhd` leaves its fragments out, a header with no duration or
 * no timescale, a `moov` child with a 64-bit size, and a truncated or malformed file. Also null when no track header
 * gives a duration, when the movie header runs more than one tick past the longest track header, or when it runs
 * longer than [MAX_MOVIE_LENGTH], since none of those is a length a muxer writes.
 */
internal suspend fun movieDurationOf(
  size: Long,
  read: ByteRangeRead,
): Duration? = walkPicture(RangedPicture(size, read)) { movieDuration() }

/**
 * The longest a movie header may say a file plays for before [movieDurationOf] stops trusting it.
 */
internal val MAX_MOVIE_LENGTH: Duration = 7.days

private suspend fun PictureWalk.movieDuration(): Duration? {
  var pos = 0L
  while (pos < size) {
    val (end, header) = topLevelBox(pos)
    if (u32(pos + 4).toInt() == MOOV) return movieHeaderDuration(pos + header, end)
    pos = end
  }
  return null
}

private suspend fun PictureWalk.movieHeaderDuration(
  start: Long,
  end: Long,
): Duration? {
  var movie: MovieHeader? = null
  var longestTrack: Long? = null
  forEachChild(start, end) { type, childStart, childEnd ->
    when (type) {
      MVEX -> return null
      MVHD -> movie = movieHeader(childStart, childEnd)
      TRAK -> trackTicks(childStart, childEnd)?.let { track -> longestTrack = maxOf(longestTrack ?: track, track) }
    }
  }
  val header = movie ?: return null
  val track = longestTrack ?: return null
  if (header.ticks - 1 > track) return null
  return lengthOf(header.ticks, header.timescale).takeIf { it <= MAX_MOVIE_LENGTH }
}

// The duration the `tkhd` of the track between start and end gives in movie ticks, or null when it has none.
private suspend fun PictureWalk.trackTicks(
  start: Long,
  end: Long,
): Long? {
  var ticks: Long? = null
  forEachChild(start, end) { type, childStart, childEnd ->
    if (type == TKHD) {
      val long = u8(childStart, limit = childEnd) == 1
      ticks = if (long) u64(childStart + 28, limit = childEnd) else knownShort(u32(childStart + 20, limit = childEnd))
    }
  }
  return ticks?.takeIf { it > 0 }
}

private suspend inline fun PictureWalk.forEachChild(
  start: Long,
  end: Long,
  visit: (type: Int, start: Long, end: Long) -> Unit,
) {
  var pos = start
  while (pos < end) {
    val declared = u32(pos, limit = end)
    if (declared < BOX_HEADER || declared > end - pos) malformed()
    visit(u32(pos + 4, limit = end).toInt(), pos + BOX_HEADER, pos + declared)
    pos += declared
  }
}

/**
 * The duration a `mvhd` gives, as [ticks] of its [timescale].
 *
 * Every `tkhd` in the movie counts its duration in the same [timescale].
 */
private class MovieHeader(
  val timescale: Long,
  val ticks: Long,
)

private suspend fun PictureWalk.movieHeader(
  start: Long,
  end: Long,
): MovieHeader? {
  val long = u8(start, limit = end) == 1
  val timescale = u32(start + if (long) 20 else 12, limit = end)
  val ticks = if (long) u64(start + 24, limit = end) else knownShort(u32(start + 16, limit = end))
  if (ticks == null || ticks <= 0 || timescale == 0L) return null
  return MovieHeader(timescale, ticks)
}

private fun knownShort(ticks: Long): Long? = ticks.takeIf { it != UNKNOWN_SHORT_DURATION }

private fun lengthOf(
  ticks: Long,
  timescale: Long,
): Duration {
  val fraction = ((ticks % timescale) * NANOS_PER_SECOND + timescale / 2) / timescale
  return (ticks / timescale).seconds + fraction.nanoseconds
}

private val MOOV = fourcc("moov")
private val MVHD = fourcc("mvhd")
private val MVEX = fourcc("mvex")
private val TRAK = fourcc("trak")
private val TKHD = fourcc("tkhd")

private const val BOX_HEADER = 8L
private const val UNKNOWN_SHORT_DURATION = 0xFFFFFFFFL
private const val NANOS_PER_SECOND = 1_000_000_000L
