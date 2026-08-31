package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.playback.contract.PixelFixture
import dev.jordond.filmstrip.playback.contract.PlayerPixelContractTest
import dev.jordond.filmstrip.playback.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.playback.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

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
  // 46.9 dB and 0.990 on this fixture, so the noise floor is well inside the default decibel bar and
  // just under the default structural one, which colour bars through an encoder are hard on.
  override val minSsim: Double get() = MIN_SSIM

  private companion object {
    const val DIM = 0.4f
    const val BRIGHT = 1.6f

    const val MIN_SSIM = 0.985
  }
}
