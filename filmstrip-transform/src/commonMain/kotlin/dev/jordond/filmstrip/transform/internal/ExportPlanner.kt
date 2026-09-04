package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.ParityNote
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.stillHold
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectScope
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.FoldedSpec
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.fusedColorMatrices
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.PlannedEffect
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.MAX_STILL_FRAME
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.geometry.frameWithin
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.audioCodecOf
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.videoCodecOf
import kotlin.math.abs
import kotlin.math.roundToInt
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
 * approximates, [supportsPassthrough] says whether it can copy a stream across without an encoder at
 * all, [canCopy] says whether the muxer will take a source's streams without re-encoding them,
 * [canToneMap] says whether it can bring an HDR grade down to SDR, and
 * [compositionGeometryPerClip] says whether composition-level geometry has to run on each clip's own
 * frame.
 */
@InternalFilmstripApi
public class ExportPlanner(
  private val resolvers: List<EffectResolver>,
  private val renderCapabilities: (Size, Boolean) -> RenderCapabilities,
  private val parityOf: (String) -> EffectParity?,
  private val unclaimedMessage: (String) -> String,
  private val ladder: List<VideoCodec>,
  private val supportsPassthrough: Boolean,
  private val canCopy: (MediaInfo) -> Boolean,
  private val noteOf: (String) -> String? = { null },
  private val canToneMap: Boolean = true,
  private val compositionGeometryPerClip: Boolean = false,
  private val hdrLadder: List<VideoCodec> = DEFAULT_HDR_LADDER,
) {
  // Where the built-in catalogue sits, or -1 when it was never registered.
  private val catalogue: Int = resolvers.indexOfFirst { it is BuiltInEffectResolver }

  // The resolvers registered ahead of the catalogue. Folding a run of colour effects rewrites it
  // into one ColorMatrix, which the catalogue claims, so a resolver that outranks the catalogue for
  // one of the effects the caller wrote is asked for that effect and the run folds around it.
  // Without this its lowering would disappear into the fold with nothing reported.
  //
  // Empty with no catalogue registered: there is no claim to outrank then, and the run folds the
  // way it does for every backend.
  private val overriding: List<EffectResolver> = if (catalogue < 0) emptyList() else resolvers.take(catalogue)

  /**
   * Negotiates [composition] against [device] into a verdict and the graph an export would run.
   *
   * @param openings Where a stream copy of each source could open, which is the sync sample at or
   *   before that clip's cut. A source with no entry names none, and a trim over it blocks the copy.
   *   Only an engine can read this, so it probes and hands the answers in the way it does `infos`.
   * @param dropped Effect ids to leave out of the graph entirely.
   * @param layoutSize The output frame text is laid out against, for a caller planning a frame
   *   smaller than the one an export writes. Null lays text out against the frame this negotiation
   *   settles on, which is what an export does.
   */
  public fun negotiate(
    composition: EditComposition,
    spec: ExportSpec,
    device: DeviceCapabilities,
    infos: Map<MediaSource, MediaInfo>,
    openings: Map<MediaSource, Duration> = emptyMap(),
    dropped: Set<String> = emptySet(),
    layoutSize: Size? = null,
  ): NegotiatedExport {
    val edit = composition.withoutEffectIds(dropped)
    val primary = edit.tracks.firstOrNull() ?: return incapable(NO_TRACKS)

    if (edit.tracks.drop(1).any { it.content != TrackContent.Audio }) return incapable(SECOND_VIDEO_TRACK)
    if (primary.clips.isEmpty()) return incapable("The primary track has no clips.")
    if (primary.content == TrackContent.Audio) return incapable(NO_PRIMARY_VIDEO)
    if (edit.tracks.all { it.looping }) return incapable(EVERY_TRACK_LOOPS)

    // What each scope asks for can cancel out: an audio-only output over a video-only track, or a
    // kept-audio output over a source that carries none, leaves no track to write.
    val keepVideo = edit.audio != AudioSpec.AudioOnly
    val anySourceAudio = edit.tracks.any { track -> track.clips.any { infos[it.source]?.audio != null } }
    val keepAudio = spec.audioCodec != AudioCodec.None && edit.audio != AudioSpec.Remove && anySourceAudio
    if (edit.tracks.none { it.contributes(keepAudio, keepVideo) }) return incapable(NOTHING_TO_ENCODE)

    edit.tracks.flatMap { it.clips }.forEach { clip ->
      val info = infos[clip.source] ?: return unreadable(clip.source)
      if (!info.isExportable) return protected(clip.source)
    }

    // A clip-only effect reads where a frame sits inside the run it is drawn over. A track effect
    // and a composition-level geometry effect are lowered by fanning onto each clip on the backends
    // that have no pass of their own to run them in, so the same declaration would draw one run on
    // one engine and a run per clip on another.
    val misplaced =
      (edit.effects + edit.tracks.flatMap { it.effects }).firstOrNull { it.scope == EffectScope.ClipOnly }
    if (misplaced != null) return incapable(ExportError.UnsupportedEffect(misplaced.id, CLIP_ONLY_SCOPE))

    // A looping track lays its clips down again from the top, so a clip on one holds more than one
    // slot on the timeline and there is no single run for a clip-only effect to measure against.
    val looped =
      edit.tracks
        .filter { it.looping }
        .flatMap { track -> track.clips.flatMap { it.effects } }
        .firstOrNull { it.scope == EffectScope.ClipOnly }
    if (looped != null) return incapable(ExportError.UnsupportedEffect(looped.id, CLIP_ONLY_LOOPING))

    val firstClip = primary.clips.first()
    // A still is held for a span rather than decoded from one, and its bounds are a sensor's rather
    // than an encoder's, so it is never what the output frame and the output cadence are measured
    // from while a clip that decodes video is on the track. Only a leading still looks past the
    // first clip, and a track that carries no video at all still falls back to it.
    val frameClip =
      firstClip.takeUnless { it.source is MediaSource.Image }
        ?: primary.clips.firstOrNull { carriesVideo(it, infos) }
        ?: firstClip
    val framedByStill = frameClip.source is MediaSource.Image
    val firstVideo = infos.getValue(frameClip.source).video ?: return incapable(NO_PRIMARY_VIDEO)

    // Clip and track geometry always finishes before the composition's own runs, so the frame the
    // composition's geometry reads is chained from these two scopes alone.
    val clipStage = frameClip.effects + primary.effects
    val compositionGeometrySpecs = edit.effects.filter { it.stage == EffectStage.Geometry }
    val compositionInputSize = frameThrough(firstVideo.displaySize, listOf(clipStage))
    val requestedSize = requestedFrame(compositionInputSize, compositionGeometrySpecs, spec.targetHeight)

    // Settled before the codec, because a grade that survives to the encoder decides which codecs
    // are eligible at all.
    val sourceTransfers =
      edit.tracks
        .flatMap { it.clips }
        .mapNotNull { infos[it.source]?.video }
        .mapTo(mutableSetOf()) { it.hdrTransfer }
    val sourceIsHdr = sourceTransfers.any { it != null }
    // Sources that disagree, an HDR clip sat beside an SDR one included, have no one transfer that
    // describes the output, so there is no grade to keep and the export tone-maps or refuses.
    val gradesAgree = sourceTransfers.size == 1
    // Measured against the requested frame, since a copy runs no encoder and so has no ceiling to
    // clamp to. This has to be known before HDR is resolved, since
    // it is one of the things that decides whether the grade can be kept.
    val firstInfo = infos.getValue(firstClip.source)
    // The one clip a copy could ever cover, since a copy needs a single track carrying a single
    // clip. Where that copy would open is settled here and nowhere else, so a backend lays the
    // window this resolves to rather than reading the source's sync samples again.
    val onlyClip =
      edit.tracks
        .singleOrNull()
        ?.clips
        ?.singleOrNull()
    val copyOpening = onlyClip?.let { copyOpening(it, openings[it.source]) }
    val blockers = copyBlockers(edit, spec, firstVideo.displaySize, requestedSize, copyOpening, firstInfo)
    val copyKeepsHdr = blockers.isEmpty()
    val hdr =
      resolveHdr(spec.hdr, sourceIsHdr, gradesAgree, device.supportsHdrEncoding, canToneMap, copyKeepsHdr)
        ?: return incapable(
          when {
            !canToneMap -> NO_HDR_PATH
            !gradesAgree -> MIXED_GRADES
            else -> NO_HDR_ENCODER
          },
        )
    // A copy that already keeps the grade needs nothing from the ladder, so it does not pin the
    // codec to HEVC for a stream nobody is going to encode.
    val encodesHdr = sourceIsHdr && hdr == ResolvedHdr.Keep && !copyKeepsHdr
    val path = exportPath(copyKeepsHdr, hdr)
    // A copy the grade alone ruled out has nothing else wrong with it, so that is the only term
    // left to report.
    val copyBlockedBy =
      if (copyKeepsHdr && path != ExportPath.Transmux) listOf(CopyBlocker.GradeMustToneMap) else blockers
    // Non-null only on a copy, and then it is the window every backend lays.
    val copyOpensAt = copyOpening.takeIf { path == ExportPath.Transmux }
    // A source that is HLG must not be written as PQ, so the transfer travels with the grade.
    // Keeping a grade already means the sources agree on one, so there is exactly one to take.
    val hdrTransfer = if (hdr == ResolvedHdr.Keep) sourceTransfers.singleOrNull() else null

    val codec =
      resolveVideoCodec(spec, device, encodesHdr, ladder, hdrLadder)
        ?: return incapable(ExportError.NoEncoder(spec.videoCodec, NO_ENCODER))
    val (codecAdjustments, videoCodec) = codec
    val encoder = device.encoderFor(videoCodec)

    // A frame above the encoder's ceiling is clamped and reported, not refused, since
    // AdjustmentKind.ResolutionClamped is what the model calls exactly this. A composition framed
    // by a still is held to MAX_STILL_FRAME on top of that, because a sensor's bounds are not a
    // frame any encoder has agreed to, and a spec that names its own height has already said what
    // the frame should be. `strict` is how a caller says the number was not negotiable, and that
    // turns the same clamp into a refusal. A copy runs no encoder, so there is no ceiling to clamp
    // to and the frame goes across whole.
    val ceiling =
      tightest(encoder?.maxSize, MAX_STILL_FRAME.takeIf { framedByStill && spec.targetHeight == null })
    val outputSize =
      if (path == ExportPath.Transmux) {
        requestedSize
      } else {
        requestedSize.fittedTo(ceiling, encoder?.sizeAlignment ?: SIZE_ALIGNMENT)
      }
    val overCeiling =
      path != ExportPath.Transmux &&
        ceiling != null &&
        (requestedSize.width > ceiling.width || requestedSize.height > ceiling.height)
    val layoutFrame = layoutSize ?: outputSize

    // A copy runs no encoder, so like the frame it has no rate ceiling to clamp to.
    val frameRate =
      resolveFrameRate(spec, firstVideo.frameRate, encoder?.maxFrameRate.takeIf { path != ExportPath.Transmux })
    val capabilities = renderCapabilities(outputSize, encodesHdr)
    // A transmuxed copy runs no effect chain and a tone-mapped export is SDR by the time one runs,
    // so the transfer an effect is lowered against is the one the encoder is actually handed.
    val encodedTransfer = hdrTransfer.takeIf { encodesHdr }

    // Every clip's chain is measured from its own source frame, and every normalised parameter
    // against the one output frame the whole composition lands on.
    val compositionGain = edit.audio.gain()
    val fannedGeometry = if (compositionGeometryPerClip) compositionGeometrySpecs else emptyList()
    val asked = mutableListOf<LoweredEffect>()
    val tracks =
      edit.tracks.map { track ->
        // Every clip's window is settled before any gain is folded, because a track's envelope is
        // written against the whole run of clips and there is no length to anchor a fade out to
        // until all of them are known.
        val timings =
          track.clips.map { clip ->
            trimWindow(clip, infos.getValue(clip.source), copyOpensAt)
              ?: return incapable("Clip ${clip.source.describe()} trims to nothing.")
          }
        val trackLength = timings.fold(Duration.ZERO) { total, (from, to) -> total + (to - from) }
        val trackEnvelope = track.audio as? AudioLevel.Envelope
        if (trackEnvelope != null && !trackEnvelope.isValidOver(trackLength)) return incapable(TRACK_ENVELOPE)
        val trackCurve = track.audio.curveOver(trackLength)
        // Where each clip lands on the composition timeline, derived here and nowhere else, since
        // every backend lays its clips end to end from the track's own start and an effect that
        // reads the time has to be measured against the same run on all of them.
        var cursor = track.start
        var withinTrack = Duration.ZERO
        ResolvedTrack(
          content = track.content,
          looping = track.looping,
          start = track.start,
          clips =
            track.clips.mapIndexed { index, clip ->
              val info = infos.getValue(clip.source)
              val timing = timings[index]
              val length = timing.second - timing.first
              val span = TimeRange.of(cursor, cursor + length)
              cursor += length
              val clipStartInTrack = withinTrack
              withinTrack += length
              val clipEnvelope = clip.audio as? AudioLevel.Envelope
              if (clipEnvelope != null && !clipEnvelope.isValidOver(length)) return incapable(CLIP_ENVELOPE)
              val resolved =
                resolve(
                  stages = listOf(clip.effects + track.effects, fannedGeometry),
                  inputSize = info.video?.displaySize ?: outputSize,
                  outputSize = outputSize,
                  layoutSize = info.video?.displaySize ?: layoutFrame,
                  capabilities = capabilities,
                  frameRate = frameRate,
                  hdrTransfer = encodedTransfer,
                  span = span,
                )
              asked += resolved
              ResolvedClip(
                source = clip.source,
                info = info,
                start = timing.first,
                end = timing.second,
                effects = resolved.mapNotNull { it.resolvedEffect },
                // The one place the scopes multiply. A backend reads this curve and never asks an
                // AudioLevel what it meant again.
                gain =
                  clip.audio.curveOver(length) *
                    trackCurve.window(clipStartInTrack, length) *
                    ResolvedGain.constant(compositionGain, Duration.ZERO, length),
                startsAtKeyFrame = path == ExportPath.Transmux,
                span = span,
              )
            },
        )
      }

    val duration =
      tracks.filterNot { it.looping }.maxOfOrNull { it.duration } ?: return incapable(EVERY_TRACK_LOOPS)
    val compositionSpan = TimeRange.of(Duration.ZERO, duration)

    val compositionGeometry =
      if (compositionGeometryPerClip) {
        emptyList()
      } else {
        resolve(
          stages = listOf(compositionGeometrySpecs),
          inputSize = compositionInputSize,
          outputSize = outputSize,
          layoutSize = compositionInputSize,
          capabilities = capabilities,
          frameRate = frameRate,
          hdrTransfer = encodedTransfer,
          span = compositionSpan,
        )
      }
    val compositionRest =
      resolve(
        stages = listOf(edit.effects.filterNot { it.stage == EffectStage.Geometry }),
        inputSize = outputSize,
        outputSize = outputSize,
        layoutSize = layoutFrame,
        capabilities = capabilities,
        frameRate = frameRate,
        hdrTransfer = encodedTransfer,
        span = compositionSpan,
      )
    asked += compositionGeometry
    asked += compositionRest

    val unsupported = linkedMapOf<String, String>()
    // A refused run is reported against every effect the caller wrote into it, so the retry that
    // drops the refused ids drops the run rather than a name the fold made up.
    asked.forEach { resolved ->
      resolved.unsupported?.let { message -> resolved.sources.forEach { unsupported[it.id] = message } }
    }
    if (unsupported.isNotEmpty()) {
      return incapableWithFallback(composition, spec, device, infos, openings, dropped, unsupported, layoutSize)
    }

    val planned = plannedEffects(edit)
    val fit =
      (clipStage.inCanonicalOrder() + compositionGeometrySpecs.inCanonicalOrder())
        .filterIsInstance<Scale>()
        .lastOrNull()
        ?.fit ?: Fit.Contain

    // AudioSpec.AudioOnly writes a file with no video track, and OutputFormat has no way to say
    // that. The video half of the format is reported as resolved and then not written, which is
    // what the ffmpeg backend does with the same edit.
    val audioCodec = resolveAudioCodec(spec, device, silent = !keepAudio)
    val audioEncoder = device.audio.firstOrNull { it.codec == audioCodec.second }
    val output =
      if (path == ExportPath.Transmux) {
        // A copy writes the source's own codecs and audio format untouched, since nothing here
        // re-encodes to change them.
        val sourceAudio = infos.getValue(firstClip.source).audio
        OutputFormat(
          size = outputSize,
          videoCodec = videoCodecOf(firstVideo.codec.kind),
          audioCodec = sourceAudio?.let { audioCodecOf(it.codec.kind) } ?: AudioCodec.None,
          bitrate = spec.bitrate,
          frameRate = frameRate,
          audioFormat = sourceAudio?.let { AudioFormat(sampleRate = it.sampleRate, channelCount = it.channelCount) },
        )
      } else {
        OutputFormat(
          size = outputSize,
          videoCodec = videoCodec,
          audioCodec = audioCodec.second,
          bitrate = spec.bitrate,
          frameRate = frameRate,
          audioFormat = if (audioCodec.second == AudioCodec.None) null else audioFormat(tracks, audioEncoder),
        )
      }
    val adjustments =
      adjustments(
        spec = spec,
        edit = edit,
        requestedSize = requestedSize,
        outputSize = outputSize,
        ceiling = ceiling.takeIf { overCeiling },
        requestedFrameRate = spec.frameRate,
        frameRate = frameRate,
        codec = codecAdjustments + audioCodec.first,
        hdr = hdr,
        sourceIsHdr = sourceIsHdr,
        gradesAgree = gradesAgree,
        degraded = asked.flatMap { it.degraded }.distinct(),
        dropped = dropped,
      )
    if (spec.strict && adjustments.isNotEmpty()) {
      return incapableStrict(adjustments, requestedSize, ceiling ?: outputSize)
    }

    val plan =
      ExportPlan(
        path = path,
        output = output,
        effectOrder = planned,
        estimate = estimate(spec, duration, path),
        parity = planned.minByOrNull { it.parity.ordinal }?.parity ?: EffectParity.Exact,
        duration = duration,
        copyBlockedBy = copyBlockedBy,
        composition = edit,
        spec = spec,
      )

    return NegotiatedExport(
      verdict = if (adjustments.isEmpty()) Verdict.Capable(plan) else Verdict.Degraded(plan, adjustments),
      composition =
        NegotiatedComposition(
          tracks = tracks,
          compositionGeometry = compositionGeometry.mapNotNull { it.resolvedEffect },
          compositionInputSize = compositionInputSize,
          compositionEffects = compositionRest.mapNotNull { it.resolvedEffect },
          output = output,
          layoutSize = layoutFrame,
          fit = fit,
          fill = edit.fill,
          duration = duration,
          hdr = hdr,
          hdrTransfer = hdrTransfer,
          path = path,
          audio = edit.audio,
          adjustments = adjustments,
          encoderName = encoder?.encoderName,
        ),
    )
  }

  private fun resolve(
    stages: List<List<EffectSpec>>,
    inputSize: Size,
    outputSize: Size,
    layoutSize: Size,
    capabilities: RenderCapabilities,
    frameRate: Int,
    hdrTransfer: HdrTransfer?,
    span: TimeRange,
  ): List<LoweredEffect> {
    var size = inputSize
    // The layout frame is chained through the same geometry the drawn frame is, so an effect late
    // in the stage is measured against the frame an export would hand it rather than the first one.
    var layout = layoutSize
    // A kept grade is written in BT.2020, so a resolver reading the colour space off an HDR export
    // sees the one the encoder is handed rather than the SDR default.
    val colorSpace = if (hdrTransfer != null) ColorSpace.Bt2020 else ColorSpace.Bt709
    val ordered = stages.flatMap { it.inCanonicalOrder() }
    val chain =
      foldedChain(
        ordered = ordered,
        overridden =
          overriddenColour(
            ordered,
            inputSize,
            layoutSize,
            outputSize,
            colorSpace,
            hdrTransfer,
            frameRate,
            span,
            capabilities,
          ),
      )

    return chain.entries.mapIndexed { position, entry ->
      val spec = entry.spec
      val attributes = Attributes(size, outputSize, layout, colorSpace, hdrTransfer, frameRate.toFloat(), span)
      val resolution =
        chain.resolutions[position] ?: resolvers.firstNotNullOfOrNull { it.resolve(spec, capabilities, attributes) }

      size = frameAfter(spec, size)
      layout = frameAfter(spec, layout)

      when (resolution) {
        is EffectResolution.Resolved -> LoweredEffect(spec, entry.sources, resolution.effect, null, null)
        is EffectResolution.Degraded -> LoweredEffect(spec, entry.sources, resolution.effect, null, resolution.message)
        is EffectResolution.Unsupported -> LoweredEffect(spec, entry.sources, null, resolution.message, null)
        null -> LoweredEffect(spec, entry.sources, null, unclaimedMessage(entry.sources.authoredIds()), null)
      }
    }
  }

  /**
   * The effects an [overriding] resolver claims out of the runs the fold would otherwise swallow,
   * keyed by their place in [ordered], with the resolution it gave.
   *
   * Only an effect inside a run of two or more is asked about, since a run of one folds to the
   * effect the caller wrote and reaches its resolver unchanged. The resolution is kept rather than
   * thrown away, so no resolver is asked for the same effect twice.
   */
  private fun overriddenColour(
    ordered: List<EffectSpec>,
    inputSize: Size,
    layoutSize: Size,
    outputSize: Size,
    colorSpace: ColorSpace,
    hdrTransfer: HdrTransfer?,
    frameRate: Int,
    span: TimeRange,
    capabilities: RenderCapabilities,
  ): Map<Int, EffectResolution> {
    if (overriding.isEmpty()) return emptyMap()

    val colour = ordered.map { colorMatrixOf(it) != null }
    val claimed = mutableMapOf<Int, EffectResolution>()
    var size = inputSize
    var layout = layoutSize

    ordered.forEachIndexed { index, spec ->
      val folds = colour[index] && (colour.getOrElse(index - 1) { false } || colour.getOrElse(index + 1) { false })
      if (folds) {
        val attributes = Attributes(size, outputSize, layout, colorSpace, hdrTransfer, frameRate.toFloat(), span)
        overriding.firstNotNullOfOrNull { it.resolve(spec, capabilities, attributes) }?.let { claimed[index] = it }
      }
      size = frameAfter(spec, size)
      layout = frameAfter(spec, layout)
    }

    return claimed
  }

  private fun incapableWithFallback(
    composition: EditComposition,
    spec: ExportSpec,
    device: DeviceCapabilities,
    infos: Map<MediaSource, MediaInfo>,
    openings: Map<MediaSource, Duration>,
    dropped: Set<String>,
    unsupported: Map<String, String>,
    layoutSize: Size?,
  ): NegotiatedExport {
    val reasons = unsupported.map { (id, message) -> ExportError.UnsupportedEffect(id, message) }

    val fallback =
      if (dropped.isEmpty()) {
        when (val retry = negotiate(composition, spec, device, infos, openings, unsupported.keys, layoutSize).verdict) {
          is Verdict.Capable -> retry.plan
          is Verdict.Degraded -> retry.plan
          is Verdict.Incapable -> null
        }
      } else {
        null
      }
    return NegotiatedExport(Verdict.Incapable(reasons, fallback), null)
  }

  private fun incapableStrict(
    adjustments: List<Adjustment>,
    requestedSize: Size,
    maxSize: Size,
  ): NegotiatedExport =
    NegotiatedExport(
      Verdict.Incapable(
        reasons =
          adjustments.map { adjustment ->
            when (adjustment.kind) {
              AdjustmentKind.CodecFallback -> {
                // The same kind covers the audio fallback, whose requested name is not a video
                // codec. Only the video one has an arm that can carry it.
                videoCodecFor(adjustment.requested)?.let { ExportError.NoEncoder(it, adjustment.message) }
                  ?: ExportError.InvalidComposition(adjustment.message)
              }
              AdjustmentKind.ResolutionClamped -> {
                ExportError.UnsupportedResolution(requestedSize, maxSize, adjustment.message)
              }
              else -> {
                ExportError.InvalidComposition(adjustment.message)
              }
            }
          },
        withoutUnsupported = null,
      ),
      null,
    )

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

  /**
   * @param ceiling The largest frame this export encodes into, when the requested one was above it,
   *   and null when the frame only moved to meet the encoder's alignment.
   */
  private fun adjustments(
    spec: ExportSpec,
    edit: EditComposition,
    requestedSize: Size,
    outputSize: Size,
    ceiling: Size?,
    requestedFrameRate: Int?,
    frameRate: Int,
    codec: List<Adjustment>,
    hdr: ResolvedHdr,
    sourceIsHdr: Boolean,
    gradesAgree: Boolean,
    degraded: List<Pair<String, String>>,
    dropped: Set<String>,
  ): List<Adjustment> =
    buildList {
      addAll(codec)
      if (requestedSize != outputSize) {
        add(
          Adjustment(
            kind = AdjustmentKind.ResolutionClamped,
            requested = requestedSize.describe(),
            resolved = outputSize.describe(),
            message =
              if (ceiling == null) {
                "The encoder wants aligned dimensions, so each side rounds to what it accepts."
              } else {
                "This export encodes into ${ceiling.describe()} at most, so the frame is scaled " +
                  "down to fit inside it and keeps its shape."
              },
          ),
        )
      }
      if (requestedFrameRate != null && requestedFrameRate != frameRate) {
        add(
          Adjustment(
            kind = AdjustmentKind.FrameRateClamped,
            requested = "$requestedFrameRate fps",
            resolved = "$frameRate fps",
            message = "The encoder does not accept $requestedFrameRate fps, so $frameRate is used instead.",
          ),
        )
      }
      // Auto is the only mode that lands somewhere the caller did not name, so it is the only one
      // with anything to report. ToneMapToSdr asked for exactly this, and honouring a request is
      // not an adjustment to it.
      if (sourceIsHdr && hdr == ResolvedHdr.ToneMap && spec.hdr == HdrMode.Auto) {
        add(
          Adjustment(
            kind = AdjustmentKind.HdrToneMapped,
            requested = spec.hdr.name,
            resolved = HdrMode.ToneMapToSdr.name,
            // Auto reaches a tone map two ways, and a device that encodes HDR fine still gets here
            // on a mix, so the reason is read rather than assumed.
            message = if (gradesAgree) TONE_MAPPED else TONE_MAPPED_MIXED,
          ),
        )
      }
      degraded.forEach { (id, message) ->
        add(
          Adjustment(
            kind = AdjustmentKind.EffectApproximated,
            requested = id,
            resolved = "approximated",
            message = message,
          ),
        )
      }
      dropped.forEach { id ->
        add(
          Adjustment(
            kind = AdjustmentKind.EffectDropped,
            requested = id,
            resolved = "removed",
            message = "$id cannot run on this device and is not in this plan.",
          ),
        )
      }
    }

  /**
   * Every reason this export cannot copy its streams across, HDR set aside.
   *
   * HDR is left out on purpose, because whether a copy can carry the grade is itself one of the
   * things that decides HDR, so this has to be answerable before that decision is made.
   *
   * [copyOpening] is where the copy would open, and null is a cut no copy can reach. [requestedSize]
   * is the frame the composition asks for, before any encoder ceiling is applied, since a copy runs
   * no encoder and so has no ceiling to be held to. Comparing it against [sourceSize] is what says
   * the geometry is identity.
   */
  private fun copyBlockers(
    edit: EditComposition,
    spec: ExportSpec,
    sourceSize: Size,
    requestedSize: Size,
    copyOpening: Duration?,
    info: MediaInfo,
  ): List<CopyBlocker> =
    buildList {
      if (!supportsPassthrough) add(CopyBlocker.BackendCannotCopy)
      if (!canCopy(info)) add(CopyBlocker.MuxerRefusesSource)
      if (!codecsAreNameable(info)) add(CopyBlocker.SourceCodecUnnameable)
      if (spec.frameRate != null) add(CopyBlocker.FrameRateSet)
      if (spec.videoCodec != VideoCodec.Auto) add(CopyBlocker.VideoCodecNamed)
      if (spec.audioCodec != AudioCodec.Auto) add(CopyBlocker.AudioCodecNamed)
      if (spec.targetHeight != null) add(CopyBlocker.TargetHeightSet)
      if (spec.bitrate != null) add(CopyBlocker.BitrateSet)
      if (sourceSize != requestedSize) add(CopyBlocker.FrameResized)
      if (edit.effects.isNotEmpty()) add(CopyBlocker.CompositionHasEffects)
      if (edit.audio != AudioSpec.Keep) add(CopyBlocker.AudioSpecChanged)
      if (edit.audio.gain() != 1f) add(CopyBlocker.CompositionGainChanged)

      val track = edit.tracks.singleOrNull()
      if (track == null) {
        add(CopyBlocker.MultipleTracks)
        return@buildList
      }
      if (track.effects.isNotEmpty()) add(CopyBlocker.TrackHasEffects)
      if (track.start > Duration.ZERO) add(CopyBlocker.TrackStartsLate)
      if (track.content != TrackContent.AudioAndVideo) add(CopyBlocker.TrackDropsAStream)
      if (!track.audio.isUnity) add(CopyBlocker.TrackGainChanged)

      val clip = track.clips.singleOrNull()
      if (clip == null) {
        add(CopyBlocker.MultipleClips)
        return@buildList
      }
      if (clip.effects.isNotEmpty()) add(CopyBlocker.ClipHasEffects)
      if (!clip.audio.isUnity) add(CopyBlocker.ClipGainChanged)
      if (copyOpening == null) add(CopyBlocker.TrimNotOnSyncSample)
    }

  private fun estimate(
    spec: ExportSpec,
    duration: Duration,
    path: ExportPath,
  ): ExportEstimate {
    val bits = spec.bitrate?.bitsPerSecond?.let { (it * duration.toDouble(DurationUnit.SECONDS)).toLong() }
    return ExportEstimate(
      outputSizeBytesMin = bits?.let { it / BITS_PER_BYTE },
      outputSizeBytesMax = bits?.let { it * HEADROOM_NUMERATOR / HEADROOM_DENOMINATOR / BITS_PER_BYTE },
      approximateDuration = null,
      isPassthrough = path == ExportPath.Transmux,
    )
  }

  private fun incapable(message: String): NegotiatedExport = incapable(ExportError.InvalidComposition(message))

  private fun incapable(error: ExportError): NegotiatedExport =
    NegotiatedExport(Verdict.Incapable(listOf(error), null), null)

  private fun unreadable(source: MediaSource): NegotiatedExport =
    incapable(ExportError.SourceUnreadable(source.describe(), UNREADABLE))

  private fun protected(source: MediaSource): NegotiatedExport =
    incapable(ExportError.SourceNotExportable("${source.describe()} is protected and cannot be exported."))

  private companion object {
    const val SIZE_ALIGNMENT = 2
    const val BITS_PER_BYTE = 8L
    const val HEADROOM_NUMERATOR = 13L
    const val HEADROOM_DENOMINATOR = 10L

    const val NO_TRACKS = "A composition needs at least one track."

    const val SECOND_VIDEO_TRACK =
      "This backend renders video from the primary track only. A second video track needs a " +
        "compositor, which has not landed here. A second audio-only track works."

    const val NO_PRIMARY_VIDEO = "The primary track contributes no video, so there is nothing to encode."

    const val NOTHING_TO_ENCODE =
      "No track survives what the composition and the spec ask for, so the export would write a " +
        "file with no tracks in it."

    const val EVERY_TRACK_LOOPS = "Every track loops, so the composition has nothing to bound it."

    const val TRACK_ENVELOPE =
      "A track's audio envelope has points that fall outside the track, run backwards, or ask for " +
        "a negative gain."

    const val CLIP_ENVELOPE =
      "A clip's audio envelope has points that fall outside the clip's trim, run backwards, or ask " +
        "for a negative gain."

    const val UNREADABLE = "The source could not be read, so there is nothing to plan against."

    const val NO_ENCODER = "This device has no encoder for the requested codec."

    const val NO_HDR_ENCODER =
      "HDR was required and this device cannot encode it. Use HdrMode.Auto to tone-map instead."

    const val NO_HDR_PATH =
      "This source carries an HDR grade and this engine can neither encode it nor tone-map it, so " +
        "there is nowhere to put it."

    const val MIXED_GRADES =
      "These sources do not share one HDR transfer, so no single grade describes the output. Use " +
        "HdrMode.Auto or HdrMode.ToneMapToSdr to bring them down to SDR together."

    const val TONE_MAPPED = "This device cannot encode HDR, so the grade is tone-mapped to SDR."

    const val TONE_MAPPED_MIXED =
      "These sources do not share one HDR transfer, so no single grade describes the output and " +
        "they are tone-mapped to SDR together."

    const val APPROXIMATE = "The preview and the export diverge by a bounded amount for this effect."

    const val CLIP_ONLY_SCOPE =
      "This effect reads where a frame sits inside the run it is drawn over, so it belongs on a " +
        "clip. Declared on a track or on the composition it would draw one run on one engine and " +
        "a run per clip on another. Move it onto the clips it should travel across."

    const val CLIP_ONLY_LOOPING =
      "This effect reads where a frame sits inside the run it is drawn over, and a looping track " +
        "lays its clips down more than once, so there is no single run to measure against. Take " +
        "the loop off the track, or take the effect off the clip."
  }
}

