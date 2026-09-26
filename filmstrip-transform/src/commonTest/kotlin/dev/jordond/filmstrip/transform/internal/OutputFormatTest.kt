package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the planner sizes the output frame and picks its codec and frame rate.
 *
 * Covers target heights, encoder alignment and size ceilings, codec fallbacks and what strict refuses of them, and
 * frame rates clamped to the encoder or kept through a copy.
 */
class OutputFormatTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a target height keeps the source aspect`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(targetHeight = 720))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.output.size shouldBe Size(1280, 720)
    plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `an odd frame rounds to the encoder's alignment and says so`() {
    val verdict = fixture.plan(composition(fixture.clip(size = Size(1921, 1081))), canCopy = { false })

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.path shouldBe ExportPath.Transcode
    degraded.plan.output.size shouldBe Size(1920, 1080)
    degraded.adjustments.single().kind shouldBe AdjustmentKind.ResolutionClamped
  }

  // An encoder's alignment is the encoder's problem, and a copy runs none, so the odd frame goes
  // across whole rather than being reported as a clamp that never happens.
  @Test
  fun `an odd frame copies whole rather than rounding when it can transmux`() {
    val verdict = fixture.plan(composition(fixture.clip(size = Size(1921, 1081))))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
    plan.output.size shouldBe Size(1921, 1081)
  }

  @Test
  fun `a frame larger than any encoder accepts still copies at its own size`() {
    val small = fixture.encoder(VideoCodec.H264, maxSize = Size(1280, 720))
    val verdict =
      fixture.plan(composition(fixture.clip(size = Size(3840, 2160))), device = fixture.device(video = listOf(small)))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
    plan.output.size shouldBe Size(3840, 2160)
  }

  @Test
  fun `a frame larger than the encoder accepts is clamped to it and reported`() {
    val small = fixture.encoder(VideoCodec.H264, maxSize = Size(1280, 720))
    val verdict =
      fixture.plan(
        composition(fixture.clip(size = Size(3840, 2160))),
        device = fixture.device(video = listOf(small)),
        canCopy = { false },
      )

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.size shouldBe Size(1280, 720)
    degraded.adjustments.single().kind shouldBe AdjustmentKind.ResolutionClamped
  }

  // A 16:9 source against a 16:9 ceiling scales the same under a per-side clamp and an
  // aspect-preserving one, so the shape only shows on a frame whose ratio differs from the ceiling's.
  @Test
  fun `a clamped frame keeps its aspect rather than being squashed onto the ceiling`() {
    val landscapeOnly = fixture.encoder(VideoCodec.H264, maxSize = Size(1920, 1080))
    val verdict =
      fixture.plan(
        composition(fixture.clip(size = Size(1080, 1920))),
        device = fixture.device(video = listOf(landscapeOnly)),
        canCopy = { false },
      )

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.size shouldBe Size(608, 1080)
    degraded.adjustments.single().kind shouldBe AdjustmentKind.ResolutionClamped
  }

  @Test
  fun `strict refuses a clamped frame by size`() {
    val small = fixture.encoder(VideoCodec.H264, maxSize = Size(1280, 720))
    val verdict =
      fixture.plan(
        composition(fixture.clip(size = Size(3840, 2160))),
        ExportSpec(strict = true),
        device = fixture.device(video = listOf(small)),
        canCopy = { false },
      )

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.UnsupportedResolution>(error).max shouldBe Size(1280, 720)
  }

  // strict says the numbers were not negotiable, and a copy negotiates none of them: the frame goes
  // across at its own size, so there is no clamp to refuse.
  @Test
  fun `strict still transmuxes an oversized frame that copies untouched`() {
    val small = fixture.encoder(VideoCodec.H264, maxSize = Size(1280, 720))
    val verdict =
      fixture.plan(
        composition(fixture.clip(size = Size(3840, 2160))),
        ExportSpec(strict = true),
        device = fixture.device(video = listOf(small)),
      )

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
    plan.output.size shouldBe Size(3840, 2160)
  }

  @Test
  fun `a portrait source keeps its portrait output size`() {
    val verdict = fixture.plan(composition(fixture.clip(size = Size(1080, 1920))))

    assertIs<Verdict.Capable>(verdict).plan.output.size shouldBe Size(1080, 1920)
  }

  @Test
  fun `an unavailable codec falls back and reports it`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(videoCodec = VideoCodec.Vp9))

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.videoCodec shouldBe VideoCodec.H264
    degraded.adjustments.single().kind shouldBe AdjustmentKind.CodecFallback
  }

  @Test
  fun `strict refuses the fallback it would otherwise take`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(videoCodec = VideoCodec.Vp9, strict = true))

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.NoEncoder>(error).codec shouldBe VideoCodec.Vp9
  }

  @Test
  fun `a device with no video encoder at all is refused`() {
    val verdict = fixture.plan(composition(fixture.clip()), device = fixture.device(video = emptyList()))

    assertIs<ExportError.NoEncoder>(assertIs<Verdict.Incapable>(verdict).reasons.single())
  }

  @Test
  fun `a frame rate that is not positive is refused by name`() {
    listOf(0, -1).forEach { rate ->
      val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(frameRate = rate))

      val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
      assertIs<ExportError.InvalidComposition>(error).message shouldBe
        "The export asks for $rate fps, and a frame rate has to be positive. Leave it unset to keep the " +
        "source's rate."
    }
  }

  // Only the rate that was asked for is read, so leaving it unset or naming a real one still plans.
  @Test
  fun `a frame rate left unset or positive still plans`() {
    assertIs<Verdict.Capable>(fixture.plan(composition(fixture.clip()), ExportSpec()))
    assertIs<Verdict.Capable>(fixture.plan(composition(fixture.clip()), ExportSpec(frameRate = 30)))
  }

  @Test
  fun `a requested frame rate above the encoder's ceiling is clamped`() {
    val capped = fixture.encoder(VideoCodec.H264, maxFrameRate = 30)
    val verdict =
      fixture.plan(
        composition(fixture.clip()),
        ExportSpec(frameRate = 60),
        device = fixture.device(video = listOf(capped)),
      )

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.frameRate shouldBe 30
    degraded.adjustments.single().kind shouldBe AdjustmentKind.FrameRateClamped
  }

  @Test
  fun `clips concatenate to one output frame`() {
    val composition = composition(fixture.clip(), fixture.clip(size = Size(1280, 720)))

    val resolved = fixture.resolve(composition)
    resolved.output.size shouldBe Size(1920, 1080)
    resolved.tracks
      .single()
      .clips.size shouldBe 2
    resolved.duration shouldBe 12_000.milliseconds
  }

  // A copy runs no encoder, so the encoder's rate ceiling is not a ceiling on anything here and the
  // source's own rate is what the file keeps.
  @Test
  fun `a copy keeps a source rate above the encoder's ceiling`() {
    val capped = fixture.encoder(VideoCodec.H264, maxFrameRate = 60)
    val verdict =
      fixture.plan(composition(fixture.clip(frameRate = 120f)), device = fixture.device(video = listOf(capped)))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
    plan.output.frameRate shouldBe 120
  }

  @Test
  fun `an encode clamps a source rate above the encoder's ceiling`() {
    val capped = fixture.encoder(VideoCodec.H264, maxFrameRate = 60)
    val verdict =
      fixture.plan(
        composition(fixture.clip(frameRate = 120f)),
        device = fixture.device(video = listOf(capped)),
        canCopy = { false },
      )

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transcode
    plan.output.frameRate shouldBe 60
  }

  // The encoder's ceiling is a box too. Coercing each side on its own would leave this 1280x1080,
  // which is a frame nobody asked for in a shape nobody asked for.
  @Test
  fun `a frame clamped to the encoder keeps its aspect`() {
    val narrow = fixture.encoder(VideoCodec.H264, maxSize = Size(1280, 1280))
    val verdict =
      fixture.plan(
        composition(fixture.clip(size = Size(1920, 1080))),
        device = fixture.device(video = listOf(narrow)),
        canCopy = { false },
      )

    assertIs<Verdict.Degraded>(verdict).plan.output.size shouldBe Size(1280, 720)
  }
}
