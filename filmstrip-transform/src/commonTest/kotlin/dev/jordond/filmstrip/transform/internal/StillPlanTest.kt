package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.MAX_STILL_FRAME
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media.videoCodecOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the planner lays stills on the timeline and sizes the frame around them.
 *
 * Covers stills refusing a copy, trims on a still, the still size ceiling and how a still over it is scaled back, and
 * which clip sets the frame and frame rate when stills sit beside video.
 */
class StillPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `an image-only composition plans capable and takes its frame from the still`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = Size(1440, 1080))))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.output.size shouldBe Size(1440, 1080)
  }

  // A still and a video clip of the same shape differ in nothing the planner reads but their
  // source, so the path they take apart on is the source's codec and not the geometry or the spec.
  @Test
  fun `a still refuses a copy where the same shaped video clip takes it`() {
    fixture
      .plan(composition(fixture.clip()))
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.path shouldBe ExportPath.Copy

    fixture
      .plan(composition(fixture.imageClip()))
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.path shouldBe
      ExportPath.Transcode
  }

  // That refusal is not a rule of its own. It falls out of the codec a still reports, which is one
  // no copy can name, so a copy is off the table before anything asks whether it would be.
  @Test
  fun `the codec a still reports is one a copy cannot name`() {
    val still = imageMediaInfoOf(Size(1920, 1080), EXIF_ORIENTATION_NORMAL, "jpeg", 6_000.milliseconds)

    videoCodecOf(
      still.video
        .shouldNotBeNull()
        .codec.kind,
    ).shouldBeNull()
  }

  // The window is measured against the length the source declares, not against a container, and it
  // collapses into that length rather than staying where the trim named. A still has no samples to
  // clip, so what a backend gets is a span to lay and nothing to seek in. Both ends of the trim sit
  // inside the still, so an off-by-one at either end fails here.
  @Test
  fun `a trim on a still collapses into the length it keeps`() {
    val still =
      fixture.imageClip(duration = 10_000.milliseconds, trim = TimeRange(2_000.milliseconds, 7_000.milliseconds))
    val resolved = fixture.resolve(composition(still))

    val clip = firstClipOf(resolved)
    clip.start shouldBe Duration.ZERO
    clip.end shouldBe 5_000.milliseconds
    resolved.duration shouldBe 5_000.milliseconds
  }

  // Only a still collapses. A video clip's window still names where in the source it opens, which is
  // what a backend seeks to before it reads a frame.
  @Test
  fun `a trim on a video clip keeps the window it named in the source`() {
    val trimmed = fixture.clip(duration = 10_000.milliseconds, trim = TimeRange(2_000.milliseconds, 7_000.milliseconds))
    val resolved = fixture.resolve(composition(trimmed))

    val clip = firstClipOf(resolved)
    clip.start shouldBe 2_000.milliseconds
    clip.end shouldBe 7_000.milliseconds
  }

  @Test
  fun `a trim past the end of a still is clamped to the length it declares`() {
    val still =
      fixture.imageClip(duration = 4_000.milliseconds, trim = TimeRange(1_000.milliseconds, 30_000.milliseconds))
    val resolved = fixture.resolve(composition(still))

    firstClipOf(resolved).duration shouldBe 3_000.milliseconds
    resolved.duration shouldBe 3_000.milliseconds
  }

  @Test
  fun `a still that trims to nothing is refused rather than laid as an empty span`() {
    val still =
      fixture.imageClip(duration = 4_000.milliseconds, trim = TimeRange(4_000.milliseconds, 9_000.milliseconds))

    fixture.plan(composition(still)).shouldBeInstanceOf<Verdict.Incapable>()
  }

  // A still has no cadence, so the clip that does is what the output rate comes from.
  @Test
  fun `a still beside a video clip leaves the frame rate to the clip that has one`() {
    val verdict = fixture.plan(composition(fixture.clip(frameRate = 24f), fixture.imageClip()))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.frameRate shouldBe 24
  }

  // The still leads here, so a rate read off the first clip rather than the first one that decodes
  // video would fall back to the default instead of finding the 24 that is right there.
  @Test
  fun `a still that leads still leaves the frame rate to the clip that has one`() {
    val verdict = fixture.plan(composition(fixture.imageClip(), fixture.clip(frameRate = 24f)))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.frameRate shouldBe 24
  }

  @Test
  fun `a still under the ceiling keeps its own frame`() {
    val under = Size(MAX_STILL_FRAME.width - 2, MAX_STILL_FRAME.height - 2)
    val verdict = fixture.plan(composition(fixture.imageClip(size = under)), device = roomyDevice())

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe under
  }

  @Test
  fun `a still exactly on the ceiling keeps its own frame`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = MAX_STILL_FRAME)), device = roomyDevice())

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe MAX_STILL_FRAME
  }

  @Test
  fun `a still just over the ceiling is scaled back inside it`() {
    val over = Size(MAX_STILL_FRAME.width + 2, MAX_STILL_FRAME.height + 2)
    val verdict = fixture.plan(composition(fixture.imageClip(size = over)), device = roomyDevice())

    verdict
      .shouldBeInstanceOf<Verdict.Degraded>()
      .plan.output.size shouldBe Size(3838, MAX_STILL_FRAME.height)
  }

  // A 12 megapixel phone photo. Both sides scale by the same factor, so the frame is 4:3 on the way
  // out exactly as it was on the way in.
  @Test
  fun `a still far over the ceiling is scaled back and keeps its aspect`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = Size(4032, 3024))), device = roomyDevice())

    val size =
      verdict
        .shouldBeInstanceOf<Verdict.Degraded>()
        .plan.output.size
    size shouldBe Size(2880, 2160)
    size.aspect shouldBe Size(4032, 3024).aspect
  }

  // Between the two, and a shape that is neither the ceiling's nor the one above. A rule that read
  // as a height cap, or that coerced each side on its own, lands somewhere else here.
  @Test
  fun `a still between the ceiling and the largest photo keeps its aspect too`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = Size(6000, 4000))), device = roomyDevice())

    val size =
      verdict
        .shouldBeInstanceOf<Verdict.Degraded>()
        .plan.output.size
    size shouldBe Size(3240, 2160)
    size.aspect shouldBe Size(6000, 4000).aspect
  }

  // The ceiling is a box rather than a height, so a portrait photo is held by its long side and
  // comes out portrait. A height-only clamp would leave this one 3024 wide.
  @Test
  fun `a portrait still is held by its long side`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = Size(3024, 4032))), device = roomyDevice())

    val size =
      verdict
        .shouldBeInstanceOf<Verdict.Degraded>()
        .plan.output.size
    size shouldBe Size(1620, 2160)
    size.aspect shouldBe Size(3024, 4032).aspect
  }

  @Test
  fun `a still scaled back to the ceiling says so`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = Size(4032, 3024))), device = roomyDevice())

    val adjustment = verdict.shouldBeInstanceOf<Verdict.Degraded>().adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.ResolutionClamped
    adjustment.requested shouldBe "4032x3024"
    adjustment.resolved shouldBe "2880x2160"
  }

  @Test
  fun `strict refuses a still over the ceiling and names it`() {
    val verdict =
      fixture.plan(
        composition(fixture.imageClip(size = Size(4032, 3024))),
        ExportSpec(strict = true),
        device = roomyDevice(),
      )

    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    val refusal = error.shouldBeInstanceOf<FilmstripError.UnsupportedResolution>()
    refusal.requested shouldBe Size(4032, 3024)
    refusal.max shouldBe MAX_STILL_FRAME
  }

  // The ceiling is there for a caller who said nothing about the frame. One who named a height has
  // said what they want, and only the encoder's own limit is left to hold them to.
  @Test
  fun `a target height frames a still rather than the ceiling`() {
    val verdict =
      fixture.plan(
        composition(fixture.imageClip(size = Size(4032, 3024))),
        ExportSpec(heightPx = 3024),
        device = roomyDevice(),
      )

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(4032, 3024)
  }

  // A photo's pixel count says nothing about the size the output should be, so a still-framed edit is the one place a
  // height above the natural frame still scales up.
  @Test
  fun `a height above a still's own size upscales it`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = Size(1280, 720))), ExportSpec(heightPx = 1080))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(1920, 1080)
  }

  // The counter-test to the still ceiling: a video frame is one an encoder already produced, so it
  // is held to the encoder's limit and to nothing else. Widening the ceiling to every source would
  // fail here.
  @Test
  fun `a video frame over the still ceiling is left at its own size`() {
    val verdict =
      fixture.plan(composition(fixture.clip(size = Size(4032, 3024))), device = roomyDevice(), muxerAccepts = { false })

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(4032, 3024)
  }

  @Test
  fun `a still after a video clip leaves the frame to the video`() {
    val verdict =
      fixture.plan(composition(fixture.clip(size = Size(1920, 1080)), fixture.imageClip(size = Size(4032, 3024))))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(1920, 1080)
  }

  // The trap the ceiling alone does not catch: the still leads, so a frame measured from the first
  // clip would hand a 16:9 edit a 4:3 output.
  @Test
  fun `a still before a video clip leaves the frame to the video`() {
    val verdict =
      fixture.plan(composition(fixture.imageClip(size = Size(4032, 3024)), fixture.clip(size = Size(1920, 1080))))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(1920, 1080)
  }

  // A still leading a source that decodes no video has nothing else to measure from, so it keeps
  // the frame it would have had.
  @Test
  fun `a still keeps the frame when nothing else on the track decodes video`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = Size(1440, 1080)), fixture.audioOnlyClip()))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(1440, 1080)
  }

  // The other branch: only a still looks past the first clip. A source with no video track of its
  // own still refuses the export, exactly as it did before a still could lead one.
  @Test
  fun `a first clip with no video is refused even when a later clip has one`() {
    val verdict = fixture.plan(composition(fixture.audioOnlyClip(), fixture.clip()))

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  /**
   * A device whose encoders publish one range per side, the way Android's do.
   *
   * Such a ceiling accepts a sensor-sized frame on paper, so it is what leaves [MAX_STILL_FRAME] as
   * the only thing holding a still's frame down.
   */
  private fun roomyDevice() =
    fixture.device(video = listOf(fixture.encoder(VideoCodec.H264, maxSize = Size(8192, 8192))))
}