/**
 * The chain a backend lowers: every run of colour effects folded, except around the effects an
 * overriding resolver claimed, with the resolutions those already gave keyed by their place here.
 */
private fun foldedChain(
  ordered: List<EffectSpec>,
  overridden: Map<Int, EffectResolution>,
): FoldedChain {
  if (overridden.isEmpty()) return FoldedChain(ordered.fusedColorMatrices(), emptyMap())

  val entries = mutableListOf<FoldedSpec>()
  val resolutions = mutableMapOf<Int, EffectResolution>()
  var start = 0

  overridden.keys.sorted().forEach { index ->
    entries += ordered.subList(start, index).fusedColorMatrices()
    resolutions[entries.size] = overridden.getValue(index)
    entries += FoldedSpec(ordered[index], listOf(ordered[index]))
    start = index + 1
  }
  entries += ordered.subList(start, ordered.size).fusedColorMatrices()

  return FoldedChain(entries, resolutions)
}

private class FoldedChain(
  val entries: List<FoldedSpec>,
  val resolutions: Map<Int, EffectResolution>,
)

// What a backend calls the effect it was handed. A folded run stands for several authored effects,
// and naming the one the fold made up puts an id in a message that the caller never wrote.
private fun List<EffectSpec>.authoredIds(): String = joinToString(", ") { it.id }

