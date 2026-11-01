package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.test.isSimulator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Core Image's HLG decode of a mid-range fill bar on a kept grade, held to the range it reads on macOS and on the
 * simulator.
 *
 * The `appleHostTest` source set is shared by `macosArm64Test` and `iosSimulatorArm64Test`. AppleHdrFillTest holds the
 * bar's HLG signal on macOS.
 */
class AppleHdrFillCoreImageTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `core image reads an hlg mid-range fill bright by its measured range`() =
    runTest(timeout = TIMEOUT) {
      val off = filmstrip.exportedFrame(HdrTransfer.Hlg, MID_BLUE).lightOff(MID_BLUE)
      val drift = if (isSimulator()) IOS_SIMULATOR_CORE_IMAGE_HLG_DRIFT else MACOS_CORE_IMAGE_HLG_DRIFT

      assertTrue(off.all { it in drift }, "Core Image's HLG decode read the bar off by $off where $drift is measured")
    }

  private companion object {
    val TIMEOUT = 5.minutes

    // Core Image's own limit. Its HLG decode reads a saturated bar bright on every channel, and a white bar reads true.
    // The bar's codes are held to the shared signal on macOS, by AppleHdrFillTest. Measured 7.47% to 7.65% on macOS
    // and 6.58% to 7.25% on the iOS simulator. Pinned from both ends, so a change to the decode shows up whichever way
    // it moves.
    val MACOS_CORE_IMAGE_HLG_DRIFT = 0.073f..0.078f
    val IOS_SIMULATOR_CORE_IMAGE_HLG_DRIFT = 0.064f..0.074f
  }
}
