package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.time.timeRangeOf
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.transform.internal.resolve.GainSegment
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.timeRange
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMTimeRangeGetEnd
import kotlin.time.Duration

/**
 * The mix that runs each clip's audio effects and applies its gain, or null when every clip is at full volume and
 * has no effect.
 *
 * AVFoundation expresses a linear ramp natively and exactly, so a clip's curve is written straight
 * across as one call per [GainSegment]. A track holding a clip with effects gets one [AvKernelTap], which runs ahead
 * of the track's ramps, so a clip's level is applied to what its effects wrote.
 *
 * @param taps The tap of each track in this list's order, or null for a track with no effect to run.
 * @param writesGain Whether each clip's gain is written as ramps. False when the taps apply it themselves.
 * @throws AvLoweringFailure when MediaToolbox refuses to build a tap.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun List<Pair<AVMutableCompositionTrack, List<Placement>>>.toAudioMix(
  taps: List<AvKernelTap?> = map { (media, placements) -> placements.toKernelTap(media) },
  writesGain: Boolean = true,
): AVMutableAudioMix? {
  if (taps.all { it == null } && all { (_, placements) -> placements.all { it.clip.gain.constant == 1f } }) return null

  val parameters =
    mapIndexed { index, (media, placements) ->
      AVMutableAudioMixInputParameters().apply {
        setTrackID(media.trackID)
        if (writesGain) placements.forEach { it.writeGain(this) }
        taps[index]?.let { tap ->
          val reference = kernelTapOf(tap) ?: throw AvLoweringFailure(NO_TAP)
          setAudioTapProcessor(reference)
          CFRelease(reference)
        }
      }
    }

  return AVMutableAudioMix().apply { setInputParameters(parameters) }
}

/**
 * The tap that runs the effects of every clip laid on one track, each over the clip's own span of the timeline, or
 * null when no clip on the track has one. It is a tap for a reader, which reads its source ahead up to where the
 * audio laid on [media] ends.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun List<Placement>.toKernelTap(media: AVMutableCompositionTrack): AvKernelTap? {
  val spans =
    filter { it.clip.audioEffects.isNotEmpty() }
      .map { TapSpan(TimeRange.of(it.start, it.end), it.clip.audioEffects) }
  return if (spans.isEmpty()) null else AvKernelTap(spans, readsUntil = CMTimeRangeGetEnd(media.timeRange).toDuration())
}

/**
 * One audio track of a composition, once its clips are laid.
 *
 * @property media The composition track the clips were laid onto.
 * @property placements Where each clip landed.
 * @property track The index of the track in the plan's own list, or null for the silence laid under a composition
 * with audio effects of its own, which no track of the plan holds.
 */
internal class LaidAudio(
  val media: AVMutableCompositionTrack,
  val placements: List<Placement>,
  val track: Int?,
)