/**
 * One effect, after the resolver chain has been asked.
 *
 * @property sources The effects the caller wrote that [spec] stands for, which is [spec] alone
 *   unless a run of colour effects folded into it.
 * @property degraded Each authored id and what was given up, when a resolver realised the effect
 *   but not exactly as declared.
 */
private class LoweredEffect(
  val spec: EffectSpec,
  val sources: List<EffectSpec>,
  effect: PlatformEffect?,
  val unsupported: String?,
  degradedMessage: String?,
) {
  val degraded: List<Pair<String, String>> =
    degradedMessage?.let { message -> sources.map { it.id to message } }.orEmpty()

  val resolvedEffect: ResolvedEffect? = effect?.let { ResolvedEffect(sources.authoredIds(), it) }
}

/**
 * Whether this track still writes something once the output's audio and video have been settled.
 */
private fun Track.contributes(
  keepAudio: Boolean,
  keepVideo: Boolean,
): Boolean = (keepVideo && content != TrackContent.Audio) || (keepAudio && content != TrackContent.Video)

private fun requestedFrame(
  sourceSize: Size,
  geometry: List<EffectSpec>,
  targetHeight: Int?,
): Size {
  var size = frameThrough(sourceSize, listOf(geometry))
  if (targetHeight != null && targetHeight > 0 && size.height > 0) {
    size = Size((targetHeight * size.aspect).roundToInt().coerceAtLeast(1), targetHeight)
  }
  return size
}

