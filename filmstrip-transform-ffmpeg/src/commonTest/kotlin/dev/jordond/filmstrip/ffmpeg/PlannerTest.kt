package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegParity
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPlanner
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.ffmpegEncoderNamed
import dev.jordond.filmstrip.ffmpeg.internal.ffmpegEncoders
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.stillUnsupportedMessage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val TONE_MAPPING = setOf("overlay", "concat", "amix", "zscale")
private val LIBPLACEBO_TONE_MAPPING = setOf("overlay", "concat", "amix", "libplacebo")

class PlannerTest {
  private val landscape = MediaSource.of("/clips/landscape.mp4")
  private val portrait = MediaSource.of("/clips/portrait.mp4")
  private val music = MediaSource.of("/clips/music.m4a")

  private val graded = MediaSource.of("/clips/graded.mov")

  private val infos =
    mapOf(
      landscape to info(Size(1920, 1080), 4_000.milliseconds),
      portrait to info(Size(1080, 1920), 3_000.milliseconds),
      graded to info(Size(1920, 1080), 4_000.milliseconds, hdr = HdrTransfer.Pq),
      music to MediaInfo(10_000.milliseconds, null, AudioTrackInfo(trackCodecOf("mp4a"), 44_100, 2, null), true),
    )

  @Test
  fun `reframes to the requested aspect and height`() {
    val composition =
      EditComposition(listOf(Track(listOf(Clip(landscape, effects = listOf(Crop(AspectRatio.Portrait)))))))

    val plan = capablePlan(composition, ExportSpec(targetHeight = 720))

    plan.output.size shouldBe Size(404, 720)
    plan.output.frameRate shouldBe 30
    plan.effectOrder.map { it.spec.id } shouldBe listOf(EffectIds.CROP)
  }

