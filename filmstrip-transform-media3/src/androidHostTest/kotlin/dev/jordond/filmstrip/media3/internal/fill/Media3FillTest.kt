package dev.jordond.filmstrip.media3.internal.fill

import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.hlgSceneFromNits
import dev.jordond.filmstrip.effects.MEDIA3_HDR_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

/**
 * What an effect writes for a fill color, in the space media3 hands it.
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
    val expected = hlgSceneFromNits(authoredHdrNits(WHITE))
    val components = fillComponents(WHITE, HdrTransfer.Hlg)

    components.forEachIndexed { index, channel -> channel shouldBeNear expected[index] }
  }

  @Test
  fun `the two transfer functions do not share a value for one color`() {
    val pq = fillComponents(WHITE, HdrTransfer.Pq)[0]
    val hlg = fillComponents(WHITE, HdrTransfer.Hlg)[0]

    // media3 leaves an HLG frame in scene light and scales a PQ one to display light, so reusing
    // one number for both writes a bar that is visibly the wrong brightness on one of them.
    withClue("the transfer functions agreed, so one of them is not running") { (abs(pq - hlg) > 0.05f) shouldBe true }
  }

  @Test
  fun `an hdr white is far below the value an untranslated fill would write`() {
    val components = fillComponents(WHITE, HdrTransfer.Pq)

    // Writing the sRGB fraction straight into this space asks for one, which is the whole nominal
    // peak rather than a diffuse white.
    withClue("an HDR white was written at ${components[0]} of media3's peak") { (components[0] < 0.5f) shouldBe true }
  }

  @Test
  fun `black is zero in every space`() {
    fillComponents(BLACK, transfer = null).forEach { it shouldBeNear 0f }
    fillComponents(BLACK, HdrTransfer.Pq).forEach { it shouldBeNear 0f }
    fillComponents(BLACK, HdrTransfer.Hlg).forEach { it shouldBeNear 0f }
  }

  private infix fun Float.shouldBeNear(expected: Float) {
    withClue("expected $expected but was $this") { (abs(this - expected) <= 1e-3f) shouldBe true }
  }

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()
  }
}