/**
 * Scales the frame down to sit inside [max], then rounds each side down to a multiple the encoder
 * accepts.
 *
 * Rounding to the nearest multiple would carry a frame that already sits on the encoder's maximum
 * past that maximum.
 */
private fun Size.fittedTo(
  max: Size?,
  alignment: Int,
): Size {
  val step = alignment.coerceAtLeast(1)
  val fitted = if (max == null) this else frameWithin(this, max)
  return Size(
    (fitted.width - fitted.width % step).coerceAtLeast(step),
    (fitted.height - fitted.height % step).coerceAtLeast(step),
  )
}

/**
 * The box both [first] and [second] fit inside, or whichever of them is the only one given.
 */
private fun tightest(
  first: Size?,
  second: Size?,
): Size? =
  when {
    first == null -> second
    second == null -> first
    else -> Size(minOf(first.width, second.width), minOf(first.height, second.height))
  }

/**
 * Whether this clip decodes video of its own, rather than holding a still for a span.
 */
private fun carriesVideo(
  clip: Clip,
  infos: Map<MediaSource, MediaInfo>,
): Boolean = clip.source !is MediaSource.Image && infos.getValue(clip.source).video != null

private fun resolveFrameRate(
  spec: ExportSpec,
  sourceRate: Float?,
  maxRate: Int?,
): Int {
  val requested = spec.frameRate ?: sourceRate?.roundToInt()?.takeIf { it > 0 } ?: DEFAULT_RATE
  return if (maxRate != null && maxRate > 0) requested.coerceAtMost(maxRate) else requested
}

