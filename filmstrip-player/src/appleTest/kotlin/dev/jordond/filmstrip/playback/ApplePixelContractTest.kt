package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.Text
import dev.jordond.filmstrip.playback.contract.PixelFixture
import dev.jordond.filmstrip.playback.contract.PlayerPixelContractTest
import dev.jordond.filmstrip.playback.contract.asTestFrame
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.awaitFrame
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.AvPlayerEngine
import dev.jordond.filmstrip.playback.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.time.Duration

/**
 * The one test that catches a preview quietly diverging from its own export.
 *
 * Apple is where it is cheapest to prove: the preview and the export are two lowerings of the same
 * edit through the same `toAvComposition`, each carrying its own `CoreImageChain` over its own
 * `AVMutableComposition`, so a difference in how the preview builds or attaches the graph shows up
 * as pixels that no longer match.
 */
class ApplePixelContractTest : PlayerPixelContractTest() {
  init {
    pumpMainRunLoopDuringContracts()
  }

  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    AvPlayerEngine(
      parent = scope,
      planner = AvPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override val fixture: PixelFixture =
    PixelFixture(
      composition = appleFixtureComposition(listOf(Brightness(DIM))),
      // Far enough apart that no rounding in either path could account for the difference, and a
      // change to a composition-level effect alone, which is what makes the diff parameters-only.
      parameterChanged = appleFixtureComposition(listOf(Brightness(BRIGHT))),
      positions = PROBE_POSITIONS,
    )

  override suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame = appleExportFrame(composition, position)

  /**
   * The readback under a cap, against the export scaled to the same frame.
   *
   * Everything else the cap touches is meant to follow it down. Text is not: the caption is laid
   * out against the frame the export writes and only the raster is brought down, so the two break
   * their lines on the same words and the plate lands on the same place in the frame. Laid out
   * against the preview's own width instead, this caption takes a different number of lines, which
   * moves the plate and drops the metrics well below these thresholds.
   */
  @Test
  fun `a capped preview breaks its lines where the export does`() =
    contractTest { scope ->
      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        val composition = appleFixtureComposition(listOf(Text(CAPTION, CAPTION_STYLE)))
        engine.setQualityPolicy(PreviewQualityPolicy.CapHeight(CAP_HEIGHT))
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val preview = engine.readback.awaitFrame(PROBE_POSITIONS.first())
        preview.size.height shouldBe CAP_HEIGHT
        preview.renderScale shouldBe CAP_FRACTION

        val exported = appleExportFrame(composition, preview.presentationTime)
        exported.size shouldBe FIXTURE_FRAME

        assertFramesSimilar(
          expected = exported.scaledTo(preview.size),
          actual = preview.asTestFrame(),
          minPsnrDb = CAPTION_MIN_PSNR_DB,
          minSsim = CAPTION_MIN_SSIM,
          message = "the capped preview wrapped the caption differently to the export",
        )
      }
    }

  /**
   * The preview inside a photo's span.
   *
   * A still holds no track of its own and takes its slot from a generated segment, so a player
   * showing the edit has to draw the photo over that slot the way the export's reader does.
   * Measured at a time inside the span rather than at either edge of it.
   */
  @Test
  fun `a preview inside a photo's span matches the export`() =
    contractTest { scope ->
      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        val composition = applePhotoComposition()
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val preview = engine.readback.awaitFrame(CLIP_LENGTH + PHOTO_LENGTH * PHOTO_FRACTION)
        val frame = preview.asTestFrame()

        // The photo itself, not only agreement with the export. Both lowerings drawing the seed's
        // own black frame would agree with each other and be wrong together.
        frame.centre() shouldBeCloseTo PHOTO_COLOR

        assertFramesSimilar(
          expected = appleExportFrame(composition, preview.presentationTime),
          actual = frame,
          message = "the preview and the export disagree inside the photo at ${preview.presentationTime}",
        )
      }
    }

  private companion object {
    const val DIM = 0.4f
    const val BRIGHT = 1.4f

    // Below what an unbroken pair scores, because the preview resamples the picture on its way down
    // and this suite resamples the export with a filter of its own: measured at 31.9 dB and 0.988.
    // Well above a caption wrapped onto a different number of lines, measured at 14.8 dB and 0.842.
    const val CAPTION_MIN_PSNR_DB = 25.0
    const val CAPTION_MIN_SSIM = 0.96
  }
}