/**
 * The audio side of a lowered composition: the taps that run its audio effects and the mix that carries them.
 *
 * An export runs a clip's effects in a tap ahead of the volume ramps that apply its gain, and runs the composition's
 * effects itself on the mixed samples. A player hands nobody its mixed samples, so a preview whose plan has an audio
 * effect puts a tap on every track that runs the clip's effects, applies the clip's gain and then runs the
 * composition's effects, and its mix writes no ramps. A player hands a tap no source ahead of a block either, so a
 * preview plays a track late by what its kernels hold back, where an export takes that out.
 *
 * @param laid The audio tracks of the composition.
 * @param resolved The plan they were laid from.
 * @param plays Whether a player plays this composition, which is what makes the taps host the gain and the mix.
 * @throws AvLoweringFailure when MediaToolbox refuses to build a tap.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvAudioGraph(
  private val laid: List<LaidAudio>,
  resolved: ResolvedComposition,
  private val plays: Boolean,
) {
  private val hosted = plays && resolved.hasAudioEffects

  // Whether silence is laid under the composition, for its own audio effects to run over.
  private val bedded = laid.any { it.track == null }

  // Which clip of its track each placement was laid from, so a later plan's clip can take its place.
  private val clipOf: List<List<Int>> =
    laid.map { each ->
      val clips = each.track?.let { resolved.tracks[it].clips }.orEmpty()
      each.placements.map { placed -> clips.indexOfFirst { it === placed.clip } }
    }

  // The tap of each track in the order they were laid, or null for a track that needs none.
  private val trackTaps: List<AvKernelTap?> =
    if (hosted) {
      laid.mapIndexed { index, each ->
        AvKernelTap(each.placements.hostedSpans(), resolved.tapMix(index))
      }
    } else {
      laid.map { it.placements.toKernelTap(it.media) }
    }

  /**
   * The tap on each track that has one.
   */
  val taps: List<AvKernelTap> = trackTaps.filterNotNull()

  /**
   * The mix a reader or a player item is handed, or null when there is nothing for one to do.
   */
  var mix: AVMutableAudioMix? = laid.map { it.media to it.placements }.toAudioMix(trackTaps, writesGain = !hosted)
    private set

  /**
   * What stopped a tap's effects running, or null while they all run.
   */
  val failure: FilmstripError? get() = taps.firstNotNullOfOrNull { it.failure }

  /**
   * Whether [update] can take [resolved], a plan of the same timeline. It cannot when the plan gains its first audio
   * effect or loses its last, since that moves the gain between the ramps and the taps, and an export's taps are
   * never swapped.
   */
  fun takes(resolved: ResolvedComposition): Boolean {
    val reachable = if (hosted) resolved.hasAudioEffects else taps.isEmpty() && !resolved.hasAudioEffects
    return reachable && clipOf.all { clips -> clips.all { it >= 0 } }
  }

  /**
   * Plays the gains and the audio effects of [resolved] from here on.
   *
   * Standing taps swap their kernels and gains in place. Without taps the ramps are written again into a new [mix],
   * which the caller hands to its player item.
   */
  fun update(resolved: ResolvedComposition) {
    require(takes(resolved)) { "This composition's audio was laid for a plan a swap cannot reach from here." }
    val placed =
      laid.mapIndexed { index, each ->
        val clips = each.track?.let { resolved.tracks[it].clips }.orEmpty()
        each.placements.mapIndexed { at, placement -> placement.laying(clips[clipOf[index][at]]) }
      }
    if (hosted) {
      trackTaps.forEachIndexed { index, tap -> tap?.swap(placed[index].hostedSpans(), resolved.tapMix(index)) }
    } else {
      mix = laid.mapIndexed { index, each -> each.media to placed[index] }.toAudioMix(trackTaps)
    }
  }

  private fun List<Placement>.hostedSpans(): List<TapSpan> =
    map { TapSpan(TimeRange.of(it.start, it.end), it.clip.audioEffects, it.clip.gain) }

  // One track keeps the sound the composition's effects make of their own, so the mix hears it once. It is the
  // silence under the composition, which covers the whole timeline, or the first track when no silence is laid.
  private fun ResolvedComposition.tapMix(track: Int): TapMix =
    TapMix(mixAudioEffects, carriesAdditive = if (bedded) laid[track].track == null else track == 0)
}

/**
 * Whether any clip or the composition itself has an audio effect to run.
 */
internal val ResolvedComposition.hasAudioEffects: Boolean
  get() = mixAudioEffects.isNotEmpty() || tracks.any { track -> track.clips.any { it.audioEffects.isNotEmpty() } }

/**
 * Writes this placement's gain curve onto [parameters], in the composition time [parameters]
 * already reads.
 *
 * A constant curve is one `setVolumeRampFromStartVolume:toEndVolume:timeRange:` holding that level
 * at both ends of the placement's own span, so the level stays where it was written until the next
 * placement writes its own. A ramping curve is one ramp per [GainSegment], with the segment's
 * clip-relative `start`/`end` shifted onto the timeline by this placement's own [Placement.start],
 * since a curve is measured from zero at the clip's own start and AVFoundation only understands the
 * composition's time. A zero-length segment is a step rather than a ramp, and AVFoundation has no
 * ramp to hold across no time, so it is written as a plain `setVolume:atTime:` at the instant the
 * step lands on.
 */
@OptIn(ExperimentalForeignApi::class)
private fun Placement.writeGain(parameters: AVMutableAudioMixInputParameters) {
  val constant = clip.gain.constant
  if (constant != null) {
    parameters.setVolumeRampFromStartVolume(
      constant,
      toEndVolume = constant,
      timeRange = timeRangeOf(start, end - start),
    )
    return
  }

  clip.gain.segments.forEach { segment ->
    val segmentStart = start + segment.start
    val length = segment.end - segment.start
    if (length <= Duration.ZERO) {
      parameters.setVolume(segment.endGain, atTime = segmentStart.toCMTime())
    } else {
      parameters.setVolumeRampFromStartVolume(
        segment.startGain,
        toEndVolume = segment.endGain,
        timeRange = timeRangeOf(segmentStart, length),
      )
    }
  }
}

private const val NO_TAP =
  "MediaToolbox refused an audio processing tap, so a clip's audio effects have nothing to run in."