/**
 * The codec to encode with, and what it cost to get there, or null when the device encodes none of
 * them.
 *
 * @param encodesHdr Whether the grade reaches the encoder. HDR needs a 10-bit profile, and not every
 *   codec this engine otherwise tries has one, so an HDR export chooses from [hdrLadder] rather than
 *   [ladder]. Leaving a codec with no such profile in it would have the plan name one that the
 *   platform then silently swaps out from under it.
 * @param ladder The order this engine tries codecs in when the request is [VideoCodec.Auto], or
 *   when the requested codec is unsupported and something has to be tried next.
 * @param hdrLadder The same, for an export that keeps HDR.
 */
private fun resolveVideoCodec(
  spec: ExportSpec,
  device: DeviceCapabilities,
  encodesHdr: Boolean,
  ladder: List<VideoCodec>,
  hdrLadder: List<VideoCodec>,
): Pair<List<Adjustment>, VideoCodec>? {
  val requested = spec.videoCodec
  val tried =
    when {
      encodesHdr -> hdrLadder
      requested == VideoCodec.Auto -> ladder
      else -> listOf(requested) + ladder.filterNot { it == requested }
    }
  val supported = tried.firstOrNull { codec -> device.video.any { it.codec == codec } } ?: return null
  val adjustments =
    if (supported == requested || requested == VideoCodec.Auto) {
      emptyList()
    } else {
      val reason =
        if (encodesHdr) {
          "Keeping the HDR grade needs a 10-bit encoder"
        } else {
          "This device has no encoder for $requested"
        }
      listOf(
        Adjustment(
          kind = AdjustmentKind.CodecFallback,
          requested = requested.name,
          resolved = supported.name,
          message = "$reason, so $supported is used instead.",
        ),
      )
    }
  return adjustments to supported
}

