package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.roundToInt

// A tagged picture's texel is the colour its description gives it, whether an ICC profile, CICP code points or PNG
// gamma and chromaticities. This file lands that colour in an export's colour space, so every backend reads a tagged
// picture the same way instead of trusting its platform's colour management.

/**
 * Converts a picture's straight, unpremultiplied ARGB texels to linear BT.2020 display light, as [colour] defines
 * their colour.
 *
 * A description whose light tops out at reference white lands its white at [HDR_REFERENCE_WHITE_NITS], so the picture
 * sits at the same brightness an untagged one does. A PQ picture keeps the absolute light its signal stands for, up to
 * [PQ_PEAK_NITS], and an HLG picture lands where [hlgNitsFromScene] puts its scene light, up to
 * [HLG_NOMINAL_PEAK_NITS]. A colour inside BT.2020 keeps its full gamut, and only one outside BT.2020 itself clips, per
 * channel. An untagged picture, one described as sRGB, and one whose profile does not parse as an RGB or grey profile
 * land exactly where [authoredHdrTexels] puts them.
 *
 * Each call builds its own cache of up to 16384 colours, so call this once per picture rather than per frame.
 *
 * @param colour How the picture describes its colour, from [dev.jordond.filmstrip.internal.pictureColour] or
 * [PictureColour.fromDecodedProfile] or [PictureColour.fromDecodedPng], or null when it is untagged.
 * @param argb The stored codes, as the picture's own decode holds them before any colour conversion.
 * @return Four floats per texel, red, green and blue in cd/m2 and then alpha in the range zero to one.
 */
@InternalFilmstripApi
public fun pictureLight(
  colour: PictureColour?,
  argb: IntArray,
): FloatArray {
  val evaluation = taggedColour(colour) ?: return authoredHdrTexels(argb)
  val reader = LinearReader(evaluation, hdr = true)
  val slots = ColourSlots(argb.size)
  val cached = FloatArray(slots.size * 3)
  val texels = FloatArray(argb.size * TEXEL_FLOATS)
  argb.forEachIndexed { index, color ->
    val rgb = color and RGB_MASK
    val slot = slots.slotOf(rgb)
    if (slots.claim(slot, rgb)) writeNits(reader.read(rgb), cached, slot * 3)
    val offset = index * TEXEL_FLOATS
    cached.copyInto(texels, offset, slot * 3, slot * 3 + 3)
    texels[offset + 3] = (color ushr ALPHA_SHIFT) / MAX_CODE.toFloat()
  }
  return texels
}

/**
 * Converts a picture's straight, unpremultiplied sixteen-bit RGBA samples to linear BT.2020 display light, as [colour]
 * defines their colour.
 *
 * The same conversion the eight-bit [pictureLight] runs, reading each sample at its full depth, so a sixteen-bit PQ or
 * HLG picture is not cut to eight bits first. A sample of a code times 257 lands exactly where that code does in the
 * eight-bit form.
 *
 * Each call builds its own cache of up to 16384 colours, and a picture with more texels than that also gets tables of
 * every sample's level under the description's curves, so call this once per picture rather than per frame.
 *
 * @param colour How the picture describes its colour, or null when it is untagged.
 * @param rgba The stored samples, four per texel in the order red, green, blue and alpha, each read as an unsigned
 * sixteen-bit value.
 * @return Four floats per texel, red, green and blue in cd/m2 and then alpha in the range zero to one.
 */
