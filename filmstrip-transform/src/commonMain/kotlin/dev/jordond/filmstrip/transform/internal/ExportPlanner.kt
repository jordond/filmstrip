package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.ParityNote
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.InertNotice
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.PlannedEffect
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketWindow
import dev.jordond.filmstrip.transform.internal.copy.CopySupport
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixPhaseOf
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.negotiation.AudioEffectLowering
import dev.jordond.filmstrip.transform.internal.negotiation.Framing
import dev.jordond.filmstrip.transform.internal.negotiation.Grade
import dev.jordond.filmstrip.transform.internal.negotiation.NO_AUDIO_TO_WRITE
import dev.jordond.filmstrip.transform.internal.negotiation.Phase
import dev.jordond.filmstrip.transform.internal.negotiation.StageLowering
import dev.jordond.filmstrip.transform.internal.negotiation.Streams
import dev.jordond.filmstrip.transform.internal.negotiation.concessionsOf
import dev.jordond.filmstrip.transform.internal.negotiation.encodingOf
import dev.jordond.filmstrip.transform.internal.negotiation.framingOf
import dev.jordond.filmstrip.transform.internal.negotiation.gradeOf
import dev.jordond.filmstrip.transform.internal.negotiation.hasAudibleAudio
import dev.jordond.filmstrip.transform.internal.negotiation.invalid
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
 * effect to a platform object, `audioResolvers` do the same for an audio effect, [renderCapabilities]
 * says what that platform can render, [ladder] is
 * the order this engine tries video codecs in, most preferred first, [hdrLadder] is the same but for
 * an export that keeps HDR, [noteOf] is what to tell a caller about an effect this engine only
 * approximates, `supportsCopy` says whether it can copy a stream across without an encoder at
 * all, `muxerAccepts` says whether the muxer will take a source's streams without re-encoding them,
 * `copyLimit` names the blocker a limit of the engine's own puts on copying a source, [canToneMap]
 * says whether it can bring an HDR grade down to SDR, [containerCarries] says whether the file an encode writes
 * carries an audio codec, given the video codec it writes, or null when it writes none, the audio codec, and whether
 * that video keeps HDR, and [containerName] names that file's container from the same video codec and HDR, or null
 * when the engine has no name for it.
 */
