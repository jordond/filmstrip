package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.EnvelopePoint
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterCrop
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegParity
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPlanner
import dev.jordond.filmstrip.ffmpeg.internal.lowering.EXPRESSION_SEGMENTS
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.SD_COLOR_FILTER
import dev.jordond.filmstrip.ffmpeg.internal.lowering.SdColor
import dev.jordond.filmstrip.ffmpeg.internal.lowering.TONE_MAP_FILTERS
import dev.jordond.filmstrip.ffmpeg.internal.lowering.arguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.bt709Tags
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegEncoderNamed
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegEncoders
import dev.jordond.filmstrip.ffmpeg.internal.lowering.filterNames
import dev.jordond.filmstrip.ffmpeg.internal.lowering.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.READS_FILES
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
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
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.OrientedPictures
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.NONE_ON_GRID_COPY
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_BT2020_TO_BT709
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.resolve.curveOver
import dev.jordond.filmstrip.transform.internal.resolve.stillUnsupportedMessage
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private val BLUR_FILTERS = setOf("pad", "fillborders", "convolution", "transpose")
private val TONE_MAPPING = CATALOGUE_FILTERS + TONE_MAP_FILTERS
private val SD_FILTERS = setOf("overlay", "concat", "amix", "pad", SD_COLOR_FILTER, "crop", "setparams")

// Enough pinned points that the curve they resolve to outruns what one volume node carries, laid
// close enough together to stay inside the landscape fixture's own length.
private const val MANY_POINTS = 60

// A window inside the landscape source's four seconds, clear of both ends and of the middle, so a
// lowering that dropped one bound cannot land on the same answer by accident.
private val TRIM_START = 1_200.milliseconds
private val TRIM_END = 2_800.milliseconds

// Where a copy of the landscape source could open for that cut, standing in for what the engine's
// own ffprobe reads. Behind the cut rather than on it, so a snap either moves or is refused and
// neither reading passes for the other.
private val SYNC_SAMPLE = 1_000.milliseconds

