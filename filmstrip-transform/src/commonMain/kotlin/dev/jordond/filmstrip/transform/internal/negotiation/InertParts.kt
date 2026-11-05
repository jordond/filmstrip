package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.color.fusedColorMatrices
import dev.jordond.filmstrip.effects.color.identityColorRuns
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.keepsWholeFrame
import dev.jordond.filmstrip.effects.overlay.OverlayEffect
import dev.jordond.filmstrip.effects.overlay.drawsNothingWithin
import dev.jordond.filmstrip.export.InertNotice
import dev.jordond.filmstrip.export.InertReason
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.ClipPlacement
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import dev.jordond.filmstrip.transform.internal.geometry.frameThrough
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.transform.internal.passesCovering
import dev.jordond.filmstrip.transform.internal.runLengthOf
import kotlin.time.Duration

/**
 * An edit with every part that renders nothing taken out, and a notice for each part taken.
 */
internal class InertElision(
  val edit: EditComposition,
  val notices: List<InertNotice>,
)

/**
 * Takes every part of [edit] that renders nothing out of it, so no backend is handed one, and names each in a notice.
 *
 * An effect is inert when its color run multiplies out to the identity, when it is a whole turn or a crop that keeps
 * the whole frame it is handed, or when it is an overlay that draws nothing over any run it is laid on. A clip's color
 * runs are folded with its track's, the way a backend lowers them, and a run is only taken out whole. A track or
 * composition effect has to be inert on every clip it reaches. A fill is inert when no track starts late and every
 * primary clip's own placement covers the frame. A fade is inert when there is no audio under it.
 *
 * Every run is measured from where each clip's trim was written, since only a transcode draws an effect and a copy
 * opening earlier on a sync sample draws none. An edit some track of which will be refused is handed back whole, so
 * the refusal reads what the caller wrote.
 */
internal fun withoutInert(
  edit: EditComposition,
  ask: Ask,
  framing: Framing,
  streams: Streams,
): InertElision {
  val windows =
    edit.tracks.map { track ->
      track.clips.map { clip ->
        trimWindow(clip, ask.infos.getValue(clip.source))
          ?: return InertElision(edit, emptyList())
      }
    }
  val duration = compositionDurationOf(edit, windows)
  val laid =
    edit.tracks.mapIndexed { index, track ->
      val lengths = windows[index].map { (from, to) -> to - from }
      val runLength = runLengthOf(track.looping, track.start, lengths, duration)
      if (trackRefusal(index, track, lengths, runLength) != null) return InertElision(edit, emptyList())
      LaidSpans(track, passesCovering(lengths, runLength).groupBy({ it.index }, { it.spanFrom(track.start) }))
    }

  val notices = mutableListOf<InertNotice>()
  val primary = edit.tracks.first()
  val primaryFrames = primary.clips.map { clip -> clipOutFrame(clip, primary, ask) }
  val tracks =
    laid.mapIndexed { trackIndex, (track, spans) ->
      val reaches =
        track.clips.mapIndexed { clipIndex, clip ->
          Reach(clip.effects + track.effects, clipEntryFrame(clip, ask), spans[clipIndex].orEmpty())
        }
      val identity = identityAcross(reaches, track.effects)
      val clips =
        track.clips.mapIndexed { clipIndex, clip ->
          val where = "clip $clipIndex of track $trackIndex"
          val effects =
            clip.effects.withoutInert(listOf(reaches[clipIndex]), identity.clips[clipIndex], where, notices)
          val (fadeIn, fadeOut) =
            fadesWithout(clip.fadeIn, clip.fadeOut, clipIsSilent(edit, streams, track, clip, ask), where, notices)
          Clip(clip.source, clip.trim, effects, clip.audio, clip.snapWithin, fadeIn, fadeOut)
        }
      val where = "track $trackIndex"
      val (fadeIn, fadeOut) =
        fadesWithout(
          track.fadeIn,
          track.fadeOut,
          track.clips.all { clipIsSilent(edit, streams, track, it, ask) },
          where,
          notices,
        )
      Track(
        clips = clips,
        content = track.content,
        effects = track.effects.withoutInert(reaches, identity.track, where, notices),
        audio = track.audio,
        start = track.start,
        looping = track.looping,
        fadeIn = fadeIn,
        fadeOut = fadeOut,
      )
    }

  val whole = TimeRange.of(Duration.ZERO, duration)
  val compositionReaches =
    listOf(Reach(edit.effects, framing.compositionInputSize, listOf(whole))) +
      primaryFrames.map { Reach(edit.effects, it, listOf(whole)) }
  val effects =
    edit.effects.withoutInert(compositionReaches, edit.effects.identityColorRuns(), "the composition", notices)

  val fillIsInert =
    edit.fill != Fill.Black &&
      edit.tracks.none { it.start > Duration.ZERO } &&
      primary.clips.all { clip -> clipPlacement(clip, primary, framing, ask)?.leavesBars == false }
  val fill =
    if (fillIsInert) {
      notices += InertNotice(FILL, InertReason.NoChangeToFrame, FILL_COVERED)
      Fill.Black
    } else {
      edit.fill
    }

  if (notices.isEmpty()) return InertElision(edit, emptyList())
  return InertElision(EditComposition(tracks, effects, edit.audio, fill), notices)
}

// One run of a declared effect: the chain it sits in, the frame that chain opens on, or null when the clip carries
// no video to measure, and the slots on the composition timeline it is laid over.
private class Reach(
  val chain: List<EffectSpec>,
  val entry: Size?,
  val spans: List<TimeRange>,
)

private data class LaidSpans(
  val track: Track,
  val spans: Map<Int, List<TimeRange>>,
)

