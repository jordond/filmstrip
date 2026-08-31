package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.playback.contract.PixelFixture
import dev.jordond.filmstrip.playback.contract.PlayerPixelContractTest
import dev.jordond.filmstrip.playback.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.playback.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * The one test that catches a preview quietly diverging from its own export.
 *
 * On this backend the two are one `-filter_complex` string, built by one lowering and handed to two
 * ffmpeg invocations that differ only in where they start and where the frames go. What this
 * compares is therefore the seam either side of that string: the window the pump opens, and the
 * encode the export runs afterwards.
 */
class JvmPixelContractTest : PlayerPixelContractTest() {
  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    FfmpegPlayerEngine(
      parent = scope,
      planner = FfmpegPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override val fixture: PixelFixture =
    PixelFixture(
      composition = jvmFixtureComposition(listOf(Brightness(DIM))),
      // Far enough apart that no rounding in either path could account for the difference, and a
      // change to a composition-level effect alone, which is what makes the diff parameters-only.
      parameterChanged = jvmFixtureComposition(listOf(Brightness(BRIGHT))),
      positions = PROBE_POSITIONS,
    )

  override suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame = jvmExportFrame(composition, position)

  /**
   * Below what two renderings of one graph score, because the export side of this comparison has
   * been through libx264 and back and the preview side has not.
   *
   * Loosened for that encode and for nothing else. A tighter bound would be failing the encoder
   * rather than the preview.
   */
  override val minPsnrDb: Double get() = ENCODED_MIN_PSNR_DB

  override val minSsim: Double get() = ENCODED_MIN_SSIM

  private companion object {
    const val DIM = 0.4f
    const val BRIGHT = 1.4f

    // The pair measures 46.6 dB and 0.992 at 300ms and 45.5 dB and 0.991 at 900ms, so these leave a
    // few dB of headroom for a slower machine's encoder settling somewhere else.
    const val ENCODED_MIN_PSNR_DB = 42.0
    const val ENCODED_MIN_SSIM = 0.985
  }
}
