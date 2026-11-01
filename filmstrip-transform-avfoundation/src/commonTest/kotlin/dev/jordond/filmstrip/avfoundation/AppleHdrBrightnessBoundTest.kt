package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks each bound AppleHdrBrightnessTest holds an HDR brightness read to still catches the wrong readings it is
 * there for.
 *
 * Works from the shared gains alone, so it runs on every Apple target without a fixture or an HDR encoder.
 */
class AppleHdrBrightnessBoundTest {
  @Test
  fun `each brightness bound is under half the miss of its nearest wrong reading`() {
    val dimmed = brightnessDisplayGain(DIM_QUARTER) / brightnessDisplayGain(DIM_HALF)
    assertCatches(DIM_QUARTER / DIM_HALF, dimmed, PQ_DIMMING_DRIFT, "a linear reading of the factor on PQ")
    assertCatches(DIM_QUARTER / DIM_HALF, dimmed, HLG_DIMMING_DRIFT, "a linear reading of the factor on HLG")
    assertCatches(onHlgScene(dimmed), dimmed, HLG_DIMMING_DRIFT, "the gain applied to HLG scene light")

    val brightened = brightnessDisplayGain(BRIGHTER)
    assertCatches(BRIGHTER, brightened, BRIGHTENING_DRIFT, "a linear reading of the factor above one")
  }

  /**
   * Checks the [wrong] reading of a grade lands more than twice [drift] off [expected], so a read held to [drift] fails
   * on it.
   */
  private fun assertCatches(
    wrong: Float,
    expected: Float,
    drift: Float,
    what: String,
  ) {
    val miss = abs(wrong - expected) / expected
    assertTrue(miss > 2 * drift, "$what lands $miss off $expected, inside twice the $drift bound that should catch it")
  }

  // A display light gain applied to HLG scene light instead, which the system gamma raises once more on the way to the
  // display.
  private fun onHlgScene(gain: Float): Float = hlgDisplayNitsFromScene(gain) / HLG_NOMINAL_PEAK_NITS
}

internal const val DIM_HALF = 0.5f
internal const val DIM_QUARTER = 0.25f
internal const val BRIGHTER = 1.5f

// How far the ratio between two dimmed PQ exports strays, as a fraction of it. Held on macOS, where it measured 0.20%,
// against 0.91% on the iOS simulator. A linear reading of the factor misses by 130%.
internal const val PQ_DIMMING_DRIFT = 0.02f

// The same ratio on HLG. Held on macOS, where it measured 0.77%, against 0.16% on the iOS simulator. The gain applied
// to scene light misses by 26% and a linear reading of the factor by 130%.
internal const val HLG_DIMMING_DRIFT = 0.02f

// How far a brightened export strays from the one at full on a channel under the ceiling, as a fraction of it. Green
// is the only channel the patch carries, since red clips and blue sits under the floor. Held on macOS, where around
// 530 nits brightened it measured 0.06%, against 0.04% on the iOS simulator, with a code of PQ there worth about a
// percent. A linear reading of the factor misses by 38%.
internal const val BRIGHTENING_DRIFT = 0.02f