  // concat refuses inputs that differ in resolution or sample aspect, so every clip carries the
  // size stage before the join.
  @Test
  fun `normalises every clip before the concat`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape), Clip(portrait)))))

    val graph = capableGraph(composition, ExportSpec(targetHeight = 360))

    graph shouldContain "[0:v]"
    graph shouldContain "[1:v]"
    graph shouldContain "concat=n=2:v=1:a=0"
    graph shouldContain "setsar=r=1"
    Regex("scale=").findAll(graph).count() shouldBe 2
  }

  // concat pins every clip to the output frame before the join, so there is no composited frame
  // left to crop. Composition geometry runs on each clip's own frame instead, which is why the two
  // clips get different pixel rectangles out of the one spec.
  @Test
  fun `crops each clip in its own frame when the crop is on the composition`() {
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(landscape), Clip(portrait)))),
        effects = listOf(CropRect(NormalizedRect(left = 0f, top = 0f, right = 0.5f, bottom = 1f))),
      )

    val graph = capableGraph(composition, ExportSpec())

    graph shouldContain "crop=w=960:h=1080:x=0:y=0"
    graph shouldContain "crop=w=540:h=1920:x=0:y=0"
  }

  // An overlay brings an input of its own, so it cannot be spelled inline the way a crop can. The
  // clip's chain has to break at the merge, and the second clip's input has to keep its index once
  // the overlay image has claimed one.
  @Test
  fun `overlays a watermark declared on a clip`() {
    val mark = ImageOverlay(ImageSource.of("/logo.png"), Corner.TopStart)
    val composition =
      EditComposition(listOf(Track(listOf(Clip(landscape, effects = listOf(mark)), Clip(portrait)))))

    val invocation = planner().lower(composition, ExportSpec(), device(), infos).invocation!!

    invocation.filterGraph shouldContain "overlay=x=43:y=43:format=auto:eof_action=pass"
    // The image claims an input after both clips, so the graph reads it at index 2.
    invocation.filterGraph shouldContain "[2:v]scale=w=384:h=-1,format=pix_fmts=rgba"
    invocation.inputs.size shouldBe 3
  }

  // Nothing here encodes HDR, so an untouched grade written into an 8-bit file is the washed-out
  // frame the pixels decode to. The curve runs ahead of every effect, and only on the graded clip.
  @Test
  fun `tone-maps only the clip that carries a grade`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(graded), Clip(landscape)))))

    val graph = capableGraph(composition, ExportSpec(), toolchain(TONE_MAPPING))

    graph shouldContain "zscale=transfer=linear:npl=100,tonemap=tonemap=hable:desat=0"
    Regex("zscale=transfer=linear").findAll(graph).count() shouldBe 1
  }

  // A build with libplacebo but no zscale takes the other route: one node instead of three, still
  // landing on BT.709.
  @Test
  fun `tone-maps through libplacebo on a build with no zscale`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(graded), Clip(landscape)))))

    val graph = capableGraph(composition, ExportSpec(), toolchain(LIBPLACEBO_TONE_MAPPING))

    graph shouldContain
      "libplacebo=tonemapping=bt.2390:colorspace=bt709:color_primaries=bt709:color_trc=bt709:range=tv"
  }

  // A copy would already preserve this clip's grade untouched, so the rotation is what forces the
  // tone map this test is actually about.
  @Test
  fun `reports the tone map it performs`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(graded, effects = listOf(Rotate(90)))))))

    val verdict = planner(toolchain(TONE_MAPPING)).lower(composition, ExportSpec(), device(), infos).verdict

    assertIs<Verdict.Degraded>(verdict).adjustments.map { it.kind } shouldBe
      listOf(AdjustmentKind.HdrToneMapped)
  }

  // Without zscale or libplacebo there is no route to run, and claiming the tone map anyway wrote
  // the grade straight into an SDR file and called it done. The rotation rules out the copy that
  // would otherwise have preserved the grade with no tone map needed at all.
  @Test
  fun `refuses a graded source on a build with no zscale or libplacebo`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(graded, effects = listOf(Rotate(90)))))))

    val verdict = planner().lower(composition, ExportSpec(), device(), infos).verdict

    val reason = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(reason).message shouldContain "neither encode it nor tone-map it"
  }

  // Every export here writes mp4, and mp4 has no sample entry for VP8, so an otherwise untouched
  // clip that carries one has to be encoded rather than copied. The muxer would refuse a copy that
  // reached it, and the plan would have promised one.
  @Test
  fun `an untouched clip mp4 cannot carry is encoded rather than copied`() {
    val source = MediaSource.of("/clips/untouched-vp8.webm")
    val composition = EditComposition(listOf(Track(listOf(Clip(source)))))
    val infos = mapOf(source to info(Size(1920, 1080), 6_000.milliseconds, codec = "vp8"))

    val verdict = planner().lower(composition, ExportSpec(), device(), infos).verdict

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  // The cheapest fix for HDR on a build with no zscale: an untouched clip needs no filter graph at
  // all, so a copy keeps the grade exactly whatever the build can do.
  @Test
  fun `an untouched graded source transmuxes on a build with no zscale`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(graded)))))

    val verdict = planner().lower(composition, ExportSpec(), device(), infos).verdict

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transmux
  }

  // The device measured an HDR-capable encoder, so the grade rides straight through to it instead
  // of being tone-mapped, and the codec ladder pins to the one profile that can carry it. A copy
  // would preserve HDR without ever touching this code, so the source carries a codec this backend
  // cannot copy and the plan is forced to actually encode.
  @Test
  fun `keeps hdr and writes the encoder's own pixel format when the device can encode it`() {
    val source = MediaSource.of("/clips/graded-mpeg2.mov")
    val composition = EditComposition(listOf(Track(listOf(Clip(source)))))
    val device = device(listOf(VideoCodec.H264, VideoCodec.Hevc), supportsHdrEncoding = true)
    val sourceInfos =
      infos + (source to info(Size(1920, 1080), 4_000.milliseconds, hdr = HdrTransfer.Pq, codec = "mpeg2video"))

    val lowering = planner().lower(composition, ExportSpec(hdr = HdrMode.KeepHdr), device, sourceInfos)

    val plan = assertIs<Verdict.Capable>(lowering.verdict).plan
    plan.output.videoCodec shouldBe VideoCodec.Hevc

    val invocation = lowering.invocation!!
    invocation.hdrTransfer shouldBe HdrTransfer.Pq
    val encoder = checkNotNull(ffmpegEncoderNamed(invocation.videoEncoder!!))
    invocation.filterGraph shouldContain "format=pix_fmts=${encoder.hdrPixelFormat}"
  }

  // An HLG source is not PQ, and writing it as PQ would tag the file with a curve the pixels were
  // never graded to.
  @Test
  fun `carries HLG rather than assuming PQ`() {
    val hlg = MediaSource.of("/clips/hlg.mov")
    val composition = EditComposition(listOf(Track(listOf(Clip(hlg)))))
    val device = device(listOf(VideoCodec.H264, VideoCodec.Hevc), supportsHdrEncoding = true)
    val hlgInfos = infos + (hlg to info(Size(1920, 1080), 4_000.milliseconds, hdr = HdrTransfer.Hlg))

    val lowering = planner().lower(composition, ExportSpec(hdr = HdrMode.KeepHdr), device, hlgInfos)

    lowering.invocation!!.hdrTransfer shouldBe HdrTransfer.Hlg
  }

  // Without gblur there is nothing to lower a blurred fill onto, and emitting the graph anyway
  // would hand ffmpeg a filter it does not have.
  @Test
  fun `refuses a blurred fill on a build with no gblur`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape)))), fill = Fill.Blurred())

    val verdict = planner().lower(composition, ExportSpec(), device(), infos).verdict

    val reason = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(reason).message shouldContain "gblur"
  }

  // targetHeight forces a re-encode rather than the stream copy an untouched clip would take,
  // since a copy never reaches the filter graph at all.
  @Test
  fun `lowers a blurred fill once the build has gblur`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape)))), fill = Fill.Blurred())

    val graph = capableGraph(composition, ExportSpec(targetHeight = 720), toolchain(TONE_MAPPING + "gblur"))

    graph shouldContain "gblur=sigma="
  }

  // colorchannelmixer is only reached once dim actually darkens something, so a build that has
  // gblur but not it is refused only for the fill that would need it, not every blurred one.
  @Test
  fun `refuses a dimmed blurred fill on a build with no colorchannelmixer`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape)))), fill = Fill.Blurred(dim = 0.5f))

    val verdict =
      planner(toolchain(TONE_MAPPING + "gblur")).lower(composition, ExportSpec(), device(), infos).verdict

    val reason = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(reason).message shouldContain "colorchannelmixer"
  }

  // Crop leaves no bars for a blurred fill to show through, so the missing filter never matters
  // and the build is not refused for a capability it never needed.
  @Test
  fun `does not refuse a blurred fill that fit never shows`() {
    val composition =
      EditComposition(
        listOf(Track(listOf(Clip(landscape, effects = listOf(Scale(360, fit = Fit.Crop)))))),
        fill = Fill.Blurred(),
      )

    val verdict = planner().lower(composition, ExportSpec(), device(), infos).verdict

    assertIs<Verdict.Capable>(verdict)
  }

  @Test
  fun `trims to the requested window`() {
    val composition =
      EditComposition(
        listOf(Track(listOf(Clip(landscape, trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds))))),
      )

    capableGraph(composition, ExportSpec()) shouldContain "trim=start=1.000000:end=3.000000"
  }

  // normalize defaults to true and divides by the input count, so adding a bed would silently
  // halve the dialogue.
  @Test
  fun `mixes a music bed without normalising`() {
    val composition =
      EditComposition(
        listOf(
          Track(listOf(Clip(landscape))),
          Track(
            clips = listOf(Clip(music)),
            content = TrackContent.Audio,
            audio = AudioLevel.Volume(0.3f),
            start = 1_000.milliseconds,
          ),
        ),
      )

    val graph = capableGraph(composition, ExportSpec())

    graph shouldContain "amix=inputs=2:duration=longest:dropout_transition=0:normalize=0"
    graph shouldContain "adelay=delays=1000:all=1"
    graph shouldContain "volume=volume=0.3"
  }

  @Test
  fun `refuses text and offers a plan without it`() {
    val composition =
      EditComposition(listOf(Track(listOf(Clip(landscape, effects = listOf(Rotate(90), TextOverlay("hi")))))))

    val verdict = planner().lower(composition, ExportSpec(), device(), infos).verdict
    assertIs<Verdict.Incapable>(verdict)

    val reason = verdict.reasons.single()
    assertIs<ExportError.UnsupportedEffect>(reason)
    reason.specId shouldBe EffectIds.TEXT_OVERLAY

    val fallback = verdict.withoutUnsupported
    fallback?.effectOrder?.map { it.spec.id } shouldBe listOf(EffectIds.ROTATE)
  }

  @Test
  fun `refuses a second video track by name`() {
    val composition =
      EditComposition(listOf(Track(listOf(Clip(landscape))), Track(listOf(Clip(portrait)))))

    val verdict = planner().lower(composition, ExportSpec(), device(), infos).verdict
    assertIs<Verdict.Incapable>(verdict)
    assertIs<ExportError.InvalidComposition>(verdict.reasons.single())
  }

  // This backend has no stills support, and the old refusal named the wrong problem: a still names
  // a real, readable file, so blaming an unreadable source was never the actual reason.
  @Test
  fun `refuses a still by kind`() {
    val still = MediaSource.Image(ImageSource.of("/photos/one.png"), 3_000.milliseconds)
    val composition = EditComposition(listOf(Track(listOf(Clip(still)))))

    val verdict = planner().lower(composition, ExportSpec(), device(), emptyMap()).verdict

    val reason = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.SourceNotExportable>(reason).message shouldBe stillUnsupportedMessage("ffmpeg")
  }

  // The scan is over every track's clips, not just the primary one, so a still parked on a music
  // bed is caught the same way as a still leading the timeline.
  @Test
  fun `refuses a still on a secondary track`() {
    val still = MediaSource.Image(ImageSource.of("/photos/one.png"), 3_000.milliseconds)
    val composition =
      EditComposition(
        listOf(
          Track(listOf(Clip(landscape))),
          Track(clips = listOf(Clip(still)), content = TrackContent.Audio),
        ),
      )

    val verdict = planner().lower(composition, ExportSpec(), device(), infos).verdict

    val reason = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.SourceNotExportable>(reason).message shouldBe stillUnsupportedMessage("ffmpeg")
  }

  @Test
  fun `rounds an odd frame down and reports it`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape)))))

    val verdict = planner().lower(composition, ExportSpec(targetHeight = 721), device(), infos).verdict
    assertIs<Verdict.Degraded>(verdict)
    verdict.plan.output.size shouldBe Size(1282, 720)
    verdict.adjustments.map { it.kind } shouldBe listOf(AdjustmentKind.ResolutionClamped)
  }

  // This backend reports its own parity: Scale is not Exact here, and TextOverlay has no answer.
  @Test
  fun `reports its own parity and not the mobile table`() {
    FfmpegParity.of(EffectIds.SCALE) shouldBe EffectParity.Approximate
    FfmpegParity.of(EffectIds.ROTATE) shouldBe EffectParity.Exact
    FfmpegParity.of(EffectIds.BRIGHTNESS) shouldBe EffectParity.Exact
    FfmpegParity.of(EffectIds.TEXT_OVERLAY) shouldBe null
  }

  // Every colour matrix lowers to a table ffmpeg evaluates exactly, whether it is the per-channel
  // one or the file, so none of them is the approximation Scale is.
  @Test
  fun `reports every colour matrix as exact`() {
    listOf(
      EffectIds.RGB_ADJUSTMENT,
      EffectIds.CONTRAST,
      EffectIds.SATURATION,
      EffectIds.HUE_ROTATE,
      EffectIds.SEPIA,
      EffectIds.INVERT,
      EffectIds.COLOR_MATRIX,
    ).forEach { FfmpegParity.of(it) shouldBe EffectParity.Exact }
  }

  @Test
  fun `carries the weakest parity onto the plan`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape, effects = listOf(Scale(360)))))))

    capablePlan(composition, ExportSpec()).parity shouldBe EffectParity.Approximate
  }

  // Encoder availability gates the codec, so an Hevc request is not echoed back when libx265 is
  // absent and the plan never names a codec the encode would fail to open.
  @Test
  fun `an unavailable codec falls back to h264 and reports it`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape)))))

    val verdict = planner().lower(composition, ExportSpec(videoCodec = VideoCodec.Hevc), device(), infos).verdict

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.videoCodec shouldBe VideoCodec.H264
    degraded.adjustments.single().kind shouldBe AdjustmentKind.CodecFallback
  }

  // Vp9 is not on the ladder Auto walks, so it is only ever reached by asking for it, and only on a
  // build that carries libvpx.
  @Test
  fun `plans VP9 when the build has an encoder for it`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape)))))
    val device = device(listOf(VideoCodec.H264, VideoCodec.Vp9))

    val lowering = planner().lower(composition, ExportSpec(videoCodec = VideoCodec.Vp9), device, infos)

    assertIs<Verdict.Capable>(lowering.verdict).plan.output.videoCodec shouldBe VideoCodec.Vp9
    lowering.invocation!!.videoEncoder shouldBe "libvpx-vp9"
  }

  // A build without libvpx has no Vp9 entry to resolve, so the request falls back the same way an
  // unavailable Hevc does rather than being silently downgraded.
  @Test
  fun `a codec this build cannot encode falls back to h264 and reports it`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape)))))

    val verdict = planner().lower(composition, ExportSpec(videoCodec = VideoCodec.Vp9), device(), infos).verdict

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.videoCodec shouldBe VideoCodec.H264
    degraded.adjustments.single().kind shouldBe AdjustmentKind.CodecFallback
  }

  // Proves this backend's own registered ladder, [H264, Hevc], not a copy of it: a build with
  // only libx265 has to fall through H264 to reach it, so Auto resolving to Hevc here means the
  // real CODEC_LADDER the planner and the capability probe share is still ordered that way.
  @Test
  fun `auto walks this backend's own ladder to the encoder that is actually there`() {
    // A codec this backend cannot stream-copy, so the ladder is what has to answer rather than
    // a copy carrying the source's own codec straight across.
    val source = MediaSource.of("/clips/mpeg2.mp4")
    val composition = EditComposition(listOf(Track(listOf(Clip(source)))))
    val sourceInfos = infos + (source to info(Size(1920, 1080), 4_000.milliseconds, codec = "mpeg2video"))

    val verdict = planner().lower(composition, ExportSpec(), device(listOf(VideoCodec.Hevc)), sourceInfos).verdict

    assertIs<Verdict.Capable>(verdict).plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  @Test
  fun `a build with no video encoder at all is refused`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(landscape)))))

    val verdict = planner().lower(composition, ExportSpec(), device(emptyList()), infos).verdict

    assertIs<ExportError.NoEncoder>(assertIs<Verdict.Incapable>(verdict).reasons.single())
  }

  private fun planner(toolchain: Toolchain = toolchain()) = FfmpegPlanner(toolchain, listOf(BuiltInEffectResolver()))

  private fun device(
    codecs: List<VideoCodec> = listOf(VideoCodec.H264),
    supportsHdrEncoding: Boolean = false,
  ) = DeviceCapabilities(
    video =
      codecs.map { codec ->
        VideoEncoderCapability(
          codec = codec,
          encoderName = codec.ffmpegEncoders().first().name,
          maxSize = Size(7680, 4320),
          maxFrameRate = null,
          maxBitrate = null,
          isHardwareAccelerated = false,
          sizeAlignment = 2,
        )
      },
    audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 8)),
    supportsHdrEncoding = supportsHdrEncoding,
    concurrentSessionBudget = null,
  )

  private fun capablePlan(
    composition: EditComposition,
    spec: ExportSpec,
  ) = when (val verdict = planner().lower(composition, spec, device(), infos).verdict) {
    is Verdict.Capable -> verdict.plan
    is Verdict.Degraded -> verdict.plan
    is Verdict.Incapable -> error("Expected a plan, got ${verdict.reasons.map { it.message }}")
  }

  private fun capableGraph(
    composition: EditComposition,
    spec: ExportSpec,
    toolchain: Toolchain = toolchain(),
  ): String = planner(toolchain).lower(composition, spec, device(), infos).invocation!!.filterGraph

  private fun toolchain(filters: Set<String> = setOf("overlay", "concat", "amix")) =
    Toolchain(
      ffmpeg = "/usr/bin/ffmpeg",
      ffprobe = "/usr/bin/ffprobe",
      version = FfmpegVersion("ffmpeg version 9.0.1", 9, 0),
      filters = filters,
      encoders = setOf("libx264", "libx265", "aac"),
    )

  private fun info(
    size: Size,
    duration: Duration,
    hdr: HdrTransfer? = null,
    codec: String = "avc1",
  ) = MediaInfo(
    duration = duration,
    video =
      VideoTrackInfo(
        codedSize = size,
        displaySize = size,
        rotationDegrees = 0,
        pixelAspectRatio = 1f,
        frameRate = 30f,
        codec = trackCodecOf(codec),
        bitDepth = if (hdr == null) 8 else 10,
        colorSpace = if (hdr == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
        hdrTransfer = hdr,
        bitrate = null,
      ),
    audio = AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null),
    isExportable = true,
  )
}
