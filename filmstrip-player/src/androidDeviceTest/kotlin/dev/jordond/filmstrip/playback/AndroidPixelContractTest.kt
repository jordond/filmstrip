package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.playback.contract.PixelFixture
import dev.jordond.filmstrip.playback.contract.PlayerPixelContractTest
import dev.jordond.filmstrip.playback.internal.Media3PlayerEngine
import dev.jordond.filmstrip.playback.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.coroutines.CoroutineScope
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

  private companion object {
    const val MIN_SSIM = 0.985

    const val DIM = 0.4f
    const val BRIGHT = 1.4f
  }
}
