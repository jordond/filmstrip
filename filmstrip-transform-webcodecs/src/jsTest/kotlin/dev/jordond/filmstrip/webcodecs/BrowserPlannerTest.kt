package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.toColumnMajor4x4
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.LoopingCases
import dev.jordond.filmstrip.test.TimelineBursts
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.passesCovering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.stillUnsupportedMessage
import dev.jordond.filmstrip.transform.internal.videoSlots
import dev.jordond.filmstrip.webcodecs.internal.BROWSER_OPUS_ENCODER
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserPlanner
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.interop.HDR_VP9_CODEC
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

// The browser planner, with fabricated probes. The lowering is pure, so none of these need
// mediabunny or a browser beyond what the runner already is.
class BrowserPlannerTest {
  private val planner = BrowserPlanner(listOf(BuiltInEffectResolver()))

  private fun lower(
    composition: Composition,
    spec: ExportSpec = videoSpec(),
    infos: Map<MediaSource, MediaInfo> = composition.clips.map { it.source to info() }.toMap(),
    device: DeviceCapabilities = device(VideoCodec.H264, VideoCodec.Vp9),
    opaque: Set<MediaSource> = emptySet(),
    openings: Map<MediaSource, Duration> = emptyMap(),
  ): LoweredExport<BrowserRender> = planner.lower(composition, Ask(spec, device, infos, openings), opaque = opaque)

  @Test
  fun audioEffectsReachTheRenderAtBothScopes() {
    val bursts =
      AudioEffectResolver { spec, _ ->
        (spec as? TimelineBursts)?.let { AudioEffectResolution.Resolved(PlatformAudioEffect(it::newKernel)) }
      }
    val hosting = BrowserPlanner(listOf(BuiltInEffectResolver()), listOf(bursts))
    val clip = Clip(source("heard"), audioEffects = listOf(TimelineBursts(0.5f)))
    val composition = Composition(listOf(Track(listOf(clip))), audioEffects = listOf(TimelineBursts(0.25f)))
    val heard =
      AudioTrackInfo(AudioTrackId(1), trackCodecOf("mp4a"), 48_000, 2, null, language = null, isDefault = true)
    val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
    val ask = Ask(spec, device(VideoCodec.H264, VideoCodec.Vp9), mapOf(clip.source to info(audio = heard)))

    val render = hosting.lower(composition, ask).graph.shouldNotBeNull()

    render.audioTracks
      .single()
      .clips
      .single()
      .audioEffects
      .map { it.specId } shouldBe listOf(TimelineBursts.ID)
    render.mixAudioEffects.map { it.specId } shouldBe listOf(TimelineBursts.ID)
  }

  private fun infosOf(composition: Composition): Map<MediaSource, MediaInfo> =
    composition.clips.map { it.source to info() }.toMap()