@InternalFilmstripApi
public class ExportPlanner(
  resolvers: List<EffectResolver>,
  audioResolvers: List<AudioEffectResolver>,
  private val renderCapabilities: (Size, Boolean) -> RenderCapabilities,
  private val parityOf: (String) -> EffectParity?,
  unclaimedMessage: (String) -> String,
  private val ladder: List<VideoCodec>,
  supportsCopy: Boolean,
  muxerAccepts: (MediaInfo) -> Boolean,
  private val noteOf: (String) -> String? = { null },
  private val canToneMap: Boolean = true,
  private val toneMapLimit: (List<MediaSource>) -> String? = { null },
  private val fillLimit: (Fill) -> String? = { null },
  private val hdrLadder: List<VideoCodec> = DEFAULT_HDR_LADDER,
  copyLimit: (MediaInfo) -> CopyBlocker? = { null },
  private val containerCarries: (VideoCodec?, AudioCodec, Boolean) -> Boolean = { _, _, _ -> true },
  private val containerName: (VideoCodec?, Boolean) -> String? = { _, _ -> null },
) {
  private val lowering = StageLowering(resolvers, unclaimedMessage)
  private val audioLowering = AudioEffectLowering(audioResolvers)
  private val copySupport = CopySupport(supportsCopy, muxerAccepts, copyLimit)

  /**
   * Negotiates [composition] against what [ask] read into a verdict and the graph an export would run.
   *
   * An edit carrying effects this device cannot run is refused with a reason per effect, and the
   * refusal carries the plan the same edit settles on with those effects left out. When that plan copies over a window
   * [ask] holds no packets for, the refusal names the window in [NegotiatedExport.fallbackCopyWindow] instead, and
   * [negotiateFallback] settles the plan once they are read.
   *
   * @param ask What the export is asked for and what the engine read about the sources and the device.
   * @param dropped Effect ids to leave out of the graph entirely.
   */
  public fun negotiate(
    composition: Composition,
    ask: Ask,
    dropped: Set<String> = emptySet(),
  ): NegotiatedExport {
    val unsupported =
      when (val first = settle(composition.withoutEffectIds(dropped), dropped, ask)) {
        is Settlement.Settled -> return first.export
        is Settlement.Failed -> return NegotiatedExport(Verdict.Incapable(first.reasons, null), null)
        is Settlement.Unsupported -> first.effects
      }

    // Only a negotiation that dropped nothing retries, so a retry that still finds an effect it
    // cannot run is refused without a fallback of its own.
    if (dropped.isNotEmpty()) return NegotiatedExport(Verdict.Incapable(unsupported.values.toList(), null), null)
    val window = if (ask.copyPackets == null) windowOf(composition.withoutEffectIds(unsupported.keys), ask) else null
    if (window != null) {
      return NegotiatedExport(Verdict.Incapable(unsupported.values.toList(), null), null, window, unsupported)
    }
    return fallbackOf(composition, ask, unsupported)
  }

  /**
   * Settles the plan [export] falls back on, with [ask] now holding the packets over its
   * [NegotiatedExport.fallbackCopyWindow].
   *
   * Only the edit without the effects [export] found unsupported is negotiated again, so no effect of the edit as
   * written is lowered twice.
   */
  public fun negotiateFallback(
    composition: Composition,
    ask: Ask,
    export: NegotiatedExport,
  ): NegotiatedExport = fallbackOf(composition, ask, export.unsupported)

  private fun fallbackOf(
    composition: Composition,
    ask: Ask,
    unsupported: Map<String, FilmstripError>,
  ): NegotiatedExport {
    val retry = settle(composition.withoutEffectIds(unsupported.keys), unsupported.keys, ask)
    // The retry drops at least one effect and reports each as an adjustment, so it is never Capable.
    val fallback = (retry as? Settlement.Settled)?.export?.verdict as? Verdict.Degraded
    return NegotiatedExport(Verdict.Incapable(unsupported.values.toList(), fallback), null)
  }

  /**
   * The grade an export of [composition] at [ask] resolves to, or that export's refusal.
   *
   * It runs the phases an export runs up to and including the grade, so a preview that takes the answer through
   * [Ask.grade] shows what the export writes. Nothing past the grade is read, so a concession a strict spec would
   * refuse never reaches this answer.
   */
  public fun exportGradeOf(
    composition: Composition,
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
   * It runs the phases [exportGradeOf] does and lowers no effect, so the window is the one the copy then opens and
   * closes on. The window of the plan an edit carrying effects this device cannot run falls back on comes from
   * [negotiate], which has lowered them by then.
   */
  public fun copyPacketWindowOf(
    composition: Composition,
    ask: Ask,
  ): CopyPacketWindow? = windowOf(composition, ask)

  private fun windowOf(
    composition: Composition,
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
    authored: Composition,
    ask: Ask,
  ): Phase<Graded> {
    val streams = streamsOf(authored, ask.spec, ask.infos)
    structuralRefusal(authored, ask.spec, ask.infos, streams)?.let { return Phase.Refused(it) }
    val inert = withoutInert(authored, ask, framingOf(authored, ask).orElse { return Phase.Refused(it) }, streams)
    val framing = framingOf(inert.edit, ask).orElse { return Phase.Refused(it) }
    val grade =
      gradeOf(inert.edit, ask, framing, copySupport, canToneMap, toneMapLimit).orElse { return Phase.Refused(it) }
    (inert.edit.fill as? Fill.Blurred)
      ?.let {
        fillLimit(
          it,
        )
      }?.let { return Phase.Refused(invalid("Fill.Blurred: $it")) }
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
    authored: Composition,
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
        audioLowering,
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
    val mix = audioLowering.lower(edit.audioEffects, timeline.span)
    val loweredAudio = timeline.loweredAudio + mix

    val unsupported = linkedMapOf<String, FilmstripError>()
    val reasons = mutableListOf<FilmstripError>()
    var failed = false

    fun report(
      ids: List<String>,
      refusal: String?,
      failure: FilmstripError?,
    ) {
      failure?.let {
        failed = true
        reasons += it
      }
      refusal?.let { message ->
        ids.forEach { id ->
          val error = FilmstripError.UnsupportedEffect(id, message)
          unsupported[id] = error
          reasons += error
        }
      }
    }
    // A refused run is reported against every effect the caller wrote into it, so the retry that
    // drops the refused ids drops the run rather than a name the fold made up.
    lowered.forEach { effect -> report(effect.sources.map { it.id }, effect.unsupported, effect.failed) }
    loweredAudio.forEach { effect -> report(listOf(effect.spec.id), effect.unsupported, effect.failed) }
    if (failed) return Settlement.Failed(reasons)
    if (unsupported.isNotEmpty()) return Settlement.Unsupported(unsupported)

    val video = encoding.video.codec.takeUnless { edit.audio == AudioSpec.AudioOnly }
    val hdr = grade.hdrTransfer != null
    val audio =
      resolveAudioCodec(
        spec = ask.spec,
        device = ask.device,
        silent = !streams.keepAudio,
        graded = ask.writesNoFile,
        copied = grade.path == ExportPath.Copy,
        // A mix effect can put sound into a mix nothing else is heard in.
        audible = hasAudibleAudio(edit, streams, ask) || edit.audioEffects.isNotEmpty(),
        container = containerLabel(video, hdr),
      ) { codec -> containerCarries(video, codec, hdr) }
    val output = outputFormatOf(ask, framing, grade, encoding, audio, timeline.tracks)
    if (!streams.keepVideo && output.audioCodec == AudioCodec.None) return refused(invalid(NO_AUDIO_TO_WRITE))
    val degraded = lowered.flatMap { it.degraded } + loweredAudio.flatMap { it.degraded }
    val concessions = concessionsOf(ask.spec, framing, grade, encoding, audio, degraded, dropped)
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
        mixAudioEffects = mix.mapNotNull { it.resolvedEffect },
        previewClock = ask.previewClock,
      )
    val verdict = if (adjustments.isEmpty()) Verdict.Capable(plan) else Verdict.Degraded(plan, adjustments)
    return Settlement.Settled(NegotiatedExport(verdict, resolved))
  }

  private fun plannedEffects(edit: Composition): List<PlannedEffect> =
    canonicalOrder(
      (edit.tracks.flatMap { track -> track.clips.flatMap { it.effects } + track.effects } + edit.effects).distinct(),
    ).map { spec ->
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

  // How the audio messages name the file an encode writes, with the video codec that chose its container.
  private fun containerLabel(
    video: VideoCodec?,
    hdr: Boolean,
  ): String {
    val name = containerName(video, hdr) ?: return "This export's container"
    val export = if (video == null) "this audio-only export" else "this ${if (hdr) "HDR " else ""}$video export"
    return "The $name file $export writes"
  }

  private fun refused(error: FilmstripError): Settlement =
    Settlement.Settled(NegotiatedExport(Verdict.Incapable(listOf(error), null), null))

  private class Graded(
    val streams: Streams,
    val edit: Composition,
    val inertNotices: List<InertNotice>,
    val framing: Framing,
    val grade: Grade,
  )

  // How one pass over the edit ended: settled on a verdict, stopped on an effect whose resolution failed, with every
  // reason in chain order, or stopped on effects this device cannot run, each id paired with the error it is refused
  // with, which the caller may retry without.
  private sealed interface Settlement {
    class Settled(
      val export: NegotiatedExport,
    ) : Settlement

    class Failed(
      val reasons: List<FilmstripError>,
    ) : Settlement

    class Unsupported(
      val effects: Map<String, FilmstripError>,
    ) : Settlement
  }

  private companion object {
    const val BITS_PER_BYTE = 8L
    const val HEADROOM_NUMERATOR = 13L
    const val HEADROOM_DENOMINATOR = 10L

    const val APPROXIMATE = "The preview and the export diverge by a bounded amount for this effect."
  }
}

private fun Composition.withoutEffectIds(ids: Set<String>): Composition {
  if (ids.isEmpty()) return this
  val stripped =
    tracks.map { track ->
      track
        .withClips(
          track.clips.map { clip ->
            clip
              .withEffects(clip.effects.filterNot { it.id in ids })
              .withAudioEffects(clip.audioEffects.filterNot { it.id in ids })
          },
        ).withEffects(track.effects.filterNot { it.id in ids })
        .withAudioEffects(track.audioEffects.filterNot { it.id in ids })
    }
  return withTracks(stripped)
    .withEffects(effects.filterNot { it.id in ids })
    .withAudioEffects(audioEffects.filterNot { it.id in ids })
}