@InternalFilmstripApi
public fun pictureLight(
  colour: PictureColour?,
  rgba: ShortArray,
): FloatArray {
  require(rgba.size % TEXEL_FLOATS == 0) { "${rgba.size} samples are not whole RGBA texels" }
  val count = rgba.size / TEXEL_FLOATS
  val slots = ColourSlots(count, wide = true)
  val reader = taggedColour(colour)?.let { LinearReader(it, hdr = true, tabulate = count > slots.size) }
  val cached = FloatArray(slots.size * 3)
  val texels = FloatArray(rgba.size)
  for (index in 0 until count) {
    val offset = index * TEXEL_FLOATS
    val red = sample(rgba, offset)
    val green = sample(rgba, offset + 1)
    val blue = sample(rgba, offset + 2)
    val high = (red shl SAMPLE_BITS) or green
    val slot = slots.slotOf(high, blue)
    if (slots.claim(slot, high, blue)) {
      if (reader == null) {
        authoredHdrNits(red / MAX_SAMPLE.toFloat(), green / MAX_SAMPLE.toFloat(), blue / MAX_SAMPLE.toFloat())
          .copyInto(cached, slot * 3)
      } else {
        writeNits(reader.read(red, green, blue), cached, slot * 3)
      }
    }
    cached.copyInto(texels, offset, slot * 3, slot * 3 + 3)
    texels[offset + 3] = sample(rgba, offset + 3) / MAX_SAMPLE.toFloat()
  }
  return texels
}

/**
 * Converts a picture's straight, unpremultiplied ARGB texels to the sRGB codes an SDR export draws, as [colour]
 * defines their colour.
 *
 * A colour outside BT.709 clips per channel to its edge, the relative colorimetric answer every colour management
 * module gives. A PQ or HLG picture's light is read against [HDR_REFERENCE_WHITE_NITS] and clips per channel above it.
 * An untagged picture, one described as sRGB, and one whose profile does not parse as an RGB or grey profile come back
 * as their stored codes.
 *
 * Each call builds its own cache of up to 16384 colours, so call this once per picture rather than per frame.
 *
 * @param colour How the picture describes its colour, from [dev.jordond.filmstrip.internal.pictureColour] or
 * [PictureColour.fromDecodedProfile] or [PictureColour.fromDecodedPng], or null when it is untagged.
 * @param argb The stored codes, as the picture's own decode holds them before any colour conversion.
 * @return One packed ARGB colour per texel, alpha carried over unchanged.
 */
@InternalFilmstripApi
public fun pictureSrgbCodes(
  colour: PictureColour?,
  argb: IntArray,
): IntArray {
  val evaluation = taggedColour(colour) ?: return argb.copyOf()
  val reader = LinearReader(evaluation, hdr = false)
  val slots = ColourSlots(argb.size)
  val cached = IntArray(slots.size)
  return IntArray(argb.size) { index ->
    val color = argb[index]
    val rgb = color and RGB_MASK
    val slot = slots.slotOf(rgb)
    if (slots.claim(slot, rgb)) cached[slot] = srgbCodes(reader.read(rgb))
    (color and ALPHA_MASK) or cached[slot]
  }
}

/**
 * Converts a picture's straight, unpremultiplied sixteen-bit RGBA samples to the sRGB codes an SDR export draws, as
 * [colour] defines their colour.
 *
 * The same conversion the eight-bit [pictureSrgbCodes] runs, reading each sample at its full depth. An untagged
 * picture, and one described as sRGB, come back as each sample rounded to its nearest eight-bit code, so a sample of
 * a code times 257 comes back as that code.
 *
 * Each call builds its own cache of up to 16384 colours, and a picture with more texels than that also gets tables of
 * every sample's level under the description's curves, so call this once per picture rather than per frame.
 *
 * @param colour How the picture describes its colour, or null when it is untagged.
 * @param rgba The stored samples, four per texel in the order red, green, blue and alpha, each read as an unsigned
 * sixteen-bit value.
 * @return One packed ARGB colour per texel, alpha rounded to its nearest eight-bit code.
 */
