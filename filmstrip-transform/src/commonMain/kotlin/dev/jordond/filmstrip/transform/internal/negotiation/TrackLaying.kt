package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.sourceLengthOf
import dev.jordond.filmstrip.edit.stillHold
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.clipAudioTrackOf
import dev.jordond.filmstrip.transform.internal.LaidPass
import dev.jordond.filmstrip.transform.internal.MAX_LAID_PASSES
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.transform.internal.passCountCovering
import dev.jordond.filmstrip.transform.internal.passesCovering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.resolve.curveOver
import dev.jordond.filmstrip.transform.internal.resolve.fadeEnvelope
import dev.jordond.filmstrip.transform.internal.resolve.isValidOver
import dev.jordond.filmstrip.transform.internal.resolve.resolvedAudioTrackOf
import kotlin.time.Duration

/**
 * One track as the planner laid it, with every effect its passes asked the resolvers for.
 */
internal class LaidTrack(
  val track: ResolvedTrack,
  val lowered: List<LoweredEffect>,
)

/**
 * Lays a track's passes onto the composition timeline and folds every scope's gain into each one.
 *
 * @param compositionGeometry Composition-level geometry, which runs on each clip's own frame after its clip and track
 * effects, and which every clip's placement is folded through.
 * @param naturalSize The frame the composition writes with no height asked of it, which each placement is measured
 *   against.
 * @param compositionGain The composition's own level, multiplied into every pass.
 * @param startsAtKeyFrame Whether every pass opens on a sync sample, which is true only on a copy.
 */
internal class TrackLayer(
  private val lowering: StageLowering,
  private val infos: Map<MediaSource, MediaInfo>,
  private val target: LoweringTarget,
  private val layoutFrame: Size,
  private val compositionGeometry: List<EffectSpec>,
  private val naturalSize: Size,
  private val compositionGain: Float,
  private val startsAtKeyFrame: Boolean,
) {
  /**
   * Lays [track] over [runLength], each clip opening and closing on its window in [windows].
   */
  fun lay(
    track: Track,
    windows: List<LaidWindow>,
    runLength: Duration,
  ): LaidTrack {
    val lengths = windows.map { it.length }
    val lowered = mutableListOf<LoweredEffect>()
    // The track's level times its fade, both read against the whole run the track covers.
    val trackCurve =
      track.audio.curveOver(runLength) * fadeEnvelope(track.fadeIn, track.fadeOut).curveOver(runLength)

    // Where each pass lands on the composition timeline, derived here and nowhere else, since a
    // backend lays what this list says and an effect that reads the time has to be measured against
    // the same run on all of them.
    val clips =
      passesCovering(lengths, runLength).map { pass ->
        val clip = track.clips[pass.index]
        val info = infos.getValue(clip.source)
        val window = windows[pass.index]
        val from = window.from
        // The clip's own length on the timeline, which its curve and its fades are written over
        // whichever part of it this pass reaches.
        val full = lengths[pass.index]
        val span = pass.spanFrom(track.start)
        val effects =
          lowering.lowerStages(
            stages = listOf(clip.effects + track.effects, compositionGeometry),
            inputSize = info.video?.displaySize ?: target.outputSize,
            layoutSize = info.video?.displaySize ?: layoutFrame,
            span = span,
            target = target,
            storedSize = info.video?.let(::storedSizeOf) ?: target.outputSize,
            sourceHasAlpha = info.video?.hasAlpha == true,
          )
        lowered += effects
        val sourceSize = info.video?.displaySize ?: target.outputSize
        val placement = placementOf(sourceSize, listOf(clip.effects + track.effects, compositionGeometry), naturalSize)
        val resolved = effects.mapNotNull { it.resolvedEffect }
        // The clip's own curve over its whole trim, cut only where the run stopped this pass short
        // of it, so a pass that plays the trim out reaches exactly the curve it would have without
        // the loop.
        val clipCurve =
          (clip.audio.curveOver(full) * fadeEnvelope(clip.fadeIn, clip.fadeOut).curveOver(full))
            .let { curve -> if (pass.length < full) curve.window(Duration.ZERO, pass.length) else curve }
        ResolvedClip(
          source = clip.source,
          info = info,
          start = from,
          // A pass that plays the trim out closes on the trim itself, so nothing rounds it away from the source's end.
          end = if (pass.length < full) from + sourceLengthOf(pass.length, window.speed) else window.to,
          effects = resolved,
          // The one place the scopes multiply. A backend reads this curve and never asks an
          // AudioLevel what it meant again.
          gain =
            clipCurve *
              trackCurve.window(pass.offset, pass.length) *
              ResolvedGain.constant(compositionGain, Duration.ZERO, pass.length),
          startsAtKeyFrame = startsAtKeyFrame,
          span = span,
          sourceIndex = pass.index,
          placement = placement,
          canvasEffectIndices = placement.canvases.map { canvasEffectIndexOf(it.chainIndex, effects, resolved) },
          speed = window.speed,
          audioTrack = resolvedAudioTrackOf(clipAudioTrackOf(clip, info), info),
        )
      }

    return LaidTrack(
      track = ResolvedTrack(content = track.content, looping = track.looping, start = track.start, clips = clips),
      lowered = lowered,
    )
  }
}

