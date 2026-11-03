package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
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
    val resolved = fixture.resolve(composition(fixture.clip()).withFill(fill))

    resolved.fill shouldBe fill
  }

  @Test
  fun `showsFill is true for a contain fit`() {
    val resolved =
      fixture.resolve(composition(fixture.clip(effects = listOf(Scale(heightPx = 480, fit = Fit.Contain)))))

    resolved.showsFill shouldBe true
  }

  @Test
  fun `showsFill is false for a crop fit`() {
    val resolved =
      fixture.resolve(composition(fixture.clip(effects = listOf(Scale(heightPx = 480, fit = Fit.Crop)))))

    resolved.showsFill shouldBe false
  }

  @Test
  fun `showsFill is false for a stretch fit`() {
    val resolved =
      fixture.resolve(composition(fixture.clip(effects = listOf(Scale(heightPx = 480, fit = Fit.Stretch)))))

    resolved.showsFill shouldBe false
  }

  @Test
  fun `showsFill is true when a track starts after the composition does`() {
    val resolved = fixture.resolve(EditComposition(listOf(Track(listOf(fixture.clip()), start = 2_000.milliseconds))))

    resolved.showsFill shouldBe true
  }

  @Test
  fun `paintsFillAfterEffects holds back only a color a bar or a gap would otherwise show`() {
    val red = Fill.Solid(0xFFFF0000.toInt())

    fixture.resolve(containComposition(Fit.Contain).withFill(red)).paintsFillAfterEffects shouldBe true
    fixture.resolve(containComposition(Fit.Contain).withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe false
    fixture.resolve(containComposition(Fit.Crop).withFill(red)).paintsFillAfterEffects shouldBe false
    fixture.resolve(containComposition(Fit.Stretch).withFill(red)).paintsFillAfterEffects shouldBe false

    val lateStart = 2_000.milliseconds
    val lateTrack =
      EditComposition(listOf(Track(listOf(fixture.clip(effects = listOf(Scale(480, Fit.Crop)))), start = lateStart)))
    fixture.resolve(lateTrack.withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe true

    val lateContainedTrack =
      EditComposition(listOf(Track(listOf(fixture.clip(effects = listOf(Scale(480, Fit.Contain)))), start = lateStart)))
    fixture.resolve(lateContainedTrack.withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe true
  }

  @Test
  fun `derivesFromFrame is true only for a blurred fill`() {
    Fill.Solid(0xFFFF0000.toInt()).derivesFromFrame shouldBe false
    Fill.Blurred().derivesFromFrame shouldBe true
  }

  private fun containComposition(fit: Fit): EditComposition =
    composition(fixture.clip(effects = listOf(Scale(480, fit))))

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
        composition(fixture.clip(hdr = HdrTransfer.Pq)).withFill(fill),
        device = fixture.device(hdr = true),
      )

    resolved.fill shouldBe fill
  }
}
