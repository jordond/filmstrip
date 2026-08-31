package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.Rotate
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.playback.contract.asTestFrame
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.awaitFrame
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.Media3PlayerEngine
import dev.jordond.filmstrip.playback.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What the preview draws after an edit is changed, against what an export of that same edit writes.
 *
 * Every case here loads one edit, then replaces it with another, and compares the frame the live
 * graph produces against the export's. A backend that swapped parameters into a graph that could no
 * longer draw them shows up as a frame that no longer matches, which no single-load test sees.
 */
class AndroidGeometrySwapTest {
  @Test
  fun aRotationAppliedToALoadedEditDrawsWhatAnExportOfItWould() =
    followingEdit(
      first = androidFixtureComposition(listOf(Brightness(DIM))),
      second = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
      rebuilds = true,
    )

  @Test
  fun aRotationChangedToAnotherQuarterTurnDrawsWhatAnExportOfItWould() =
    followingEdit(
      first = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
      second = androidFixtureComposition(listOf(Rotate(THREE_QUARTERS), Brightness(DIM))),
      rebuilds = false,
    )

  @Test
  fun aRotationTakenBackOffDrawsWhatAnExportOfItWould() =
    followingEdit(
      first = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
      second = androidFixtureComposition(listOf(Brightness(DIM))),
      rebuilds = true,
    )

  @Test
  fun aHalfTurnAfterAQuarterTurnDrawsWhatAnExportOfItWould() =
    followingEdit(
      first = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
      second = androidFixtureComposition(listOf(Rotate(HALF), Brightness(DIM))),
      rebuilds = true,
    )

  @Test
  fun aFillChangedUnderALetterboxDrawsWhatAnExportOfItWould() =
    followingEdit(
      first = androidFixtureComposition(listOf(Rotate(QUARTER)), Fill.Solid(RED)),
      second = androidFixtureComposition(listOf(Rotate(QUARTER)), Fill.Solid(BLUE)),
      rebuilds = true,
    )

