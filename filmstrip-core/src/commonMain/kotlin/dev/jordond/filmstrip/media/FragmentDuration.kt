package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.picture.PictureWalk
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.topLevelBox
import kotlin.time.Duration

/**
 * How long a fragmented MP4 whose `moov` children run from [start] to [end] plays for, read off its fragments.
 *
 * Each track's media runs to the end of its last fragment. That is the `tfdt` of its last `traf` plus the sample
 * durations its `trun` boxes give, or for a `traf` with no `tfdt` the end of the one before it plus those durations,
 * starting from the samples the `moov` itself holds. A sample's duration comes from its `trun` entry, else the `tfhd`
 * default, else the track's `trex` default. The track then plays for that less the media time its edit list opens on,
 * plus any empty edit ahead of it. The file plays for its longest track.
 *
 * Every top level box header is read, and of each `moof` only the `tfhd` and `tfdt` of every `traf` and the `trun`
 * boxes of the last `traf` per track that carries a `tfdt`. Null when no track has a fragment or samples, when a
 * track header has no timescale, and when any track's ticks, edits or sample counts would run past [MAX_MOVIE_LENGTH].
 */
internal suspend fun PictureWalk.fragmentedDuration(
  start: Long,
  end: Long,
): Duration? {
  val tracks = fragmentTracks(start, end) ?: return null
  var pos = 0L
  while (pos < size) {
    val (boxEnd, header) = topLevelBox(pos)
    if (u32(pos + 4).toInt() == MOOF) {
      forEachChild(pos + header, boxEnd) { type, trafStart, trafEnd ->
        if (type == TRAF) fragment(trafStart, trafEnd, tracks)
      }
    }
    pos = boxEnd
  }
  tracks.values.forEach { settle(it) }
  return tracks.values
    .mapNotNull { it.presented() }
    .maxOrNull()
    ?.takeIf { it <= MAX_MOVIE_LENGTH }
}

// What one track of a fragmented file says about its timing, with how far its fragments have reached so far.
private class FragmentTrack(
  val timescale: Long,
  val editStart: Long,
  val emptyEdit: Duration,
  var defaultDuration: Long = 0,
) {
  // The most media ticks a track may reach before the walk stops trusting the file.
  val limit: Long = MAX_MOVIE_LENGTH.inWholeSeconds * timescale

  var reached: Long = 0
  var hasMedia: Boolean = false

  // The last traf read with a tfdt, whose truns are summed only once a later traf needs it or the walk ends.
  var pendingBase: Long? = null
  var pendingStart: Long = 0
  var pendingEnd: Long = 0
  var pendingDefault: Long = 0

  fun presented(): Duration? = if (hasMedia) lengthOf(maxOf(0L, reached - editStart), timescale) + emptyEdit else null

  // Moves the track on to ticks, failing the walk past its limit.
  fun reach(ticks: Long) {
    if (ticks < 0 || ticks > limit) malformed()
    reached = ticks
  }
}

private suspend fun PictureWalk.fragmentTracks(
  start: Long,
  end: Long,
): Map<Long, FragmentTrack>? {
  var movieTimescale: Long? = null
  val tracks = mutableMapOf<Long, FragmentTrack>()
  val defaults = mutableMapOf<Long, Long>()
  val trakRanges = mutableListOf<Pair<Long, Long>>()
  forEachChild(start, end) { type, childStart, childEnd ->
    when (type) {
      MVHD -> {
        val long = u8(childStart, limit = childEnd) == 1
        movieTimescale = u32(childStart + if (long) 20 else 12, limit = childEnd)
      }
      TRAK -> {
        trakRanges += childStart to childEnd
      }
      MVEX -> {
        forEachChild(childStart, childEnd) { extendsType, trexStart, trexEnd ->
          if (extendsType == TREX) defaults[u32(trexStart + 4, limit = trexEnd)] = u32(trexStart + 12, limit = trexEnd)
        }
      }
    }
  }
  val movieScale = movieTimescale?.takeIf { it > 0 } ?: return null
  for ((trakStart, trakEnd) in trakRanges) {
    val (id, track) = fragmentTrack(trakStart, trakEnd, movieScale) ?: return null
    track.defaultDuration = defaults[id] ?: 0
    tracks[id] = track
  }
  return tracks
}

private suspend fun PictureWalk.fragmentTrack(
  start: Long,
  end: Long,
  movieScale: Long,
): Pair<Long, FragmentTrack>? {
  var id: Long? = null
  var timescale = 0L
  var ticks = 0L
  var editStart = 0L
  var emptyEdit = 0L
  forEachChild(start, end) { type, childStart, childEnd ->
    when (type) {
      TKHD -> {
        val long = u8(childStart, limit = childEnd) == 1
        id = u32(childStart + if (long) 20 else 12, limit = childEnd)
      }
      EDTS -> {
        forEachChild(childStart, childEnd) { editType, listStart, listEnd ->
          if (editType == ELST) {
            val (opening, delay) = editOpening(listStart, listEnd, movieScale)
            editStart = opening
            emptyEdit = delay
          }
        }
      }
      MDIA -> {
        forEachChild(childStart, childEnd) { mediaType, mediaStart, mediaEnd ->
          if (mediaType == MDHD) {
            val long = u8(mediaStart, limit = mediaEnd) == 1
            timescale = u32(mediaStart + if (long) 20 else 12, limit = mediaEnd)
            ticks =
              if (long) {
                u64(mediaStart + 24, limit = mediaEnd)
              } else {
                knownShort(u32(mediaStart + 16, limit = mediaEnd))
                  ?: 0
              }
          }
        }
      }
    }
  }
  val trackId = id ?: return null
  if (timescale <= 0) return null
  val track = FragmentTrack(timescale, editStart, lengthOf(emptyEdit, movieScale))
  track.reach(ticks)
  track.hasMedia = ticks > 0
  return trackId to track
}

