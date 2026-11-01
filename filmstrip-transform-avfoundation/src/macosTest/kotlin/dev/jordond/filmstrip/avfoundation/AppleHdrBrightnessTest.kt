package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.brightnessDisplayGain
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What a brightness factor does to a frame that keeps its grade, measured in light.
 *
 * Core Image holds an HDR frame as display referred linear light, so the figure under test is the
 * ratio the effect moved that light by. It comes from [brightnessDisplayGain] rather than from a
 * measurement taken once, which is what keeps this backend and the other three on one answer.
 *
 * A case fails when its fixture is missing, as in [AppleExportTest].
 */
class AppleHdrBrightnessTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  // Two dimmed exports rather than one against an untouched reference, so both frames paid the
  // same encode and the ratio between them is the effect alone. Halving twice over is where a
  // display-gamma reading and a linear one are furthest apart.
  @Test
  fun `a factor moves light by the display gamma rather than linearly`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)
      assertDimmed(source, PQ_DIMMING_DRIFT)
    }

  // The HLG arm. Core Image is display referred whatever the transfer function, so the same gain
  // serves both, and this is what says so rather than the KDoc claiming it.
  @Test
  fun `an HLG grade moves by the same gain a PQ one does`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Hlg)
      assertDimmed(source, HLG_DIMMING_DRIFT)
    }

  // Above 1f there is headroom rather than a ceiling. The patch's red asks for more light than PQ
  // can carry and clips, which is the format's limit rather than the effect's, so it is asserted as
  // one. The channels that stay under the ceiling are held to the gain.
  @Test
  fun `brightening a kept grade adds light until the format runs out`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)
      val lit = exportedLight(source, 1f)
      val brightened = exportedLight(source, BRIGHTER)
      val gain = brightnessDisplayGain(BRIGHTER)
      val ceiling = PQ_PEAK_NITS * HEADROOM
      val (clipped, carried) = lit.indices.filter { lit[it] >= FLOOR_NITS }.partition { lit[it] * gain > ceiling }
      assertTrue(
        carried.isNotEmpty(),
        "every channel of $lit runs past the ceiling at $BRIGHTER, so no gain was read",
      )
      assertTrue(clipped.isNotEmpty(), "no channel of $lit reaches the ceiling at $BRIGHTER, so no clip was read")

      carried.forEach { channel ->
        val expected = lit[channel] * gain
        assertTrue(
          abs(brightened[channel] - expected) <= expected * BRIGHTENING_DRIFT,
          "channel $channel read ${brightened[channel]} nits, expected $expected from ${lit[channel]} at full",
        )
      }
      clipped.forEach { channel ->
        assertTrue(
          brightened[channel] > lit[channel] && brightened[channel] >= ceiling,
          "channel $channel asked for ${lit[channel] * gain} nits and read ${brightened[channel]}, short of the " +
            "format's own ceiling",
        )
      }
    }

  /**
   * Holds the ratio between a quarter's and a half's export of [source] to [brightnessDisplayGain] on every lit
   * channel, within [drift] as a fraction of it.
   */
  private suspend fun assertDimmed(
    source: String,
    drift: Float,
  ) {
    val half = exportedLight(source, DIM_HALF)
    val quarter = exportedLight(source, DIM_QUARTER)
    val expected = brightnessDisplayGain(DIM_QUARTER) / brightnessDisplayGain(DIM_HALF)

    half.litChannels().forEach { channel ->
      val ratio = quarter[channel] / half[channel]
      assertTrue(
        abs(ratio - expected) <= expected * drift,
        "channel $channel moved by $ratio, expected $expected between ${half[channel]} and ${quarter[channel]}",
      )
    }
  }

  // The effect is carried at every factor, one at 1f included, so the reference frame went through the same encoder
  // as the frame under test rather than down the copy path.
  private suspend fun exportedLight(
    source: String,
    factor: Float,
  ): List<Float> = filmstrip.exportedGradeLight(source, at = "$factor") { brightness(factor) }

  private companion object {
    val TIMEOUT = 5.minutes

    // How close to PQ's peak a reading has to get to count as having hit the format's ceiling
    // rather than the effect's answer.
    const val HEADROOM = 0.9f
  }
}