/**
 * This pass's slot on the composition timeline, for a track that starts at [trackStart].
 */
internal fun LaidPass.spanFrom(trackStart: Duration): TimeRange =
  TimeRange.of(trackStart + offset, trackStart + offset + length)

/**
 * Why the track at [trackIndex] cannot be laid over [runLength], or null when it can.
 *
 * Checks every clip's level and fades against its own length on the timeline in [lengths], then the pass
 * count, then the track's level and fades against the whole run. The passes are counted rather
 * than laid, so a run that would bury a backend in passes costs nothing to refuse.
 */
internal fun trackRefusal(
  trackIndex: Int,
  track: Track,
  lengths: List<Duration>,
  runLength: Duration,
): String? {
  track.clips.forEachIndexed { index, clip ->
    if (!clip.audio.isValidOver(lengths[index])) return CLIP_LEVEL
    if (!fadesFit(clip.fadeIn, clip.fadeOut, lengths[index])) return CLIP_FADE
  }
  if (passCountCovering(lengths, runLength) > MAX_LAID_PASSES) return tooManyPasses(trackIndex)
  if (!track.audio.isValidOver(runLength)) return TRACK_LEVEL
  if (!fadesFit(track.fadeIn, track.fadeOut, runLength)) return TRACK_FADE
  return null
}

/**
 * One clip's source window and the length it runs for on the composition timeline.
 *
 * @property from Where the window opens in source time.
 * @property to Where the window closes in source time.
 * @property length How long the clip runs for on the composition timeline.
 * @property speed How fast the window plays, which is always one for a still.
 */
internal class LaidWindow(
  val from: Duration,
  val to: Duration,
  val length: Duration,
  val speed: Double,
)

/**
 * Every clip's window, by track and then by clip, and how long the composition runs once each opens and closes on it.
 *
 * @property duration The end of the longest track that does not loop.
 */
internal class LaidWindows(
  val tracks: List<List<LaidWindow>>,
  val duration: Duration,
)

/**
 * The one reading of every clip's length in [edit], which the planner, the inert elision and the composition's duration
 * all consume.
 *
 * @param openAt Where each window opens on a copy, passed to [trimWindow].
 * @param onNothing Called for the first clip that trims to nothing, and has to leave the caller.
 */
internal inline fun laidWindows(
  edit: Composition,
  ask: Ask,
  openAt: Duration? = null,
  onNothing: (Clip) -> Nothing,
): LaidWindows {
  val tracks =
    edit.tracks.map { track ->
      track.clips.map { clip -> laidWindowOf(clip, ask.infos.getValue(clip.source), openAt) ?: onNothing(clip) }
    }
  val duration =
    edit.tracks
      .withIndex()
      .filterNot { (_, track) -> track.looping }
      .maxOf { (index, track) -> tracks[index].fold(track.start) { total, window -> total + window.length } }
  return LaidWindows(tracks, duration)
}

