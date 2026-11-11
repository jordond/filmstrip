package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.MimeTypes
import dev.jordond.filmstrip.media3.internal.encoder.AAC_UNIT_FRAMES

private const val MICROS_PER_SECOND = 1_000_000L

/**
 * Where a copy's video track ends in microseconds, given the times of the frames it [written] and the end of the
 * plan's grid, or null when the muxer's own end stands.
 *
 * A muxer gives a track's last frame the length of the one before it, and no length at all when the track holds fewer
 * than three. Ending the track on [gridEnd] makes the last frame last to the end of the last slot the plan covers. A
 * copy that wrote a reference presented at or past the grid already ends beyond it, so its end is left alone.
 */
internal fun copiedVideoEndOf(
  written: List<Long>,
  gridEnd: Long,
): Long? {
  val last = written.maxOrNull() ?: return null
  return gridEnd.takeIf { last < it }
}

/**
 * Where a copy's audio track ends, as [copiedAudioEndOf] reads it off the units the copy wrote.
 */
internal sealed interface CopiedAudioEnd {
  /**
   * The track ends at [time] in microseconds, one unit after its last unit starts.
   */
  data class At(
    val time: Long,
  ) : CopiedAudioEnd

  /**
   * No unit is presented at or after zero, so the track holds nothing to hear and is left out of the file.
   */
  data object Silent : CopiedAudioEnd

  /**
   * The track holds a lone unit of a length nothing gives, so the muxer's own end stands.
   */
  data object Unstamped : CopiedAudioEnd
}

/**
 * Where a copy's audio track ends, given the times of the units it [written] in microseconds.
 *
 * A unit lasts as long as the gap between the last two written, and [loneUnit] when only one was. A unit presented
 * before zero is priming the container trims away, so a track holding nothing else is [CopiedAudioEnd.Silent].
 */
internal fun copiedAudioEndOf(
  written: List<Long>,
  loneUnit: Long?,
): CopiedAudioEnd {
  val shown = written.sortedDescending()
  val last = shown.firstOrNull()?.takeIf { it >= 0 } ?: return CopiedAudioEnd.Silent
  val unit = shown.getOrNull(1)?.let { last - it } ?: loneUnit ?: return CopiedAudioEnd.Unstamped
  return CopiedAudioEnd.At(last + unit)
}

/**
 * How long one unit of an audio track lasts in microseconds when its codec fixes that, or null when it does not.
 *
 * Only AAC is known, where a unit holds [AAC_UNIT_FRAMES] frames at any sample rate.
 */
internal fun fixedAudioUnitOf(
  mimeType: String?,
  sampleRate: Int,
): Long? =
  if (mimeType == MimeTypes.AUDIO_AAC && sampleRate > 0) {
    AAC_UNIT_FRAMES * MICROS_PER_SECOND / sampleRate
  } else {
    null
  }
