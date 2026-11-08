package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.ParityNote
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.InertNotice
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.PlannedEffect
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketWindow
import dev.jordond.filmstrip.transform.internal.copy.CopySupport
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixPhaseOf
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.negotiation.Framing
import dev.jordond.filmstrip.transform.internal.negotiation.Grade
import dev.jordond.filmstrip.transform.internal.negotiation.Phase
import dev.jordond.filmstrip.transform.internal.negotiation.StageLowering
import dev.jordond.filmstrip.transform.internal.negotiation.Streams
import dev.jordond.filmstrip.transform.internal.negotiation.concessionsOf
import dev.jordond.filmstrip.transform.internal.negotiation.encodingOf
import dev.jordond.filmstrip.transform.internal.negotiation.framingOf
import dev.jordond.filmstrip.transform.internal.negotiation.gradeOf
import dev.jordond.filmstrip.transform.internal.negotiation.laidWindowOf
import dev.jordond.filmstrip.transform.internal.negotiation.orElse
import dev.jordond.filmstrip.transform.internal.negotiation.outputFormatOf
import dev.jordond.filmstrip.transform.internal.negotiation.resolveAudioCodec
import dev.jordond.filmstrip.transform.internal.negotiation.streamsOf
import dev.jordond.filmstrip.transform.internal.negotiation.structuralRefusal
import dev.jordond.filmstrip.transform.internal.negotiation.timelineOf
import dev.jordond.filmstrip.transform.internal.negotiation.withoutInert
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The codecs an export that keeps HDR chooses from, most preferred first.
 *
 * A kept grade needs a 10-bit profile, and not every codec has an encoder for one. A backend whose
 * encoders differ passes its own, and a backend that
 * measures whether it can encode HDR at all answers for the encoder this resolves to rather than for
 * any encoder it happens to carry.
 */
@InternalFilmstripApi
public val DEFAULT_HDR_LADDER: List<VideoCodec> = listOf(VideoCodec.Hevc)

/**
 * Negotiates a composition against a device into a verdict and a [NegotiatedExport], the policy
 * shared by every engine that drives a platform encoder.
 *
 * Everything it needs to know about the sources arrives in `infos`, so the whole negotiation can be
 * asserted on without a device. What it cannot decide alone is handed in. The resolvers lower an
 * effect to a platform object, [renderCapabilities] says what that platform can render, [ladder] is
 * the order this engine tries video codecs in, most preferred first, [hdrLadder] is the same but for
 * an export that keeps HDR, [noteOf] is what to tell a caller about an effect this engine only
 * approximates, `supportsCopy` says whether it can copy a stream across without an encoder at
 * all, `muxerAccepts` says whether the muxer will take a source's streams without re-encoding them,
 * and [canToneMap] says whether it can bring an HDR grade down to SDR.
 */
