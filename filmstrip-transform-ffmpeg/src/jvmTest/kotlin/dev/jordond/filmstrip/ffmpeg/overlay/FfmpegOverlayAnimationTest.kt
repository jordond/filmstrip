package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.ffmpeg.internal.lowering.formatSeconds
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.BLEND_RANGE_FLOOR
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.FRAME
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.RAMP
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.drawnAt
import dev.jordond.filmstrip.ffmpeg.support.drawnProbe
import dev.jordond.filmstrip.ffmpeg.support.drawnRect
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.pointIn
import dev.jordond.filmstrip.ffmpeg.support.regionAverage
import dev.jordond.filmstrip.geometry.Corner
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

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
      val fading = fixture.watermark(corner = Corner.BottomEnd, animation = fade(inDuration = RAMP))
      val bare = fixture.exportedWatermark("filmstrip-overlay-fade-bare", null)
      val opaque = fixture.exportedWatermark("filmstrip-overlay-fade-solid", solid)
      val faded = fixture.exportedWatermark("filmstrip-overlay-fade", fading)
      val size = faded.plan.output.size
      val span = TimeRange.of(Duration.ZERO, faded.plan.duration)
      val probe = centreOf(fading.placedOn(size, OVERLAY_SIZE).rectOn(size), size)

      listOf(RAMP / 4, RAMP * 3 / 4).forEach { at ->
        val expected = fading.frameAt(at, span).shouldNotBeNull().opacity
        val measured =
          blendAlpha(
            regionAverage(bare.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
            regionAverage(opaque.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
            regionAverage(faded.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
          )
        withClue("at $at the file blended at $measured where the sampler answers $expected") {
          (abs(measured - expected) < ALPHA_TOLERANCE).shouldBeTrue()
        }
      }

      // An aux branch a frame short drops the overlay from where it runs out, silently, so every
      // overlay test reads the last frame of the run as well as the middle of it.
      val last = faded.plan.duration - FRAME
      withClue("the watermark is gone by $last") { drawnAt(bare, faded, probe, last).shouldBeTrue() }

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

      withClue("the watermark is not where the sampler puts it at $quarter") {
        drawnAt(bare, slid, early, quarter).shouldBeTrue()
      }
      withClue("the watermark is not there at $threeQuarters") {
        drawnAt(bare, slid, late, threeQuarters).shouldBeTrue()
      }
      withClue("the watermark has already arrived at $quarter") { (!drawnAt(bare, slid, late, quarter)).shouldBeTrue() }
      withClue("the watermark has not moved on by $threeQuarters") {
        (!drawnAt(bare, slid, early, threeQuarters)).shouldBeTrue()
      }

      val last = slid.plan.duration - FRAME
      withClue("the watermark is gone by $last") {
        drawnAt(bare, slid, drawnProbe(sliding, size, span, last), last).shouldBeTrue()
      }

      listOf(bare, slid).forEach { it.file.delete() }
    }

  // The animation holds one offset, so every frame is commanded to the same odd x and the read does not hang on which
  // frame a seek opens. An 8-bit export draws the watermark in 4:2:0, where ffmpeg's overlay puts a commanded odd x on
  // the even pixel below it. Luma is read rather than RGB, since the written chroma of the column before the edge is
  // shared with it.
  @Test
  fun `moves a watermark commanded to an odd pixel onto the even pixel below it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val bare = fixture.exportedWatermark("filmstrip-overlay-odd-bare", null)
      val size = bare.plan.output.size
      val span = TimeRange.of(Duration.ZERO, bare.plan.duration)
      val authored = drawnRect(fixture.watermark(), size, span, Duration.ZERO).left * size.width
      val target = authored.toInt() + ODD_SHIFT + (authored.toInt() + ODD_SHIFT + 1) % 2
      val shift = OverlayOffset((target - authored) / size.width, 0f)
      val held = fixture.watermark(animation = OverlayAnimation { OverlayFrame(offset = shift) })
      val moved = fixture.exportedWatermark("filmstrip-overlay-odd", held)
      val at = bare.plan.duration / 2
      val rect = drawnRect(held, size, span, at)
      val x = (rect.left * size.width).roundToInt()
      val y = ((rect.top + rect.height / 2) * size.height).roundToInt()

      fun lift(column: Int): Int =
        lumaAt(moved.file.absolutePath, column, y, at) - lumaAt(bare.file.absolutePath, column, y, at)

      withClue("the sampler puts the watermark at x=$x") { (x % 2 == 1).shouldBeTrue() }
      withClue("commanded to x=$x, the column before it lifts by ${lift(x - 1)}") {
        (abs(lift(x - 1)) > EDGE_LIFT).shouldBeTrue()
      }
      withClue("commanded to x=$x, the column two before it lifts by ${lift(x - 2)}") {
        (abs(lift(x - 2)) < EDGE_LIFT).shouldBeTrue()
      }

      listOf(bare, moved).forEach { it.file.delete() }
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

      withClue("the watermark is missing at $quarter") { drawnAt(bare, grown, inside, quarter).shouldBeTrue() }
      withClue("the watermark is missing at $threeQuarters") {
        drawnAt(bare, grown, inside, threeQuarters).shouldBeTrue()
      }
      withClue("the watermark has already grown at $quarter") {
        (!drawnAt(bare, grown, onlyLate, quarter)).shouldBeTrue()
      }
      withClue("the watermark has not grown by $threeQuarters") {
        drawnAt(bare, grown, onlyLate, threeQuarters).shouldBeTrue()
      }

      val last = grown.plan.duration - FRAME
      withClue("the watermark is gone by $last") { drawnAt(bare, grown, inside, last).shouldBeTrue() }

      listOf(bare, grown).forEach { it.file.delete() }
    }

  // The sidecar's clock is the clip's own, so an animation on the second of two clips is read
  // against the time the clip has been running rather than against the composition's.
  @Test
  fun `fades a clip's watermark on the clip's own clock`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val fading = fixture.watermark(animation = fade(inDuration = RAMP))
      val bare = fixture.exportedWatermark("filmstrip-overlay-clipfade-bare", null, clips = 2, onClip = true)
      val opaque =
        fixture.exportedWatermark("filmstrip-overlay-clipfade-solid", fixture.watermark(), clips = 2, onClip = true)
      val faded = fixture.exportedWatermark("filmstrip-overlay-clipfade", fading, clips = 2, onClip = true)
      val size = faded.plan.output.size
      val slot = TimeRange.of(faded.plan.duration / 2, faded.plan.duration)
      val probe = centreOf(fading.placedOn(size, OVERLAY_SIZE).rectOn(size), size)

      faded.plan.duration shouldBe 4.seconds
      listOf(slot.start + RAMP / 4, slot.start + RAMP * 3 / 4).forEach { at ->
        val expected = fading.frameAt(at, slot).shouldNotBeNull().opacity
        val measured =
          blendAlpha(
            regionAverage(bare.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
            regionAverage(opaque.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
            regionAverage(faded.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at),
          )
        withClue("at $at the file blended at $measured where the sampler answers $expected") {
          (abs(measured - expected) < ALPHA_TOLERANCE).shouldBeTrue()
        }
      }

      val last = faded.plan.duration - FRAME
      withClue("the watermark is gone by $last") { drawnAt(bare, faded, probe, last).shouldBeTrue() }

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
    withClue("the watermark reads the same as the frame under it") { (denominator > BLEND_RANGE_FLOOR).shouldBeTrue() }

    return (numerator / denominator).toFloat()
  }

  // The stored luma of one pixel of the frame presented at [at], read by ffmpeg as the gray plane.
  private fun lumaAt(
    path: String,
    x: Int,
    y: Int,
    at: Duration,
  ): Int {
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-ss",
        formatSeconds(at.toDouble(DurationUnit.SECONDS)),
        "-i",
        path,
        "-vf",
        "format=gray,crop=1:1:$x:$y",
        "-vframes",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gray",
        "-",
      ).start()
    val pixel = process.inputStream.readNBytes(1)
    process.waitFor()
    withClue("ffmpeg read no luma at $x,$y of $path") { pixel.size shouldBe 1 }
    return pixel[0].toInt() and 0xFF
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

    // How far the watermark moves a luma code from the frame under it, well clear of what the H264 round trip moves the
    // column beside an edge by.
    const val EDGE_LIFT = 30

    // How far right of where it was authored the held watermark is moved, before the pixel is made odd.
    const val ODD_SHIFT = 20
  }
}
