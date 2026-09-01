package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.brightnessDisplayGain
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
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
 * Skipped when the fixtures are absent, as in [AppleHdrTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleHdrBrightnessTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  // Two dimmed exports rather than one against an untouched reference, so both frames paid the
  // same encode and the ratio between them is the effect alone. Halving twice over is where a
  // display-gamma reading and a linear one are furthest apart.
  @Test
  fun `a factor moves light by the display gamma rather than linearly`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val half = exportedLight(source, HALF)
      val quarter = exportedLight(source, QUARTER)
      val expected = brightnessDisplayGain(QUARTER) / brightnessDisplayGain(HALF)

      half.litChannels().forEach { channel ->
        val ratio = quarter[channel] / half[channel]
        assertTrue(
          abs(ratio - expected) <= expected * LIGHT_DRIFT,
          "channel $channel moved by $ratio, expected $expected between ${half[channel]} and ${quarter[channel]}",
        )
        // Reading the factor as a bare multiply on light is the mistake this rules out. It would
        // have halved the light rather than quartered it.
        val linear = QUARTER / HALF
        assertTrue(abs(ratio - linear) > abs(ratio - expected), "a linear reading of the factor was as close")
      }
    }

  // The HLG arm. Core Image is display referred whatever the transfer function, so the same gain
  // serves both, and this is what says so rather than the KDoc claiming it.
  @Test
  fun `an HLG grade moves by the same gain a PQ one does`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture(HLG_CLIP) ?: return@runTest
      if (!encodesHdr()) return@runTest

      val half = exportedLight(source, HALF)
      val quarter = exportedLight(source, QUARTER)
      val expected = brightnessDisplayGain(QUARTER) / brightnessDisplayGain(HALF)

      half.litChannels().forEach { channel ->
        val ratio = quarter[channel] / half[channel]
        assertTrue(
          abs(ratio - expected) <= expected * LIGHT_DRIFT,
          "channel $channel moved by $ratio, expected $expected between ${half[channel]} and ${quarter[channel]}",
        )
      }
    }

  // Above 1f there is headroom rather than a ceiling. Where the light asked for is past what PQ can
  // carry it clips, and that is the format's limit rather than the effect's, so it is asserted as
  // one instead of pretending the two exports match there.
  @Test
  fun `brightening a kept grade adds light until the format runs out`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val lit = exportedLight(source, 1f)
      val brightened = exportedLight(source, BRIGHTER)
      val gain = brightnessDisplayGain(BRIGHTER)

      lit.litChannels().forEach { channel ->
        val expected = lit[channel] * gain
        if (expected <= PQ_PEAK_NITS * HEADROOM) {
          assertTrue(
            abs(brightened[channel] - expected) <= expected * LIGHT_DRIFT,
            "channel $channel read ${brightened[channel]} nits, expected $expected from ${lit[channel]} at full",
          )
        } else {
          assertTrue(
            brightened[channel] > lit[channel] && brightened[channel] >= PQ_PEAK_NITS * HEADROOM,
            "channel $channel asked for $expected nits and read ${brightened[channel]}, short of the format's own ceiling",
          )
        }
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

  private suspend fun encodesHdr(): Boolean =
    withContext(Dispatchers.Default) {
      (filmstrip.capabilities() as? CapabilitiesResult.Success)?.capabilities?.supportsHdrEncoding == true
    }

  private fun fixture(clip: String = CLIP): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$clip"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private companion object {
    val TIMEOUT = 5.minutes

    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val CLIP = "apple_export_hdr.mp4"
    const val HLG_CLIP = "apple_export_hdr_hlg.mp4"
    const val CENTRE = 0.5f
    const val HALF = 0.5f
    const val QUARTER = 0.25f
    const val BRIGHTER = 1.5f

    // What a real HEVC encode and a 4:2:0 chroma round trip leave on a reading, as a fraction of it.
    const val LIGHT_DRIFT = 0.12f

    // How close to PQ's peak a reading has to get to count as having hit the format's ceiling
    // rather than the effect's answer.
    const val HEADROOM = 0.9f

    const val FLOOR_NITS = 20f
    const val FLOOR_FRACTION = 0.3f
  }
}
