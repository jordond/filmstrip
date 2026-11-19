package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.negotiation.SOLIDS_NEED_A_FRAME
import dev.jordond.filmstrip.transform.internal.negotiation.solidDurationNotPositive
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the planner lays a solid colour on the timeline and sizes the frame around it.
 */
class SolidPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a leading solid leaves the frame and the frame rate to the clip after it`() {
    val edit = composition(fixture.solidClip(), fixture.clip(size = Size(1280, 720), frameRate = 24f))

    val plan = fixture.plan(edit).shouldBeInstanceOf<Verdict.Capable>().plan

    plan.output.size shouldBe Size(1280, 720)
    plan.output.frameRate shouldBe 24
  }

  @Test
  fun `a solid between two stills is never the frame clip`() {
    val edit = composition(fixture.solidClip(), fixture.imageClip(size = Size(1440, 1080)))

    fixture
      .plan(edit)
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(1440, 1080)
  }

  @Test
  fun `only solids refuse by name unless the frame is fixed`() {
    val solids = composition(fixture.solidClip(), fixture.solidClip(color = RED))

    for (frame in listOf(OutputFrame.Auto, OutputFrame.Aspect(AspectRatio.Square))) {
      val incapable = fixture.plan(solids.withFrame(frame)).shouldBeInstanceOf<Verdict.Incapable>()
      incapable.reasons shouldContainExactly listOf(FilmstripError.InvalidComposition(SOLIDS_NEED_A_FRAME))
    }

    fixture
      .plan(solids.withFrame(OutputFrame.Fixed(Size(640, 360))))
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(640, 360)
  }

  @Test
  fun `a solid held for no time refuses by name`() {
    val empty = fixture.solidClip(duration = Duration.ZERO)
    val incapable = fixture.plan(composition(fixture.clip(), empty)).shouldBeInstanceOf<Verdict.Incapable>()

    incapable.reasons shouldContainExactly
      listOf(FilmstripError.InvalidComposition(solidDurationNotPositive(empty.source as MediaSource.Solid)))
  }

  @Test
  fun `a solid covers the whole frame whatever its fit asks for`() {
    for (fit in Fit.entries) {
      val solid = fixture.solidClip().withEffects(listOf(Scale(720, fit)))
      val resolved = fixture.resolve(composition(fixture.clip(size = Size(1920, 1080)), solid))

      val placement = resolved.tracks[0].clips[1].placement
      placement.drawnRect shouldBe NormalizedRect.Full
      placement.leavesBars shouldBe false
    }
  }

  @Test
  fun `a solid is laid at the frame clips land in`() {
    val edit =
      composition(fixture.clip(size = Size(1920, 1080)), fixture.solidClip())
        .withFrame(OutputFrame.Fixed(Size(640, 480)))
        .withEffects(listOf(Scale(240)))

    val laid =
      fixture
        .resolve(edit)
        .tracks[0]
        .clips[1]
        .info.video

    laid?.displaySize shouldBe Size(640, 480)
    laid?.codedSize shouldBe Size(640, 480)
  }

  @Test
  fun `a trim on a solid collapses into the length it keeps`() {
    val solid =
      fixture.solidClip(duration = 10_000.milliseconds, trim = TimeRange(2_000.milliseconds, 7_000.milliseconds))
    val resolved = fixture.resolve(composition(solid).withFrame(OutputFrame.Fixed(Size(640, 360))))

    val clip = firstClipOf(resolved)
    clip.start shouldBe Duration.ZERO
    clip.end shouldBe 5_000.milliseconds
    resolved.duration shouldBe 5_000.milliseconds
  }

  @Test
  fun `a speed on a solid is left out as it is on a still`() {
    val solid = fixture.solidClip(duration = 4_000.milliseconds, speed = 2.0)
    val plan =
      fixture
        .plan(composition(fixture.clip(), solid))
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan

    plan.inertNotices.map { it.subject } shouldContainExactly listOf("speed")
    fixture
      .resolve(composition(fixture.clip(), solid))
      .tracks[0]
      .clips[1]
      .let { it.end - it.start } shouldBe
      4_000.milliseconds
  }

  @Test
  fun `a lone solid transcodes`() {
    fixture
      .plan(composition(fixture.solidClip()).withFrame(OutputFrame.Fixed(Size(640, 360))))
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.path shouldBe ExportPath.Transcode
  }
}

private const val RED = 0xFFFF0000.toInt()
