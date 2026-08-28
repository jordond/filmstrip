package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class HdrFillTest {
  @Test
  fun `white lands on reference white rather than on the format peak`() {
    val white = hdrFillNits(WHITE)

    white.forEach { channel -> channel shouldBeNear HDR_REFERENCE_WHITE_NITS }
  }

  @Test
  fun `black is black whatever the transfer function`() {
    val black = hdrFillNits(BLACK)

    black.forEach { channel -> channel shouldBeNear 0f }
    HdrTransfer.Pq.signalFromNits(black).forEach { it shouldBeNear 0f }
    HdrTransfer.Hlg.signalFromNits(black).forEach { it shouldBeNear 0f }
  }

  @Test
  fun `an HLG reference white is the seventy five percent signal broadcast practice puts it at`() {
    // The one figure here with a source outside this file, so it is what says the whole conversion
    // is right and not just self consistent.
    HdrTransfer.Hlg.signalFromNits(hdrFillNits(WHITE)).forEach { it shouldBeNear 0.75f }
  }

  @Test
  fun `a PQ reference white is the code value the transfer function defines for it`() {
    HdrTransfer.Pq.signalFromNits(hdrFillNits(WHITE)).forEach { it shouldBeNear 0.580689f }
  }

  @Test
  fun `a mid grey is darker than half of white because the ramp is not linear`() {
    val grey = hdrFillNits(0xFF808080.toInt())

    // Half of 203 would be 101.5. Reading the sRGB value as though it were already linear is the
    // mistake this pins.
    grey[0] shouldBeNear 43.8197f
    assertTrue(grey[0] < HDR_REFERENCE_WHITE_NITS / 2f, "a mid grey came out at or above half of white")
  }

  @Test
  fun `a saturated red gains the green and blue that a wider gamut spreads it over`() {
    val red = hdrFillNits(RED)

    // BT.2020's primaries sit outside BT.709's, so the same red is a mix there rather than a single
    // channel. Zero in either other channel would mean the primaries were never converted.
    red[0] shouldBeNear 127.363f
    red[1] shouldBeNear 14.0268f
    red[2] shouldBeNear 3.3275f
  }

  @Test
  fun `the HLG transfer is driven by luminance rather than by each channel alone`() {
    val red = hdrFillNits(RED)
    val scene = hlgSceneFromNits(red)

    scene[0] shouldBeNear 0.21504f

    // Running the same power down each channel on its own is the shortcut this rules out. It agrees
    // on a grey and drifts this far on a saturated colour, which is the shape of a bug that looks
    // fine in every test that only ever checks white.
    val perChannel = (red[0] / HLG_NOMINAL_PEAK_NITS).toDouble().pow(1.0 / 1.2).toFloat()
    assertTrue(
      abs(scene[0] - perChannel) > 0.02f,
      "a per-channel power matched the luminance-driven one, so the transfer is not anchored",
    )
  }

  @Test
  fun `the two transfer functions disagree about the same colour`() {
    val white = hdrFillNits(WHITE)
    val pq = HdrTransfer.Pq.signalFromNits(white)[0]
    val hlg = HdrTransfer.Hlg.signalFromNits(white)[0]

    // A backend that encodes one and reuses the number for the other is off by this much, which is
    // far too small to look like a bug and far too large to look right.
    assertTrue(abs(pq - hlg) > 0.15f, "the transfer functions agreed, so one of them is not running")
  }

  @Test
  fun `scene light sits below display light because the system gamma has not run yet`() {
    hlgSceneFromNits(hdrFillNits(WHITE))[0] shouldBeNear 0.264797f
    hlgSceneFromNits(floatArrayOf(HLG_NOMINAL_PEAK_NITS, HLG_NOMINAL_PEAK_NITS, HLG_NOMINAL_PEAK_NITS))[0] shouldBeNear
      1f
  }

  private infix fun Float.shouldBeNear(expected: Float) {
    val tolerance = if (abs(expected) > 1f) abs(expected) * 1e-3f else 1e-3f
    assertTrue(abs(this - expected) <= tolerance, "expected $expected but was $this")
  }

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val RED = 0xFFFF0000.toInt()
  }
}