  @Test
  fun aBrightnessChangedUnderARotationDrawsWhatAnExportOfItWould() =
    followingEdit(
      first = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
      second = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(BRIGHT))),
      rebuilds = false,
    )

  /**
   * A parameter changed and read back, over and over, the way a slider does.
   *
   * Every readback has to show the value that was just set. Reading a frame, changing a parameter
   * and reading again is back to back, so nothing the previous read left open has time to go away
   * on its own, and a stale answer reads as a slider that stopped responding.
   */
  @Test
  fun everyParameterChangeReadsBackTheValueThatWasSet() =
    contractTest(timeout = BUDGET) { scope ->
      val engine =
        Media3PlayerEngine(
          parent = scope,
          context = contractContext(),
          planner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
          config = PlayerConfig(),
        )
      withEngine(engine) { recorder ->
        engine.setQualityPolicy(PreviewQualityPolicy.Full)

        var previous: ReadbackFrame? = null
        LEVELS.forEachIndexed { step, level ->
          engine
            .awaitComposition(androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(level))))
            .shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("step $step to be presentable") { recorder.lastState.hasComposition }

          val frame = engine.readback.awaitFrame(PROBE)
          previous?.let {
            assertFramesDiffer(
              expected = it.asTestFrame(),
              actual = frame.asTestFrame(),
              message = "step $step read back the frame step ${step - 1} drew",
            )
          }
          previous = frame
        }

        // Every change after the first rode the live swap, so none of this cost a fresh graph.
        engine.platformLoads shouldBe 1
      }
    }

  /**
   * A rotated edit against an export of it, which is where the two pipelines could disagree about
   * the frame a quarter turn produces.
   */
  @Test
  fun aRotatedEditDrawsWhatAnExportOfItWould() =
    contractTest(timeout = BUDGET) { scope ->
      val rotated = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM)))
      val preview = readbackAfter(scope, rotated, null)
      preview.renderScale shouldBe 1f

      val exported = androidExportFrame(rotated, preview.presentationTime)
      if (exported.size != preview.size) {
        fail("the export is ${exported.size} at ${preview.presentationTime}, the preview ${preview.size}")
      }

      assertFramesSimilar(
        expected = exported,
        actual = preview.asTestFrame(),
        minPsnrDb = EXPORT_MIN_PSNR_DB,
        minSsim = EXPORT_MIN_SSIM,
        message = "the rotated preview and the rotated export disagree",
      )
    }

  @Test
  fun aRotationMovesTheOutputFrameThePreviewReports() =
    contractTest(timeout = BUDGET) { scope ->
      val engine =
        Media3PlayerEngine(
          parent = scope,
          context = contractContext(),
          planner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
          config = PlayerConfig(),
        )
      withEngine(engine) { recorder ->
        engine
          .awaitComposition(androidFixtureComposition(listOf(Brightness(DIM))))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first preview info") { recorder.previewInfo.isNotEmpty() }
        val upright = recorder.previewInfo.last().outputSize

        engine
          .awaitComposition(androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the second preview info") { recorder.previewInfo.size >= 2 }
        val turned = recorder.previewInfo.last().outputSize

        // A quarter turn swaps the frame. A surface sized from a stale one letterboxes the picture
        // into the shape the edit used to have.
        turned shouldBe Size(upright.height, upright.width)
      }
    }

  /**
   * Loads [first], draws it, replaces it with [second], and compares against a preview that only
   * ever loaded [second].
   *
   * Both sides are previews, so the encoder is out of the comparison and the two frames have to be
   * the same graph's output twice. A change the standing graph took but could not honour is the
   * only thing that separates them.
   */
  private fun followingEdit(
    first: EditComposition,
    second: EditComposition,
    rebuilds: Boolean,
  ) = contractTest(timeout = BUDGET) { scope ->
    val before = readbackAfter(scope, first, null)
    val changed = readbackAfter(scope, first, second)
    val fresh = readbackAfter(scope, second, null)

    // Which path the change took, so a swap that quietly became a rebuild, or a rebuild that
    // quietly became a swap, fails here rather than only where the pixels happen to notice.
    changed.platformLoads shouldBe if (rebuilds) 2 else 1
    changed.size shouldBe fresh.size
    assertFramesSimilar(
      expected = fresh.asTestFrame(),
      actual = changed.asTestFrame(),
      minPsnrDb = MIN_PSNR_DB,
      minSsim = MIN_SSIM,
      // Whether the frame is still the one the first edit drew separates a change that never
      // reached the graph from a change that reached it and came out wrong.
      message =
        "the preview after the edit changed is not the preview a fresh load of it draws. " +
          "Against the first edit's own frame it scores ${describe(before.frame, changed.frame)}. " +
          "Read again straight after, it scores ${describe(changed.frame, changed.again)} against " +
          "the first read and ${describe(fresh.frame, changed.again)} against the fresh load",
    )
  }

  /**
   * How close [changed] is to [before], where the two are the same size.
   */
  private fun describe(
    before: ReadbackFrame,
    changed: ReadbackFrame,
  ): String =
    if (before.size != changed.size) {
      "nothing, the frames are ${before.size} and ${changed.size}"
    } else {
      compareFrames(before.asTestFrame(), changed.asTestFrame()).toString()
    }

  /**
   * Loads [first], draws it, then loads [second] where there is one, and reads a frame back.
   */
  private suspend fun readbackAfter(
    scope: CoroutineScope,
    first: EditComposition,
    second: EditComposition?,
  ): Drawn {
    val engine =
      Media3PlayerEngine(
        parent = scope,
        context = contractContext(),
        planner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
        config = PlayerConfig(),
      )
    var frame: ReadbackFrame? = null
    var again: ReadbackFrame? = null
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(first).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the first edit to be presentable") { recorder.lastState.hasComposition }
      engine.readback.awaitFrame(PROBE)

      if (second != null) {
        engine.awaitComposition(second).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the second edit to be presentable") { recorder.lastState.hasComposition }
      }
      frame = engine.readback.awaitFrame(PROBE)
      // A second read of the same thing, so a first answer that was stale can be told from a
      // parameter that never reached the graph at all.
      again = engine.readback.awaitFrame(PROBE)
    }
    return Drawn(
      checkNotNull(frame) { "no frame was read back" },
      checkNotNull(again) { "no second frame was read back" },
      engine.platformLoads,
    )
  }

  /**
   * One frame the preview drew, and how many graphs it cost to get there.
   */
  private class Drawn(
    val frame: ReadbackFrame,
    val again: ReadbackFrame,
    val platformLoads: Int,
  ) {
    val size get() = frame.size

    val renderScale get() = frame.renderScale

    val presentationTime get() = frame.presentationTime

    fun asTestFrame() = frame.asTestFrame()
  }

  private companion object {
    val PROBE: Duration = PROBE_POSITIONS.first()
    const val QUARTER = 90
    const val HALF = 180
    const val THREE_QUARTERS = 270

    // Alternating, so each step has to differ from the one before it rather than only from the
    // first. Far enough apart that no rounding could account for the difference.
    val LEVELS = listOf(0.4f, 1.4f, 0.4f, 1.4f, 0.4f, 1.4f)

    const val DIM = 0.4f
    const val BRIGHT = 1.4f
    val RED = 0xFFFF0000.toInt()
    val BLUE = 0xFF0000FF.toInt()

    // Two previews of the same graph, so anything but decoder noise is a real difference. An edit
    // the standing graph mishandled scores far below this, not just under it.
    const val MIN_PSNR_DB = 45.0
    const val MIN_SSIM = 0.99

    // The numbers AndroidPixelContractTest measured, for the one case that compares against an
    // export the preview never encoded.
    const val EXPORT_MIN_PSNR_DB = 40.0
    const val EXPORT_MIN_SSIM = 0.985

    // Six cases, each with an export in it.
    val BUDGET = 5.minutes
  }
}
