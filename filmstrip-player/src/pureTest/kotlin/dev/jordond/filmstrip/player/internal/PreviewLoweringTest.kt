package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.still.StillFormat
import dev.jordond.filmstrip.transform.internal.ExportGrade
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PreviewLoweringTest {
  @Test
  fun `a height inside the natural frame caps it`() {
    cappedHeight(MID_HEIGHT, NATURAL.height) shouldBe MID_HEIGHT
    cappedHeight(1, NATURAL.height) shouldBe 1
    cappedHeight(NATURAL.height - 1, NATURAL.height) shouldBe NATURAL.height - 1
  }

  @Test
  fun `zero or null or anything at or above the natural height leaves the frame alone`() {
    for (height in listOf(null, 0, NATURAL.height, NATURAL.height * 2)) {
      cappedHeight(height, NATURAL.height).shouldBeNull()
    }
  }

  @Test
  fun `a policy caps by the same rule a request does`() {
    PreviewQualityPolicy.CapHeight(MID_HEIGHT).cappedHeight(NATURAL.height) shouldBe
      cappedHeight(MID_HEIGHT, NATURAL.height)
    PreviewQualityPolicy.CapHeight(NATURAL.height).cappedHeight(NATURAL.height).shouldBeNull()
    PreviewQualityPolicy.Full.cappedHeight(NATURAL.height).shouldBeNull()
  }

  @Test
  fun `a cap below one pixel is refused when it is built`() {
    assertFailsWith<IllegalArgumentException> { PreviewQualityPolicy.CapHeight(0) }
  }

  @Test
  fun `an uncapped height lowers once at the natural frame`() =
    runTest {
      val resolver = RecordingResolve()

      val lowering =
        lowerAtHeight(
          EDIT,
          0,
          ExportSpec(),
          resolver.grade,
          resolver.resolve,
        ).shouldBeInstanceOf<PlanResult.Ready<HeightLowering>>().plan

      resolver.calls shouldBe listOf(ExportSpec() to null)
      lowering.spec shouldBe ExportSpec()
      lowering.layoutSize.shouldBeNull()
      lowering.naturalSize shouldBe NATURAL
      lowering.resolved.output.size shouldBe NATURAL
    }

  @Test
  fun `a capped height lowers again against the natural frame as its layout`() =
    runTest {
      val resolver = RecordingResolve()

      val lowering =
        lowerAtHeight(EDIT, MID_HEIGHT, ExportSpec(), resolver.grade, resolver.resolve)
          .shouldBeInstanceOf<PlanResult.Ready<HeightLowering>>()
          .plan

      val capped = ExportSpec(heightPx = MID_HEIGHT)
      resolver.calls shouldBe listOf(ExportSpec() to null, capped to NATURAL)
      lowering.spec shouldBe capped
      lowering.layoutSize shouldBe NATURAL
      lowering.naturalSize shouldBe NATURAL
      lowering.resolved.output.size.height shouldBe MID_HEIGHT
    }

  @Test
  fun `every lowering takes the grade of the export and a frame spec without its settings`() =
    runTest {
      val resolver = RecordingResolve(answer = ExportGrade.Settled(ResolvedHdr.ToneMap))
      val export = ExportSpec(hdr = HdrMode.ToneMapToSdr, bitrate = Bitrate.mbps(8))

      lowerAtHeight(EDIT, MID_HEIGHT, export, resolver.grade, resolver.resolve)
        .shouldBeInstanceOf<PlanResult.Ready<HeightLowering>>()

      resolver.graded shouldBe listOf(export)
      resolver.grades shouldBe listOf(ResolvedHdr.ToneMap, ResolvedHdr.ToneMap)
      resolver.calls shouldBe listOf(ExportSpec() to null, ExportSpec(heightPx = MID_HEIGHT) to NATURAL)
    }

  @Test
  fun `a refused grade lowers nothing and reads as an unsupported format with the planner's message`() =
    runTest {
      val resolver = RecordingResolve(answer = ExportGrade.Refused(FilmstripError.InvalidComposition(MESSAGE)))

      planPreview(EDIT, PreviewQualityPolicy.Full, ExportSpec(), emptyList(), resolver.grade, resolver.resolve)
        .shouldBeInstanceOf<PlanResult.Refused<PlaybackError>>()
        .error shouldBe PlaybackError.UnsupportedFormat(MESSAGE)

      resolver.calls.shouldBeEmpty()
    }

  @Test
  fun `a refusal at either step is the lowering's answer`() =
    runTest {
      val refusal = FilmstripError.SourceUnreadable("a.mp4", "unreadable")

      RecordingResolve(refuseAt = 0)
        .let { lowerAtHeight(EDIT, MID_HEIGHT, ExportSpec(), it.grade, it.resolve) }
        .shouldBeInstanceOf<PlanResult.Refused<FilmstripError>>()
        .error shouldBe refusal
      RecordingResolve(refuseAt = 1)
        .let { lowerAtHeight(EDIT, MID_HEIGHT, ExportSpec(), it.grade, it.resolve) }
        .shouldBeInstanceOf<PlanResult.Refused<FilmstripError>>()
        .error shouldBe refusal
    }

  @Test
  fun `a capped preview reports the share of the natural frame it renders`() =
    runTest {
      val plan =
        RecordingResolve()
          .let {
            planPreview(
              EDIT,
              PreviewQualityPolicy.CapHeight(MID_HEIGHT),
              ExportSpec(),
              emptyList(),
              it.grade,
              it.resolve,
            )
          }.shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
          .plan

      plan.info.outputSize shouldBe NATURAL
      plan.info.renderScale shouldBe plan.resolved.output.size.height
        .toFloat() / NATURAL.height
      plan.info.renderScale shouldBe MID_HEIGHT.toFloat() / NATURAL.height
      plan.spec shouldBe ExportSpec(heightPx = MID_HEIGHT)
      plan.layoutSize shouldBe NATURAL
    }

  @Test
  fun `a full preview renders at scale one`() =
    runTest {
      val plan =
        RecordingResolve()
          .let { planPreview(EDIT, PreviewQualityPolicy.Full, ExportSpec(), emptyList(), it.grade, it.resolve) }
          .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
          .plan

      plan.info.renderScale shouldBe 1f
      plan.layoutSize.shouldBeNull()
    }

  @Test
  fun `only a kept grade leaves the display to decide`() =
    runTest {
      val fidelity = listOf(NO_ENCODER_PREVIEW, DISPLAY_DECIDES_HDR)
      val kept = RecordingResolve()
      val mapped = RecordingResolve(resolvedHdr = ResolvedHdr.ToneMap)

      planPreview(EDIT, PreviewQualityPolicy.Full, ExportSpec(), fidelity, kept.grade, kept.resolve)
        .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
        .plan.info.fidelity shouldBe fidelity
      planPreview(EDIT, PreviewQualityPolicy.Full, ExportSpec(), fidelity, mapped.grade, mapped.resolve)
        .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
        .plan.info.fidelity shouldBe listOf(NO_ENCODER_PREVIEW)
    }

  @Test
  fun `a refused preview reads the refusal as a playback error`() =
    runTest {
      RecordingResolve(refuseAt = 0)
        .let { planPreview(EDIT, PreviewQualityPolicy.Full, ExportSpec(), emptyList(), it.grade, it.resolve) }
        .shouldBeInstanceOf<PlanResult.Refused<PlaybackError>>()
        .error shouldBe PlaybackError.SourceUnreadable("unreadable")
    }

  @Test
  fun `every export error has a playback meaning`() {
    val unclassified = PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, MESSAGE)
    val named =
      mapOf(
        FilmstripError.SourceUnreadable("a.mp4", MESSAGE) to PlaybackError.SourceUnreadable(MESSAGE),
        FilmstripError.SourceNotExportable(MESSAGE) to PlaybackError.SourceNotExportable(MESSAGE),
        FilmstripError.BackendMissing("artifact", MESSAGE) to PlaybackError.BackendMissing("artifact", MESSAGE),
        FilmstripError.ToolchainMissing("ffmpeg", null, MESSAGE) to PlaybackError.DecoderUnavailable(MESSAGE),
        FilmstripError.Underlying(PLATFORM_CODE, MESSAGE) to PlaybackError.Underlying(PLATFORM_CODE, MESSAGE),
        FilmstripError.InterruptedByBackgrounding(MESSAGE) to unclassified,
        FilmstripError.InsufficientStorage(null, MESSAGE) to unclassified,
        FilmstripError.SinkUnwritable("out.mp4", MESSAGE) to unclassified,
        FilmstripError.TimedOut("source probe", 30.seconds, MESSAGE) to unclassified,
      )
    val unsupported =
      listOf(
        FilmstripError.NoEncoder(VideoCodec.Hevc, MESSAGE),
        FilmstripError.UnsupportedResolution(NATURAL, NATURAL, MESSAGE),
        FilmstripError.DecoderRejectedInput("hvc1", MESSAGE),
        FilmstripError.InvalidComposition(MESSAGE),
        FilmstripError.UnsupportedEffect("spec", MESSAGE),
        FilmstripError.UnpreviewableEffect("spec", MESSAGE),
        FilmstripError.PlatformEffectAsymmetry("spec", MESSAGE),
        FilmstripError.UnsupportedStillFormat(StillFormat.Png, MESSAGE),
      )

    for ((error, expected) in named) error.toPlaybackError() shouldBe expected
    for (error in unsupported) error.toPlaybackError() shouldBe PlaybackError.UnsupportedFormat(MESSAGE)
  }

  // Answers the natural frame for an uncapped spec and the capped height for a capped one, refusing the call at
  // [refuseAt] when there is one.
  private class RecordingResolve(
    private val refuseAt: Int? = null,
    private val answer: ExportGrade = ExportGrade.Settled(ResolvedHdr.Keep),
    private val resolvedHdr: ResolvedHdr = ResolvedHdr.Keep,
  ) {
    val calls = mutableListOf<Pair<ExportSpec, Size?>>()
    val grades = mutableListOf<ResolvedHdr?>()
    val graded = mutableListOf<ExportSpec>()

    val grade: CompositionGrade = { _, spec ->
      graded += spec
      answer
    }

    val resolve: CompositionResolve = { _, spec, layoutSize, hdr ->
      val index = calls.size
      calls += spec to layoutSize
      grades += hdr
      if (index == refuseAt) {
        ResolveResult.Refused(FilmstripError.SourceUnreadable("a.mp4", "unreadable"))
      } else {
        val height = spec.heightPx ?: NATURAL.height
        ResolveResult.Resolved(
          resolvedAt(Size(NATURAL.width * height / NATURAL.height, height), resolvedHdr),
          Verdict.Incapable(emptyList(), null),
        )
      }
    }
  }

  private companion object {
    val NATURAL = Size(1920, 1080)
    const val MID_HEIGHT = 720
    const val MESSAGE = "it went wrong"
    const val PLATFORM_CODE = 7

    val EDIT =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of("a.mp4"), TimeRange.of(Duration.ZERO, 2.seconds))))),
      )

    fun resolvedAt(
      size: Size,
      hdr: ResolvedHdr,
    ): ResolvedComposition =
      ResolvedComposition(
        tracks = emptyList(),
        compositionEffects = emptyList(),
        output =
          OutputFormat(
            size = size,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.Aac,
            bitrate = null,
            frameRate = 30,
            audioFormat = null,
          ),
        layoutSize = NATURAL,
        fill = Fill.Black,
        duration = 2.seconds,
        hdr = hdr,
        hdrTransfer = null,
        path = ExportPath.Transcode,
        audio = AudioSpec.Keep,
        adjustments = emptyList(),
      )
  }
}
