package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.stillHold
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.LaidPass
import dev.jordond.filmstrip.transform.internal.MAX_LAID_PASSES
import dev.jordond.filmstrip.transform.internal.passCountCovering
import dev.jordond.filmstrip.transform.internal.passesCovering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.resolve.curveOver
import dev.jordond.filmstrip.transform.internal.resolve.fadeEnvelope
import dev.jordond.filmstrip.transform.internal.resolve.isValidOver
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
 * @param fannedGeometry Composition-level geometry this engine runs on each clip's own frame, or
 *   empty when it runs once on the composited frame.
 * @param compositionGain The composition's own level, multiplied into every pass.
 * @param startsAtKeyFrame Whether every pass opens on a sync sample, which is true only on a copy.
 */
internal class TrackLayer(
  private val lowering: StageLowering,
  private val infos: Map<MediaSource, MediaInfo>,
  private val target: LoweringTarget,
  private val layoutFrame: Size,
  private val fannedGeometry: List<EffectSpec>,
  private val compositionGain: Float,
  private val startsAtKeyFrame: Boolean,
) {
  /**
   * Lays [track] over [runLength], each clip opening and closing on its window in [timings].
   */
  fun lay(
    track: Track,
    timings: List<Pair<Duration, Duration>>,
    runLength: Duration,
  ): LaidTrack {
    val lengths = timings.map { (from, to) -> to - from }
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
        val from = timings[pass.index].first
        // The clip's own trimmed length, which its curve and its fades are written over whichever
        // part of it this pass reaches.
        val full = lengths[pass.index]
        val span = pass.spanFrom(track.start)
        val effects =
          lowering.lowerStages(
            stages = listOf(clip.effects + track.effects, fannedGeometry),
            inputSize = info.video?.displaySize ?: target.outputSize,
            layoutSize = info.video?.displaySize ?: layoutFrame,
            span = span,
            target = target,
          )
        lowered += effects
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
          end = from + pass.length,
          effects = effects.mapNotNull { it.resolvedEffect },
          // The one place the scopes multiply. A backend reads this curve and never asks an
          // AudioLevel what it meant again.
          gain =
            clipCurve *
              trackCurve.window(pass.offset, pass.length) *
              ResolvedGain.constant(compositionGain, Duration.ZERO, pass.length),
          startsAtKeyFrame = startsAtKeyFrame,
          span = span,
          sourceIndex = pass.index,
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
 * Checks every clip's level and fades against its own trimmed length in [lengths], then the pass
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
    "that fall outside the clip's trim or run backwards."

private const val TRACK_FADE =
  "A track's fade is negative, longer than the track, or its fade in and fade out together run " +
    "longer than the track, so the two ramps would cross."

private const val CLIP_FADE =
  "A clip's fade is negative, longer than the clip's trim, or its fade in and fade out " +
    "together run longer than the trim, so the two ramps would cross."
