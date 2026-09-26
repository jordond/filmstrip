package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.ffmpeg.ExportConstants.BLEND_RANGE_FLOOR
import dev.jordond.filmstrip.ffmpeg.ExportConstants.FRAME
import dev.jordond.filmstrip.ffmpeg.ExportConstants.RAMP
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.geometry.Corner
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Animated overlays, with fades, slides and scaling read back against what the shared sampler answers.
 */
class FfmpegOverlayAnimationTest {
  private val fixture = FfmpegExportFixture()

  // Read at a quarter and at three quarters of the ramp. The ends agree under both an additive and
  // a multiplicative reading, so a test that only read them would pass while the middle was wrong.
  @Test
  fun `fades a watermark by the ratio the sampler answers`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val solid = fixture.watermark(corner = Corner.BottomEnd)
      val fading = fixture.watermark(corner = Corner.BottomEnd, animation = fadeIn(RAMP))
      val bare = fixture.exportedWatermark("filmstrip-overlay-fade-bare", null)
      val opaque = fixture.exportedWatermark("filmstrip-overlay-fade-solid", solid)
      val faded = fixture.exportedWatermark("filmstrip-overlay-fade", fading)
      val size = faded.plan.output.size
      val span = TimeRange.of(Duration.ZERO, faded.plan.duration)
      val probe = centreOf(fading.placedOn(size, OVERLAY_SIZE).rectOn(size), size)

