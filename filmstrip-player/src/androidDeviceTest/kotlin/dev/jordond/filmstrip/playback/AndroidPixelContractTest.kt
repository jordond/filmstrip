package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.playback.contract.PixelFixture
import dev.jordond.filmstrip.playback.contract.PlayerPixelContractTest
import dev.jordond.filmstrip.playback.contract.asTestFrame
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.awaitFrame
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.Media3PlayerEngine
import dev.jordond.filmstrip.playback.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.time.Duration

/**
 * The one test that catches a preview quietly diverging from its own export.
 *
 * Both sides lower the same edit through the same `toMedia3`: the preview keeps the chain behind
 * swappable slots and renders it through `FrameExtractor`, the export hands the same chain to
 * `Transformer` and writes a file. A difference in how the preview builds or attaches the graph
 * shows up as pixels that no longer match.
 *
 * Unlike the Apple suite there is no seam between the composition and the encoder here, so the
 * frames being compared are one encode apart. The thresholds are set for that and no wider: see the
 * numbers below.
 */
class AndroidPixelContractTest : PlayerPixelContractTest() {
  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    Media3PlayerEngine(
      parent = scope,
      context = contractContext(),
      planner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override val fixture: PixelFixture =
    PixelFixture(
      composition = androidFixtureComposition(listOf(Brightness(DIM))),
      // Far enough apart that no rounding in either path could account for the difference, and a
      // change to a composition-level effect alone, which is what makes the diff parameters-only.
      parameterChanged = androidFixtureComposition(listOf(Brightness(BRIGHT))),
      positions = PROBE_POSITIONS,
    )

  override suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame = androidExportFrame(composition, position)

  override val minSsim: Double get() = MIN_SSIM

  /**
   * The frame a photo draws is the one the file carries, which `FrameExtractor` cannot answer for.
   *
   * The probe sits inside the photo's span rather than at either end of it, since a reader that
   * picked its path once per composition rather than once per span would still be right at a
   * boundary.
   */
  @Test
  fun aPreviewInsideAPhotosSpanMatchesTheExport() =
    contractTest { scope ->
      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        val composition = androidPhotoComposition(listOf(Brightness(DIM)))
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val preview = engine.readback.awaitFrame(PHOTO_PROBE)
        val frame = preview.asTestFrame()

        // The photo itself, not only agreement with the export, since two lowerings both drawing a
        // blank frame would agree with each other and be wrong together. And not the raw picture
        // either: the chain dims it, so a preview that ran no effects comes back at full strength.
        frame.centre() shouldBeNothingLike BLACK
        frame.centre() shouldBeNothingLike PHOTO_COLOR

        assertFramesSimilar(
          expected = androidExportFrame(composition, preview.presentationTime),
          actual = frame,
          minSsim = MIN_SSIM,
          message = "the preview and the export disagree inside the photo at ${preview.presentationTime}",
        )
      }
    }

  /**
   * A pan over a photo, previewed against the export at two readings inside its span.
   *
   * This is the reading ADR-style parity actually turns on for a time-varying effect. The preview
   * decodes the picture and pushes it through the chain itself, while the export runs the same
   * chain inside the transformer, so the two only agree when both hand that chain the same
   * composition time. Measured at 40% and 60% through the span, since a pan that stood still would
   * still match at either end of it.
   */
  @Test
  fun aPanOverAPhotoPreviewsTheWayItExports() =
    contractTest { scope ->
      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        engine.setQualityPolicy(PreviewQualityPolicy.Full)
        val composition = androidPannedPhotoComposition()
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val exported = mutableListOf<TestFrame>()
        val drawn =
          PAN_FRACTIONS.map { fraction ->
            val preview = engine.readback.awaitFrame(PHOTO_START + PHOTO_LENGTH * fraction)
            val frame = preview.asTestFrame()
            val export = androidExportFrame(composition, preview.presentationTime)
            exported += export

            assertFramesSimilar(
              expected = export,
              actual = frame,
              minPsnrDb = PAN_MIN_PSNR_DB,
              minSsim = MIN_SSIM,
              message = "the preview and the export disagree $fraction through the pan",
            )
            frame
          }

        // Two readings of a pan that never moved would match each other and match the export at
        // both, which is the way this passes while drawing the wrong thing.
        assertFramesDiffer(
          expected = drawn.first(),
          actual = drawn.last(),
          message = "the pan drew the same frame at ${PAN_FRACTIONS.first()} and ${PAN_FRACTIONS.last()}",
        )
        // And the floor above is not so low that a frame a fifth of the travel away clears it.
        assertFramesDiffer(
          expected = exported.last(),
          actual = drawn.first(),
          minPsnrDb = PAN_MIN_PSNR_DB,
          minSsim = MIN_SSIM,
          message = "the reading at ${PAN_FRACTIONS.first()} matched the export at ${PAN_FRACTIONS.last()}",
        )
      }
    }

  /**
   * A run of video, photo, video, read back in all three of its spans.
   *
   * One path serves the outer two and another serves the middle, so this is what catches a reader
   * that chose between them once rather than per span.
   */
  @Test
  fun aPreviewOfAVideoPhotoVideoEditDrawsEverySpan() =
    contractTest { scope ->
      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        val composition = androidSandwichComposition()
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val opening = engine.readback.awaitFrame(MID_GOP_POSITION).asTestFrame()
        val photo = engine.readback.awaitFrame(PHOTO_PROBE).asTestFrame()
        val closing = engine.readback.awaitFrame(PHOTO_START + PHOTO_LENGTH + MID_GOP_POSITION).asTestFrame()

        photo.centre() shouldBeCloseTo PHOTO_COLOR
        opening.centre() shouldBeNothingLike PHOTO_COLOR
        closing.centre() shouldBeNothingLike PHOTO_COLOR

        assertFramesSimilar(
          expected = opening,
          actual = closing,
          minSsim = MIN_SSIM,
          message = "the two video spans of one repeated clip drew different frames",
        )
      }
    }

  /**
   * An edit that is nothing but a photo, which has no video clip for a reader to fall back on.
   */
  @Test
  fun aPhotoOnItsOwnPreviewsAndMatchesItsExport() =
    contractTest { scope ->
      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        val composition = androidPhotoOnlyComposition(listOf(Brightness(DIM)))
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val preview = engine.readback.awaitFrame(PHOTO_LENGTH * PHOTO_FRACTION)
        val frame = preview.asTestFrame()

        frame.size shouldBe FIXTURE_FRAME
        frame.centre() shouldBeNothingLike BLACK
        frame.centre() shouldBeNothingLike PHOTO_COLOR

        assertFramesSimilar(
          expected = androidExportFrame(composition, preview.presentationTime),
          actual = frame,
          minSsim = MIN_SSIM,
          message = "a photo-only preview and its export disagree at ${preview.presentationTime}",
        )
      }
    }

  private companion object {
    const val MIN_SSIM = 0.985

    // The split photo carries a hard colour edge, which is the one thing an encoder rings on and
    // which none of the flat fixtures have. An unbroken pair scores 38.1 dB and 0.997 across it,
    // against 40 dB for a flat one, so the floor comes down for this fixture and no other. A
    // reading a fifth of the travel away from its export is asserted to fall below it.
    const val PAN_MIN_PSNR_DB = 34.0

    const val DIM = 0.4f
    const val BRIGHT = 1.4f

    // What a span that drew nothing would read as, which is the failure worth telling apart from a
    // photo that really rendered.
    val BLACK: Triple<Int, Int, Int> = Triple(0, 0, 0)
  }
}