  @Test
  fun emptyCompositionIsIncapable() {
    val verdict = lower(Composition(tracks = emptyList())).verdict
    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  @Test
  fun audioIsAllowedWhetherKeptOrRemoved() {
    val spec = ExportSpec(videoCodec = VideoCodec.H264)
    val withAudio = Composition(tracks = listOf(Track(listOf(Clip(source("a"))))), audio = AudioSpec.Keep)
    lower(withAudio, spec).verdict.shouldBeInstanceOf<Verdict.Capable>()

    val withoutAudio = Composition(tracks = listOf(Track(listOf(Clip(source("a"))))), audio = AudioSpec.Remove)
    lower(withoutAudio, spec).verdict.shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun audioNonePassesEvenWithAudioSpec() {
    val composition = Composition(tracks = listOf(Track(listOf(Clip(source("a"))))), audio = AudioSpec.Keep)
    val spec = ExportSpec(audioCodec = AudioCodec.None)
    lower(composition, spec).verdict.shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun pathSourceIsUnreadable() {
    val composition = Composition(tracks = listOf(Track(listOf(Clip(MediaSource.Path("/tmp/clip.mp4"))))))
    val verdict = lower(composition, infos = emptyMap()).verdict
    verdict.shouldBeInstanceOf<Verdict.Incapable>()
    verdict.reasons.any { it is FilmstripError.SourceUnreadable } shouldBe true
  }

  @Test
  fun rotatedSourceIsRefused() {
    val clip = Clip(source("a"))
    val verdict = lower(compositionOf(listOf(clip)), infos = mapOf(clip.source to info(rotation = 90))).verdict
    verdict.shouldBeInstanceOf<Verdict.Incapable>()
    verdict.reasons.any { it.message.contains("rotation") } shouldBe true
  }

  // This backend draws its output by decoding a video track. A still is refused at plan time
  // rather than left to fail once an export or a preview actually tries to open it.
  @Test
  fun stillIsRefusedAtPlanTime() {
    val still = MediaSource.Image(ImageSource.of("/photos/one.png"), 3_000.milliseconds)
    val verdict = lower(compositionOf(listOf(Clip(still)))).verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.SourceNotExportable>()
    reason.message shouldBe stillUnsupportedMessage("browser")
  }

  // The scan is over every track's clips, not just the primary one, so a still parked on a
  // secondary audio track is caught the same way as a still leading the timeline.
  @Test
  fun stillOnASecondaryTrackIsRefusedAtPlanTime() {
    val still = MediaSource.Image(ImageSource.of("/photos/one.png"), 3_000.milliseconds)
    val composition =
      Composition(
        tracks =
          listOf(
            Track(listOf(Clip(source("a")))),
            Track(clips = listOf(Clip(still)), content = TrackContent.Audio),
          ),
        audio = AudioSpec.Remove,
      )

    val verdict = lower(composition).verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.SourceNotExportable>()
    reason.message shouldBe stillUnsupportedMessage("browser")
  }

  @Test
  fun oddSizeIsEvenedAndReported() {
    val clip = Clip(source("a"))
    val verdict = lower(compositionOf(listOf(clip)), infos = mapOf(clip.source to info(1921, 1081))).verdict
    verdict.shouldBeInstanceOf<Verdict.Degraded>()
    verdict.plan.output.size shouldBe Size(1920, 1080)
    verdict.adjustments.any { it.kind == AdjustmentKind.ResolutionClamped } shouldBe true
  }

  // Every export here is a real decode-draw-encode pass, so a portrait clip is encoded at its own
  // portrait size rather than turned into a landscape encode the container has to correct.
  @Test
  fun portraitOutputKeepsItsPortraitSize() {
    val clip = Clip(source("a"))
    val verdict = lower(compositionOf(listOf(clip)), infos = mapOf(clip.source to info(1080, 1920))).verdict

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(1080, 1920)
  }

  @Test
  fun heightScalesOutput() {
    val verdict = lower(compositionOf(listOf(Clip(source("a")))), spec = videoSpec(heightPx = 360)).verdict
    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.output.size shouldBe Size(640, 360)
  }

  // mediabunny is handed a number off the plan, never a Quality, which it would run as a quantizer instead.
  @Test
  fun encoderRunsAtThePlannedBitrate() {
    val lowering = lower(compositionOf(listOf(Clip(source("a")))), spec = videoSpec(heightPx = 720))
    val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan

    plan.output.bitrate shouldBe resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, Size(1280, 720), 30)
    lowering.graph
      .shouldNotBeNull()
      .bitrate
      .toLong() shouldBe
      plan.output.bitrate
        ?.bitsPerSecond
  }

  @Test
  fun panIsExactAndKeepsTheFrameSize() {
    val pan = KenBurns(NormalizedRect.Full, NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f))
    val clip = Clip(source("a"), effects = listOf(pan))

    val verdict = lower(compositionOf(listOf(clip))).verdict

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.parity shouldBe EffectParity.Exact
    verdict.plan.output.size shouldBe frameAfter(pan, info().video.shouldNotBeNull().displaySize)
  }

  // A turn off the quarter turns is outside what a rotation takes, and the browser refuses it rather than guess.
  @Test
  fun unsupportedEffectRefusesWithFallback() {
    val composition = compositionOf(listOf(Clip(source("a"))), effects = listOf(Rotate(45)))
    val verdict = lower(composition).verdict
    verdict.shouldBeInstanceOf<Verdict.Incapable>()
    verdict.reasons.any { it is FilmstripError.UnsupportedEffect } shouldBe true
    val fallback = verdict.withoutUnsupported.shouldNotBeNull()
    fallback.plan.effectOrder shouldBe emptyList()
  }

  @Test
  fun rotateAndScaleAreExactAndSizeTheOutput() {
    val rotate = Rotate(90)
    val scale = Scale(640, Fit.Crop)
    val clip = Clip(source("a"), effects = listOf(rotate))
    val verdict = lower(compositionOf(listOf(clip), effects = listOf(scale))).verdict

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.parity shouldBe EffectParity.Exact
    verdict.plan.effectOrder.size shouldBe 2
    verdict.plan.output.size shouldBe frameAfter(scale, frameAfter(rotate, info().video.shouldNotBeNull().displaySize))
  }

  @Test
  fun supportedEffectsAreExact() {
    // A crop to the aspect the source already has keeps every pixel and is left out of the plan, so this one cuts.
    val effects: List<EffectSpec> =
      listOf(Crop(AspectRatio(4, 3), Fit.Crop), Flip(FlipAxis.Horizontal), Brightness(0.5f))
    val verdict = lower(compositionOf(listOf(Clip(source("a"))), effects = effects)).verdict
    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.parity shouldBe EffectParity.Exact
    verdict.plan.effectOrder.size shouldBe 3
    val render = lower(compositionOf(listOf(Clip(source("a"))), effects = effects)).graph.shouldNotBeNull()
    val graded = render.clips.single().compositionColorMatrix
    withClue("the composition lowered to ${graded.toList()}") {
      graded.contentEquals(matrixOf(Brightness(0.5f))) shouldBe true
    }
  }

  // The planner folds a run of color effects per stage and the pass clamps between the two, the
  // way ffmpeg writes the clip's stage into a frame before the composition's filters run. The two
  // matrices therefore reach the shader as two rather than as their product.
  @Test
  fun clipAndCompositionColorStayTwoMatrices() {
    val clip = Clip(source("a"), effects = listOf(Sepia(1f)))
    val composition = compositionOf(listOf(clip), effects = listOf(Contrast(1.5f)))

    val render = lower(composition).graph.shouldNotBeNull()
    val drawn = render.clips.single()

    assertMatrix(checkNotNull(colorMatrixOf(Sepia(1f))).toColumnMajor4x4(), drawn.colorMatrix, "the clip")
    assertMatrix(
      checkNotNull(colorMatrixOf(Contrast(1.5f))).toColumnMajor4x4(),
      drawn.compositionColorMatrix,
      "the composition",
    )
  }

  // The uniform holds single precision and the fold on this side runs in whatever the host has, so
  // the two agree to a tolerance rather than bit for bit.
  private fun assertMatrix(
    expected: FloatArray,
    lowered: FloatArray,
    what: String,
  ) {
    lowered.size shouldBe expected.size
    expected.indices.forEach { index ->
      withClue("$what lowered to ${lowered.toList()}, and the matrix it wrote is ${expected.toList()}") {
        lowered[index] shouldBe (expected[index] plusOrMinus MATRIX_TOLERANCE)
      }
    }
  }

  // The pass carries the matrix as authored either way. The compositor is what knows which domain
  // the frame is in, and it wraps the same entries in the shared HDR arm when it keeps a grade.
  @Test
  fun colorLowersTheSameWayOnAKeptGradeAsOnAnSdrFrame() {
    val resolver = BuiltInEffectResolver()

    val graded =
      resolver
        .resolve(
          Sepia(),
          webCapabilities(),
          attributesOf(HdrTransfer.Pq),
        ).shouldBeInstanceOf<EffectResolution.Resolved>()
    val sdr =
      resolver.resolve(Sepia(), webCapabilities(), attributesOf(null)).shouldBeInstanceOf<EffectResolution.Resolved>()

    (
      graded.effect.pass.uniforms
        .getValue("uColorMatrix")
    ) shouldBe (
      sdr.effect.pass.uniforms
        .getValue("uColorMatrix")
    )
  }

  private fun webCapabilities(): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.WebGl,
      supportsFragmentShader = true,
      supportsComputeShader = false,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = 4096,
      features = emptySet(),
    )

  private fun attributesOf(transfer: HdrTransfer?): Attributes =
    Attributes(
      inputSize = Size(1920, 1080),
      outputSize = Size(1920, 1080),
      layoutSize = Size(1920, 1080),
      colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
      hdrTransfer = transfer,
      frameRate = 30f,
      span = TimeRange.of(Duration.ZERO, 1.minutes),
    )

  // A pass this backend did not write owns the name of that uniform as much as this one does, and
  // spelling it as something other than a mat4 is a lowering the fold leaves alone rather than an
  // exception out of plan().
  @Test
  fun foreignColorUniformThatIsNotAMat4IsLeftOutOfTheFold() {
    val foreign = BrowserPlanner(listOf(ForeignPassResolver(), BuiltInEffectResolver()))
    val composition = compositionOf(listOf(Clip(source("a"), effects = listOf(Brightness(0.5f)))))

    val render =
      foreign
        .lower(
          composition,
          Ask(videoSpec(), device(VideoCodec.H264), infosOf(composition)),
        ).graph
        .shouldNotBeNull()

    withClue("the clip lowered to ${render.clips.single().colorMatrix.toList()}") {
      (
        render.clips
          .single()
          .colorMatrix
          .contentEquals(matrixOf(Brightness(1f)))
      ) shouldBe true
    }
  }