      listOf(RAMP / 4, RAMP * 3 / 4).forEach { at ->
        val expected = assertNotNull(fading.frameAt(at, span)).opacity
        val measured =
          blendAlpha(
            regionAverage(bare.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
            regionAverage(opaque.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
            regionAverage(faded.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
          )
        assertTrue(
          abs(measured - expected) < ALPHA_TOLERANCE,
          "at $at the file blended at $measured where the sampler answers $expected",
        )
      }

      // An aux branch a frame short drops the overlay from where it runs out, silently, so every
      // overlay test reads the last frame of the run as well as the middle of it.
      val last = faded.plan.duration - FRAME
      assertTrue(drawnAt(bare, faded, probe, last), "the watermark is gone by $last")

      listOf(bare, opaque, faded).forEach { it.file.delete() }
    }

  // Where the overlay lands is taken from the shared placement rather than from a number this test
  // writes down, and the two readings are far enough apart that neither rectangle covers the other's
  // probe.
  @Test
  fun `slides a watermark to where the shared placement puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val sliding = fixture.watermark(animation = slideIn(OverlayOffset(SLIDE_FRACTION, 0f), RAMP))
      val bare = fixture.exportedWatermark("filmstrip-overlay-slide-bare", null)
      val slid = fixture.exportedWatermark("filmstrip-overlay-slide", sliding)
      val size = slid.plan.output.size
      val span = TimeRange.of(Duration.ZERO, slid.plan.duration)
      val quarter = RAMP / 4
      val threeQuarters = RAMP * 3 / 4
      val early = drawnProbe(sliding, size, span, quarter)
      val late = drawnProbe(sliding, size, span, threeQuarters)

      assertTrue(drawnAt(bare, slid, early, quarter), "the watermark is not where the sampler puts it at $quarter")
      assertTrue(drawnAt(bare, slid, late, threeQuarters), "the watermark is not there at $threeQuarters")
      assertTrue(!drawnAt(bare, slid, late, quarter), "the watermark has already arrived at $quarter")
      assertTrue(!drawnAt(bare, slid, early, threeQuarters), "the watermark has not moved on by $threeQuarters")

      val last = slid.plan.duration - FRAME
      assertTrue(drawnAt(bare, slid, drawnProbe(sliding, size, span, last), last), "the watermark is gone by $last")

      listOf(bare, slid).forEach { it.file.delete() }
    }

  // Both dimensions grow, which the probe between the two rectangles' far corners reads: it is
  // outside the early one on each axis and inside the late one on both.
  //
  // This is a composition overlay, so the fill is deferred and the frames it merges onto carry
  // alpha. That is the case the aux chain's trailing `scale=iw:ih:eval=frame` exists for: without
  // it the converter ffmpeg inserts to bring both inputs to one pixel format holds the overlay at
  // the size it was configured with, and this reads 128 wide at both points rather than growing.
  @Test
  fun `grows a watermark by the factor the sampler answers`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val growing = fixture.watermark(animation = GROW)
      val bare = fixture.exportedWatermark("filmstrip-overlay-scale-bare", null)
      val grown = fixture.exportedWatermark("filmstrip-overlay-scale", growing)
      val size = grown.plan.output.size
      val span = TimeRange.of(Duration.ZERO, grown.plan.duration)
      val quarter = RAMP / 4
      val threeQuarters = RAMP * 3 / 4
      val early = drawnRect(growing, size, span, quarter)
      val late = drawnRect(growing, size, span, threeQuarters)
      val inside = centreOf(early, size)
      val onlyLate = pointIn((early.right + late.right) / 2, (early.bottom + late.bottom) / 2, size)

      assertTrue(drawnAt(bare, grown, inside, quarter), "the watermark is missing at $quarter")
      assertTrue(drawnAt(bare, grown, inside, threeQuarters), "the watermark is missing at $threeQuarters")
      assertTrue(!drawnAt(bare, grown, onlyLate, quarter), "the watermark has already grown at $quarter")
      assertTrue(drawnAt(bare, grown, onlyLate, threeQuarters), "the watermark has not grown by $threeQuarters")

      val last = grown.plan.duration - FRAME
      assertTrue(drawnAt(bare, grown, inside, last), "the watermark is gone by $last")

      listOf(bare, grown).forEach { it.file.delete() }
    }

  // The sidecar's clock is the clip's own, so an animation on the second of two clips is read
  // against the time the clip has been running rather than against the composition's.
  @Test
  fun `fades a clip's watermark on the clip's own clock`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val fading = fixture.watermark(animation = fadeIn(RAMP))
      val bare = fixture.exportedWatermark("filmstrip-overlay-clipfade-bare", null, clips = 2, onClip = true)
      val opaque =
        fixture.exportedWatermark("filmstrip-overlay-clipfade-solid", fixture.watermark(), clips = 2, onClip = true)
      val faded = fixture.exportedWatermark("filmstrip-overlay-clipfade", fading, clips = 2, onClip = true)
      val size = faded.plan.output.size
      val slot = TimeRange.of(faded.plan.duration / 2, faded.plan.duration)
      val probe = centreOf(fading.placedOn(size, OVERLAY_SIZE).rectOn(size), size)

      faded.plan.duration shouldBe 4.seconds
      listOf(slot.start + RAMP / 4, slot.start + RAMP * 3 / 4).forEach { at ->
        val expected = assertNotNull(fading.frameAt(at, slot)).opacity
        val measured =
          blendAlpha(
            regionAverage(bare.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
            regionAverage(opaque.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
            regionAverage(faded.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
          )
        assertTrue(
          abs(measured - expected) < ALPHA_TOLERANCE,
          "at $at the file blended at $measured where the sampler answers $expected",
        )
      }

      val last = faded.plan.duration - FRAME
      assertTrue(drawnAt(bare, faded, probe, last), "the watermark is gone by $last")

      listOf(bare, opaque, faded).forEach { it.file.delete() }
    }

  /**
   * How much of the watermark the blended frame carries, solved from the same probe read out of
   * three exports: one with no overlay, one with an opaque overlay, and the animated one.
   *
   * Every channel contributes, weighted by how far apart the two references are on it, so a probe
   * where the watermark happens to match the frame under it on one channel is carried by the other
   * two rather than dividing by nothing.
   */
  private fun blendAlpha(
    bare: Triple<Double, Double, Double>,
    opaque: Triple<Double, Double, Double>,
    blended: Triple<Double, Double, Double>,
  ): Float {
    val under = bare.toList()
    val over = opaque.toList()
    val measured = blended.toList()
    var numerator = 0.0
    var denominator = 0.0
    over.forEachIndexed { channel, value ->
      val range = value - under[channel]
      numerator += (measured[channel] - under[channel]) * range
      denominator += range * range
    }
    assertTrue(denominator > BLEND_RANGE_FLOOR, "the watermark reads the same as the frame under it")

    return (numerator / denominator).toFloat()
  }

  private companion object {
    // Far enough that the rectangle at a quarter of the slide and the one at three quarters do not
    // overlap, so each reading's probe is bare in the other.
    const val SLIDE_FRACTION = 0.3f

    // Doubles the overlay over the ramp. There is no built-in scale helper, and holding the one
    // instance is what keeps the export and the expectation reading the same animation.
    val GROW =
      OverlayAnimation { time ->
        OverlayFrame(scale = 1f + (time.elapsed / RAMP).toFloat().coerceIn(0f, 1f))
      }

    // A little over what the H264 round trip moves a flat region by, read as a fraction of the
    // range between the bare frame and the opaque overlay.
    const val ALPHA_TOLERANCE = 0.1f
  }
}
