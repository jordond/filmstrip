package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.picture.ByteRangeRead
import dev.jordond.filmstrip.picture.PictureWalk
import dev.jordond.filmstrip.picture.RangedPicture
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.heif.hvccSpsLayout
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

/**
 * One track of an MP4 or QuickTime file, as its `tkhd`, its `mdia/mdhd`, its `mdia/hdlr` and the first sample entry in
 * its `stsd` describe it.
 *
 * @property id The track id the `tkhd` gives.
 * @property isEnabled Whether the `tkhd` flags the track enabled, which is how a muxer marks the track of each kind a
 * player should pick.
 * @property handler The four-character handler type in `mdia/hdlr`, such as `soun` for audio, or null when the track
 * has none.
 * @property language The ISO 639-2 code packed into `mdia/mdhd`, `und` included, or null when the track has no media
 * header or QuickTime wrote a Macintosh language number there instead.
 * @property signal The color a video sample entry states, from its `colr` box, or else from the first sequence
 * parameter set in its `hvcC`. Null for any other track, and for a sample entry that states none or does not parse.
 */
internal class TrackHeader(
  val id: Long,
  val isEnabled: Boolean,
  val handler: Int?,
  val language: String?,
  val signal: CodestreamSignal? = null,
) {
  /**
   * Whether this is an audio track.
   */
  val isSound: Boolean get() = handler == SOUN
}

/**
 * Every track header of an MP4 or QuickTime file of [size] bytes that [read] fetches ranges of, in the order its `moov`
 * box lists them.
 *
 * Fetches the same boxes [movieDurationOf] does plus each track's `mdia/mdhd`, `mdia/hdlr` and `stsd`. Null for any
 * other container, a truncated or malformed file, a track with no `tkhd`, and a `moov` holding more than
 * [MAX_TRACK_HEADERS] tracks.
 */
internal suspend fun trackHeadersOf(
  size: Long,
  read: ByteRangeRead,
): List<TrackHeader>? = walkPicture(RangedPicture(size, read)) { inMovie { start, end -> trackHeaders(start, end) } }

/**
 * The [AudioTrackId] of each of the first [count] audio tracks of a file of [size] bytes that [read] fetches ranges of.
 *
 * An MP4 or QuickTime file names the audio track at each place by the track id in the sound track header at that place,
 * in the order its `moov` box lists them. Any other container, a file [trackHeadersOf] cannot read, and a track past
 * the last sound header are named by their place counted from one. Every id goes through [audioTrackIdOf], and the list
 * is always [count] long.
 *
 * A caller whose reader lists fewer audio tracks than the file has sound headers is paired with the headers by place.
 *
 * @param read Reads up to `count` bytes of the file starting at `start`, handing back fewer when the file ends first.
 */
@InternalFilmstripApi
public suspend fun movieAudioTrackIdsOf(
  size: Long,
  read: suspend (start: Long, count: Int) -> ByteArray,
  count: Int,
): List<AudioTrackId> {
  val headers = trackHeadersOf(size, read)
  val sound = headers.orEmpty().filter { it.isSound }
  return List(count) { ordinal -> audioTrackIdOf(isMovie = headers != null, sound.getOrNull(ordinal)?.id, ordinal) }
}

/**
 * The most tracks [trackHeadersOf] lists before it stops trusting the file.
 */
internal const val MAX_TRACK_HEADERS: Int = 1024

private suspend fun PictureWalk.movieDuration(): Duration? = inMovie { start, end -> movieHeaderDuration(start, end) }

// Runs read over the children of the top level `moov` box, or gives null when the file has none.
private suspend inline fun <T> PictureWalk.inMovie(read: (start: Long, end: Long) -> T?): T? {
  var pos = 0L
  while (pos < size) {
    val (end, header) = topLevelBox(pos)
    if (u32(pos + 4).toInt() == MOOV) return read(pos + header, end)
    pos = end
  }
  return null
}

private suspend fun PictureWalk.trackHeaders(
  start: Long,
  end: Long,
): List<TrackHeader>? {
  val tracks = mutableListOf<TrackHeader>()
  forEachChild(start, end) { type, childStart, childEnd ->
    if (type == TRAK) {
      if (tracks.size == MAX_TRACK_HEADERS) return null
      tracks += trackHeader(childStart, childEnd) ?: return null
    }
  }
  return tracks
}

