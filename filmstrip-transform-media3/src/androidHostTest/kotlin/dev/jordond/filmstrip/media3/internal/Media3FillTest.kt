package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.effects.MEDIA3_HDR_PEAK_NITS
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.hdrFillNits
import dev.jordond.filmstrip.transform.internal.hlgSceneFromNits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What an effect writes for a fill colour, in the space media3 hands it.
 *
 * Every expectation here is the shared conversion applied to media3's own normalisation rather than
 * a figure copied out of it, so a change to what reference white means moves both together.
 */
class Media3FillTest {
  @Test
  fun `an sdr fill goes in as authored`() {
    val components = fillComponents(MAGENTA, transfer = null)

    components[0] shouldBeNear 1f
    components[1] shouldBeNear 0f
    components[2] shouldBeNear 1f
  }

  @Test
  fun `a pq fill is reference white against the peak media3 normalises to`() {
    val components = fillComponents(WHITE, HdrTransfer.Pq)

    components.forEach { it shouldBeNear HDR_REFERENCE_WHITE_NITS / MEDIA3_HDR_PEAK_NITS }
  }

  @Test
  fun `an hlg fill is the scene light the shared conversion gives for it`() {
    val expected = hlgSceneFromNits(hdrFillNits(WHITE))
    val components = fillComponents(WHITE, HdrTransfer.Hlg)

    components.forEachIndexed { index, channel -> channel shouldBeNear expected[index] }
  }

  @Test
  fun `the two transfer functions do not share a value for one colour`() {
    val pq = fillComponents(WHITE, HdrTransfer.Pq)[0]
    val hlg = fillComponents(WHITE, HdrTransfer.Hlg)[0]

    // media3 leaves an HLG frame in scene light and scales a PQ one to display light, so reusing
    // one number for both writes a bar that is visibly the wrong brightness on one of them.
    assertTrue(abs(pq - hlg) > 0.05f, "the transfer functions agreed, so one of them is not running")
  }

  @Test
  fun `an hdr white is far below the value an untranslated fill would write`() {
    val components = fillComponents(WHITE, HdrTransfer.Pq)

    // Writing the sRGB fraction straight into this space asks for one, which is the whole nominal
    // peak rather than a diffuse white.
    assertTrue(components[0] < 0.5f, "an HDR white was written at ${components[0]} of media3's peak")
  }

  @Test
  fun `black is zero in every space`() {
    fillComponents(BLACK, transfer = null).forEach { it shouldBeNear 0f }
    fillComponents(BLACK, HdrTransfer.Pq).forEach { it shouldBeNear 0f }
    fillComponents(BLACK, HdrTransfer.Hlg).forEach { it shouldBeNear 0f }
  }

  private infix fun Float.shouldBeNear(expected: Float) {
    assertTrue(abs(this - expected) <= 1e-3f, "expected $expected but was $this")
  }

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()
  }
}