@InternalFilmstripApi
public fun pictureSrgbCodes(
  colour: PictureColour?,
  rgba: ShortArray,
): IntArray {
  require(rgba.size % TEXEL_FLOATS == 0) { "${rgba.size} samples are not whole RGBA texels" }
  val count = rgba.size / TEXEL_FLOATS
  val slots = ColourSlots(count, wide = true)
  val reader = taggedColour(colour)?.let { LinearReader(it, hdr = false, tabulate = count > slots.size) }
  val cached = IntArray(slots.size)
  return IntArray(count) { index ->
    val offset = index * TEXEL_FLOATS
    val red = sample(rgba, offset)
    val green = sample(rgba, offset + 1)
    val blue = sample(rgba, offset + 2)
    val high = (red shl SAMPLE_BITS) or green
    val slot = slots.slotOf(high, blue)
    if (slots.claim(slot, high, blue)) {
      cached[slot] =
        if (reader == null) {
          (nearestCode(red) shl RED_SHIFT) or (nearestCode(green) shl GREEN_SHIFT) or nearestCode(blue)
        } else {
          srgbCodes(reader.read(red, green, blue))
        }
    }
    (nearestCode(sample(rgba, offset + 3)) shl ALPHA_SHIFT) or cached[slot]
  }
}

/**
 * The matrix taking D50 connection space XYZ to linear BT.2020, adapting the white to D65 with Bradford first.
 *
 * The profile's white lands on one in every channel.
 */
internal val PCS_TO_BT2020: DoubleArray by lazy {
  multiply(rgbFromXyz(BT2020_PRIMARIES), bradford(PCS_WHITE, D65_WHITE))
}

/**
 * The matrix taking D50 connection space XYZ to linear BT.709, adapting the white to D65 with Bradford first.
 *
 * The profile's white lands on one in every channel.
 */
internal val PCS_TO_BT709: DoubleArray by lazy {
  multiply(rgbFromXyz(BT709_PRIMARIES), bradford(PCS_WHITE, D65_WHITE))
}

private val BT2020_TO_BT709: DoubleArray by lazy {
  multiply(PCS_TO_BT709, checkNotNull(invert(PCS_TO_BT2020)) { "BT.2020 spans no colour space" })
}

private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

private fun taggedColour(colour: PictureColour?): ColourEvaluation? =
  colour?.evaluator()?.takeUnless { it.profile.isSrgb }

private fun rgbFromXyz(chromaticities: DoubleArray): DoubleArray =
  checkNotNull(rgbToXyz(chromaticities)?.let(::invert)) { "a fixed output gamut spans no colour space" }

private fun writeNits(
  light: DoubleArray,
  out: FloatArray,
  offset: Int,
) {
  for (channel in 0 until 3) out[offset + channel] = (light[channel] * HDR_REFERENCE_WHITE_NITS.toDouble()).toFloat()
}

private fun srgbCodes(light: DoubleArray): Int =
  light.fold(0) { packed, channel -> (packed shl 8) or (linearToSrgb(channel.toFloat()) * MAX_CODE).roundToInt() }

private fun sample(
  rgba: ShortArray,
  index: Int,
): Int = rgba[index].toInt() and SAMPLE_MASK

private fun nearestCode(sample: Int): Int = (sample * CODE_MASK + SAMPLE_MASK / 2) / SAMPLE_MASK

/**
 * Reads a colour through [evaluation] into linear light where one is [HDR_REFERENCE_WHITE_NITS], in linear BT.2020
 * when [hdr] holds and linear BT.709 when it does not.
 *
 * Each channel clips at zero and at the top of the range the output keeps. On HDR that is the description's own peak,
 * one for a description whose light tops out at reference white and [HdrTransfer.displayLightCeiling] for PQ and HLG.
 * On SDR it is one. An HLG colour runs [hlgNitsFromScene] on its scene light in BT.2020, so the opto-optical transfer
 * reads the colour's real luminance whatever its primaries. Sixteen-bit samples read their curves from tables of every
 * sample when [tabulate] holds.
 *
 * The array [read] returns is reused by the next call.
 */