private fun resolveAudioCodec(
  spec: ExportSpec,
  device: DeviceCapabilities,
  silent: Boolean,
): Pair<List<Adjustment>, AudioCodec> {
  if (silent) return emptyList<Adjustment>() to AudioCodec.None
  if (device.audio.any { it.codec == spec.audioCodec }) return emptyList<Adjustment>() to spec.audioCodec
  val fallback = device.audio.firstOrNull()?.codec ?: return emptyList<Adjustment>() to AudioCodec.None
  if (spec.audioCodec == AudioCodec.Auto) return emptyList<Adjustment>() to fallback

  return listOf(
    Adjustment(
      kind = AdjustmentKind.CodecFallback,
      requested = spec.audioCodec.name,
      resolved = fallback.name,
      message = "This device has no ${spec.audioCodec} encoder, so $fallback is used instead.",
    ),
  ) to fallback
}

/**
 * What to do about high dynamic range, or null when nothing this engine can do satisfies the ask.
 *
 * An SDR source resolves to [ResolvedHdr.Keep] whatever was asked for, because there is no grade to
 * map. Saying so is what keeps a stream copy possible, since a platform told to tone-map has to
 * decode every frame to do it.
 *
 * A grade is only ever kept when every video source carries the same transfer, which [gradesAgree]
 * says. A mix has nothing that is true of the whole output, so keeping one would tag the rest of the
 * frames with a grade they do not have.
 *
 * [copyKeepsHdr] gives HDR a second way to reach [ResolvedHdr.Keep]: a copy carries whatever grade
 * the source already has without asking an encoder for anything, so a device that cannot encode HDR
 * still keeps it when the rest of the export would already have been a copy. An explicit tone map
 * still means what it says, since asking for one forces a transcode either way.
 *
 * An engine that can neither encode the grade, copy it across, nor bring it down has nowhere to put
 * an HDR source, and null is how it refuses rather than writing the untouched grade into an SDR
 * file.
 */