// The color effects of one track that can be taken out: per clip, and on the track itself.
private class IdentityColor(
  val clips: List<List<EffectSpec>>,
  val track: List<EffectSpec>,
)

// Every color run each clip's chain folds to the identity, with the track's effects folded in. A track effect goes only
// when every run it sits in is the identity on every clip, and a run goes only when every track effect in it goes, so
// no run is ever split. Effects are matched by reference, since two equal effects can sit in different runs.
private fun identityAcross(
  reaches: List<Reach>,
  trackEffects: List<EffectSpec>,
): IdentityColor {
  val runs =
    reaches.map { reach ->
      val identity = reach.chain.identityColorRuns()
      reach.chain
        .inCanonicalOrder()
        .fusedColorMatrices()
        .map { it.sources }
        .filter { run -> run.all { spec -> identity.any { it === spec } } }
    }
  var track = trackEffects.filter { spec -> runs.all { clipRuns -> clipRuns.any { run -> run.any { it === spec } } } }

  fun List<EffectSpec>.goes() = all { member -> trackEffects.none { it === member } || track.any { it === member } }

  while (true) {
    val kept =
      track.filter { spec ->
        runs.all { clipRuns -> clipRuns.filter { run -> run.any { it === spec } }.all { it.goes() } }
      }
    if (kept.size == track.size) break
    track = kept
  }
  return IdentityColor(runs.map { clipRuns -> clipRuns.filter { it.goes() }.flatten() }, track)
}

private fun List<EffectSpec>.withoutInert(
  reaches: List<Reach>,
  identityColor: List<EffectSpec>,
  where: String,
  notices: MutableList<InertNotice>,
): List<EffectSpec> {
  if (isEmpty()) return this
  return filterNot { spec ->
    val reason =
      when {
        identityColor.any { it === spec } -> InertReason.NoChangeToFrame
        spec.changesNoGeometry(reaches) -> InertReason.NoChangeToFrame
        spec is OverlayEffect && reaches.isNotEmpty() &&
          reaches.all { reach -> reach.spans.all { spec.drawsNothingWithin(it) } } -> InertReason.NeverVisible
        else -> null
      }
    reason?.also { notices += InertNotice(spec.id, it, inertMessage(spec.id, where, it)) } != null
  }
}

private fun EffectSpec.changesNoGeometry(reaches: List<Reach>): Boolean =
  when (this) {
    is Rotate -> {
      degrees % FULL_TURN_DEGREES == 0
    }
    is CropRect -> {
      rect == NormalizedRect.Full
    }
    is Crop -> {
      reaches.isNotEmpty() &&
        reaches.all { reach -> reach.frameEntering(this)?.let(::keepsWholeFrame) == true }
    }
    else -> {
      false
    }
  }

// The frame the chain hands [spec], walked in canonical order through every geometry effect ahead of it.
private fun Reach.frameEntering(spec: EffectSpec): Size? {
  var size = entry ?: return null
  for (step in chain.inCanonicalOrder()) {
    if (step === spec) return size
    size = frameAfter(step, size)
  }
  return null
}

private fun clipEntryFrame(
  clip: Clip,
  ask: Ask,
): Size? =
  ask.infos
    .getValue(clip.source)
    .video
    ?.displaySize

// Where a primary clip lands in the output frame, or null when it carries no video to place.
private fun clipPlacement(
  clip: Clip,
  track: Track,
  framing: Framing,
  ask: Ask,
): ClipPlacement? =
  clipEntryFrame(clip, ask)?.let { entry ->
    placementOf(entry, listOf(clip.effects + track.effects, framing.compositionGeometry), framing.naturalSize)
  }

// The frame a primary clip hands the composition, after its own and its track's geometry.
private fun clipOutFrame(
  clip: Clip,
  track: Track,
  ask: Ask,
): Size? = clipEntryFrame(clip, ask)?.let { frameThrough(it, listOf(clip.effects + track.effects)) }

private fun clipIsSilent(
  edit: EditComposition,
  streams: Streams,
  track: Track,
  clip: Clip,
  ask: Ask,
): Boolean =
  !streams.keepAudio ||
    edit.audio == AudioSpec.Mute ||
    track.content == TrackContent.Video ||
    track.audio == AudioLevel.Mute ||
    clip.audio == AudioLevel.Mute ||
    ask.infos.getValue(clip.source).audio == null

private fun fadesWithout(
  fadeIn: Duration,
  fadeOut: Duration,
  silent: Boolean,
  where: String,
  notices: MutableList<InertNotice>,
): Pair<Duration, Duration> {
  if (!silent) return fadeIn to fadeOut
  if (fadeIn >
    Duration.ZERO
  ) {
    notices += InertNotice(FADE_IN, InertReason.NoStreamToApplyTo, fadeMessage(FADE_IN, where))
  }
  if (fadeOut > Duration.ZERO) {
    notices += InertNotice(FADE_OUT, InertReason.NoStreamToApplyTo, fadeMessage(FADE_OUT, where))
  }
  return Duration.ZERO to Duration.ZERO
}

private fun inertMessage(
  id: String,
  where: String,
  reason: InertReason,
): String =
  when (reason) {
    InertReason.NeverVisible -> "The $id on $where is never drawn, so it was left out."
    else -> "The $id on $where leaves every pixel as it was, so it was left out."
  }

private fun fadeMessage(
  subject: String,
  where: String,
): String = "The $subject on $where has no audio to apply to, so it was left out."

private const val FULL_TURN_DEGREES = 360
private const val FILL = "fill"
private const val FADE_IN = "fadeIn"
private const val FADE_OUT = "fadeOut"
private const val FILL_COVERED = "Every clip covers the frame and no track starts late, so the fill is never seen."
