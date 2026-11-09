package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.geometry.derivesFromFrame
import dev.jordond.filmstrip.transform.internal.resolve.paintsFillAfterEffects
import dev.jordond.filmstrip.transform.internal.resolve.showsFill
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the planner carries a fill onto the negotiated composition.
 *
 * Covers which fits and track layouts leave a fill showing, when a fill is painted after effects or read from the
 * frame, and fills over an hdr source kept as hdr.
 */
class FillPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a non-default fill lands on the negotiated composition`() {
    val fill = Fill.Blurred(radius = 0.1f, dim = 0.5f)
    // The second clip is four by three, so it leaves bars for the fill to show in.
    val resolved = fixture.resolve(composition(fixture.clip(), fixture.clip(size = NARROW)).withFill(fill))

    resolved.fill shouldBe fill
  }

  @Test
  fun `showsFill is true for a clip whose own contain fit leaves bars`() {
    val resolved =
      fixture.resolve(
        composition(fixture.clip(), fixture.clip(size = NARROW, effects = listOf(Scale(480, Fit.Contain)))),
      )

    resolved.showsFill shouldBe true
  }

  @Test
  fun `showsFill is false when every clip's own fit is a crop`() {
    val resolved = fixture.resolve(coveredComposition(Fit.Crop))

    resolved.showsFill shouldBe false
  }

  @Test
  fun `showsFill is false when every clip's own fit is a stretch`() {
    val resolved = fixture.resolve(coveredComposition(Fit.Stretch))

    resolved.showsFill shouldBe false
  }

  @Test
  fun `showsFill is true when a clip with no scale follows one that crops`() {
    val resolved =
      fixture.resolve(composition(fixture.clip(effects = listOf(Scale(480, Fit.Crop))), fixture.clip(size = NARROW)))

    resolved.showsFill shouldBe true
  }

  @Test
  fun `showsFill is true when a track starts after the composition does`() {
    val resolved = fixture.resolve(Composition(listOf(Track(listOf(fixture.clip()), start = 2_000.milliseconds))))

    resolved.showsFill shouldBe true
  }

  // The clip in the middle has sound and no picture, so the video between the other two is a gap the fill shows in.
  @Test
  fun `showsFill is true when a clip with no picture leaves a gap in the video`() {
    val resolved = fixture.resolve(composition(fixture.clip(), fixture.audioOnlyClip(), fixture.clip()))

    resolved.showsFill shouldBe true
    resolved.paintsFillAfterEffects shouldBe true
  }

  @Test
  fun `paintsFillAfterEffects holds back only a color a bar or a gap would otherwise show`() {
    val red = Fill.Solid(0xFFFF0000.toInt())

    fixture.resolve(coveredComposition(Fit.Contain).withFill(red)).paintsFillAfterEffects shouldBe true
    fixture.resolve(coveredComposition(Fit.Contain).withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe false
    fixture.resolve(coveredComposition(Fit.Crop).withFill(red)).paintsFillAfterEffects shouldBe false
    fixture.resolve(coveredComposition(Fit.Stretch).withFill(red)).paintsFillAfterEffects shouldBe false

    val lateStart = 2_000.milliseconds
    val lateTrack =
      Composition(listOf(Track(listOf(fixture.clip(effects = listOf(Scale(480, Fit.Crop)))), start = lateStart)))
    fixture.resolve(lateTrack.withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe true

    val lateContainedTrack =
      Composition(listOf(Track(listOf(fixture.clip(effects = listOf(Scale(480, Fit.Contain)))), start = lateStart)))
    fixture.resolve(lateContainedTrack.withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe true
  }

  // The music bed is a four by three video whose picture is never drawn, so its bars are nobody's.
  @Test
  fun `a clip on an audio track leaves no bars for the fill`() {
    val bed = Track(listOf(fixture.clip(size = NARROW)), content = TrackContent.Audio)
    val resolved = fixture.resolve(Composition(listOf(Track(listOf(fixture.clip())), bed)))

    resolved.showsFill shouldBe false
    resolved.paintsFillAfterEffects shouldBe false
  }

  @Test
  fun `derivesFromFrame is true only for a blurred fill`() {
    Fill.Solid(0xFFFF0000.toInt()).derivesFromFrame shouldBe false
    Fill.Blurred().derivesFromFrame shouldBe true
  }

  // A sixteen by nine clip followed by a four by three one, each fitted to the frame by its own scale.
  private fun coveredComposition(fit: Fit): Composition =
    composition(fixture.clip(), fixture.clip(size = NARROW, effects = listOf(Scale(480, fit))))

  @Test
  fun `an hdr source kept as hdr accepts a fill other than black`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Hlg)).withFill(Fill.Blurred()),
        device = fixture.device(hdr = true),
      )

    verdict.shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun `an hdr source kept as hdr carries a solid fill through to the negotiated composition`() {
    val fill = Fill.Solid(0xFFFF00FFL.toInt())
    val resolved =
      fixture.resolve(
        composition(
          fixture.clip(hdr = HdrTransfer.Pq),
          fixture.clip(size = NARROW, hdr = HdrTransfer.Pq),
        ).withFill(fill),
        device = fixture.device(hdr = true),
      )

    resolved.fill shouldBe fill
  }

  private companion object {
    val NARROW = Size(1440, 1080)
  }
}
