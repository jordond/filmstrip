package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * How the planner refuses a [Fill.Blurred] on a device that cannot draw one.
 */
class FillLimitPlanTest {
  private val fixture = PlannerFixture()
  private val squareContain = Crop(AspectRatio.Square, fit = Fit.Contain)

  @Test
  fun `a blurred fill is refused by its name with the limit the device gives`() {
    val edit = Composition(listOf(Track(listOf(fixture.clip()))), listOf(squareContain), fill = Fill.Blurred())
    val planner = fixture.planner(fillLimit = { LIMIT })

    val verdict = planner.negotiate(edit, Ask(ExportSpec(), fixture.device(), fixture.infos(edit))).verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    val message = reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message
    message shouldContain "Fill.Blurred"
    message shouldContain LIMIT
  }

  @Test
  fun `a solid fill never asks for the limit`() {
    var asked = 0
    val edit = Composition(listOf(Track(listOf(fixture.clip()))), fill = Fill.Black)
    val planner =
      fixture.planner(
        fillLimit = {
          asked++
          LIMIT
        },
      )

    planner
      .negotiate(edit, Ask(ExportSpec(), fixture.device(), fixture.infos(edit)))
      .verdict
      .shouldBeInstanceOf<Verdict.Capable>()

    asked shouldBe 0
  }

  private companion object {
    const val LIMIT = "this device cannot compile a kernel"
  }
}
