package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.resolve.flatGain
import dev.jordond.filmstrip.transform.internal.resolve.isValid
import dev.jordond.filmstrip.transform.internal.runLengthOf
import kotlin.time.Duration

/**
 * Every track laid onto the composition timeline, and how long that timeline runs.
 *
 * Every clip's window is settled before any gain is folded, because a looping track's curve is
 * written against the run it covers and there is no length to anchor a fade out to until the
 * composition's own duration is known.
 */
internal fun timelineOf(
  edit: EditComposition,
  ask: Ask,
  grade: Grade,
  encoding: Encoding,
  streams: Streams,
  lowering: StageLowering,
  naturalSize: Size,
): Phase<Timeline> {
  val windows =
    edit.tracks.map { track ->
      track.clips.map { clip ->
        trimWindow(clip, ask.infos.getValue(clip.source), grade.copyOpensAt)
          ?: return Phase.Refused(invalid("Clip ${clip.source.describe()} trims to nothing."))
      }
    }
  // The structural refusals already turned away an edit where every track loops, so at least one
  // track bounds the composition.
  val duration = compositionDurationOf(edit, windows)

  if (!edit.audio.isValid) return Phase.Refused(invalid(COMPOSITION_LEVEL))
  val compositionGeometry = edit.effects.filter { it.stage == EffectStage.Geometry }
  val layer =
    TrackLayer(
      lowering = lowering,
      infos = ask.infos,
      target = encoding.target,
      layoutFrame = encoding.layoutFrame,
      compositionGeometry = compositionGeometry,
      naturalSize = naturalSize,
      compositionGain = edit.audio.flatGain(),
      startsAtKeyFrame = grade.path == ExportPath.Copy,
    )
  val laid =
    edit.tracks.mapIndexed { index, track ->
      val lengths = windows[index].map { (from, to) -> to - from }
      val runLength = runLengthOf(track.looping, track.start, lengths, duration)
      trackRefusal(index, track, lengths, runLength)?.let { return Phase.Refused(invalid(it)) }
      layer.lay(track, windows[index], runLength)
    }

  val primary = laid.first().track
  if (primary.clips.isEmpty()) return Phase.Refused(invalid(PRIMARY_LAYS_NOTHING))
  // The passes tile the primary's run, so the run covers no output slot exactly when none of its
  // passes does. An audio-only export writes no picture for it to cover.
  val primaryRun =
    TimeRange.of(
      primary.start,
      checkNotNull(
        primary.clips
          .last()
          .span.endExclusive,
      ),
    )
  val frameRate = encoding.target.frameRate
  if (streams.keepVideo && outputSlotsOf(primaryRun, frameRate).isEmpty()) {
    return Phase.Refused(invalid(primaryCoversNoFrame(checkNotNull(primaryRun.duration), frameRate)))
  }

  return Phase.Next(Timeline(duration, laid.map { it.track }, laid.flatMap { it.lowered }))
}

/**
 * How long the composition runs once every clip opens and closes on its window in [windows]: the longest track that
 * does not loop.
 */
internal fun compositionDurationOf(
  edit: EditComposition,
  windows: List<List<Pair<Duration, Duration>>>,
): Duration =
  edit.tracks
    .withIndex()
    .filterNot { (_, track) -> track.looping }
    .maxOf { (index, track) -> windows[index].fold(track.start) { total, (from, to) -> total + (to - from) } }

private const val PRIMARY_LAYS_NOTHING = "The primary track starts at or after the composition ends."

private fun primaryCoversNoFrame(
  run: Duration,
  frameRate: Int,
): String =
  "The primary track's video runs $run, starting and ending on the same output frame at $frameRate fps, so " +
    "it covers none."

private const val COMPOSITION_LEVEL = "The composition's audio volume asks for a negative or non-finite gain."
