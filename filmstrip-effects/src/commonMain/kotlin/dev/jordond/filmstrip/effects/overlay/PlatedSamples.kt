package dev.jordond.filmstrip.effects.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How many premultiplied eight-bit steps a glyph texel's color may sit from the text color and still read as the
 * text's ink.
 *
 * A platform stores a glyph premultiplied on the eight-bit grid and hands it back divided out, so a channel of an
 * edge texel can read back a few steps from the text color. [glyphInkReach] turns these steps into codes at a texel's
 * alpha.
 */
internal const val INK_STEPS: Int = 3

/**
 * The most codes a glyph texel's channel may sit from the text color's and still read as ink, at [coverage] alpha.
 *
 * It is [INK_STEPS] premultiplied steps seen through the texel's alpha, 255 over [coverage] codes for each step, so a
 * texel at full alpha reads as ink within three codes and one at alpha 12 within about 64.
 */
internal fun glyphInkReach(coverage: Int): Double = INK_STEPS * TOP_CODE.toDouble() / coverage

/**
 * Lays [text] over [plate] by [glyphs] into straight sixteen-bit RGBA samples, four a texel.
 *
 * Each texel is the glyph's color, at the text's alpha times the texel's coverage, drawn source over the plate, or
 * over nothing when [plate] is null. A texel whose every channel sits within [glyphInkReach] of the text color at its
 * alpha is ink and takes the text color exactly. Any other texel, such as one of a color font's glyphs, keeps its own
 * color at the eight bits it was read at. The blend is worked on straight colors and rounded once to sixteen bits, so
 * a texel the glyphs leave uncovered holds the plate's color and alpha exactly as the codes it names widen.
 *
 * @param glyphs Four bytes a texel, the straight red, green, blue and alpha the glyphs were drawn in at the text
 * color's own red, green and blue and full alpha. Each alpha is how much of the texel the glyphs cover.
 * @param text The text color, straight ARGB.
 * @param plate The plate color, straight ARGB, or null for no plate.
 * @param samples Where the samples are written, as long as [glyphs]. A new array unless the caller allocates its own.
 * @return [samples], filled.
 * @throws IllegalArgumentException When [samples] is not as long as [glyphs].
 */
internal fun platedSamples(
  glyphs: ByteArray,
  text: Int,
  plate: Int?,
  samples: ShortArray = ShortArray(glyphs.size),
): ShortArray {
  require(samples.size == glyphs.size) { "${glyphs.size} glyph bytes are laid into ${samples.size} samples" }
  val textAlpha = levelOf(text, ALPHA_SHIFT)
  val plateAlpha = plate?.let { levelOf(it, ALPHA_SHIFT) } ?: 0.0
  val ink = IntArray(RGB_CHANNELS) { codeOf(text, RGB_SHIFTS[it]) }
  val plateColor = DoubleArray(RGB_CHANNELS) { channel -> plate?.let { levelOf(it, RGB_SHIFTS[channel]) } ?: 0.0 }

  for (texel in glyphs.indices step RGBA_CHANNELS) {
    val coverage = glyphs.codeAt(texel + ALPHA)
    val over = textAlpha * coverage / TOP_CODE
    val under = plateAlpha * (1 - over)
    val alpha = over + under
    if (alpha <= 0.0) {
      samples.fill(0, texel, texel + RGBA_CHANNELS)
      continue
    }
    val inked = coverage == 0 || glyphs.isInk(texel, coverage, ink)
    for (channel in 0 until RGB_CHANNELS) {
      val glyph = (if (inked) ink[channel] else glyphs.codeAt(texel + channel)).toDouble() / TOP_CODE
      samples[texel + channel] = sampleOf((glyph * over + plateColor[channel] * under) / alpha)
    }
    samples[texel + ALPHA] = sampleOf(alpha)
  }
  return samples
}

private fun ByteArray.isInk(
  texel: Int,
  coverage: Int,
  ink: IntArray,
): Boolean {
  val reach = glyphInkReach(coverage)
  return (0 until RGB_CHANNELS).all { abs(codeAt(texel + it) - ink[it]) <= reach }
}

private fun ByteArray.codeAt(index: Int): Int = this[index].toInt() and BYTE_MASK

private fun codeOf(
  argb: Int,
  shift: Int,
): Int = (argb ushr shift) and BYTE_MASK

private fun levelOf(
  argb: Int,
  shift: Int,
): Double = codeOf(argb, shift).toDouble() / TOP_CODE

private fun sampleOf(level: Double): Short = (level.coerceIn(0.0, 1.0) * TOP_SAMPLE).roundToInt().toShort()

private const val RGBA_CHANNELS = 4
private const val RGB_CHANNELS = 3
private const val ALPHA = 3
private const val ALPHA_SHIFT = 24
private val RGB_SHIFTS = intArrayOf(16, 8, 0)
private const val BYTE_MASK = 0xFF
