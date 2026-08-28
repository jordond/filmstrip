package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.fillCIColor
import dev.jordond.filmstrip.avfoundation.internal.hasColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.hdrFillNits
import dev.jordond.filmstrip.transform.internal.signalFromNits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The colour a fill is handed to Core Image as.
 *
 * [fillCIColor] falls back to sRGB when a platform cannot name an HDR colour space, which draws a
 * bar at the wrong brightness rather than failing. That fallback is silent, so what these check is
 * that it is not being taken.
 */
class AppleFillTest {
  @Test
  fun `this platform can name both HDR colour spaces`() {
    // The fallback in fillCIColor is only correct as a last resort. Reaching it on a platform the
    // library supports would mean every HDR fill quietly draws dim.
    assertTrue(HdrTransfer.Pq.hasColorSpace(), "PQ has no colour space here, so a PQ fill falls back to sRGB")
    assertTrue(HdrTransfer.Hlg.hasColorSpace(), "HLG has no colour space here, so an HLG fill falls back to sRGB")
  }

  @Test
  fun `an sdr fill keeps the components it was authored with`() {
    val color = fillCIColor(MAGENTA, transfer = null)

    color.red() shouldBeNear 1.0
    color.green() shouldBeNear 0.0
    color.blue() shouldBeNear 1.0
  }

  @Test
  fun `an hdr fill carries the signal the shared conversion gives rather than the authored fraction`() {
    val expected = HdrTransfer.Pq.signalFromNits(hdrFillNits(WHITE))
    val color = fillCIColor(WHITE, HdrTransfer.Pq)

    color.red() shouldBeNear expected[0].toDouble()

    // One is what an untranslated white would carry, and it is the whole point of the conversion
    // that this is not that.
    assertTrue(color.red() < 0.9, "an HDR white went in at ${color.red()}, which is the format's peak")
  }

  @Test
  fun `the two transfer functions hand Core Image different components`() {
    val pq = fillCIColor(WHITE, HdrTransfer.Pq).red()
    val hlg = fillCIColor(WHITE, HdrTransfer.Hlg).red()

    assertTrue(abs(pq - hlg) > 0.05, "both transfer functions produced $pq, so one of them is not running")
  }

  @Test
  fun `black is black in every space`() {
    listOf(null, HdrTransfer.Pq, HdrTransfer.Hlg).forEach { transfer ->
      val color = fillCIColor(BLACK, transfer)

      color.red() shouldBeNear 0.0
      color.green() shouldBeNear 0.0
      color.blue() shouldBeNear 0.0
    }
  }

  private infix fun Double.shouldBeNear(expected: Double) {
    assertTrue(abs(this - expected) <= 1e-3, "expected $expected but was $this")
  }

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()
  }
}
