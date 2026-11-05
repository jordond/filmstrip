package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transform
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A clip's grade and the composition's, read back from the written file.
 *
 * The planner folds a run of color effects per stage, so the clip's stage clamps before the composition's runs, the
 * way every other backend writes the clip's stage into a frame first.
 */
class AndroidColorStageTest {
  private val probe = Media3ExportProbe()

  // Doubled and then halved, the cyan bar's two full channels clamp at white and come back at half, while the one near
  // black comes back as it went in. Folded into one product with no clamp between, all three would come back as they
  // went in.
  @Test
  fun aClipGradeClampsBeforeTheCompositionGradeRuns() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = probe.frame(probe.export(EditComposition(listOf(Track(listOf(Clip(source)))))))
      val graded =
        probe.frame(
          probe.export(EditComposition(listOf(Track(listOf(Clip(source, effects = listOf(UP))))), listOf(DOWN))),
        )

      val before = plain.averageAt(CYAN_BAR, CENTRE)
      val after = graded.averageAt(CYAN_BAR, CENTRE)
      val up = checkNotNull(colorMatrixOf(UP))
      val down = checkNotNull(colorMatrixOf(DOWN))
      val clipStage = up.transform(before.first / FULL, before.second / FULL, before.third / FULL)
      val clamped = clipStage.map { it.coerceIn(0f, 1f) }
      val expected = down.transform(clamped[0], clamped[1], clamped[2])
      val predicted = Triple(code(expected[0]), code(expected[1]), code(expected[2]))

      withClue("the bar read $before, which the doubling has to take past white for this to tell") {
        (distance(predicted, before) > MOVED) shouldBe true
      }
      withClue("the graded bar read $after, where the two stages clamped between them say $predicted") {
        (distance(after, predicted) < GRADE_DRIFT) shouldBe true
      }
    }

  private fun code(channel: Float): Int = (channel * FULL).toInt()

  private companion object {
    val UP = Brightness(2f)
    val DOWN = Brightness(0.5f)
    const val FULL = 255f
    const val CENTRE = 0.5f

    // The middle of the cyan bar in the band of bars that crosses the frame's centre.
    const val CYAN_BAR = 0.19f

    // How far an export's 4:2:0 round trip moves a bar, and how far the clamp has to move it to be told apart.
    const val GRADE_DRIFT = 24
    const val MOVED = 60
  }
}