// The media time the first edit that shows media opens on, and how many movie ticks of empty edits come before it.
internal suspend fun PictureWalk.editOpening(
  start: Long,
  end: Long,
  movieScale: Long,
): Pair<Long, Long> {
  val long = u8(start, limit = end) == 1
  val count = u32(start + 4, limit = end)
  val entrySize = if (long) 20L else 12L
  var delay = 0L
  var entry = start + 8
  repeat(count.coerceAtMost((end - entry) / entrySize).toInt()) {
    val duration = if (long) u64(entry, limit = end) else u32(entry, limit = end)
    val mediaTime = if (long) u64(entry + 8, limit = end) else u32(entry + 4, limit = end).toInt().toLong()
    if (mediaTime != EMPTY_EDIT) return (mediaTime.takeIf { it >= 0 } ?: malformed()) to delay
    delay += duration
    if (duration < 0 || delay > MAX_MOVIE_LENGTH.inWholeSeconds * movieScale) malformed()
    entry += entrySize
  }
  return 0L to delay
}

private suspend fun PictureWalk.fragment(
  start: Long,
  end: Long,
  tracks: Map<Long, FragmentTrack>,
) {
  var track: FragmentTrack? = null
  var default = 0L
  var base: Long? = null
  forEachChild(start, end) { type, childStart, childEnd ->
    when (type) {
      TFHD -> {
        val flags = u32(childStart, limit = childEnd) and FLAGS
        val named = tracks[u32(childStart + 4, limit = childEnd)] ?: return
        var field = childStart + 8
        if (flags and BASE_DATA_OFFSET != 0L) field += 8
        if (flags and SAMPLE_DESCRIPTION != 0L) field += 4
        default = if (flags and DEFAULT_DURATION != 0L) u32(field, limit = childEnd) else named.defaultDuration
        track = named
      }
      TFDT -> {
        val long = u8(childStart, limit = childEnd) == 1
        base = if (long) u64(childStart + 4, limit = childEnd) else u32(childStart + 4, limit = childEnd)
      }
    }
  }
  val owner = track ?: return
  val known = base
  if (known != null) {
    owner.pendingBase = known
    owner.pendingStart = start
    owner.pendingEnd = end
    owner.pendingDefault = default
  } else {
    settle(owner)
    owner.reach(owner.reached + runTicks(start, end, default, owner.limit))
  }
  owner.hasMedia = true
}

// Sums the truns of a track's pending traf onto its base, leaving the track with nothing pending.
private suspend fun PictureWalk.settle(track: FragmentTrack) {
  val base = track.pendingBase ?: return
  if (base > track.limit) malformed()
  track.reach(base + runTicks(track.pendingStart, track.pendingEnd, track.pendingDefault, track.limit))
  track.pendingBase = null
}

private suspend fun PictureWalk.runTicks(
  start: Long,
  end: Long,
  default: Long,
  limit: Long,
): Long {
  var ticks = 0L
  forEachChild(start, end) { type, runStart, runEnd ->
    if (type == TRUN) ticks += runDuration(runStart, runEnd, default, limit)
    if (ticks > limit) malformed()
  }
  return ticks
}

private suspend fun PictureWalk.runDuration(
  start: Long,
  end: Long,
  default: Long,
  limit: Long,
): Long {
  val flags = u32(start, limit = end) and FLAGS
  val count = u32(start + 4, limit = end)
  if (flags and SAMPLE_DURATION == 0L) {
    if (default > 0 && count > limit / default) malformed()
    return count * default
  }
  var entry = start + 8
  if (flags and DATA_OFFSET != 0L) entry += 4
  if (flags and FIRST_SAMPLE_FLAGS != 0L) entry += 4
  val entrySize =
    4L * listOf(SAMPLE_DURATION, SAMPLE_SIZE, SAMPLE_FLAGS, COMPOSITION_OFFSET).count { flags and it != 0L }
  if (count > (end - entry) / entrySize) malformed()
  var ticks = 0L
  repeat(count.toInt()) {
    ticks += u32(entry, limit = end)
    entry += entrySize
  }
  return ticks
}

private val MOOF = fourcc("moof")
private val TRAF = fourcc("traf")
private val TFHD = fourcc("tfhd")
private val TFDT = fourcc("tfdt")
private val TRUN = fourcc("trun")
private val TREX = fourcc("trex")
internal val EDTS = fourcc("edts")
internal val ELST = fourcc("elst")

private const val FLAGS = 0xFFFFFFL
private const val EMPTY_EDIT = -1L

private const val BASE_DATA_OFFSET = 0x01L
private const val SAMPLE_DESCRIPTION = 0x02L
private const val DEFAULT_DURATION = 0x08L

private const val DATA_OFFSET = 0x01L
private const val FIRST_SAMPLE_FLAGS = 0x04L
private const val SAMPLE_DURATION = 0x100L
private const val SAMPLE_SIZE = 0x200L
private const val SAMPLE_FLAGS = 0x400L
private const val COMPOSITION_OFFSET = 0x800L