@InternalFilmstripApi
public class ExportPlanner(
  resolvers: List<EffectResolver>,
  private val renderCapabilities: (Size, Boolean) -> RenderCapabilities,
  private val parityOf: (String) -> EffectParity?,
  unclaimedMessage: (String) -> String,
  private val ladder: List<VideoCodec>,
  supportsCopy: Boolean,
  muxerAccepts: (MediaInfo) -> Boolean,
  private val noteOf: (String) -> String? = { null },
  private val canToneMap: Boolean = true,
  private val toneMapLimit: (List<MediaSource>) -> String? = { null },
  private val hdrLadder: List<VideoCodec> = DEFAULT_HDR_LADDER,
) {
  private val lowering = StageLowering(resolvers, unclaimedMessage)
  private val copySupport = CopySupport(supportsCopy, muxerAccepts)

  /**
   * Negotiates [composition] against what [ask] read into a verdict and the graph an export would run.
   *
   * An edit carrying effects this device cannot run is refused with a reason per effect, and the
   * refusal carries the plan the same edit settles on with those effects left out.
   *
   * @param ask What the export is asked for and what the engine read about the sources and the device.
   * @param dropped Effect ids to leave out of the graph entirely.
   */
  public fun negotiate(
    composition: EditComposition,
    ask: Ask,
    dropped: Set<String> = emptySet(),
  ): NegotiatedExport {
    val unsupported =
      when (val first = settle(composition.withoutEffectIds(dropped), dropped, ask)) {
        is Settlement.Settled -> return first.export
        is Settlement.Unsupported -> first.effects
      }

    // Only a negotiation that dropped nothing retries, so a retry that still finds an effect it
    // cannot run is refused without a fallback of its own.
    val fallback =
      if (dropped.isEmpty()) {
        val retry = settle(composition.withoutEffectIds(unsupported.keys), unsupported.keys, ask)
        // The retry drops at least one effect and reports each as an adjustment, so it is never Capable.
        (retry as? Settlement.Settled)?.export?.verdict as? Verdict.Degraded
      } else {
        null
      }
    val reasons = unsupported.map { (id, message) -> FilmstripError.UnsupportedEffect(id, message) }
    return NegotiatedExport(Verdict.Incapable(reasons, fallback), null)
  }

  /**
   * The grade an export of [composition] at [ask] resolves to, or that export's refusal.
   *
   * It runs the phases an export runs up to and including the grade, so a preview that takes the answer through
   * [Ask.grade] shows what the export writes. Nothing past the grade is read, so a concession a strict spec would
   * refuse never reaches this answer.
   */
  public fun exportGradeOf(
    composition: EditComposition,
    ask: Ask,
  ): ExportGrade =
    when (val graded = graded(composition, ask)) {
      is Phase.Refused -> ExportGrade.Refused(graded.error)
      is Phase.Next -> ExportGrade.Settled(graded.value.grade.hdr)
    }

  /**
   * The window an export of [composition] at [ask] copies whose packets an engine reads into [Ask.copyPackets], or
   * null when it does not copy or the copy runs to its source's end.
   *
   * It runs the phases [exportGradeOf] does, so the window is the one the copy then opens and closes on. An edit
   * carrying effects this device cannot run names the window of the plan [negotiate] falls back on without them, since
   * that plan can copy where the edit itself cannot.
   */
  public fun copyPacketWindowOf(
    composition: EditComposition,
    ask: Ask,
  ): CopyPacketWindow? {
    windowOf(composition, ask)?.let { return it }
    // Leaving every effect out is as far as the fallback can go, so an edit that does not copy even then is not
    // negotiated again to find out which effects it would leave out.
    val ids = composition.effectIds()
    if (ids.isEmpty() || windowOf(composition.withoutEffectIds(ids), ask) == null) return null
    val unsupported = (settle(composition, emptySet(), ask) as? Settlement.Unsupported)?.effects ?: return null
    return windowOf(composition.withoutEffectIds(unsupported.keys), ask)
  }

  private fun windowOf(
    composition: EditComposition,
    ask: Ask,
  ): CopyPacketWindow? {
    val graded = (graded(composition, ask) as? Phase.Next)?.value ?: return null
    val opensAt = graded.grade.copyOpensAt ?: return null
    val clip =
      graded.edit.tracks
        .single()
        .clips
        .single()
    val info = ask.infos.getValue(clip.source)
    val window = laidWindowOf(clip, info, opensAt) ?: return null
    return if (window.to < info.duration) CopyPacketWindow(clip.source, window.from, window.to) else null
  }

  // The phases up to the grade, which an export goes on from and a preview reads its grade off.
  private fun graded(
    authored: EditComposition,
    ask: Ask,
  ): Phase<Graded> {
    val streams = streamsOf(authored, ask.spec, ask.infos)
    structuralRefusal(authored, ask.spec, ask.infos, streams)?.let { return Phase.Refused(it) }
    val inert = withoutInert(authored, ask, framingOf(authored, ask).orElse { return Phase.Refused(it) }, streams)
    val framing = framingOf(inert.edit, ask).orElse { return Phase.Refused(it) }
    val grade =
      gradeOf(inert.edit, ask, framing, copySupport, canToneMap, toneMapLimit).orElse { return Phase.Refused(it) }
    return Phase.Next(Graded(streams, inert.edit, inert.notices, framing, grade))
  }

  /**
   * Runs every phase over [authored] in order, stopping at the first that refuses.
   *
   * The shape of the edit is checked as written. Every part that renders nothing is then taken out, and the rest of
   * the phases negotiate what is left, so no backend is handed one and a clip whose only effects did nothing can
   * still copy.
   */
  private fun settle(
    authored: EditComposition,
    dropped: Set<String>,
    ask: Ask,
  ): Settlement {
    val graded = graded(authored, ask).orElse { return refused(it) }
    val streams = graded.streams
    val edit = graded.edit
    val framing = graded.framing
    val grade = graded.grade
    val encoding =
      encodingOf(ask, framing, grade, streams, ladder, hdrLadder, renderCapabilities).orElse { return refused(it) }
    val timeline =
      timelineOf(
        edit,
        ask,
        grade,
        encoding,
        streams,
        lowering,
        framing.naturalSize,
      ).orElse { return refused(it) }

    val rest =
      lowering.lowerStages(
        stages = listOf(edit.effects.filterNot { it.stage == EffectStage.Geometry }),
        inputSize = encoding.outputSize,
        layoutSize = encoding.layoutFrame,
        span = timeline.span,
        target = encoding.target,
      )
    val lowered = timeline.lowered + rest

    val unsupported = linkedMapOf<String, String>()
    // A refused run is reported against every effect the caller wrote into it, so the retry that
    // drops the refused ids drops the run rather than a name the fold made up.
    lowered.forEach { effect ->
      effect.unsupported?.let { message -> effect.sources.forEach { unsupported[it.id] = message } }
    }
    if (unsupported.isNotEmpty()) return Settlement.Unsupported(unsupported)

    val audio = resolveAudioCodec(ask.spec, ask.device, silent = !streams.keepAudio, graded = ask.writesNoFile)
    val output = outputFormatOf(ask, framing, grade, encoding, audio, timeline.tracks)
    val concessions = concessionsOf(ask.spec, framing, grade, encoding, audio, lowered, dropped)
    if (ask.spec.strict && concessions.isNotEmpty()) {
      val refusal = Verdict.Incapable(concessions.map { it.strictRefusal }, withoutUnsupported = null)
      return Settlement.Settled(NegotiatedExport(refusal, null))
    }
    val adjustments = concessions.map { it.adjustment }
    val copyPrefix =
      copyPrefixPhaseOf(ask.copyPackets, grade.path, timeline.tracks, timeline.duration, output.frameRate)
        .orElse { return refused(it) }

    val planned = plannedEffects(edit)
    val plan =
      ExportPlan(
        path = grade.path,
        output = output,
        effectOrder = planned,
        estimate = estimate(output, timeline.duration, grade.path),
        parity = planned.maxByOrNull { it.parity.ordinal }?.parity ?: EffectParity.Exact,
        duration = timeline.duration,
        copyBlockedBy = grade.copyBlockedBy,
        inertNotices = graded.inertNotices,
        composition = authored,
        spec = ask.spec,
        droppedEffects = adjustments.filter { it.kind == AdjustmentKind.EffectDropped },
      )
    val resolved =
      ResolvedComposition(
        tracks = timeline.tracks,
        compositionEffects = rest.mapNotNull { it.resolvedEffect },
        output = output,
        layoutSize = encoding.layoutFrame,
        naturalSize = framing.naturalSize,
        fill = edit.fill,
        duration = timeline.duration,
        hdr = grade.hdr,
        hdrTransfer = grade.hdrTransfer,
        path = grade.path,
        audio = edit.audio,
        adjustments = adjustments,
        encoderName = encoding.encoder?.encoderName,
        copyPrefix = copyPrefix,
      )
    val verdict = if (adjustments.isEmpty()) Verdict.Capable(plan) else Verdict.Degraded(plan, adjustments)
    return Settlement.Settled(NegotiatedExport(verdict, resolved))
  }

  private fun plannedEffects(edit: EditComposition): List<PlannedEffect> =
    (edit.tracks.flatMap { track -> track.clips.flatMap { it.effects } + track.effects } + edit.effects)
      .distinct()
      .inCanonicalOrder()
      .map { spec ->
        val parity = parityOf(spec.id) ?: EffectParity.Exact
        PlannedEffect(
          spec = spec,
          stage = spec.stage,
          parity = parity,
          note =
            if (parity == EffectParity.Exact) {
              null
            } else {
              ParityNote(spec.id, parity, noteOf(spec.id) ?: APPROXIMATE)
            },
        )
      }

  private fun estimate(
    output: OutputFormat,
    duration: Duration,
    path: ExportPath,
  ): ExportEstimate {
    val bits = output.bitrate?.bitsPerSecond?.let { (it * duration.toDouble(DurationUnit.SECONDS)).toLong() }
    return ExportEstimate(
      outputSizeBytesMin = bits?.let { it / BITS_PER_BYTE },
      outputSizeBytesMax = bits?.let { it * HEADROOM_NUMERATOR / HEADROOM_DENOMINATOR / BITS_PER_BYTE },
      approximateDuration = null,
      isCopy = path == ExportPath.Copy,
    )
  }

  private fun refused(error: FilmstripError): Settlement =
    Settlement.Settled(NegotiatedExport(Verdict.Incapable(listOf(error), null), null))

  private class Graded(
    val streams: Streams,
    val edit: EditComposition,
    val inertNotices: List<InertNotice>,
    val framing: Framing,
    val grade: Grade,
  )

  // How one pass over the edit ended: settled on a verdict, or stopped on effects this device
  // cannot run, which the caller may retry without.
  private sealed interface Settlement {
    class Settled(
      val export: NegotiatedExport,
    ) : Settlement

    class Unsupported(
      val effects: Map<String, String>,
    ) : Settlement
  }

  private companion object {
    const val BITS_PER_BYTE = 8L
    const val HEADROOM_NUMERATOR = 13L
    const val HEADROOM_DENOMINATOR = 10L

    const val APPROXIMATE = "The preview and the export diverge by a bounded amount for this effect."
  }
}

private fun EditComposition.effectIds(): Set<String> =
  (tracks.flatMap { track -> track.clips.flatMap { it.effects } + track.effects } + effects).map { it.id }.toSet()

private fun EditComposition.withoutEffectIds(ids: Set<String>): EditComposition {
  if (ids.isEmpty()) return this
  val stripped =
    tracks.map { track ->
      Track(
        clips = track.clips.map { clip -> clip.withEffects(clip.effects.filterNot { it.id in ids }) },
        content = track.content,
        effects = track.effects.filterNot { it.id in ids },
        audio = track.audio,
        start = track.start,
        looping = track.looping,
        fadeIn = track.fadeIn,
        fadeOut = track.fadeOut,
      )
    }
  return EditComposition(stripped, effects.filterNot { it.id in ids }, audio, fill)
}