private fun resolveHdr(
  mode: HdrMode,
  sourceIsHdr: Boolean,
  gradesAgree: Boolean,
  deviceEncodesHdr: Boolean,
  canToneMap: Boolean,
  copyKeepsHdr: Boolean,
): ResolvedHdr? {
  if (!sourceIsHdr) return ResolvedHdr.Keep

  val canKeep = gradesAgree && (deviceEncodesHdr || copyKeepsHdr)
  return when (mode) {
    HdrMode.ToneMapToSdr -> {
      if (canToneMap) ResolvedHdr.ToneMap else null
    }
    HdrMode.KeepHdr -> {
      if (canKeep) ResolvedHdr.Keep else null
    }
    HdrMode.Auto -> {
      when {
        canKeep -> ResolvedHdr.Keep
        canToneMap -> ResolvedHdr.ToneMap
        else -> null
      }
    }
  }
}

/**
 * The format every track's audio is normalised to before mixing.
 *
 * The first clip that has audio proposes the rate, because that is the stream the platform keeps,
 * and [encoder] gets the last word: a rate it does not list is snapped to the nearest one it does,
 * since an encoder handed a rate it cannot write resamples on its own and leaves the written file
 * disagreeing with the plan. An encoder that lists no rates at all is taken at its word and given
 * the source's. Channel count is fixed, since mixing tracks of differing widths needs one width to
 * mix into.
 */