/**
 * One clip's [LaidWindow], or null when its trim keeps no frames or its speed leaves it no time on the timeline.
 *
 * A still's window is already the length it holds for, so its speed does not divide it.
 */
internal fun laidWindowOf(
  clip: Clip,
  info: MediaInfo,
  openAt: Duration? = null,
): LaidWindow? {
  val (from, to) = trimWindow(clip, info, openAt) ?: return null
  val speed = if (clip.source is MediaSource.Image) 1.0 else clip.speed
  val length = outputLengthOf(to - from, speed)
  return if (length <= Duration.ZERO) null else LaidWindow(from, to, length, speed)
}

/**
 * The trim window, resolved against the source's real duration, or null when it keeps no frames.
 *
 * A still holds the same pixels for its whole span, so a trim over one takes nothing away but
 * length. The window it resolves to therefore opens at zero and runs for as long as the trim kept,
 * which is what leaves every backend a span to lay and no samples it has to clip.
 *
 * [openAt] overrides where the window opens on a copy, which is the sync sample the cut moved back
 * to. Every length the plan reports is folded from these windows, so a copy that opened earlier than
 * asked has to be measured from where it opened.
 */
internal fun trimWindow(
  clip: Clip,
  info: MediaInfo,
  openAt: Duration? = null,
): Pair<Duration, Duration>? {
  if (clip.source is MediaSource.Image) {
    val hold = stillHold(info.duration, clip.trim)
    return if (hold <= Duration.ZERO) null else Duration.ZERO to hold
  }
  val start = openAt ?: clip.trim?.start ?: Duration.ZERO
  val end = (clip.trim?.endExclusive ?: info.duration).coerceAtMost(info.duration)
  return if (end <= start) null else start to end
}

// Whether neither fade runs backwards and the two together fit inside a scope this long without
// crossing.
private fun fadesFit(
  fadeIn: Duration,
  fadeOut: Duration,
  length: Duration,
): Boolean = fadeIn >= Duration.ZERO && fadeOut >= Duration.ZERO && fadeIn + fadeOut <= length

private fun tooManyPasses(trackIndex: Int): String =
  "The track at index $trackIndex lays more than $MAX_LAID_PASSES passes, which is the most one track may " +
    "lay. A looping track repeats until the composition ends, so a short clip under a long composition asks " +
    "for a pass per repeat. A track laid once lays one pass per clip."

private const val TRACK_LEVEL =
  "A track's audio level asks for a negative or non-finite gain, or its envelope has points " +
    "that fall outside the track or run backwards."

private const val CLIP_LEVEL =
  "A clip's audio level asks for a negative or non-finite gain, or its envelope has points " +
    "that fall outside the clip or run backwards."

private const val TRACK_FADE =
  "A track's fade is negative, longer than the track, or its fade in and fade out together run " +
    "longer than the track, so the two ramps would cross."

private const val CLIP_FADE =
  "A clip's fade is negative, longer than the clip, or its fade in and fade out together run " +
    "longer than the clip, so the two ramps would cross."

// Where the crop at [chainIndex] of a clip's flattened chain sits in its resolved effects. The lowering keeps the
// chain's order, folding a run into one effect that names every spec it was folded from, so counting sources finds the
// crop.
// A crop no resolver ran is refused before the clip reaches a backend, so it answers an index no effect has.
private fun canvasEffectIndexOf(
  chainIndex: Int,
  lowered: List<LoweredEffect>,
  resolved: List<ResolvedEffect>,
): Int {
  var seen = 0
  val crop =
    checkNotNull(lowered.firstOrNull { (seen + it.sources.size > chainIndex).also { _ -> seen += it.sources.size } }) {
      "A clip's canvas is made by effect $chainIndex of its chain, which lowered only $seen effects."
    }
  return crop.resolvedEffect?.let { effect -> resolved.indexOfFirst { it === effect } } ?: -1
}