private suspend fun PictureWalk.trackHeader(
  start: Long,
  end: Long,
): TrackHeader? {
  var id: Long? = null
  var enabled = false
  var handler: Int? = null
  var language: String? = null
  var signal: CodestreamSignal? = null
  forEachChild(start, end) { type, childStart, childEnd ->
    when (type) {
      TKHD -> {
        val long = u8(childStart, limit = childEnd) == 1
        enabled = u32(childStart, limit = childEnd) and TRACK_ENABLED != 0L
        id = u32(childStart + if (long) 20 else 12, limit = childEnd)
      }
      MDIA -> {
        forEachChild(childStart, childEnd) { mediaType, mediaStart, mediaEnd ->
          when (mediaType) {
            HDLR -> handler = u32(mediaStart + 8, limit = mediaEnd).toInt()
            MDHD -> language = mediaLanguage(mediaStart, mediaEnd)
            MINF -> signal = sampleSignal(mediaStart, mediaEnd)
          }
        }
      }
    }
  }
  return id?.let { TrackHeader(it, enabled, handler, language, signal) }
}

// The color the first sample entry under the `minf` between start and end states, when it is a video one. A sample
// entry that does not parse gives null rather than losing the track's other fields.
private suspend fun PictureWalk.sampleSignal(
  start: Long,
  end: Long,
): CodestreamSignal? =
  try {
    var signal: CodestreamSignal? = null
    forEachChild(start, end) { type, tableStart, tableEnd ->
      if (type == STBL) {
        forEachChild(tableStart, tableEnd) { boxType, boxStart, boxEnd ->
          if (boxType == STSD) signal = firstEntrySignal(boxStart, boxEnd)
        }
      }
    }
    signal
  } catch (_: MalformedPicture) {
    null
  }

private suspend fun PictureWalk.firstEntrySignal(
  start: Long,
  end: Long,
): CodestreamSignal? {
  val entry = start + STSD_HEADER
  val declared = u32(entry, limit = end)
  if (declared < BOX_HEADER + VISUAL_ENTRY_FIELDS || declared > end - entry) return null
  val type = u32(entry + 4, limit = end).toInt()
  if (type !in VISUAL_ENTRIES) return null
  var described: CodestreamSignal? = null
  var configured: CodestreamSignal? = null
  forEachChild(entry + BOX_HEADER + VISUAL_ENTRY_FIELDS, entry + declared) { childType, childStart, childEnd ->
    when (childType) {
      COLR -> described = colorBox(childStart, childEnd) ?: described
      HVCC -> configured = hvccSpsLayout(block(childStart, childEnd - childStart), 0)?.signal
    }
  }
  return described ?: configured
}

// The code points of an `nclx` or QuickTime `nclc` color box, or null for one carrying a profile.
private suspend fun PictureWalk.colorBox(
  start: Long,
  end: Long,
): CodestreamSignal? {
  val kind = u32(start, limit = end).toInt()
  if (kind != NCLX && kind != NCLC) return null
  return CodestreamSignal(
    primaries = u16(start + 4, limit = end),
    transfer = u16(start + 6, limit = end),
    matrix = u16(start + 8, limit = end),
    fullRange = if (kind == NCLX) u8(start + 10, limit = end) and FULL_RANGE != 0 else null,
  )
}

// The language a `mdhd` packs as three five-bit letters, each offset from 0x60.
private suspend fun PictureWalk.mediaLanguage(
  start: Long,
  end: Long,
): String? {
  val long = u8(start, limit = end) == 1
  val packed = u16(start + if (long) 32 else 20, limit = end)
  if (packed < FIRST_PACKED_LANGUAGE) return null
  return CharArray(3) { letter -> ((packed shr (10 - letter * 5) and 0x1F) + 0x60).toChar() }.concatToString()
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
private val MDIA = fourcc("mdia")
private val HDLR = fourcc("hdlr")
private val MDHD = fourcc("mdhd")
private val SOUN = fourcc("soun")
private val MINF = fourcc("minf")
private val STBL = fourcc("stbl")
private val STSD = fourcc("stsd")
private val COLR = fourcc("colr")
private val HVCC = fourcc("hvcC")
private val NCLX = fourcc("nclx")
private val NCLC = fourcc("nclc")
private val VISUAL_ENTRIES =
  setOf(fourcc("avc1"), fourcc("avc3"), fourcc("hvc1"), fourcc("hev1"), fourcc("vp09"), fourcc("av01"))

private const val BOX_HEADER = 8L
private const val TRACK_ENABLED = 1L

// An `stsd` opens on its version, flags and entry count, and a visual sample entry on 78 bytes of fields before its
// child boxes.
private const val STSD_HEADER = 8L
private const val VISUAL_ENTRY_FIELDS = 78L
private const val FULL_RANGE = 0x80

// QuickTime numbers Macintosh languages below this, and the packed form of `aaa` is the lowest ISO code above it.
private const val FIRST_PACKED_LANGUAGE = 0x400
private const val UNKNOWN_SHORT_DURATION = 0xFFFFFFFFL
private const val NANOS_PER_SECOND = 1_000_000_000L
