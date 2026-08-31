package dev.jordond.filmstrip.playback.contract

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.DEFAULT_MIN_PSNR_DB
import dev.jordond.filmstrip.test.DEFAULT_MIN_SSIM
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The contracts that need real media, a real export and pixel readback.
 *
 * Separate from [PlayerEngineContractTest] because the cost of inheriting is different: this one
 * asks a backend for a decoded fixture, an export it can render frames out of, and a preview
 * pipeline that reads back. A backend that has an engine but no export path yet inherits the other
 * class alone rather than stubbing half of this one out.
 *
 * The comparison that matters is the first one. A preview and an export lowered from the same
 * [EditComposition] can diverge quietly for a long time, and nothing else in the suite notices.
 *
 * Camel case test names, for the reason [PlayerEngineContractTest] gives.
 */
abstract class PlayerPixelContractTest {
  /**
   * Builds a fresh engine holding no composition.
   *
   * Called once per test. The suite disposes the engine afterwards.
   *
   * @param scope The dispatcher the suite drives the engine from. Confine platform callbacks to it.
   */
  protected abstract fun createEngine(scope: CoroutineScope): PlayerEngine

  /**
   * Real media this backend decodes, along with the frames worth comparing.
   */
  protected abstract val fixture: PixelFixture

  /**
   * Renders the frame [composition] exports at [position], through the export path rather than the
   * preview one.
   *
   * The frame must be the export's own output at exactly that composition time, at the
   * composition's output size, as tightly packed RGBA_8888.
   */
  protected abstract suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame

  /**
   * How close the two frames have to be, for a backend whose export path costs something the
   * preview's does not.
   *
   * The default is what two renderings of the same graph score, and a backend only loosens it to
   * honour a limit of its own. A backend with no seam between its composition and its encoder is
   * comparing one encode apart, and says so where it overrides these, with the numbers it measured.
   * Loosening because a comparison failed, rather than because the path is known to cost something,
   * is how this test stops catching what it exists for.
   */
  protected open val minPsnrDb: Double get() = DEFAULT_MIN_PSNR_DB

  protected open val minSsim: Double get() = DEFAULT_MIN_SSIM

  @Test
  fun aReadbackMatchesTheExportAtTheSameCompositionTime() =
    contractTest { scope ->
      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        // Full, so renderScale is 1f and the two frames are the same size without rescaling either.
        engine.setQualityPolicy(PreviewQualityPolicy.Full)
        engine.awaitComposition(fixture.composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        for (position in fixture.positions) {
          val preview = engine.readback.awaitFrame(position)
          preview.renderScale shouldBe 1f

          val exported = exportFrame(fixture.composition, preview.presentationTime)
          if (exported.size != preview.size) {
            fail("The export is ${exported.size} at ${preview.presentationTime}, the preview ${preview.size}.")
          }

          assertFramesSimilar(
            expected = exported,
            actual = preview.asTestFrame(),
            minPsnrDb = minPsnrDb,
            minSsim = minSsim,
            message = "the preview and the export disagree at ${preview.presentationTime}",
          )
        }
      }
    }

  @Test
  fun aParameterChangeRedrawsWithoutRestartingPlayback() =
    contractTest { scope ->
      diff(fixture.composition, fixture.parameterChanged) shouldBe CompositionDiff.ParametersOnly

      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        engine.setQualityPolicy(PreviewQualityPolicy.Full)
        engine.awaitComposition(fixture.composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val probe = fixture.positions.first()
        engine.seekTo(probe, SeekAccuracy.Exact)
        awaitContract("the probe seek to land") { recorder.seekCompletions.isNotEmpty() }
        val before = engine.readback.awaitFrame(probe)

        engine.play()
        awaitContract("the playhead to advance past the probe") {
          recorder.lastState.isPlaying && (recorder.playhead ?: Duration.ZERO) > probe
        }

        val mark = recorder.mark()
        val playheadBefore = recorder.playhead ?: Duration.ZERO
        engine.awaitComposition(fixture.parameterChanged).shouldBeInstanceOf<SetCompositionResult.Success>()

        // A rebuild reinitialises the decoder, which shows up as a trip through Preparing and a
        // playhead dropped back to the start. Neither may happen for a change confined to parameters.
        recorder.statesSince(mark).forEach { it.status shouldBe PlaybackStatus.Ready }
        recorder.lastState.playWhenReady shouldBe true
        ((recorder.playhead ?: Duration.ZERO) >= playheadBefore) shouldBe true

        engine.pause()
        val after = engine.readback.awaitFrame(probe)
        assertFramesDiffer(
          expected = before.asTestFrame(),
          actual = after.asTestFrame(),
          message = "the parameter change reached no pixel at $probe",
        )
      }
    }
}

/**
 * Real media for the pixel suite, and the two compositions the parameter contract compares.
 *
 * @property composition The edit to preview and export. Its clips must be trimmed, so the suite has
 *   a duration to play through.
 * @property parameterChanged The same edit with one effect parameter moved, far enough that the
 *   difference survives [assertFramesDiffer]. The suite checks it really is a parameter-only change
 *   before trusting it.
 * @property positions Composition times to compare frames at. Pick frames with detail in them: a
 *   frame of flat black matches everything, including a broken preview.
 */
class PixelFixture(
  val composition: EditComposition,
  val parameterChanged: EditComposition,
  val positions: List<Duration>,
)

/**
 * The same pixels, in the form the comparison helpers take.
 */
internal fun ReadbackFrame.asTestFrame(): TestFrame = TestFrame(pixels, size)