  // Composition geometry resolves against the frame clip geometry leaves behind, not the frame it
  // produces. A composition-level Crop measuring its own output against itself would see no aspect
  // mismatch to crop away.
  @Test
  fun compositionCropChangesTheTextureMatrix() {
    val effects: List<EffectSpec> = listOf(Crop(AspectRatio.Square, Fit.Crop))
    val composition = compositionOf(listOf(Clip(source("a"))), effects = effects)

    val render = lower(composition).graph.shouldNotBeNull()

    (
      render.clips
        .single()
        .matrix
        .at(Duration.ZERO)
        .contentEquals(IDENTITY_MATRIX)
    ) shouldBe false
  }

  @Test
  fun missingCodecFallsBack() {
    val spec = ExportSpec(videoCodec = VideoCodec.Hevc, audioCodec = AudioCodec.None)
    val verdict = lower(compositionOf(listOf(Clip(source("a")))), spec).verdict
    verdict.shouldBeInstanceOf<Verdict.Degraded>()
    verdict.plan.output.videoCodec shouldBe VideoCodec.H264
    verdict.adjustments.any { it.kind == AdjustmentKind.CodecFallback } shouldBe true
  }

  // Proves this backend's own registered ladder, BROWSER_LADDER, not a copy of it: a device
  // without H264 has to fall through to Vp9 before Hevc, so Auto resolving to Vp9 here means the
  // real ladder the planner and this assertion share is still ordered [H264, Vp9, Hevc].
  @Test
  fun autoWalksThisBackendsOwnLadderToTheEncoderThatIsActuallyThere() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(videoCodec = VideoCodec.Auto, audioCodec = AudioCodec.None)
    val vp9AndHevcOnly = device(VideoCodec.Vp9, VideoCodec.Hevc)
    val ask = Ask(spec, vp9AndHevcOnly, mapOf(clip.source to info()))
    val verdict = planner.lower(compositionOf(listOf(clip)), ask).verdict
    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.videoCodec shouldBe VideoCodec.Vp9
  }

  @Test
  fun strictMissingCodecRefuses() {
    val spec = ExportSpec(videoCodec = VideoCodec.Hevc, audioCodec = AudioCodec.None, strict = true)
    val verdict = lower(compositionOf(listOf(Clip(source("a")))), spec).verdict
    verdict.shouldBeInstanceOf<Verdict.Incapable>()
    verdict.reasons.any { it is FilmstripError.NoEncoder } shouldBe true
  }

  // A trim used to force the encode path. A clip that lets the cut move back far enough to reach a
  // sync sample stays on mediabunny's packet copy, and the window it copies opens on that sample
  // rather than on the cut. The tolerance is taken between the two, since a copy that ignored it
  // and one that always honoured it agree at either end.
  @Test
  fun aReachableOpeningCopiesFromTheSyncSampleRatherThanTheCut() {
    val clip = Clip(source("a"), trim = TimeRange(TRIM_START, TRIM_END), snapWithin = REACHES)
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)

    val lowering = lower(composition, ExportSpec(), openings = mapOf(clip.source to OPENING))

    val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
    withClue("the copy was blocked by ${plan.copyBlockedBy}") { plan.copyBlockedBy.isEmpty() shouldBe true }
    val render = lowering.graph.shouldNotBeNull()
    render.duration shouldBe TRIM_END - OPENING
    val rendered = render.clips.single()
    rendered.trimStartUs shouldBe OPENING.inWholeMicroseconds.toDouble()
    rendered.trimEndUs shouldBe TRIM_END.inWholeMicroseconds.toDouble()
  }

  // The same opening, with a tolerance that does not reach it. The cut stays where it was asked
  // for and the plan names the trim as what cost the copy.
  @Test
  fun anOpeningOutOfReachBlocksTheCopyAndLeavesTheCutAlone() {
    val clip = Clip(source("a"), trim = TimeRange(TRIM_START, TRIM_END), snapWithin = FALLS_SHORT)
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)

    val lowering = lower(composition, ExportSpec(), openings = mapOf(clip.source to OPENING))

    val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    withClue("the plan blamed ${plan.copyBlockedBy}") {
      (CopyBlocker.TrimNotOnSyncSample in plan.copyBlockedBy) shouldBe true
    }
    val render = lowering.graph.shouldNotBeNull()
    render.duration shouldBe TRIM_END - TRIM_START
    render.clips.single().trimStartUs shouldBe TRIM_START.inWholeMicroseconds.toDouble()
  }

  // Zero is the default, and it says the cut lands where it was asked for however close a sync
  // sample sits to it.
  @Test
  fun aTrimLeftAtTheDefaultToleranceBlocksTheCopy() {
    val clip = Clip(source("a"), trim = TimeRange(TRIM_START, TRIM_END))
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)

    val lowering = lower(composition, ExportSpec(), openings = mapOf(clip.source to OPENING))

    val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    withClue("the plan blamed ${plan.copyBlockedBy}") {
      (CopyBlocker.TrimNotOnSyncSample in plan.copyBlockedBy) shouldBe true
    }
    lowering.graph
      .shouldNotBeNull()
      .clips
      .single()
      .trimStartUs shouldBe TRIM_START.inWholeMicroseconds.toDouble()
  }

  // Snapping only saves the work it costs accuracy for on a copy. Anything that re-encodes decodes
  // the leading group of pictures anyway, so the cut lands where it was asked whatever the
  // tolerance allowed.
  @Test
  fun anEffectedClipTranscodesFromTheCutEvenWithAReachableOpening() {
    val clip =
      Clip(
        source("a"),
        trim = TimeRange(TRIM_START, TRIM_END),
        effects = listOf(Brightness(0.5f)),
        snapWithin = REACHES,
      )
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)

    val lowering = lower(composition, ExportSpec(), openings = mapOf(clip.source to OPENING))

    val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    withClue("the plan blamed ${plan.copyBlockedBy}") {
      (CopyBlocker.ClipHasEffects in plan.copyBlockedBy) shouldBe true
    }
    val render = lowering.graph.shouldNotBeNull()
    render.duration shouldBe TRIM_END - TRIM_START
    render.clips.single().trimStartUs shouldBe TRIM_START.inWholeMicroseconds.toDouble()
  }

  // An HDR source goes through the same HdrMode negotiation media3 and AVFoundation use, so it is
  // never drawn and encoded as if it were SDR with nothing in the verdict saying so.
  @Test
  fun keepingHdrOnASourceThatCarriesItIsRefusedRatherThanSilentlyMisencoded() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
    val verdict =
      lower(
        compositionOf(listOf(clip)),
        spec,
        infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg)),
      ).verdict
    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  // Every HDR clip runs the shared tone curve before the composite, so a source this backend can
  // neither encode nor copy is brought down to SDR, and the verdict says so.
  @Test
  fun hdrToneMapsByDefaultWhenItCanNeitherEncodeNorCopy() {
    val clip = Clip(source("a"))
    val lowering = lower(compositionOf(listOf(clip)), infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg)))

    val adjustments = lowering.verdict.shouldBeInstanceOf<Verdict.Degraded>().adjustments
    adjustments.map { it.kind } shouldBe listOf(AdjustmentKind.HdrToneMapped)
    val render = lowering.graph.shouldNotBeNull()
    render.hdrTransfer.shouldBeNull()
    render.clips.single().toneMapFrom shouldBe HdrTransfer.Hlg
  }

  // A tone map reads each clip by its own transfer, so a PQ clip beside an SDR one runs the curve
  // and the SDR clip is uploaded as it is.
  @Test
  fun aToneMappedRenderNamesEachClipsOwnTransfer() {
    val hdr = Clip(source("a"))
    val sdr = Clip(source("b"))
    val infos = mapOf(hdr.source to info(hdr = HdrTransfer.Pq), sdr.source to info())

    val render = lower(compositionOf(listOf(hdr, sdr)), infos = infos).graph.shouldNotBeNull()

    render.clips.map { it.toneMapFrom } shouldBe listOf(HdrTransfer.Pq, null)
  }

  // A tone map reads the planes a kept grade does, so a clip whose frames come out opaque cannot be
  // brought down to SDR either, and the refusal names the source on every mode that would.
  @Test
  fun aSourceWhoseGradeCannotBeReadIsRefusedByNameWhenToneMapped() {
    val clip = Clip(source("a"))
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg, codec = "mpeg2video"))

    listOf(HdrMode.Auto, HdrMode.ToneMapToSdr).forEach { mode ->
      val verdict = lower(composition, ExportSpec(hdr = mode), infos, opaque = setOf(clip.source)).verdict

      val reasons = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons
      withClue("$mode was refused with ${reasons.map { it.message }}, which does not name the source") {
        reasons.any { it.message.contains("opaque frame") } shouldBe true
      }
    }
  }

  // An untouched clip needs no WebGL pass at all, so mediabunny's packet copy carries HDR through
  // whatever this backend's own encoder can do, and the grade is kept rather than tone mapped.
  @Test
  fun anUntouchedHdrClipCopiesRatherThanBeingRefused() {
    val clip = Clip(source("a"))
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg, codec = "hvc1"))

    val lowering = lower(composition, ExportSpec(), infos)

    val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
    // The copy carries the source's own grade, and the render says which one so the muxer tags it
    // rather than guessing. Claiming Capable alone would pass just as well for a silent SDR write.
    val render = lowering.graph.shouldNotBeNull()
    render.hdrTransfer shouldBe HdrTransfer.Hlg
    withClue("a copy opens no encoder, so it names no codec string") { render.videoEncoding.shouldBeNull() }
  }

  // A browser's default decoder keeps an HDR frame opaque above some size, and HEVC has no software
  // decoder here at all, so a device that really does encode HDR can still meet a clip whose grade
  // it cannot read. The engine takes the encoder away for that composition, and the refusal has to
  // say the source is the reason rather than repeat the device's own answer back.
  @Test
  fun aSourceWhoseGradeCannotBeReadIsRefusedByName() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(hdr = HdrMode.KeepHdr)
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg, codec = "mpeg2video"))

    val verdict =
      lower(
        composition,
        spec,
        infos,
        device(VideoCodec.H264, VideoCodec.Vp9, supportsHdrEncoding = false),
        opaque = setOf(clip.source),
      ).verdict

    val reasons = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons
    withClue("the refusal was ${reasons.map { it.message }}, which does not say the source is the reason") {
      reasons.any { it.message.contains("opaque frame") } shouldBe true
    }
  }

  // Nothing about a source is wrong when the device simply has no HDR encoder, so the refusal stays
  // the shared negotiator's rather than being rewritten into one about a clip.
  @Test
  fun aDeviceWithNoHdrEncoderIsStillRefusedInItsOwnWords() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(hdr = HdrMode.KeepHdr)
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg, codec = "mpeg2video"))

    val verdict = lower(composition, spec, infos).verdict

    val reasons = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons
    withClue("the refusal blamed the source where the device is what has no encoder") {
      reasons.none { it.message.contains("opaque frame") } shouldBe true
    }
  }

  // There is no browser encoder for HEVC Main10, so an HDR export that has to encode pins the
  // ladder to VP9 Profile 2 rather than the HEVC the mobile backends use. The source carries a
  // codec this backend cannot copy, which is what forces the encode this test is actually about.
  @Test
  fun keepingHdrPinsTheCodecToVp9WhenTheDeviceCanEncodeIt() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(hdr = HdrMode.KeepHdr)
    val device = device(VideoCodec.H264, VideoCodec.Vp9, VideoCodec.Hevc, supportsHdrEncoding = true)
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg, codec = "mpeg2video"))

    val lowering = lower(composition, spec, infos, device)

    val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    plan.output.videoCodec shouldBe VideoCodec.Vp9
    lowering.graph.shouldNotBeNull().hdrTransfer shouldBe HdrTransfer.Hlg
    // The codec alone does not settle the bit depth: VP9 Profile 0 is 8-bit and would write an SDR
    // stream into a file tagged BT.2020.
    lowering.graph?.videoEncoding?.encoderCodec shouldBe HDR_VP9_CODEC
  }

  // The source would copy, which is how the export keeps its grade, and only the cap reaches for an encoder.
  @Test
  fun aCappedPreviewOfACopiedGradeLowersOnTheTenBitStringWithNoEncoderForIt() {
    val clip = Clip(source("a"))
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos = mapOf(clip.source to info(hdr = HdrTransfer.Pq))

    for (device in listOf(device(), device(VideoCodec.H264))) {
      val ask = Ask(ExportSpec(heightPx = 720), device, infos, grade = ResolvedHdr.Keep)

      val render = planner.lower(composition, ask).graph.shouldNotBeNull()

      render.hdrTransfer shouldBe HdrTransfer.Pq
      render.videoEncoding?.encoderCodec shouldBe HDR_VP9_CODEC
    }
  }

  // ffmpeg and the mobile backends stream-copy an untouched clip straight through. This pipeline
  // now can too: mediabunny reads the source's packets and writes them into a new container with
  // no decode, draw or encode step, so a request for exactly what the source already is claims it.
  @Test
  fun untouchedCompositionClaimsACopy() {
    val composition =
      Composition(tracks = listOf(Track(listOf(Clip(source("a"))))), audio = AudioSpec.Keep)
    val verdict = lower(composition, ExportSpec()).verdict
    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.estimate.isCopy shouldBe true
  }

  // mediabunny's writer carries VP8 and Vorbis into mp4, which is more than the shared mp4 baseline
  // the other backends read, so the divergence is this backend's to claim and to prove.
  @Test
  fun anUntouchedVp8ClipCopiesBecauseMediabunnyCarriesIt() {
    val clip = Clip(source("a"))
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos =
      mapOf(
        clip.source to
          info(
            codec = "vp8",
            audio =
              AudioTrackInfo(
                AudioTrackId(1),
                trackCodecOf("vorbis"),
                48_000,
                2,
                null,
                language = null,
                isDefault = true,
              ),
          ),
      )

    val verdict = lower(composition, ExportSpec(), infos).verdict

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Copy
  }

  // On a copy, ExportPlanner reports the source's own codec in output.videoCodec rather than the
  // ladder's pick, and VideoCodec has entries webCodecString/muxCodecKey never encode. Planning
  // must succeed rather than throw
  // building fields an encode never runs, and render.videoEncoding has to say so rather than disagree
  // with the mp4 a copy actually writes.
  @Test
  fun anUntouchedVp8OrAv1SourcePlansWithoutThrowing() {
    for ((codecName, expected) in listOf("vp8" to VideoCodec.Vp8, "av01" to VideoCodec.Av1)) {
      val clip = Clip(source("a"))
      val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
      val lowering = lower(composition, ExportSpec(), infos = mapOf(clip.source to info(codec = codecName)))

      val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
      withClue("codec $codecName") { plan.output.videoCodec shouldBe expected }
      withClue("codec $codecName") {
        lowering.graph
          .shouldNotBeNull()
          .videoEncoding
          .shouldBeNull()
      }
    }
  }

  // muxerAccepts refusing a codec falls back to a normal transcode rather than a refusal or a broken
  // copy. Audio is off here to isolate that fallback from anything about audio.
  @Test
  fun uncopyableCodecStillPlansTranscode() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(videoCodec = VideoCodec.Auto, audioCodec = AudioCodec.None)
    val verdict = lower(compositionOf(listOf(clip)), spec, infos = mapOf(clip.source to info(codec = "theora"))).verdict
    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.estimate.isCopy shouldBe false
  }

  // AudioSpec.AudioOnly writes a file with an audio track and nothing else. The plan has to say so
  // here, or the pipeline opens a video encoder and draws frames the caller never asked for.
  @Test
  fun audioOnlyPlansNothingToDraw() {
    val clip = Clip(source("a"))
    val composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.AudioOnly)
    val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
    val lowering =
      planner.lower(
        composition,
        Ask(
          spec,
          device(VideoCodec.H264, audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2))),
          mapOf(
            clip.source to
              info(
                audio =
                  AudioTrackInfo(
                    AudioTrackId(1),
                    trackCodecOf("mp4a"),
                    48_000,
                    2,
                    null,
                    language = null,
                    isDefault = true,
                  ),
              ),
          ),
        ),
      )

    lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    val render = lowering.graph.shouldNotBeNull()
    render.writesVideo shouldBe false
    render.clips shouldBe emptyList()
    render.videoEncoding.shouldBeNull()
    render.estimatedFrames shouldBe 0L
    render.audioFormat.shouldNotBeNull().sampleRate shouldBe 48_000
  }

  // The mixer renders in windows, so a long timeline costs a window at a time and there is nothing
  // left for the planner to refuse it over.
  @Test
  fun aLongMixIsPlannedRatherThanRefused() {
    val lowering = mixExport()

    lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    lowering.graph
      .shouldNotBeNull()
      .audioFormat
      .shouldNotBeNull()
      .sampleRate shouldBe 48_000
  }

  @Test
  fun aLongCopyStillCopies() {
    val lowering = mixExport(spec = ExportSpec())

    lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    lowering.graph
      .shouldNotBeNull()
      .videoEncoding
      .shouldBeNull()
  }

  private fun transcodeSpec(audioCodec: AudioCodec): ExportSpec =
    ExportSpec(heightPx = 720, videoCodec = VideoCodec.H264, audioCodec = audioCodec)

  // One clip of a source that carries audio, running well past a single mix window by default.
  private fun mixExport(
    spec: ExportSpec = transcodeSpec(AudioCodec.Aac),
    duration: Duration = 30.minutes,
    sampleRate: Int = 48_000,
    channelCount: Int = 2,
  ): LoweredExport<BrowserRender> {
    val clip = Clip(source("a"))
    return planner.lower(
      composition = Composition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep),
      ask =
        Ask(
          spec = spec,
          device = device(VideoCodec.H264, audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2))),
          infos =
            mapOf(
              clip.source to
                info(
                  duration = duration,
                  audio =
                    AudioTrackInfo(
                      AudioTrackId(1),
                      trackCodecOf("mp4a"),
                      sampleRate,
                      channelCount,
                      null,
                      language = null,
                      isDefault = true,
                    ),
                ),
            ),
        ),
    )
  }

  /**
   * A looping video primary under a longer audio-only track, which is case 3 of the looping suite
   * every backend runs.
   *
   * The render draws the passes the planner laid rather than repeating one clip of its own, so what
   * is drawn has to be the laid list: the same count, the same slots, and a last pass cut where the
   * composition ends. There is no encoder here to write the picture, so the passes are pinned on
   * the render instead of read back out of a file.
   *
   * At twenty five frames a second a pass is thirty seven and a half slots, so every other boundary
   * falls between two slots and each pass has to open on the slot the grid puts it on.
   */
  @Test
  fun aLoopingPrimaryDrawsEveryPassThePlannerLaid() {
    val bed = Clip(source("bed"), trim = LoopingCases.VIDEO_TRIM)
    val under = Clip(source("under"), trim = TimeRange(Duration.ZERO, LoopingCases.UNDERLAY_RUN))
    val tone = AudioTrackInfo(AudioTrackId(1), trackCodecOf("mp4a"), 48_000, 2, null, language = null, isDefault = true)
    val lowering =
      planner.lower(
        composition =
          Composition(
            tracks =
              listOf(
                Track(listOf(bed), looping = true),
                Track(listOf(under), content = TrackContent.Audio),
              ),
            audio = AudioSpec.Keep,
          ),
        ask =
          Ask(
            spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac, frameRate = GRID_RATE),
            device = device(VideoCodec.H264, audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2))),
            infos =
              mapOf(
                bed.source to info(width = 320, height = 240, duration = LOOP_SOURCE, audio = tone),
                under.source to info(duration = UNDER_SOURCE, audio = tone),
              ),
          ),
      )

    lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    val render = lowering.graph.shouldNotBeNull()
    render.duration shouldBe LoopingCases.UNDERLAY_RUN
    render.frameRate shouldBe GRID_RATE

    // The schedule by name first, so a wrong pass count or an uncut last pass fails before any
    // geometry is compared.
    val track = render.audioTracks.first()
    val laid = track.clips
    val expected =
      passesCovering(LoopingCases.VIDEO_LENGTHS, render.duration - track.start).map { pass ->
        Triple(pass.index, track.start + pass.offset, track.start + pass.offset + pass.length)
      }
    laid.map { Triple(it.sourceIndex, it.span.start, it.span.endExclusive) } shouldBe expected

    // And the drawn clips against the laid ones rather than against those numbers again, since a
    // render working the repeat out for itself is what this pins.
    render.clips.size shouldBe laid.size
    var opens = render.leadFrames
    laid.forEachIndexed { index, pass ->
      val drawn = render.clips[index]
      withClue("pass $index") { drawn.source shouldBe bed.source }
      withClue("pass $index") { drawn.firstSlot shouldBe outputSlotOf(pass.span.start, GRID_RATE) }
      withClue("pass $index opens away from where the one before it ended") { drawn.firstSlot shouldBe opens }
      withClue("pass $index") { drawn.trimStartUs shouldBe micros(pass.start) }
      withClue("pass $index") { drawn.trimEndUs shouldBe micros(pass.end) }
      opens = drawn.firstSlot + drawn.frames
    }
    render.leadFrames + render.clips.sumOf { it.frames } shouldBe
      outputSlotOf(checkNotNull(laid.last().span.endExclusive), GRID_RATE)
    // The loop runs the primary to the composition's end, so nothing is left for a held frame to cover.
    render.clips.forEachIndexed { index, drawn -> withClue("pass $index") { drawn.heldFrames shouldBe 0L } }

    // The track underneath does not loop, so it lays its one clip once across the whole run and
    // nothing about the primary's repeat reaches it.
    val beneath = render.audioTracks[1].clips.single()
    beneath.span.start shouldBe Duration.ZERO
    beneath.span.endExclusive shouldBe LoopingCases.UNDERLAY_RUN
  }

  // A primary that starts late draws every pass on the slot the grid puts it on, and the slots in
  // front of the first one belong to the render. The start sits off the frame grid, so the gap rounds
  // to its nearest slot and the first clip opens on the slot straight after the last gap frame.
  @Test
  fun aPrimaryThatStartsLateLeadsWithTheGapAndDrawsWhereItWasLaid() {
    val first = Clip(source("a"))
    val second = Clip(source("b"), trim = TimeRange(TRIM_START, TRIM_END))
    val composition =
      Composition(tracks = listOf(Track(listOf(first, second), start = LATE_START)), audio = AudioSpec.Remove)

    val render = lower(composition).graph.shouldNotBeNull()

    val laid = render.audioTracks.first().clips
    laid.first().span.start shouldBe LATE_START
    render.clips.size shouldBe laid.size
    laid.forEachIndexed { index, pass ->
      withClue("clip $index") { render.clips[index].firstSlot shouldBe outputSlotOf(pass.span.start, render.frameRate) }
    }

    render.leadFrames shouldBe outputSlotOf(LATE_START, render.frameRate)
    render.estimatedFrames shouldBe render.leadFrames + render.clips.sumOf { it.frames }
    // No gap slot reaches the first clip, so the timestamps the pipeline writes keep climbing
    // across the seam.
    render.clips.first().firstSlot shouldBe render.leadFrames
  }

  // The gap is the slot the shared grid puts the start on, which is what ffmpeg's tpad pads the same
  // start with. The rule itself is pinned where the grid is derived, so this reads the count against
  // the function rather than a copy of its answers.
  @Test
  fun aLateStartRoundsToTheNearestSlot() {
    listOf(
      510.milliseconds to 30,
      520.milliseconds to 30,
      250.milliseconds to 30,
      // Exactly half of a forty millisecond slot.
      20.milliseconds to 25,
      // Under half a slot.
      10.milliseconds to 30,
    ).forEach { (start, frameRate) ->
      withClue("a start of $start at $frameRate fps") {
        leadOf(start, frameRate) shouldBe outputSlotOf(start, frameRate)
      }
    }
  }

  private fun leadOf(
    start: Duration,
    frameRate: Int,
  ): Long {
    val composition =
      Composition(tracks = listOf(Track(listOf(Clip(source("a"))), start = start)), audio = AudioSpec.Remove)
    val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, frameRate = frameRate)

    val render = lower(composition, spec).graph.shouldNotBeNull()
    withClue("the plan ran at another rate than $start was measured against") { render.frameRate shouldBe frameRate }
    return render.leadFrames
  }

  // A primary with no gap leads with nothing, and its first clip opens the composition.
  @Test
  fun aPrimaryThatStartsWithTheCompositionHasNoLead() {
    val render = lower(compositionOf(listOf(Clip(source("a"))))).graph.shouldNotBeNull()

    render.leadFrames shouldBe 0L
    render.clips.single().firstSlot shouldBe 0L
    render.estimatedFrames shouldBe render.clips.single().frames
  }

  // A clip trimmed a quarter of a slot past a whole one covers the slots its span does and no more,
  // so the next clip opens on the slot straight after its last and the render runs to where the grid
  // puts the track's end.
  @Test
  fun anOffGridClipEndsOnTheSlotTheNextOneOpensOn() {
    val first = Clip(source("a"), trim = TimeRange(Duration.ZERO, OFF_GRID_LENGTH))
    val second = Clip(source("b"))

    val render = lower(compositionOf(listOf(first, second)), gridSpec()).graph.shouldNotBeNull()

    val laid = render.audioTracks.first().clips
    render.clips.first().frames shouldBe outputSlotsOf(laid.first().span, GRID_RATE).count().toLong()
    render.clips.last().firstSlot shouldBe render.clips.first().firstSlot + render.clips.first().frames
    render.estimatedFrames shouldBe outputSlotOf(checkNotNull(laid.last().span.endExclusive), GRID_RATE)
  }

  // Exactly half a slot in. The gap takes the slot the grid rounds the start onto, and the first clip
  // opens on the slot straight after it rather than half way through it.
  @Test
  fun aStartHalfASlotInOpensTheFirstClipOnTheSlotAfterTheGap() {
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(source("a"))), start = HALF_SLOT_START)),
        audio = AudioSpec.Remove,
      )

    val render = lower(composition, gridSpec()).graph.shouldNotBeNull()

    render.leadFrames shouldBe outputSlotOf(HALF_SLOT_START, GRID_RATE)
    render.clips.first().firstSlot shouldBe render.leadFrames
  }

  // Ten milliseconds from a whole second never reaches the point half way to the next slot, so the
  // clip covers no slot of its own, draws nothing, and the clips either side of it meet.
  @Test
  fun aClipUnderHalfASlotBetweenTwoOthersDrawsNothing() {
    val composition =
      compositionOf(
        listOf(
          Clip(source("a")),
          Clip(source("b"), trim = TimeRange(Duration.ZERO, UNDER_HALF_SLOT)),
          Clip(source("c")),
        ),
      )

    val render = lower(composition, gridSpec()).graph.shouldNotBeNull()

    val laid = render.audioTracks.first().clips
    withClue("a span of $UNDER_HALF_SLOT covers a slot of its own") {
      outputSlotsOf(laid[1].span, GRID_RATE).isEmpty() shouldBe true
    }
    render.clips[1].frames shouldBe 0L
    render.clips[2].firstSlot shouldBe render.clips[0].firstSlot + render.clips[0].frames
  }

  // A bed that outlasts the primary sets the composition's length, and the render holds the primary's last clip over
  // every slot past it. The last clip still draws only the slots its own span covers, and the tail opens on the slot
  // straight after them.
  @Test
  fun aBedThatOutlastsThePrimaryHoldsItsLastClipOverTheTail() {
    val primary =
      listOf(
        Clip(source("a"), trim = TimeRange(Duration.ZERO, TRIM_START)),
        Clip(source("b"), trim = TimeRange(Duration.ZERO, OFF_GRID_LENGTH)),
      )
    val lowering = lowerUnderABed(primary)

    val resolved = checkNotNull(lowering.export.composition)
    val render = lowering.graph.shouldNotBeNull()
    val tail = resolved.heldTail
    withClue("a bed of $BED_SOURCE under a shorter primary left no tail") { tail.isEmpty() shouldBe false }

    val laid = render.audioTracks.first().clips
    val last = render.clips.last()
    last.frames shouldBe outputSlotsOf(laid.last().span, GRID_RATE).count().toLong()
    last.heldFrames shouldBe tail.count().toLong()
    tail.first shouldBe last.firstSlot + last.frames
    render.clips.dropLast(1).forEachIndexed { index, clip -> withClue("clip $index") { clip.heldFrames shouldBe 0L } }
    render.estimatedFrames shouldBe resolved.videoSlots
  }

  // Nothing outlasts the primary here, so no clip holds and the render walks the slots the grid gives the composition.
  @Test
  fun aPrimaryNothingOutlastsHoldsNoFrames() {
    val composition =
      compositionOf(listOf(Clip(source("a")), Clip(source("b"), trim = TimeRange(Duration.ZERO, OFF_GRID_LENGTH))))
    val lowering = lower(composition, gridSpec())

    val resolved = checkNotNull(lowering.export.composition)
    val render = lowering.graph.shouldNotBeNull()
    withClue("a primary with nothing under it left a tail of ${resolved.heldTail}") {
      resolved.heldTail.isEmpty() shouldBe true
    }
    render.clips.forEachIndexed { index, clip -> withClue("clip $index") { clip.heldFrames shouldBe 0L } }
    render.estimatedFrames shouldBe resolved.videoSlots
  }

  // A last clip too short to cover a slot draws nothing, so the frame held over the tail is the one the clip before it
  // closes on, and the hold sits on that clip instead.
  @Test
  fun aLastClipThatCoversNoSlotLeavesTheHoldOnTheClipBeforeIt() {
    val lowering =
      lowerUnderABed(listOf(Clip(source("a")), Clip(source("b"), trim = TimeRange(Duration.ZERO, UNDER_HALF_SLOT))))

    val resolved = checkNotNull(lowering.export.composition)
    val render = lowering.graph.shouldNotBeNull()
    val tail = resolved.heldTail
    withClue("a bed of $BED_SOURCE under a shorter primary left no tail") { tail.isEmpty() shouldBe false }

    val (covered, empty) = render.clips
    empty.frames shouldBe 0L
    empty.heldFrames shouldBe 0L
    covered.heldFrames shouldBe tail.count().toLong()
    tail.first shouldBe covered.firstSlot + covered.frames
    render.estimatedFrames shouldBe resolved.videoSlots
  }

  /**
   * Lowers [primary] at [GRID_RATE] over a bed of [BED_SOURCE] on an audio track of its own.
   *
   * The bed's source carries no audio, so nothing is mixed and the bed does no more than set how long the composition
   * runs.
   */
  private fun lowerUnderABed(primary: List<Clip>): LoweredExport<BrowserRender> {
    val bed = Clip(source("bed"))
    val composition =
      Composition(
        tracks = listOf(Track(primary), Track(listOf(bed), content = TrackContent.Audio)),
        audio = AudioSpec.Remove,
      )
    val infos = primary.associate { it.source to info() } + (bed.source to info(duration = BED_SOURCE))
    return lower(composition, gridSpec(), infos = infos)
  }

  private fun gridSpec(): ExportSpec =
    ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, frameRate = GRID_RATE)

  @Test
  fun secondVideoTrackIsRefused() {
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(source("a")))), Track(listOf(Clip(source("b"))))),
        audio = AudioSpec.Remove,
      )
    lower(composition).verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  @Test
  fun vp9GoesToWebm() {
    val spec = ExportSpec(videoCodec = VideoCodec.Vp9, audioCodec = AudioCodec.None)
    val lowering = lower(compositionOf(listOf(Clip(source("a")))), spec)
    lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    lowering.graph
      .shouldNotBeNull()
      .container shouldBe "webm"
  }

  @Test
  fun aacAskedWithOnlyOpusListedFallsBackToOpus() {
    val lowering =
      audioExport(ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac), listOf(BROWSER_OPUS_ENCODER))

    val adjustment =
      lowering.verdict
        .shouldBeInstanceOf<Verdict.Degraded>()
        .adjustments
        .single()
    adjustment.kind shouldBe AdjustmentKind.CodecFallback
    adjustment.requested shouldBe AudioCodec.Aac.name
    adjustment.resolved shouldBe AudioCodec.Opus.name
    lowering.graph.shouldNotBeNull().audioCodec shouldBe AudioCodec.Opus
  }

  // WebM carries no AAC, so a VP9 export writes Opus even where the browser encodes both.
  @Test
  fun vp9WithAacAndOpusListedPlansOpus() {
    val encoders = listOf(BROWSER_AAC_ENCODER, BROWSER_OPUS_ENCODER)

    val auto = audioExport(ExportSpec(videoCodec = VideoCodec.Vp9), encoders)
    val aac = audioExport(ExportSpec(videoCodec = VideoCodec.Vp9, audioCodec = AudioCodec.Aac), encoders)

    auto.verdict.shouldBeInstanceOf<Verdict.Capable>()
    auto.graph.shouldNotBeNull().audioCodec shouldBe AudioCodec.Opus
    auto.graph.shouldNotBeNull().container shouldBe "webm"
    val fallback =
      aac.verdict
        .shouldBeInstanceOf<Verdict.Degraded>()
        .adjustments
        .single()
    fallback.kind shouldBe AdjustmentKind.CodecFallback
    fallback.resolved shouldBe AudioCodec.Opus.name
    aac.graph.shouldNotBeNull().audioCodec shouldBe AudioCodec.Opus
  }

  @Test
  fun anH264ExportKeepsAacWhereBothAreListed() {
    val lowering =
      audioExport(ExportSpec(videoCodec = VideoCodec.H264), listOf(BROWSER_AAC_ENCODER, BROWSER_OPUS_ENCODER))

    lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    lowering.graph.shouldNotBeNull().audioCodec shouldBe AudioCodec.Aac
    lowering.graph.shouldNotBeNull().container shouldBe "mp4"
  }

  @Test
  fun audioOnlyOpusPlansWebm() {
    val lowering = audioExport(ExportSpec(), listOf(BROWSER_OPUS_ENCODER), AudioSpec.AudioOnly)

    val render = lowering.graph.shouldNotBeNull()
    render.audioCodec shouldBe AudioCodec.Opus
    render.container shouldBe "webm"
  }

  // The video codec asked for writes no file here, so the container follows the audio and mp4 keeps AAC.
  @Test
  fun audioOnlyAskingForVp9KeepsAacOnAnAacOnlyDevice() {
    val lowering =
      audioExport(ExportSpec(videoCodec = VideoCodec.Vp9), listOf(BROWSER_AAC_ENCODER), AudioSpec.AudioOnly)

    lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    val render = lowering.graph.shouldNotBeNull()
    render.audioCodec shouldBe AudioCodec.Aac
    render.container shouldBe "mp4"
  }

  // Opus codes at 48 kHz only, so a 44.1 kHz source is resampled up to it. Opus is asked for so the AAC source is
  // encoded rather than copied.
  @Test
  fun opusPlansFortyEightKilohertzFromAFortyFourOneSource() {
    val spec = ExportSpec(audioCodec = AudioCodec.Opus)
    val lowering = audioExport(spec, listOf(BROWSER_OPUS_ENCODER), sampleRate = 44_100)

    val output =
      lowering.verdict
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.output
    output.audioCodec shouldBe AudioCodec.Opus
    output.audioFormat.shouldNotBeNull().sampleRate shouldBe 48_000
  }

  private fun audioExport(
    spec: ExportSpec,
    encoders: List<AudioEncoderCapability>,
    audio: AudioSpec = AudioSpec.Keep,
    sampleRate: Int = 48_000,
  ): LoweredExport<BrowserRender> {
    val clip = Clip(source("a"))
    return planner.lower(
      Composition(tracks = listOf(Track(listOf(clip))), audio = audio),
      Ask(
        spec,
        device(VideoCodec.H264, VideoCodec.Vp9, audio = encoders),
        mapOf(
          clip.source to
            info(
              audio =
                AudioTrackInfo(
                  AudioTrackId(1),
                  trackCodecOf("mp4a"),
                  sampleRate,
                  2,
                  null,
                  language = null,
                  isDefault = true,
                ),
            ),
        ),
      ),
    )
  }

  @Test
  fun parityIsExactForSupportedAndNullForTheRest() {
    val prober = MediaProber { ProbeResult.Failure(FilmstripError.SourceUnreadable("", "unused by this test")) }
    val engine = BrowserExportEngine(ComponentRegistry.Builder().build(), prober)
    engine.parityOf(EffectIds.CROP) shouldBe EffectParity.Exact
    COLOR_IDS.forEach { id -> withClue(id) { engine.parityOf(id) shouldBe EffectParity.Exact } }
    engine.parityOf(EffectIds.ROTATE) shouldBe EffectParity.Exact
    engine.parityOf(EffectIds.SCALE) shouldBe EffectParity.Exact
    engine.parityOf(EffectIds.KEN_BURNS) shouldBe EffectParity.Exact
    engine.parityOf("acme.filmgrain").shouldBeNull()
  }

  private fun matrixOf(spec: EffectSpec): FloatArray = checkNotNull(colorMatrixOf(spec)).toColumnMajor4x4()

  // The render carries its times as WebCodecs microseconds, so a laid clip is compared in those.
  private fun micros(duration: Duration): Double = duration.inWholeMicroseconds.toDouble()

  // Column-major on both sides, so a row lands at a stride of four, the layout the uniform takes.
  private fun compositionOf(
    clips: List<Clip>,
    effects: List<EffectSpec> = emptyList(),
  ): Composition =
    Composition(
      tracks = listOf(Track(clips = clips)),
      effects = effects,
      audio = AudioSpec.Remove,
    )

  private fun source(name: String): MediaSource = MediaSource.Bytes(name.encodeToByteArray())

  private fun videoSpec(heightPx: Int? = null): ExportSpec =
    ExportSpec(heightPx = heightPx, videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None)

  private fun info(
    width: Int = 1920,
    height: Int = 1080,
    rotation: Int = 0,
    duration: Duration = 2000.milliseconds,
    hdr: HdrTransfer? = null,
    codec: String = "avc1",
    audio: AudioTrackInfo? = null,
  ): MediaInfo =
    MediaInfo(
      duration = duration,
      video =
        VideoTrackInfo(
          codedSize = Size(width, height),
          displaySize = Size(width, height),
          rotationDegrees = rotation,
          pixelAspectRatio = 1f,
          frameRate = 30f,
          codec = trackCodecOf(codec),
          bitDepth = if (hdr == null) 8 else 10,
          colorSpace = ColorSpace.Bt709,
          hdrTransfer = hdr,
          bitrate = null,
          hasAlpha = false,
        ),
      audioTracks = listOfNotNull(audio),
      isExportable = true,
    )

  private fun device(
    vararg codecs: VideoCodec,
    audio: List<AudioEncoderCapability> = emptyList(),
    supportsHdrEncoding: Boolean = false,
  ): DeviceCapabilities =
    DeviceCapabilities(
      video = codecs.map { VideoEncoderCapability(it, null, Size(3840, 2160), null, null, false, 2) },
      audio = audio,
      supportsHdrEncoding = supportsHdrEncoding,
      concurrentSessionBudget = null,
    )

  private companion object {
    // Opens a window up to the shared TRIM_END inside the fabricated source's two seconds, clear of
    // both its ends, so a lowering that dropped one bound cannot land on the same answer by accident.
    val TRIM_START = 700.milliseconds

    // The sync sample a copy would open on, four hundred milliseconds back from the cut, with a
    // tolerance either side of that gap so neither answer is the one a broken snap gives anyway.
    val OPENING = 300.milliseconds

    // Fifteen slots and three tenths of another at the fabricated sources' thirty frames a second.
    val LATE_START = 510.milliseconds

    // A slot runs forty milliseconds at twenty five frames a second, so a start of twenty is exactly
    // half of one, a clip of 1.010 seconds ends a quarter of the way into one, and ten milliseconds is
    // a quarter of one.
    const val GRID_RATE = 25
    val HALF_SLOT_START = 20.milliseconds
    val OFF_GRID_LENGTH = 1_010.milliseconds
    val UNDER_HALF_SLOT = 10.milliseconds

    // Longer than any primary the tail cases lay over it.
    val BED_SOURCE = 5_000.milliseconds

    // The fabricated sources case 3 of the looping suite is probed with, both longer than the trims
    // taken out of them.
    val LOOP_SOURCE = 3_000.milliseconds
    val UNDER_SOURCE = 12_000.milliseconds
    val REACHES = 600.milliseconds
    val FALLS_SHORT = 200.milliseconds

    val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    const val MATRIX_TOLERANCE = 1e-5f

    val COLOR_IDS =
      listOf(
        EffectIds.BRIGHTNESS,
        EffectIds.RGB_ADJUSTMENT,
        EffectIds.CONTRAST,
        EffectIds.SATURATION,
        EffectIds.HUE_ROTATE,
        EffectIds.SEPIA,
        EffectIds.INVERT,
        EffectIds.COLOR_MATRIX,
      )
  }
}

// Stands in for a third-party pass that spells a uniform of the same name its own way. The fold has
// to read past it rather than take it for a mat4.
private class ForeignPassResolver : EffectResolver {
  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (spec !is Brightness) return null

    return EffectResolution.Resolved(
      PlatformEffect(WebGlPass("foreign.grade", mapOf("uColorMatrix" to floatArrayOf(1f, 0f, 0f)))),
    )
  }
}