private fun audioFormat(
  tracks: List<ResolvedTrack>,
  encoder: AudioEncoderCapability?,
): AudioFormat {
  val source =
    tracks
      .flatMap { it.clips }
      .firstNotNullOfOrNull {
        it.info.audio
          ?.sampleRate
          ?.takeIf { rate -> rate > 0 }
      } ?: DEFAULT_SAMPLE_RATE
  val accepted = encoder?.sampleRates.orEmpty()
  val channels = encoder?.maxChannelCount?.takeIf { it > 0 } ?: CHANNEL_COUNT
  return AudioFormat(
    sampleRate = if (accepted.isEmpty() || source in accepted) source else accepted.minBy { abs(it - source) },
    channelCount = CHANNEL_COUNT.coerceAtMost(channels),
  )
}

/**
 * Whether the platform can copy the streams across without re-encoding them.
 *
 * Deliberately conservative: [copyKeepsHdr] already answers everything about the shape of the
 * composition, so the one thing left to add is whether HDR actually resolved to being kept rather
 * than tone-mapped. Which path actually ran is reported by the backend once the file is written.
 */
private fun exportPath(
  copyKeepsHdr: Boolean,
  hdr: ResolvedHdr,
): ExportPath = if (copyKeepsHdr && hdr == ResolvedHdr.Keep) ExportPath.Transmux else ExportPath.Transcode

/**
 * Reads where a stream copy of [composition] could open, keyed the way [ExportPlanner.negotiate]
 * reads it.
 *
 * Only a single clip on a single track can ever be copied, and only a cut past zero has a sync
 * sample to look for, so at most one [probe] runs. It runs whatever else the spec asks for, because
 * a plan that skipped the probe could not tell a caller whether the trim was the term that cost
 * them the copy.
 */
@InternalFilmstripApi
public suspend fun copyOpenings(
  composition: EditComposition,
  probe: suspend (MediaSource, Duration) -> Duration?,
): Map<MediaSource, Duration> {
  val clip =
    composition.tracks
      .singleOrNull()
      ?.clips
      ?.singleOrNull() ?: return emptyMap()
  if (clip.source is MediaSource.Image) return emptyMap()
  val cut = clip.trim?.start?.takeIf { it > Duration.ZERO } ?: return emptyMap()
  return probe(clip.source, cut)?.let { mapOf(clip.source to it) } ?: emptyMap()
}

/**
 * Where a stream copy of [clip] would open, or null when no copy can reach its cut.
 *
 * An untrimmed clip opens at zero, which every stream starts on. A trimmed one opens on [opening],
 * the sync sample at or before its cut, and only while that sits no further back than
 * [Clip.snapWithin] allows. A cut already on a sync sample is reached at any tolerance, zero
 * included, because opening there costs no accuracy.
 */
private fun copyOpening(
  clip: Clip,
  opening: Duration?,
): Duration? {
  val asked = clip.trim?.start ?: return Duration.ZERO
  if (asked == Duration.ZERO) return Duration.ZERO
  val sync = opening ?: return null
  if (sync > asked) return null
  return sync.takeIf { asked - it <= clip.snapWithin }
}

/**
 * Whether [info]'s streams could report codecs the public enums can name.
 *
 * A muxer can agree to copy bytes neither [VideoCodec] nor [AudioCodec] has a member for, which
 * `canCopy` alone does not catch, so this is checked alongside it.
 */
private fun codecsAreNameable(info: MediaInfo): Boolean =
  runCatching { info.video?.let { videoCodecOf(it.codec.kind) } }.isSuccess &&
    runCatching { info.audio?.let { audioCodecOf(it.codec.kind) } }.isSuccess

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
private fun trimWindow(
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

/**
 * Whether this level leaves the audio exactly as it was, which is what lets a copy skip the mixer.
 *
 * An envelope is never unity here, even one written flat at one, since reading it would mean
 * folding the curve the copy path does not build.
 */
private val AudioLevel.isUnity: Boolean
  get() =
    when (this) {
      is AudioLevel.Inherit -> true
      is AudioLevel.Volume -> gain == 1f
      is AudioLevel.Mute, is AudioLevel.Envelope -> false
    }

private fun AudioSpec.gain(): Float =
  when (this) {
    is AudioSpec.Keep, is AudioSpec.AudioOnly -> 1f
    is AudioSpec.Mute, is AudioSpec.Remove -> 0f
    is AudioSpec.Volume -> gain
  }

private fun videoCodecFor(name: String): VideoCodec? = VideoCodec.entries.firstOrNull { it.name == name }

private fun Size.describe(): String = "${width}x$height"

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
      )
    }
  return EditComposition(stripped, effects.filterNot { it.id in ids }, audio, fill)
}

private const val DEFAULT_RATE = 30
private const val DEFAULT_SAMPLE_RATE = 44_100
private const val CHANNEL_COUNT = 2
