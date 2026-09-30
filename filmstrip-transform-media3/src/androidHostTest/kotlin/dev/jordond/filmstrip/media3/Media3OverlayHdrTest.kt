package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.media3WorkingLight
import dev.jordond.filmstrip.effects.overlay.media3HdrTexels
import dev.jordond.filmstrip.effects.overlay.media3OverlayResolution
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.srgbToLinear
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What media3 is handed for an overlay on a kept grade, and what the plan says about it.
 *
 * media3 reads an HDR overlay texel as its luminance multiplier times the sRGB decode of the code, so
 * every check here decodes the mapped codes that way and compares against [media3WorkingLight] of the
 * authored colour, the figure a fill of the same colour is written at.
 */
class Media3OverlayHdrTest {
  @Test
  fun `every texel read the way media3 reads it lands on the light of its authored colour`() {
    HdrTransfer.entries.forEach { transfer ->
      val texels = media3HdrTexels(COLOURS, transfer)

      COLOURS.forEachIndexed { index, colour ->
        val expected = media3WorkingLight(authoredHdrNits(colour), transfer)
        CHANNEL_SHIFTS.forEachIndexed { channel, shift ->
          val code = (texels.argb[index] ushr shift) and 0xFF
          val read = texels.hdrLuminance * srgbToLinear(code / MAX_CODE)
          val slack = texels.hdrLuminance * halfCodeStep(code)
          assertTrue(
            abs(read - expected[channel]) <= slack,
            "$transfer ${colour.toUInt().toString(16)} channel $channel read $read, expected ${expected[channel]}",
          )
        }
      }
    }
  }

  @Test
  fun `white takes the top code and the multiplier is where reference white sits`() {
    HdrTransfer.entries.forEach { transfer ->
      val texels = media3HdrTexels(intArrayOf(WHITE), transfer)

      assertEquals(WHITE, texels.argb[0], "$transfer white moved off the top code")
      assertEquals(media3WorkingLight(authoredHdrNits(WHITE), transfer).max(), texels.hdrLuminance)
    }
  }

  // Every row of the change of primaries sums to one, so on PQ, where the working light is a plain
  // scale of display light, a neutral texel has nothing to move by.
  @Test
  fun `a neutral texel keeps its own code on a pq grade`() {
    val greys = IntArray(CODES) { code -> OPAQUE or (code shl 16) or (code shl 8) or code }

    assertEquals(greys.toList(), media3HdrTexels(greys, HdrTransfer.Pq).argb.toList())
  }

  @Test
  fun `a colour past white on an hlg grade raises the multiplier rather than clipping`() {
    val white = media3WorkingLight(authoredHdrNits(WHITE), HdrTransfer.Hlg).max()
    val blue = media3WorkingLight(authoredHdrNits(BLUE), HdrTransfer.Hlg).max()
    assertTrue(blue > white, "blue does not go past white in HLG scene light, nothing to measure")

    assertEquals(blue, media3HdrTexels(intArrayOf(WHITE, BLUE), HdrTransfer.Hlg).hdrLuminance)
  }

  @Test
  fun `a texel's alpha is passed through untouched`() {
    val alphas = listOf(0, QUARTER_ALPHA, HALF_ALPHA, 0xFF)
    val texels = IntArray(alphas.size) { (alphas[it] shl 24) or (MID_GREY and RGB_MASK) }

    HdrTransfer.entries.forEach { transfer ->
      val mapped = media3HdrTexels(texels, transfer).argb
      assertEquals(alphas, mapped.map { it ushr 24 }, "$transfer changed an alpha")
    }
  }

  // The resolution keys on the grade being written and on nothing about the device, so an SDR export
  // on a device that could have written HDR resolves the same as one on a device that could not.
  @Test
  fun `an overlay on an sdr grade resolves`() {
    assertIs<EffectResolution.Resolved>(media3OverlayResolution(PlatformEffect(Any()), transfer = null))
  }

  @Test
  fun `an overlay on a pq grade resolves`() {
    assertIs<EffectResolution.Resolved>(media3OverlayResolution(PlatformEffect(Any()), HdrTransfer.Pq))
  }

  @Test
  fun `an overlay on an hlg grade says it blends in scene light`() {
    val degraded = assertIs<EffectResolution.Degraded>(media3OverlayResolution(PlatformEffect(Any()), HdrTransfer.Hlg))

    assertTrue("scene light" in degraded.message, "the message does not name the blend: ${degraded.message}")
  }

  // Half the distance to the neighbouring code on either side, in the linear light the code decodes
  // to, which is as close as an eight-bit texel can land.
  private fun halfCodeStep(code: Int): Float {
    val below = srgbToLinear((code - 0.5f) / MAX_CODE)
    val above = srgbToLinear((code + 0.5f) / MAX_CODE)
    return (above - below) / 2f
  }

  private companion object {
    const val OPAQUE = 0xFF shl 24
    const val RGB_MASK = 0xFFFFFF
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MID_GREY = 0xFF808080.toInt()
    const val MID_GREEN = 0xFF308020.toInt()
    const val QUARTER_GREY = 0xFF404040.toInt()
    const val THREE_QUARTER_GREY = 0xFFC0C0C0.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    val COLOURS = intArrayOf(WHITE, MID_GREY, MID_GREEN, QUARTER_GREY, THREE_QUARTER_GREY, BLUE)

    const val QUARTER_ALPHA = 0x40
    const val HALF_ALPHA = 0x80
    const val CODES = 256
    const val MAX_CODE = 255f
    val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
  }
}
