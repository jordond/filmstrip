package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
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
@OptIn(ExperimentalForeignApi::class)
class AppleHdrBrightnessTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  // The wrong readings each bound below has to catch, from the shared gains alone, so this runs without a fixture or
  // an HDR encoder.
  @Test
  fun `each brightness bound is under half the miss of its nearest wrong reading`() {
    val dimmed = brightnessDisplayGain(QUARTER) / brightnessDisplayGain(HALF)
    assertCatches(QUARTER / HALF, dimmed, PQ_DIMMING_DRIFT, "a linear reading of the factor on PQ")
    assertCatches(QUARTER / HALF, dimmed, HLG_DIMMING_DRIFT, "a linear reading of the factor on HLG")
    assertCatches(onHlgScene(dimmed), dimmed, HLG_DIMMING_DRIFT, "the gain applied to HLG scene light")

    val brightened = brightnessDisplayGain(BRIGHTER)
    assertCatches(BRIGHTER, brightened, BRIGHTENING_DRIFT, "a linear reading of the factor above one")
  }

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
    val half = exportedLight(source, HALF)
    val quarter = exportedLight(source, QUARTER)
    val expected = brightnessDisplayGain(QUARTER) / brightnessDisplayGain(HALF)

    half.litChannels().forEach { channel ->
      val ratio = quarter[channel] / half[channel]
      assertTrue(
        abs(ratio - expected) <= expected * drift,
        "channel $channel moved by $ratio, expected $expected between ${half[channel]} and ${quarter[channel]}",
      )
    }
  }

  /**
   * The centre pixel of a graded export, in cd/m2.
   *
   * The effect is carried at every factor, one at 1f included, so the reference frame went through
   * the same encoder as the frame under test rather than down the copy path.
   */
  private suspend fun exportedLight(
    source: String,
    factor: Float,
  ): List<Float> {
    val composition =
      compositionOf {
        clip(MediaSource.of(source))
        effects { brightness(factor) }
      }
    val spec = ExportSpec(targetHeight = 720, hdr = HdrMode.KeepHdr)
    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("refused: ${verdict.reasons.joinToString { it.message }}")
      }

    val finished = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.Temporary).toList() }.last()
    if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
    val path = assertIs<MediaSink.Path>(assertIs<ExportStatus.Success>(finished).output).path

    try {
      val frame = hdrFrameOf(path) ?: error("could not decode a frame of the export at $factor")
      return frame.nitsAt(CENTRE, CENTRE)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
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

  /**
   * The channels of a read that carry enough light to say anything.
   *
   * Measured against the brightest channel as well as against an absolute floor. A saturated colour
   * carries its dim channel through 4:2:0 chroma, where the slop is a fraction of the bright
   * channels rather than of the dim one, so a channel far below them is moved more by the encode
   * than by the effect.
   */
  private fun List<Float>.litChannels(): List<Int> {
    val floor = maxOf(FLOOR_NITS, (maxOrNull() ?: 0f) * FLOOR_FRACTION)

    return indices.filter { this[it] >= floor }.also {
      assertTrue(it.isNotEmpty(), "every channel of the reference frame read as black, so nothing was measured")
    }
  }

  private companion object {
    val TIMEOUT = 5.minutes

    const val CENTRE = 0.5f
    const val HALF = 0.5f
    const val QUARTER = 0.25f
    const val BRIGHTER = 1.5f

    // How far the ratio between two dimmed PQ exports strays, as a fraction of it. Measured 0.20% on macOS and 0.91%
    // on the iOS simulator. A linear reading of the factor misses by 130%.
    const val PQ_DIMMING_DRIFT = 0.02f

    // The same ratio on HLG. Measured 0.77% on macOS and 0.16% on the iOS simulator. The gain applied to scene light
    // misses by 26% and a linear reading of the factor by 130%.
    const val HLG_DIMMING_DRIFT = 0.02f

    // How far a brightened export strays from the one at full on a channel under the ceiling, as a fraction of it.
    // Green is the only channel the patch carries, since red clips and blue sits under the floor. Around 530 nits
    // brightened it measured 0.06% on macOS and 0.04% on the iOS simulator, with a code of PQ there worth about a
    // percent. A linear reading of the factor misses by 38%.
    const val BRIGHTENING_DRIFT = 0.02f

    // How close to PQ's peak a reading has to get to count as having hit the format's ceiling
    // rather than the effect's answer.
    const val HEADROOM = 0.9f

    const val FLOOR_NITS = 20f
    const val FLOOR_FRACTION = 0.3f
  }
}
