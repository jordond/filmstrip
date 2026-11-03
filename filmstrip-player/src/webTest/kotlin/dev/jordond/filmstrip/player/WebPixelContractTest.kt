package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.player.contract.PixelFixture
import dev.jordond.filmstrip.player.contract.PlayerPixelContractTest
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one test that catches a preview quietly diverging from its own export.
 *
 * The browser is where the claim is strongest, because both sides are literally the same WebGL
 * pass over the same decoded frames. What separates them is only the encode: the exported file is
 * read back through a decoder that never saw the encoder, so the comparison is one H.264 round trip
 * apart rather than pixel for pixel, which is what the thresholds below allow for and nothing more.
 */
class WebPixelContractTest : PlayerPixelContractTest() {
  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    BrowserPlayerEngine(
      parent = scope,
      planner = BrowserPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override val fixture: PixelFixture =
    PixelFixture(
      composition = webFixtureComposition(listOf(Brightness(DIM))),
      // Far enough apart that no rounding in either path could account for the difference, and a
      // change to a composition-level effect alone, which is what makes the diff parameters-only.
      parameterChanged = webFixtureComposition(listOf(Brightness(BRIGHT))),
      positions = PROBE_POSITIONS,
    )

  override suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame = webExportFrame(composition, position)

  // Only the structural threshold is relaxed, and only by what the encode costs. The pair measures
  // 46.9 dB on Chrome 153 and 44.6 dB on 151 and 152, so the noise floor is well inside the default
  // decibel bar and under the default structural one, which color bars through an encoder are hard on.
  override val minSsim: Double get() = encodedMinSsim()

  /**
   * A caption and a fading badge, read inside the fade.
   *
   * Both ends of a ramp agree whether the opacity is added or multiplied in, so the badge is read at a
   * quarter and three quarters of it. Where it should land comes from `frameAt` over the preview of the
   * same edit without the badge, and the whole frame, caption included, has to match the export.
   */
  @Test
  fun aCaptionAndAFadingBadgePreviewTheWayTheyExport() =
    contractTest { scope ->
      val caption = TextOverlay(CAPTION)
      val badge = ImageOverlay(BADGE, Corner.TopEnd, animation = fadeIn(RAMP))
      val composition = webFixtureComposition(listOf(caption, badge))
      val span = TimeRange(Duration.ZERO, CLIP_LENGTH)
      val positions =
        listOf(RAMP / 4, RAMP * 3 / 4).map {
          outputSlotTime(outputSlotOf(it, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
        }

      val drawn = readbacks(scope, composition, positions)
      val bare = readbacks(scope, webFixtureComposition(listOf(caption)), positions)

      for ((preview, under) in drawn.zip(bare)) {
        under.presentationTime shouldBe preview.presentationTime
        val at = preview.presentationTime
        val rect = badge.placedOn(preview.size, BADGE_SIZE).rectOn(preview.size)
        val opacity = withClue("the badge is hidden at $at") { badge.frameAt(at, span).shouldNotBeNull() }.opacity
        withClue("$at is outside the ramp, at $opacity") { (opacity > 0f && opacity < 1f).shouldBeTrue() }

        val previewed = preview.asTestFrame()
        val expected = under.asTestFrame().around(rect).towards(Rgb.of(BADGE_COLOR), opacity)
        assertReads(expected, previewed.around(rect), "the preview's badge at $at, $opacity opaque")

        val exported = exportFrame(composition, at)
        assertReads(
          previewed.around(rect),
          exported.around(rect),
          "the export's badge at $at",
          OverlayPixels.ENCODED_TOLERANCE,
        )
        assertFramesSimilar(
          expected = exported,
          actual = previewed,
          minPsnrDb = minPsnrDb,
          minSsim = minSsim,
          message = "the preview and the export disagree under the overlays at $at",
        )
      }
    }

  private suspend fun readbacks(
    scope: CoroutineScope,
    composition: EditComposition,
    positions: List<Duration>,
  ): List<ReadbackFrame> {
    val engine = createEngine(scope)
    var frames = emptyList<ReadbackFrame>()
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }
      frames = positions.map { engine.readback.awaitFrame(it) }
    }
    return frames
  }

  private companion object {
    const val DIM = 0.4f
    const val BRIGHT = 1.6f

    const val CAPTION = "Filmstrip"

    // A quarter and three quarters of it land on 300ms and 900ms, both inside the fixture's length.
    val RAMP = 1200.milliseconds
  }
}
