package dev.jordond.filmstrip.effects.overlay

/**
 * The most bytes reading or drawing one overlay picture may hold at its peak, 1 GiB.
 *
 * Apple counts its reads and its draws at their colour against it, a browser counts decoding a PNG's samples beside
 * the bitmap drawn from it, and a text raster on every backend counts itself, the floats an HDR frame draws it from
 * and any copies held on the way. A picture or text whose count passes it is refused by name, since iOS ends an app
 * past its memory limit without malloc ever failing and a browser tab runs out the same way. The reason names the
 * ceiling in force, which Android lowers in `textCeiling`.
 */
internal const val PICTURE_PEAK_CEILING: Long = 1L shl 30

/**
 * The bytes one texel of a text raster takes, four sixteen-bit samples.
 *
 * Every backend holds its text raster at this size, Apple as a sixteen-bit bitmap and Android and the browser as the
 * samples [platedSamples] lays, and counts it at this size against [textRasterFits].
 */
internal const val TEXT_TEXEL_BYTES: Int = 4 * Short.SIZE_BYTES

/**
 * The bytes one texel takes as floats, four channels of four bytes.
 *
 * Apple counts the premultiplied floats it draws a picture from at this size, and a text raster counts the floats an
 * HDR frame draws it from the same way.
 */
internal const val FLOAT_TEXEL_BYTES: Long = 16L

/**
 * The bytes a read or a draw of an overlay picture or a text raster holds at its peak, or null when that count does
 * not fit in a [Long] or any part of it is null or negative.
 *
 * Every read and draw on Apple and every text raster is counted this way against [PICTURE_PEAK_CEILING]. [held] is
 * what the step works from, which stays held the whole time. [band] is everything else the step holds on the way, such
 * as copies of the samples or one band of rows. [made] is what the step writes. The three are held together at the
 * peak, so the count is their sum.
 */
internal fun picturePeakBytes(
  held: Long?,
  band: Long?,
  made: Long?,
): Long? {
  if (held == null || band == null || made == null) return null
  if (held < 0 || band < 0 || made < 0) return null
  if (held > Long.MAX_VALUE - band || held + band > Long.MAX_VALUE - made) return null
  return held + band + made
}

/**
 * Whether a [width] by [height] text raster of [bytesPerTexel] bytes a texel fits under [ceiling].
 *
 * The count is [picturePeakBytes] with the raster held, the [band] bytes a backend holds on the way to the floats an
 * HDR frame draws the raster from, and those floats made at [FLOAT_TEXEL_BYTES] a texel. A backend that makes the
 * floats in one go passes a [band] of 0, and a null [band] is one that could not be counted. A raster with no texels
 * holds nothing and fits. One with a negative side, which is a size that overflowed on the way here, does not, and
 * neither does one whose count cannot be made or does not fit in a [Long]. A backend asks before it allocates and
 * refuses a raster that does not fit with the reason [textTooLargeToDraw] gives.
 *
 * The sides are [Long] so a backend can pass the [plateSide] it measured without narrowing it first. A side past
 * [Int.MAX_VALUE] does not fit, since every backend makes its raster with [Int] sides, so a raster that fits narrows
 * to an [Int] on both sides.
 *
 * @param ceiling The most bytes the raster may hold at its peak, [PICTURE_PEAK_CEILING] unless a test sets it.
 * @throws IllegalArgumentException When [bytesPerTexel] is not positive.
 */
internal fun textRasterFits(
  width: Long,
  height: Long,
  bytesPerTexel: Int,
  band: Long?,
  ceiling: Long = PICTURE_PEAK_CEILING,
): Boolean {
  require(bytesPerTexel > 0) { "a texel takes at least one byte, not $bytesPerTexel" }
  if (width < 0 || height < 0 || width > Int.MAX_VALUE || height > Int.MAX_VALUE) return false
  if (width == 0L || height == 0L) return true
  val texels = width * height
  if (texels > Long.MAX_VALUE / maxOf(bytesPerTexel.toLong(), FLOAT_TEXEL_BYTES)) return false
  val peak = picturePeakBytes(held = texels * bytesPerTexel, band = band, made = texels * FLOAT_TEXEL_BYTES)
  return peak != null && peak <= ceiling
}

/**
 * The reason a [width] by [height] text raster of [bytesPerTexel] bytes a texel is refused when [textRasterFits] says
 * it does not fit under [ceiling].
 */
internal fun textTooLargeToDraw(
  width: Long,
  height: Long,
  bytesPerTexel: Int,
  ceiling: Long = PICTURE_PEAK_CEILING,
): String =
  "The $width by $height text is too large to draw. Its raster at $bytesPerTexel bytes a texel, the floats an HDR " +
    "frame draws it from at $FLOAT_TEXEL_BYTES bytes a texel and any copies held on the way must fit in $ceiling bytes."

/**
 * The reason a [width] by [height] text raster is refused when it fit under its ceiling but the platform could not
 * allocate it.
 *
 * It names the whole raster's size and no ceiling, since the count passed and the device ran out on its own.
 */
internal fun textAllocationFailed(
  width: Int,
  height: Int,
): String = "The $width by $height text could not be drawn, since this device could not allocate its raster."
