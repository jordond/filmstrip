package dev.jordond.filmstrip.compose.overlay

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class PlanWithOverlaysTest {
  private val real = Filmstrip { ffmpegBackend() }

  @Test
  fun theBakerIsAskedForTheFirstPlansOutputSize() =
    runTest {
      val asked = mutableListOf<Size>()
      val filmstrip = Scripted(real) { _, composition -> real.plan(composition, SPEC) }

      filmstrip.planWithOverlays(EDIT, SPEC) { output ->
        asked += output
        emptyList()
      }

      asked shouldContainExactly listOf(OUTPUT)
    }

  @Test
  fun theSecondPlanCarriesTheEditsEffectsThenTheBakedOnesInOrder() =
    runTest {
      val capable = real.plan(EDIT, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      val filmstrip = Scripted(real) { _, _ -> capable }
      val edit = EDIT.withEffects(listOf(OWN))

      filmstrip.planWithOverlays(edit, SPEC) { listOf(BOTTOM, TOP) }

      filmstrip.planned shouldHaveSize 2
      filmstrip.planned[0].effects shouldContainExactly listOf(OWN)
      filmstrip.planned[1].effects shouldContainExactly listOf(OWN, BOTTOM, TOP)
    }

  @Test
  fun anIncapableFirstPlanIsBakedOnItsFallbacksFrameAndPlannedAgain() =
    runTest {
      val small = real.plan(SMALL_EDIT, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      val first = Verdict.Incapable(emptyList(), Verdict.Degraded(small.plan, listOf(DROPPED)))
      val second = Verdict.Incapable(emptyList(), null)
      val filmstrip = Scripted(real) { call, _ -> if (call == 0) first else second }
      val asked = mutableListOf<Size>()

      val verdict =
        filmstrip.planWithOverlays(EDIT, SPEC) { output ->
          asked += output
          listOf(BOTTOM)
        }

      asked shouldContainExactly listOf(SMALL)
      verdict shouldBeSameInstanceAs second
      filmstrip.planned[1].effects shouldContainExactly listOf(BOTTOM)
    }

  @Test
  fun anIncapableFirstPlanWithNoFallbackComesBackWithoutABake() =
    runTest {
      val incapable = Verdict.Incapable(emptyList(), null)
      val filmstrip = Scripted(real) { _, _ -> incapable }
      var bakes = 0

      val verdict =
        filmstrip.planWithOverlays(EDIT, SPEC) {
          bakes++
          listOf(BOTTOM)
        }

      verdict shouldBeSameInstanceAs incapable
      bakes shouldBe 0
      filmstrip.planned shouldHaveSize 1
    }

  @Test
  fun anEmptyBakeReturnsTheFirstVerdict() =
    runTest {
      val capable = real.plan(EDIT, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      val filmstrip = Scripted(real) { _, _ -> capable }

      val verdict = filmstrip.planWithOverlays(EDIT, SPEC) { emptyList() }

      verdict shouldBeSameInstanceAs capable
      filmstrip.planned shouldHaveSize 1
    }

  @Test
  fun aDegradedSecondPlanIsTheOneReturned() =
    runTest {
      val capable = real.plan(EDIT, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      val degraded = Verdict.Degraded(capable.plan, listOf(DROPPED))
      val filmstrip = Scripted(real) { call, _ -> if (call == 0) capable else degraded }

      val verdict = filmstrip.planWithOverlays(EDIT, SPEC) { listOf(BOTTOM) }

      verdict shouldBeSameInstanceAs degraded
    }

  @Test
  fun aBakerThatThrowsReachesTheCaller() =
    runTest {
      val capable = real.plan(EDIT, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      val filmstrip = Scripted(real) { _, _ -> capable }

      val failure =
        assertFailsWith<IllegalStateException> { filmstrip.planWithOverlays(EDIT, SPEC) { error(NO_STAGE) } }

      failure.message shouldBe NO_STAGE
      filmstrip.planned shouldHaveSize 1
    }

  // Answers each plan from [answer], given how many plans came before it, and keeps what it was asked to plan.
  private class Scripted(
    real: Filmstrip,
    private val answer: suspend (Int, Composition) -> Verdict,
  ) : Filmstrip by real {
    val planned = mutableListOf<Composition>()

    override suspend fun plan(
      composition: Composition,
      spec: ExportSpec,
    ): Verdict {
      planned += composition
      return answer(planned.size - 1, composition)
    }
  }

  private companion object {
    val OUTPUT = Size(640, 360)
    val SPEC = ExportSpec()
    val EDIT =
      compositionOf {
        solid(GREY, 1.seconds)
        frame(OutputFrame.Fixed(OUTPUT))
      }
    val SMALL = Size(320, 180)
    val SMALL_EDIT =
      compositionOf {
        solid(GREY, 1.seconds)
        frame(OutputFrame.Fixed(SMALL))
      }
    val OWN = picture(0.2f)
    val BOTTOM = picture(0.4f)
    val TOP = picture(0.6f)
    val DROPPED = Adjustment(AdjustmentKind.EffectDropped, "a blur", "nothing", "The blur was left out.")
    const val GREY = 0xFF808080.toInt()

    fun picture(at: Float): ImageOverlay =
      ImageOverlay(ImageSource.ofBytes(byteArrayOf(1)), OverlayPosition.At(Anchor(at, at)))
  }
}