private class LinearReader(
  private val evaluation: ColourEvaluation,
  private val hdr: Boolean,
  tabulate: Boolean = false,
) {
  private val hlg = evaluation.transfer == HdrTransfer.Hlg
  private val matrix =
    when {
      !hlg -> if (hdr) PCS_TO_BT2020 else PCS_TO_BT709
      hdr -> IDENTITY
      else -> BT2020_TO_BT709
    }
  private val ceiling =
    evaluation.transfer
      ?.takeIf { hdr }
      ?.displayLightCeiling
      ?.toDouble() ?: 1.0
  private val samples = evaluation.profile.sampleReader(tabulate)
  private val pcs = DoubleArray(3)
  private val light = DoubleArray(3)

  fun read(rgb: Int): DoubleArray {
    evaluation.profile.codesToPcs(rgb, pcs)
    return land()
  }

  fun read(
    red: Int,
    green: Int,
    blue: Int,
  ): DoubleArray {
    samples.read(red, green, blue, pcs)
    return land()
  }

  private fun land(): DoubleArray {
    val source = if (hlg) hlgNitsFromScene(apply(PCS_TO_BT2020, pcs)) else pcs
    val divisor = if (hlg) HDR_REFERENCE_WHITE_NITS.toDouble() else 1.0
    for (row in 0 until 3) {
      light[row] =
        (matrix[row * 3] * source[0] + matrix[row * 3 + 1] * source[1] + matrix[row * 3 + 2] * source[2])
          .div(divisor)
          .coerceIn(0.0, ceiling)
    }
    return light
  }
}

/**
 * Slots that each hold the last colour that hashed to them, so a colour seen again reads its converted value from its
 * slot instead of being converted again.
 *
 * An eight-bit colour is one packed RGB key. A sixteen-bit colour, when [wide] holds, is a high key packing red and
 * green and a low key holding blue. The slot count is the picture's texel count rounded up to a power of two, at least
 * two and at most 16384, whichever the depth.
 */
internal class ColourSlots(
  texelCount: Int,
  wide: Boolean = false,
) {
  private val bits =
    (Int.SIZE_BITS - (texelCount - 1).coerceAtLeast(1).countLeadingZeroBits()).coerceAtMost(MAX_CACHE_BITS)
  private val keys = IntArray(1 shl bits) { EMPTY_SLOT }
  private val lows = if (wide) IntArray(keys.size) { EMPTY_SLOT } else null

  val size: Int get() = keys.size

  fun slotOf(rgb: Int): Int = (rgb * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - bits)

  fun slotOf(
    high: Int,
    low: Int,
  ): Int = (((high * HASH_MULTIPLIER) xor low) * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - bits)

  /**
   * Points [slot] at [rgb] and returns true when the caller must fill it, or returns false when it already held
   * [rgb].
   */
  fun claim(
    slot: Int,
    rgb: Int,
  ): Boolean {
    if (keys[slot] == rgb) return false
    keys[slot] = rgb
    return true
  }

  /**
   * Points [slot] at the sixteen-bit colour [high] and [low] and returns true when the caller must fill it, or returns
   * false when it already held that colour.
   *
   * A low key is a sixteen-bit sample and never matches the empty mark, so an empty slot never reads as filled.
   */
  fun claim(
    slot: Int,
    high: Int,
    low: Int,
  ): Boolean {
    val lows = checkNotNull(lows) { "these slots hold eight-bit colours" }
    if (keys[slot] == high && lows[slot] == low) return false
    keys[slot] = high
    lows[slot] = low
    return true
  }
}

// Red, green and blue chromaticities and then the D65 white, x then y.
private val BT709_PRIMARIES = doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06, 0.3127, 0.3290)
private val BT2020_PRIMARIES = doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290)

private const val RGB_MASK = 0xFFFFFF
private const val ALPHA_MASK = 0xFF shl 24
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val MAX_CODE = 255.0
private const val CODE_MASK = 0xFF
private const val MAX_SAMPLE = 65535.0
private const val SAMPLE_MASK = 0xFFFF
private const val SAMPLE_BITS = 16
private const val TEXEL_FLOATS = 4
private const val MAX_CACHE_BITS = 14
private const val EMPTY_SLOT = -1

// Fibonacci hashing, the golden ratio in 32 bits, spreads neighbouring colours across the slots.
private const val HASH_MULTIPLIER = -0x61C88647