// Wide enough to reach SYNC_SAMPLE with room to spare, so the test pins the reachable case rather
// than the boundary one.
private val SNAP_WITHIN = 500.milliseconds

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
      music to
        MediaInfo(
          10_000.milliseconds,
          null,
          listOf(
            AudioTrackInfo(AudioTrackId(1), trackCodecOf("mp4a"), 44_100, 2, null, language = null, isDefault = true),
          ),
          true,
        ),
    )

  // What the engine hands the planner once ffprobe has named the sync sample ahead of the cut.
  private val openings = mapOf(landscape to SYNC_SAMPLE)

  @Test
  fun `reframes to the requested aspect and height`() {
    val composition =
      Composition(listOf(Track(listOf(Clip(landscape, effects = listOf(Crop(AspectRatio.Portrait)))))))

    val plan = capablePlan(composition, ExportSpec(heightPx = 720))

    plan.output.size shouldBe Size(404, 720)
    plan.output.frameRate shouldBe 30
    plan.effectOrder.map { it.spec.id } shouldBe listOf(EffectIds.CROP)
  }

  // The planner takes every effect that renders nothing out before this backend lowers anything, so the graph is the
  // one the same clip with no effects at all builds.
  @Test
  fun `is handed no effect that renders nothing`() {
    val inert = listOf(Brightness(1f), HueRotate(360f), Crop(AspectRatio(16, 9)), Rotate(360))
    val composition = Composition(listOf(Track(listOf(Clip(landscape, effects = inert)))))
    val plain = Composition(listOf(Track(listOf(Clip(landscape)))))

    capableGraph(composition, ExportSpec(heightPx = 360)) shouldBe capableGraph(plain, ExportSpec(heightPx = 360))
    capablePlan(composition, ExportSpec(heightPx = 360)).inertNotices.size shouldBe inert.size
  }

  // A clip's effects and its track's fold as one run here, so a track brightness that undoes the clip's leaves the
  // run with nothing to lower.
  @Test
  fun `is handed no brightness its track undoes`() {
    val composition =
      Composition(
        listOf(Track(listOf(Clip(landscape, effects = listOf(Brightness(2f)))), effects = listOf(Brightness(0.5f)))),
      )
    val plain = Composition(listOf(Track(listOf(Clip(landscape)))))

    capableGraph(composition, ExportSpec(heightPx = 360)) shouldBe capableGraph(plain, ExportSpec(heightPx = 360))
    capablePlan(composition, ExportSpec(heightPx = 360)).inertNotices.size shouldBe 2
  }

  // concat refuses inputs that differ in resolution or sample aspect, so every clip carries the
  // size stage before the join.
  @Test
  fun `normalises every clip before the concat`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape), Clip(portrait)))))

    val graph = capableGraph(composition, ExportSpec(heightPx = 360))

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
      Composition(
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
    val mark = ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.TopStart)
    val composition =
      Composition(listOf(Track(listOf(Clip(landscape, effects = listOf(mark)), Clip(portrait)))))

    val invocation = planner().lower(composition, Ask(ExportSpec(), device(), infos)).graph!!

    // A chain's input labels link to its first filter, so the clip's own trim sits on a pad of its
    // own and the merge reads that pad. Written in one chain the graph is refused outright.
    invocation.filterGraph shouldContain "[v0m0pre][v0m0a0]overlay=x=42:y=42:eof_action=pass:format=yuv420"
    // The image claims an input after both clips, so the graph reads it at index 2.
    val height = mark.placedOn(Size(1920, 1080), OrientedPictures.SIZE).size.height
    invocation.filterGraph shouldContain "[2:v]scale=w=384:h=$height,format=pix_fmts=rgba"
    invocation.inputs.size shouldBe 3
  }

  // An image input carries one frame at t = 0, so the branch ends on it and overlay hands every
  // frame after the first on undrawn. The branch is looped over the clip the overlay is drawn on,
  // a frame longer than the clip so a length that does not divide the frame interval cannot land
  // short.
  @Test
  fun `loops the overlay image across the clip it is drawn on`() {
    val mark = ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.TopStart)
    val composition =
      Composition(listOf(Track(listOf(Clip(landscape, effects = listOf(mark)), Clip(portrait)))))

    val invocation = planner().lower(composition, Ask(ExportSpec(), device(), infos)).graph!!

    val image = invocation.inputs[2]
    image.loopFrameRate shouldBe 30f
    image.durationSeconds shouldBe 4.0 + 1.0 / 30f
  }

  // The span the planner hands a clip effect is that clip's slot on the composition, and the branch
  // it lowers onto counts from its own start, so the window has to be moved onto that clock. The
  // second clip here occupies four to seven seconds, and a window over five to six is its own one
  // to two. The gate rides the overlay's branch, so it reaches the graph ahead of the setpts that
  // closes it.
  @Test
  fun `writes a clip's window against the clip's own clock`() {
    val mark =
      ImageOverlay(
        image = ImageSource.ofBytes(OrientedPictures.png()),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.of(5_000.milliseconds, 6_000.milliseconds),
      )
    val composition =
      Composition(listOf(Track(listOf(Clip(landscape), Clip(portrait, effects = listOf(mark))))))

    val build = toolchain(CATALOGUE_FILTERS + "colorchannelmixer")
    val graph = planner(build).lower(composition, Ask(ExportSpec(), device(), infos)).graph!!.filterGraph

    val gate =
      Regex("""colorchannelmixer=aa=0:enable=not\(gte\(t\\,([^)]+)\)\*lt\(t\\,([^)]+)\)\),setpts=expr=PTS-STARTPTS""")
    val (open, shut) = withClue(graph) { gate.find(graph).shouldNotBeNull() }.destructured
    // A bound is compared against a frame's own time on the branch, so the frames it lets through
    // run from the first whose time reaches the one bound to the first whose time reaches the other.
    ceil(open.toDouble() * 30).toLong() shouldBe outputSlotOf(1.seconds, 30)
    ceil(shut.toDouble() * 30).toLong() shouldBe outputSlotOf(2.seconds, 30)
  }

  // A composition overlay merges onto the joined timeline, so its branch has to cover both clips
  // rather than the first one.
  @Test
  fun `loops a composition overlay across the whole composition`() {
    val mark = ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.TopStart)
    val composition =
      Composition(listOf(Track(listOf(Clip(landscape), Clip(portrait)))), effects = listOf(mark))

    val invocation = planner().lower(composition, Ask(ExportSpec(), device(), infos)).graph!!

    invocation.inputs.last().durationSeconds shouldBe 7.0 + 1.0 / 30f
  }

  // Nothing here encodes HDR, so an untouched grade written into an 8-bit file is the washed-out
  // frame the pixels decode to. The curve runs ahead of every effect, and only on the graded clip.
  @Test
  fun `tone-maps only the clip that carries a grade`() {
    val composition = Composition(listOf(Track(listOf(Clip(graded), Clip(landscape)))))

    val graph = capableGraph(composition, ExportSpec(), toolchain(TONE_MAPPING))

    val matrix = TONE_MAP_BT2020_TO_BT709.red
    Regex("geq=").findAll(graph).count() shouldBe 1
    Regex("colorchannelmixer=rr=${matrix.r}:rg=${matrix.g}:rb=${matrix.b}").findAll(graph).count() shouldBe 1
    // The tone-mapped clip's frames, and every frame the graph hands the encoder.
    Regex("setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv").findAll(graph).count() shouldBe 2
    graph shouldNotContain "zscale"
    graph shouldNotContain "tonemap"
  }

  // A copy would already preserve this clip's grade untouched, so the rotation is what forces the
  // tone map this test is actually about.
  @Test
  fun `reports the tone map it performs`() {
    val composition = Composition(listOf(Track(listOf(Clip(graded, effects = listOf(Rotate(90)))))))

    val verdict = planner(toolchain(TONE_MAPPING)).lower(composition, Ask(ExportSpec(), device(), infos)).verdict

    verdict.shouldBeInstanceOf<Verdict.Degraded>().adjustments.map { it.kind } shouldBe
      listOf(AdjustmentKind.HdrToneMapped)
  }

  // Without lut1d, geq and colorchannelmixer there is no curve to run, and claiming the tone map
  // anyway would write the grade straight into an SDR file and call it done. The rotation rules out the copy that
  // would otherwise have preserved the grade with no tone map needed at all.
  @Test
  fun `refuses a graded source on a build that cannot run the tone curve`() {
    val composition = Composition(listOf(Track(listOf(Clip(graded, effects = listOf(Rotate(90)))))))

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), infos)).verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldContain
      "neither encode it nor tone-map it"
  }

  // Every export here writes mp4, and mp4 has no sample entry for VP8, so an otherwise untouched
  // clip that carries one has to be encoded rather than copied. The muxer would refuse a copy that
  // reached it, and the plan would have promised one.
  @Test
  fun `an untouched clip mp4 cannot carry is encoded rather than copied`() {
    val source = MediaSource.of("/clips/untouched-vp8.webm")
    val composition = Composition(listOf(Track(listOf(Clip(source)))))
    val infos = mapOf(source to info(Size(1920, 1080), 6_000.milliseconds, codec = "vp8"))

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), infos)).verdict

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  // The cheapest fix for HDR on a build with no tone curve: an untouched clip needs no filter graph at
  // all, so a copy keeps the grade exactly whatever the build can do.
  @Test
  fun `an untouched graded source copies on a build that cannot tone map`() {
    val composition = Composition(listOf(Track(listOf(Clip(graded)))))

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), infos)).verdict

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Copy
  }

  // A trim used to force a re-encode on every backend. A snapWithin wide enough to reach the sync
  // sample before the cut leaves an otherwise untouched clip on the copy path, and the window has
  // to reach the input's own bounds since a copy runs no graph to carry it.
  @Test
  fun `a reachable snap on an untouched clip copies the window rather than re-encoding it`() {
    val composition = trimmed(snapWithin = SNAP_WITHIN)

    val lowering = planner().lower(composition, Ask(ExportSpec(), device(), infos, openings))

    lowering.verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.path shouldBe ExportPath.Copy
    val clip =
      lowering.export.composition!!
        .tracks
        .single()
        .clips
        .single()
    clip.startsAtKeyFrame shouldBe true
    // The planner already moved the cut back, so the input lays that window and derives nothing of
    // its own.
    clip.start shouldBe SYNC_SAMPLE
    val input = lowering.graph!!.inputs.single()
    input.startSeconds shouldBe clip.start.toDouble(DurationUnit.SECONDS)
    input.durationSeconds shouldBe clip.sourceDuration.toDouble(DurationUnit.SECONDS)
  }

  // Snapping only saves the work it costs accuracy for on a copy. An export with an effect on it
  // decodes the leading group of pictures anyway, so the cut lands where it was asked and the
  // caller is told which term cost them the copy.
  @Test
  fun `a snapped clip with an effect transcodes from the cut it asked for`() {
    val composition =
      Composition(
        listOf(
          Track(
            listOf(
              Clip(
                source = landscape,
                trim = TimeRange.of(TRIM_START, TRIM_END),
                effects = listOf(Rotate(180)),
                snapWithin = SNAP_WITHIN,
              ),
            ),
          ),
        ),
      )

    val lowering = planner().lower(composition, Ask(ExportSpec(), device(), infos, openings))

    val verdict = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.path shouldBe ExportPath.Transcode
    verdict.plan.copyBlockedBy shouldContain CopyBlocker.ClipHasEffects
    val clip =
      lowering.export.composition!!
        .tracks
        .single()
        .clips
        .single()
    clip.startsAtKeyFrame shouldBe false
    clip.start shouldBe TRIM_START
  }

  // Every packet has a time and none lands on the grid, so the count is zero and the copy would write no video.
  @Test
  fun `a copy no packet lands on the grid of is refused by name`() {
    val composition = trimmed(snapWithin = SNAP_WITHIN)
    val pastTheGrid = (0 until 30).map { CopyPacket(TRIM_END + it.seconds / 30, key = it == 0) }

    val lowering =
      planner().lower(composition, Ask(ExportSpec(), device(), infos, openings, copyPackets = pastTheGrid))

    val verdict = lowering.verdict.shouldBeInstanceOf<Verdict.Incapable>()
    verdict.reasons.single() shouldBe FilmstripError.SourceUnreadable(landscape.describe(), NONE_ON_GRID_COPY)
    lowering.graph shouldBe null
  }

  // Zero is the default, and it means the cut lands where it was asked, which no stream copy can
  // promise on a cut that is not already a sync sample. The trim therefore goes on re-encoding and
  // the plan names the term that cost it the copy.
  @Test
  fun `a cut left at the default snap does not move and does not copy`() {
    val composition = trimmed()

    val lowering = planner().lower(composition, Ask(ExportSpec(), device(), infos, openings))

    val verdict = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.path shouldBe ExportPath.Transcode
    verdict.plan.copyBlockedBy shouldContain CopyBlocker.TrimNotOnSyncSample
    lowering.export.composition!!
      .tracks
      .single()
      .clips
      .single()
      .start shouldBe TRIM_START
  }

  // The snap is a bound, not a licence to move as far as the nearest sync sample happens to sit.
  @Test
  fun `a snap too narrow to reach the sync sample leaves the cut where it was asked`() {
    val composition = trimmed(snapWithin = TRIM_START - SYNC_SAMPLE - 1.milliseconds)

    val lowering = planner().lower(composition, Ask(ExportSpec(), device(), infos, openings))

    val verdict = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.path shouldBe ExportPath.Transcode
    verdict.plan.copyBlockedBy shouldContain CopyBlocker.TrimNotOnSyncSample
  }

  // The device measured an HDR-capable encoder, so the grade rides straight through to it instead
  // of being tone-mapped, and the codec ladder pins to the one profile that can carry it. A copy
  // would preserve HDR without ever touching this code, so the source carries a codec this backend
  // cannot copy and the plan is forced to actually encode.
  @Test
  fun `keeps hdr and writes the encoder's own pixel format when the device can encode it`() {
    val source = MediaSource.of("/clips/graded-mpeg2.mov")
    val composition = Composition(listOf(Track(listOf(Clip(source)))))
    val device = device(listOf(VideoCodec.H264, VideoCodec.Hevc), supportsHdrEncoding = true)
    val sourceInfos =
      infos + (source to info(Size(1920, 1080), 4_000.milliseconds, hdr = HdrTransfer.Pq, codec = "mpeg2video"))

    val lowering = planner().lower(composition, Ask(ExportSpec(hdr = HdrMode.KeepHdr), device, sourceInfos))

    val plan = lowering.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.output.videoCodec shouldBe VideoCodec.Hevc

    val invocation = lowering.graph!!
    invocation.hdrTransfer shouldBe HdrTransfer.Pq
    val encoder = checkNotNull(ffmpegEncoderNamed(invocation.videoEncoder!!))
    invocation.filterGraph shouldContain "format=pix_fmts=${encoder.hdrPixelFormat}"
  }

  // An HLG source is not PQ, and writing it as PQ would tag the file with a curve the pixels were
  // never graded to.
  @Test
  fun `carries HLG rather than assuming PQ`() {
    val hlg = MediaSource.of("/clips/hlg.mov")
    val composition = Composition(listOf(Track(listOf(Clip(hlg)))))
    val device = device(listOf(VideoCodec.H264, VideoCodec.Hevc), supportsHdrEncoding = true)
    val hlgInfos = infos + (hlg to info(Size(1920, 1080), 4_000.milliseconds, hdr = HdrTransfer.Hlg))

    val lowering = planner().lower(composition, Ask(ExportSpec(hdr = HdrMode.KeepHdr), device, hlgInfos))

    lowering.graph!!.hdrTransfer shouldBe HdrTransfer.Hlg
  }

  // Without convolution there is nothing to lower a blurred fill onto, and emitting the graph anyway
  // would hand ffmpeg a filter it does not have.
  @Test
  fun `refuses a blurred fill on a build with no convolution`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape), Clip(portrait)))), fill = Fill.Blurred())

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), infos)).verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldContain "convolution"
  }

  // Each of these is a filter Vhs lowers, read one at a time so a name missing from the check fails on its own.
  @Test
  fun `refuses a film by name on a build missing any filter it lowers`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))), listOf(Film()))
    val named = FILM_FILTERS + TONE_MAPPING
    val spec = ExportSpec(heightPx = 720)
    val resolvers = listOf(BuiltInEffectResolver(), FilterEffectResolver())

    FfmpegPlanner(toolchain(named), resolvers).lower(composition, Ask(spec, device(), infos)).graph.shouldNotBeNull()

    FILM_FILTERS.forEach { filter ->
      val verdict =
        FfmpegPlanner(toolchain(named - filter), resolvers).lower(composition, Ask(spec, device(), infos)).verdict
      withClue(filter) {
        val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
        val message = reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message
        message shouldContain filter
        message shouldContain Film.ID
      }
    }
  }

  @Test
  fun `refuses a crt by name on a build missing any filter it lowers`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))), listOf(Crt()))
    val named = CRT_FILTERS + TONE_MAPPING
    val spec = ExportSpec(heightPx = 720)
    val resolvers = listOf(BuiltInEffectResolver(), FilterEffectResolver())

    FfmpegPlanner(toolchain(named), resolvers).lower(composition, Ask(spec, device(), infos)).graph.shouldNotBeNull()

    CRT_FILTERS.forEach { filter ->
      val verdict =
        FfmpegPlanner(toolchain(named - filter), resolvers).lower(composition, Ask(spec, device(), infos)).verdict
      withClue(filter) {
        val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
        val message = reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message
        message shouldContain filter
        message shouldContain Crt.ID
      }
    }
  }

  @Test
  fun refusesALutByNameOnABuildMissingAnyFilterItLowers() {
    val table = CubeLut.parse("LUT_3D_SIZE 2\n" + "0.5 0.25 0.75\n".repeat(8))
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))), listOf(Lut(table)))
    val named = LUT_FILTERS + TONE_MAPPING
    val spec = ExportSpec(heightPx = 720)
    val resolvers = listOf(BuiltInEffectResolver(), FilterEffectResolver())

    FfmpegPlanner(toolchain(named), resolvers).lower(composition, Ask(spec, device(), infos)).graph.shouldNotBeNull()

    LUT_FILTERS.forEach { filter ->
      val verdict =
        FfmpegPlanner(toolchain(named - filter), resolvers).lower(composition, Ask(spec, device(), infos)).verdict
      withClue(filter) {
        val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
        val message = reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message
        message shouldContain filter
        message shouldContain Lut.ID
      }
    }
  }

  @Test
  fun `refuses a night vision by name on a build missing any filter it lowers`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))), listOf(NightVision()))
    val named = NIGHT_VISION_FILTERS + TONE_MAPPING
    val spec = ExportSpec(heightPx = 720)
    val resolvers = listOf(BuiltInEffectResolver(), FilterEffectResolver())

    FfmpegPlanner(toolchain(named), resolvers).lower(composition, Ask(spec, device(), infos)).graph.shouldNotBeNull()

    NIGHT_VISION_FILTERS.forEach { filter ->
      val verdict =
        FfmpegPlanner(toolchain(named - filter), resolvers).lower(composition, Ask(spec, device(), infos)).verdict
      withClue(filter) {
        val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
        val message = reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message
        message shouldContain filter
        message shouldContain NightVision.ID
      }
    }
  }

  @Test
  fun `refuses a vhs by name on a build missing any filter it lowers`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))), listOf(Vhs()))
    val named = VHS_FILTERS + TONE_MAPPING
    val spec = ExportSpec(heightPx = 720)
    val resolvers = listOf(BuiltInEffectResolver(), FilterEffectResolver())

    FfmpegPlanner(toolchain(named), resolvers).lower(composition, Ask(spec, device(), infos)).graph.shouldNotBeNull()

    VHS_FILTERS.forEach { filter ->
      val verdict =
        FfmpegPlanner(toolchain(named - filter), resolvers).lower(composition, Ask(spec, device(), infos)).verdict
      withClue(filter) {
        val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
        val message = reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message
        message shouldContain filter
        message shouldContain Vhs.ID
      }
    }
  }

  @Test
  fun `refuses a glitch by name on a build missing any filter it lowers`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))), listOf(Glitch()))
    val named = GLITCH_FILTERS + TONE_MAPPING
    val spec = ExportSpec(heightPx = 720)
    val resolvers = listOf(BuiltInEffectResolver(), FilterEffectResolver())

    FfmpegPlanner(toolchain(named), resolvers).lower(composition, Ask(spec, device(), infos)).graph.shouldNotBeNull()

    GLITCH_FILTERS.forEach { filter ->
      val verdict =
        FfmpegPlanner(toolchain(named - filter), resolvers).lower(composition, Ask(spec, device(), infos)).verdict
      withClue(filter) {
        val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
        val message = reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message
        message shouldContain filter
        message shouldContain Glitch.ID
      }
    }
  }

  @Test
  fun `names every filter a fragment leaves to the backend`() {
    val branched =
      FilterFragment(
        readsCompositionClock = true,
        branches = listOf(FilterBranch(listOf(FilterBranch.INPUT), emptyList(), listOf("a", "b"))),
      )
    val cropped = FilterFragment(crop = FilterCrop("1", "1"))
    val lanes = FilterFragment(chain = listOf(FilterNode(PlaneLanes.NAME)))
    val clocked = FilterFragment(chain = listOf(FilterNode("geq")), readsCompositionClock = true)

    branched.filterNames() shouldBe setOf("split", "setpts")
    clocked.filterNames() shouldBe setOf("geq", "setpts")
    cropped.filterNames() shouldBe setOf("split", "crop", "overlay")
    lanes.filterNames() shouldBe setOf("format", "extractplanes", "mergeplanes")
  }

  // heightPx forces a re-encode rather than the stream copy an untouched clip would take,
  // since a copy never reaches the filter graph at all.
  @Test
  fun `lowers a blurred fill once the build has convolution`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape), Clip(portrait)))), fill = Fill.Blurred())

    val graph = capableGraph(composition, ExportSpec(heightPx = 720), toolchain(TONE_MAPPING + BLUR_FILTERS))

    graph shouldContain "convolution="
  }

  // colorchannelmixer is only reached once dim actually darkens something, so a build that has
  // the blur filters but not it is refused only for the fill that would need it, not every blurred one.
  @Test
  fun `refuses a dimmed blurred fill on a build with no colorchannelmixer`() {
    val composition =
      Composition(listOf(Track(listOf(Clip(landscape), Clip(portrait)))), fill = Fill.Blurred(dim = 0.5f))

    val verdict =
      planner(toolchain(CATALOGUE_FILTERS + BLUR_FILTERS))
        .lower(composition, Ask(ExportSpec(), device(), infos))
        .verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldContain "colorchannelmixer"
  }

  @Test
  fun `refuses a clip on SD primaries on a build missing any filter it lowers to`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))))
    val color = SdColor(SMPTE_C_PRIMARIES, "smpte170m")

    color.filterNames() shouldBe setOf("pad", SD_COLOR_FILTER, "crop", "setparams")
    color.filterNames().forEach { filter ->
      val verdict =
        planner(toolchain(SD_FILTERS - filter))
          .lower(composition, Ask(ExportSpec(), device(), infos), sdColors = mapOf(landscape to color))
          .verdict

      val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
      reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldContain "no $filter filter"
    }
  }

  @Test
  fun `one SD colour keeps its tags while anything beside it writes BT709`() {
    fun lowered(
      vararg clips: Clip,
      sdColors: Map<MediaSource, SdColor>,
    ): Invocation =
      checkNotNull(
        planner(toolchain(SD_FILTERS))
          .lower(
            Composition(listOf(Track(clips.toList()))),
            Ask(ExportSpec(), device(), infos),
            sdColors = sdColors,
          ).graph,
      )
    val sd = mapOf<MediaSource, SdColor>(landscape to SdColor(SMPTE_C_PRIMARIES, "smpte170m"))

    val kept = lowered(Clip(landscape), Clip(landscape), sdColors = sd)
    kept.filterGraph shouldNotContain bt709Tags().render()
    kept.previewColor.single().name shouldBe SD_COLOR_FILTER
    kept.filterGraph shouldNotContain SD_COLOR_FILTER

    val beside = lowered(Clip(landscape), Clip(portrait), sdColors = sd)
    beside.filterGraph shouldContain bt709Tags().render()
    beside.previewColor shouldBe emptyList()
    beside.filterGraph shouldContain "colorspace=ispace=smpte170m:iprimaries=smpte170m"
    Regex("colorspace=ispace").findAll(beside.filterGraph).count() shouldBe 1
  }

  @Test
  fun `one SD colour writes BT709 only under an effect that draws`() {
    val sd = mapOf<MediaSource, SdColor>(landscape to SdColor(SMPTE_C_PRIMARIES, "smpte170m"))

    fun lowered(composition: Composition): Invocation =
      checkNotNull(
        planner(toolchain(CATALOGUE_FILTERS + SD_FILTERS))
          .lower(composition, Ask(ExportSpec(), device(), infos), sdColors = sd)
          .graph,
      )

    fun onClip(vararg effects: EffectSpec) =
      Composition(listOf(Track(listOf(Clip(landscape, effects = effects.toList())))))

    listOf(
      onClip(Brightness(1f)),
      onClip(Brightness(2f), Brightness(0.5f)),
      onClip(Invert(), Invert()),
      onClip(Flip(FlipAxis.Horizontal)),
      onClip(Rotate(90)),
      onClip(Scale(360)),
    ).forEach { composition ->
      val kept = lowered(composition)
      kept.filterGraph shouldNotContain bt709Tags().render()
      kept.filterGraph shouldNotContain SD_COLOR_FILTER
    }

    listOf(
      onClip(Invert()),
      onClip(Brightness(1.5f)),
      onClip(TextOverlay("hi")),
      onClip(Flip(FlipAxis.Horizontal), Invert()),
      Composition(listOf(Track(listOf(Clip(landscape)), effects = listOf(Invert())))),
      Composition(listOf(Track(listOf(Clip(landscape)))), effects = listOf(Invert())),
    ).forEach { composition ->
      val moved = lowered(composition)
      moved.filterGraph shouldContain bt709Tags().render()
      moved.previewColor shouldBe emptyList()
      moved.filterGraph shouldContain "colorspace=ispace=smpte170m:iprimaries=smpte170m"
    }
  }

  // Crop leaves no bars for a blurred fill to show through, so the missing filter never matters
  // and the build is not refused for a capability it never needed.
  @Test
  fun `does not refuse a blurred fill that fit never shows`() {
    val composition =
      Composition(
        listOf(Track(listOf(Clip(landscape, effects = listOf(Scale(360, fit = Fit.Crop)))))),
        fill = Fill.Blurred(),
      )

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), infos)).verdict

    verdict.shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun `trims to the requested window`() {
    val composition =
      Composition(
        listOf(Track(listOf(Clip(landscape, trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds))))),
      )

    capableGraph(composition, ExportSpec()) shouldContain "trim=start=1.000000:end=3.000000"
  }

  // normalize defaults to true and divides by the input count, so adding a bed would silently
  // halve the dialogue.
  @Test
  fun `mixes a music bed without normalising`() {
    val composition =
      Composition(
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
    // A gain that holds one number needs neither a per-frame read nor the frames a ramp is cut to.
    graph shouldNotContain "asetnsamples"
    graph shouldNotContain "eval=frame"
  }

  // The curve the planner folded is what the expression has to spell, so the breakpoint and the
  // gains either side of it are read off it rather than written out again.
  @Test
  fun `a clip fade lowers to a volume expression over the curve it resolved`() {
    val fade = 1_000.milliseconds
    val level = AudioLevel.Envelope(listOf(EnvelopePoint(Duration.ZERO, 0f), EnvelopePoint(fade, 1f)))
    val composition = Composition(listOf(Track(listOf(Clip(landscape, audio = level)))))

    val graph = capableGraph(composition, ExportSpec())
    val clipLength = infos.getValue(landscape).duration
    val curve = level.curveOver(clipLength)
    val ramp = curve.segments.first()

    graph shouldContain "eval=frame"
    graph shouldContain "if(lt(t\\,${formatSeconds(ramp.end.toDouble(DurationUnit.SECONDS))})"
    graph shouldContain "${ramp.startGain}+(${ramp.endGain}-${ramp.startGain})*clip("
    // The gain the curve holds once the fade is over, which is the chain's innermost branch.
    graph shouldContain "\\,${curve.gainAt(clipLength)})"
  }

  // ffmpeg's expression parser refuses a nest much deeper than a hundred ifs, and a curve folded
  // from an envelope runs past that, so a long one is chained across several nodes that multiply
  // back to it. How many they split into is read off the curve rather than counted out here.
  @Test
  fun `a curve too long for one volume node is chained across several`() {
    val level =
      AudioLevel.Envelope(
        (0..MANY_POINTS).map { EnvelopePoint((it * 50).milliseconds, it.toFloat() / MANY_POINTS) },
      )
    val composition = Composition(listOf(Track(listOf(Clip(landscape, audio = level)))))

    val graph = capableGraph(composition, ExportSpec())
    val segments = level.curveOver(infos.getValue(landscape).duration).segments.size

    Regex("volume=volume=").findAll(graph).count() shouldBe
      (segments + EXPRESSION_SEGMENTS - 1) / EXPRESSION_SEGMENTS
    Regex(":eval=frame").findAll(graph).count() shouldBe
      (segments + EXPRESSION_SEGMENTS - 1) / EXPRESSION_SEGMENTS
    // One asetnsamples for the branch, not one for each node the curve was split across.
    Regex("asetnsamples").findAll(graph).count() shouldBe 1
  }

  // volume reads its expression once per frame, so the frame size is ffmpeg's own bound on how
  // finely a ramp steps. The literal pins that bound, not anything the shared curve says.
  @Test
  fun `a ramping gain cuts the frames volume steps on`() {
    val level = AudioLevel.Envelope(listOf(EnvelopePoint(Duration.ZERO, 0f), EnvelopePoint(1_000.milliseconds, 1f)))
    val composition = Composition(listOf(Track(listOf(Clip(landscape, audio = level)))))

    capableGraph(composition, ExportSpec()) shouldContain "asetnsamples=n=64:p=0"
  }

  @Test
  fun `plans text beside the clip's other effects`() {
    val composition =
      Composition(listOf(Track(listOf(Clip(landscape, effects = listOf(Rotate(90), TextOverlay("hi")))))))

    val lowered = planner().lower(composition, Ask(ExportSpec(), device(), infos))
    val verdict = lowered.verdict.shouldBeInstanceOf<Verdict.Capable>()

    verdict.plan.effectOrder.map { it.spec.id } shouldBe listOf(EffectIds.ROTATE, EffectIds.TEXT_OVERLAY)
    lowered.graph.shouldNotBeNull().filterGraph shouldContain "overlay="
  }

  @Test
  fun `refuses a second video track by name`() {
    val composition =
      Composition(listOf(Track(listOf(Clip(landscape))), Track(listOf(Clip(portrait)))))

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), infos)).verdict
    verdict.shouldBeInstanceOf<Verdict.Incapable>()
    verdict.reasons.single().shouldBeInstanceOf<FilmstripError.InvalidComposition>()
  }

  // This backend has no stills support, and the old refusal named the wrong problem: a still names
  // a real, readable file, so blaming an unreadable source was never the actual reason.
  @Test
  fun `refuses a still by kind`() {
    val still = MediaSource.Image(ImageSource.of("/photos/one.png"), 3_000.milliseconds)
    val composition = Composition(listOf(Track(listOf(Clip(still)))))

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), emptyMap())).verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.SourceNotExportable>().message shouldBe stillUnsupportedMessage("ffmpeg")
  }

  // The scan is over every track's clips, not just the primary one, so a still parked on a music
  // bed is caught the same way as a still leading the timeline.
  @Test
  fun `refuses a still on a secondary track`() {
    val still = MediaSource.Image(ImageSource.of("/photos/one.png"), 3_000.milliseconds)
    val composition =
      Composition(
        listOf(
          Track(listOf(Clip(landscape))),
          Track(clips = listOf(Clip(still)), content = TrackContent.Audio),
        ),
      )

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), infos)).verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.SourceNotExportable>().message shouldBe stillUnsupportedMessage("ffmpeg")
  }

  // How a file: URL reads is filePathOf's answer, pinned in filmstrip-core. What this pins is that
  // the source arm asks it rather than stripping the scheme itself.
  @Test
  fun `reads a file uri as the path it names`() {
    readablePath(MediaSource.ofUri("file:///clips/my%20holiday.mp4")) shouldBe "/clips/my holiday.mp4"
  }

  @Test
  fun `leaves a path alone`() {
    readablePath(MediaSource.of("/clips/my holiday.mp4")) shouldBe "/clips/my holiday.mp4"
    readablePath(MediaSource.ofUri("/clips/my holiday.mp4")) shouldBe "/clips/my holiday.mp4"
  }

  @Test
  fun `refuses a uri this backend cannot open`() {
    readablePath(MediaSource.ofUri("http://example.com/clips/one.mp4")) shouldBe null
    readablePath(MediaSource.ofUri("content://media/external/video/1")) shouldBe null
  }

  // file://host/one.mp4 names a file on another machine, and stripping the scheme off it would hand
  // ffmpeg "host/one.mp4", a relative path in whatever directory the process happens to be in.
  @Test
  fun `refuses a file uri naming another host`() {
    readablePath(MediaSource.ofUri("file://host/clips/one.mp4")) shouldBe null
  }

  @Test
  fun `opens a clip named by a file uri at the decoded path`() {
    val source = MediaSource.ofUri("file:///clips/my%20holiday.mp4")
    val composition = Composition(listOf(Track(listOf(Clip(source)))))
    val infos = mapOf(source to info(Size(1920, 1080), 4_000.milliseconds))

    val invocation = planner().lower(composition, Ask(ExportSpec(), device(), infos)).graph!!

    invocation.inputs
      .first()
      .source
      .shouldBeInstanceOf<InputSource.OfPath>()
      .path shouldBe "/clips/my holiday.mp4"
  }

  @Test
  fun `refuses a clip named by a uri this backend cannot open`() {
    val source = MediaSource.ofUri("content://media/external/video/1")
    val composition = Composition(listOf(Track(listOf(Clip(source)))))

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(), emptyMap())).verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldBe READS_FILES
  }

  @Test
  fun `rounds an odd frame down and reports it`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))))

    val verdict = planner().lower(composition, Ask(ExportSpec(heightPx = 721), device(), infos)).verdict
    verdict.shouldBeInstanceOf<Verdict.Degraded>()
    verdict.plan.output.size shouldBe Size(1282, 720)
    verdict.adjustments.map { it.kind } shouldBe listOf(AdjustmentKind.ResolutionClamped)
  }

  // This backend reports its own parity: Scale, TextOverlay, GaussianBlur and Vignette are not Exact here.
  @Test
  fun `reports its own parity and not the mobile table`() {
    FfmpegParity.of(EffectIds.SCALE) shouldBe EffectParity.Approximate
    FfmpegParity.of(EffectIds.ROTATE) shouldBe EffectParity.Exact
    FfmpegParity.of(EffectIds.BRIGHTNESS) shouldBe EffectParity.Exact
    FfmpegParity.of(EffectIds.TEXT_OVERLAY) shouldBe EffectParity.Approximate
    FfmpegParity.of(EffectIds.GAUSSIAN_BLUR) shouldBe EffectParity.Approximate
    FfmpegParity.of(EffectIds.VIGNETTE) shouldBe EffectParity.Approximate
    FfmpegParity.noteFor(EffectIds.GAUSSIAN_BLUR).shouldNotBeNull()
    FfmpegParity.noteFor(EffectIds.VIGNETTE).shouldNotBeNull()
  }

  // Every color matrix lowers to a table ffmpeg evaluates exactly, whether it is the per-channel
  // one or the file, so none of them is the approximation Scale is.
  @Test
  fun `reports every color matrix as exact`() {
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
    val composition = Composition(listOf(Track(listOf(Clip(landscape, effects = listOf(Scale(360)))))))

    capablePlan(composition, ExportSpec()).parity shouldBe EffectParity.Approximate
  }

  // Rotate is exact here and Scale is not, so a plan that took the strongest parity would read exact.
  @Test
  fun `an exact effect beside an approximate one plans approximate`() {
    val effects = listOf(Rotate(90), Scale(360))
    val composition = Composition(listOf(Track(listOf(Clip(landscape, effects = effects)))))

    capablePlan(composition, ExportSpec()).parity shouldBe EffectParity.Approximate
  }

  // Encoder availability gates the codec, so an Hevc request is not echoed back when libx265 is
  // absent and the plan never names a codec the encode would fail to open.
  @Test
  fun `an unavailable codec falls back to h264 and reports it`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))))

    val verdict = planner().lower(composition, Ask(ExportSpec(videoCodec = VideoCodec.Hevc), device(), infos)).verdict

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    degraded.plan.output.videoCodec shouldBe VideoCodec.H264
    degraded.adjustments.single().kind shouldBe AdjustmentKind.CodecFallback
  }

  // Vp9 is not on the ladder Auto walks, so it is only ever reached by asking for it, and only on a
  // build that carries libvpx.
  @Test
  fun `plans VP9 when the build has an encoder for it`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))))
    val device = device(listOf(VideoCodec.H264, VideoCodec.Vp9))

    val lowering = planner().lower(composition, Ask(ExportSpec(videoCodec = VideoCodec.Vp9), device, infos))

    lowering.verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.videoCodec shouldBe VideoCodec.Vp9
    lowering.graph!!.videoEncoder shouldBe "libvpx-vp9"
  }

  // A build without libvpx has no Vp9 entry to resolve, so the request falls back the same way an
  // unavailable Hevc does rather than being silently downgraded.
  @Test
  fun `a codec this build cannot encode falls back to h264 and reports it`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))))

    val verdict = planner().lower(composition, Ask(ExportSpec(videoCodec = VideoCodec.Vp9), device(), infos)).verdict

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
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
    val composition = Composition(listOf(Track(listOf(Clip(source)))))
    val sourceInfos = infos + (source to info(Size(1920, 1080), 4_000.milliseconds, codec = "mpeg2video"))

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(listOf(VideoCodec.Hevc)), sourceInfos)).verdict

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  @Test
  fun `a build with no video encoder at all is refused`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))))

    val verdict = planner().lower(composition, Ask(ExportSpec(), device(emptyList()), infos)).verdict

    verdict
      .shouldBeInstanceOf<Verdict.Incapable>()
      .reasons
      .single()
      .shouldBeInstanceOf<FilmstripError.NoEncoder>()
  }

  // The rate reaching the command line is the one the planner settled on, which is the shared bitrate for the default
  // tier at the size and rate the export writes.
  @Test
  fun `encodes at the bitrate the planner settled on`() {
    val composition = Composition(listOf(Track(listOf(Clip(landscape)))))
    val lowered = planner().lower(composition, Ask(ExportSpec(heightPx = 720), device(), infos))
    val plan = lowered.verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    val arguments =
      lowered.graph
        .shouldNotBeNull()
        .arguments(toolchain(), FfmpegConfig(), listOf("/clips/landscape.mp4"), emptyList(), "/out.mp4")

    plan.output.bitrate shouldBe resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, Size(1280, 720), 30)
    arguments[arguments.indexOf("-b:v") + 1] shouldBe
      plan.output.bitrate
        ?.bitsPerSecond
        .toString()
  }

  private fun planner(toolchain: Toolchain = toolchain()) = FfmpegPlanner(toolchain, listOf(BuiltInEffectResolver()))

  private fun trimmed(snapWithin: Duration = Duration.ZERO): Composition =
    Composition(
      listOf(Track(listOf(Clip(landscape, trim = TimeRange.of(TRIM_START, TRIM_END), snapWithin = snapWithin)))),
    )

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
    composition: Composition,
    spec: ExportSpec,
  ) = when (val verdict = planner().lower(composition, Ask(spec, device(), infos)).verdict) {
    is Verdict.Capable -> verdict.plan
    is Verdict.Degraded -> verdict.plan
    is Verdict.Incapable -> error("Expected a plan, got ${verdict.reasons.map { it.message }}")
  }

  private fun capableGraph(
    composition: Composition,
    spec: ExportSpec,
    toolchain: Toolchain = toolchain(),
  ): String = planner(toolchain).lower(composition, Ask(spec, device(), infos)).graph!!.filterGraph

  private fun toolchain(filters: Set<String> = CATALOGUE_FILTERS) =
    Toolchain(
      ffmpeg = "ffmpeg",
      ffprobe = "ffprobe",
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
        hasAlpha = false,
      ),
    audioTracks =
      listOf(
        AudioTrackInfo(AudioTrackId(1), trackCodecOf("mp4a"), 48_000, 2, null, language = null, isDefault = true),
      ),
    isExportable = true,
  )
}
