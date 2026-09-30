package dev.jordond.filmstrip.media

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthoredHdrLightTest {
  @Test
  fun `white lands on reference white rather than on the format peak`() {
    val white = authoredHdrNits(WHITE)

    white.forEach { channel -> channel shouldBeNear HDR_REFERENCE_WHITE_NITS }
  }

  @Test
  fun `black is black whatever the transfer function`() {
    val black = authoredHdrNits(BLACK)

    black.forEach { channel -> channel shouldBeNear 0f }
    HdrTransfer.Pq.signalFromNits(black).forEach { it shouldBeNear 0f }
    HdrTransfer.Hlg.signalFromNits(black).forEach { it shouldBeNear 0f }
  }

  @Test
  fun `an HLG reference white is the seventy five percent signal broadcast practice puts it at`() {
    // The one figure here with a source outside this file, so it is what says the whole conversion
    // is right and not just self consistent.
    HdrTransfer.Hlg.signalFromNits(authoredHdrNits(WHITE)).forEach { it shouldBeNear 0.75f }
  }

  @Test
  fun `a PQ reference white is the code value the transfer function defines for it`() {
    HdrTransfer.Pq.signalFromNits(authoredHdrNits(WHITE)).forEach { it shouldBeNear 0.580689f }
  }

  @Test
  fun `a mid grey is darker than half of white because the ramp is not linear`() {
    val grey = authoredHdrNits(MID_GREY)

    // Half of 203 would be 101.5. Reading the sRGB value as though it were already linear is the
    // mistake this pins.
    grey[0] shouldBeNear 43.8197f
    assertTrue(grey[0] < HDR_REFERENCE_WHITE_NITS / 2f, "a mid grey came out at or above half of white")
  }

  @Test
  fun `a saturated red gains the green and blue that a wider gamut spreads it over`() {
    val red = authoredHdrNits(RED)

    // BT.2020's primaries sit outside BT.709's, so the same red is a mix there rather than a single
    // channel. Zero in either other channel would mean the primaries were never converted.
    red[0] shouldBeNear 127.363f
    red[1] shouldBeNear 14.0268f
    red[2] shouldBeNear 3.3275f
  }

  @Test
  fun `the HLG transfer is driven by luminance rather than by each channel alone`() {
    val red = authoredHdrNits(RED)
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
    val white = authoredHdrNits(WHITE)
    val pq = HdrTransfer.Pq.signalFromNits(white)[0]
    val hlg = HdrTransfer.Hlg.signalFromNits(white)[0]

    // A backend that encodes one and reuses the number for the other is off by this much, which is
    // far too small to look like a bug and far too large to look right.
    assertTrue(abs(pq - hlg) > 0.15f, "the transfer functions agreed, so one of them is not running")
  }

  @Test
  fun `scene light sits below display light because the system gamma has not run yet`() {
    hlgSceneFromNits(authoredHdrNits(WHITE))[0] shouldBeNear 0.264797f
    hlgSceneFromNits(floatArrayOf(HLG_NOMINAL_PEAK_NITS, HLG_NOMINAL_PEAK_NITS, HLG_NOMINAL_PEAK_NITS))[0] shouldBeNear
      1f
  }

  // A mid green is coloured and sits in the middle of the range, so a round trip that only holds for
  // a grey or at the ends fails here.
  @Test
  fun `HLG scene light and display light round trip on a coloured value`() {
    val nits = authoredHdrNits(MID_GREEN)
    val scene = hlgSceneFromNits(nits)

    hlgNitsFromScene(scene).forEachIndexed { channel, value -> value shouldMatch nits[channel] }
    hlgSceneFromNits(hlgNitsFromScene(scene)).forEachIndexed { channel, value -> value shouldMatch scene[channel] }
  }

  @Test
  fun `a grey leaves the scene the way the per channel transfer does`() {
    listOf(0.1f, 0.25f, 0.5f).forEach { level ->
      hlgNitsFromScene(floatArrayOf(level, level, level)).forEach { it shouldMatch hlgDisplayNitsFromScene(level) }
    }
  }

  @Test
  fun `a coloured scene leaves through its luminance rather than each channel alone`() {
    val scene = hlgSceneFromNits(authoredHdrNits(MID_GREEN))
    val display = hlgNitsFromScene(scene)
    val perChannel = hlgDisplayNitsFromScene(scene[2])

    // Blue is the smallest channel of this green, so a per-channel power drags it furthest.
    assertTrue(
      abs(display[2] - perChannel) > perChannel * 0.1f,
      "a per-channel power matched the luminance-driven one, so the transfer is not anchored",
    )
  }

  @Test
  fun `black scene light is black display light`() {
    hlgNitsFromScene(FloatArray(3)).forEach { assertEquals(0f, it) }
  }

  @Test
  fun `a packed colour lands where its channels do`() {
    listOf(MID_GREY, MID_GREEN, WHITE).forEach { color ->
      val channels =
        authoredHdrNits(
          ((color shr 16) and 0xFF) / 255f,
          ((color shr 8) and 0xFF) / 255f,
          (color and 0xFF) / 255f,
        )

      assertContentEquals(channels, authoredHdrNits(color), "0x${color.toUInt().toString(16)}")
    }
  }

  // Every code on every channel, so the middle of the ramp is read and not only its ends. The table
  // the texels decode through has to give the same figure as the curve, not one near it.
  @Test
  fun `every eight-bit code decodes to the same light as a single colour does`() {
    listOf(16, 8, 0).forEach { shift ->
      val argb = IntArray(256) { code -> (0xFF shl 24) or (code shl shift) }
      val texels = authoredHdrTexels(argb)

      argb.forEachIndexed { index, color ->
        val expected = authoredHdrNits(color)
        repeat(3) { channel ->
          assertEquals(
            expected[channel],
            texels[index * 4 + channel],
            "code $index shifted by $shift, channel $channel",
          )
        }
      }
    }
  }

  @Test
  fun `a texel keeps its straight colour and its alpha untouched`() {
    val alphas = intArrayOf(0, 128, 255)
    val texels = authoredHdrTexels(IntArray(alphas.size) { (alphas[it] shl 24) or (MID_GREEN and 0xFFFFFF) })
    val colour = authoredHdrNits(MID_GREEN)

    alphas.forEachIndexed { index, alpha ->
      assertEquals((alpha / 255f).stored(), texels[index * 4 + 3], "alpha $alpha")
      repeat(3) { channel -> assertEquals(colour[channel], texels[index * 4 + channel], "alpha $alpha") }
    }
  }

  // Kotlin/JS only rounds a Float to 32 bits when it lands in a FloatArray, so an expected figure goes
  // through one to match the texel it is compared with.
  private fun Float.stored(): Float = floatArrayOf(this)[0]

  // Relative, because scene light in the middle of the range is small enough that a fixed tolerance
  // would pass a round trip that drifted by a tenth.
  private infix fun Float.shouldMatch(expected: Float) {
    assertTrue(abs(this - expected) <= abs(expected) * 1e-5f, "expected $expected but was $this")
  }

  private infix fun Float.shouldBeNear(expected: Float) {
    val tolerance = if (abs(expected) > 1f) abs(expected) * 1e-3f else 1e-3f
    assertTrue(abs(this - expected) <= tolerance, "expected $expected but was $this")
  }

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val RED = 0xFFFF0000.toInt()

    // The same codes as the authored colours the backend suites draw, which this module cannot see.
    const val MID_GREY = 0xFF808080.toInt()
    const val MID_GREEN = 0xFF308020.toInt()
  }
}
