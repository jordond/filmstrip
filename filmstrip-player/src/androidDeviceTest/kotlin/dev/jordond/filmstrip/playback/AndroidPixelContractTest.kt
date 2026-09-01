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

    const val DIM = 0.4f
    const val BRIGHT = 1.4f

    // What a span that drew nothing would read as, which is the failure worth telling apart from a
    // photo that really rendered.
    val BLACK: Triple<Int, Int, Int> = Triple(0, 0, 0)
  }
}
