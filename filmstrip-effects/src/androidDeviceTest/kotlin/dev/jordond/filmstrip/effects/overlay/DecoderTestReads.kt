package dev.jordond.filmstrip.effects.overlay

import android.content.Context
import android.os.Build
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.ycbcrToRgb
import kotlin.math.abs

/**
 * Why a HEIC device test skips on an Android whose decoder hands back no frame for the [ColorPictures] HEIC stills.
 */
internal const val HEIC_FIXTURE_UNDECODED =
  "Android's HEIC decoder hands back no frame for the 64 by 64 ColorPictures HEIC stills"

/**
 * The packed opaque ARGB color these luma, blue difference and red difference codes turn into through the ITU-T H.273
 * [matrix] beside [primaries] and [transfer] at full or limited range, as [ycbcrToRgb] decodes them.
 */
internal fun List<Int>.argbThrough(
  matrix: Int,
  primaries: Int,
  transfer: Int,
  fullRange: Boolean,
): Int =
  ycbcrToRgb(this[0], this[1], this[2], matrix, primaries, transfer, fullRange)
    .fold(OPAQUE) { argb, code -> (argb shl 8) or code }

/**
 * The packed opaque ARGB color these codes turn into through [matrix] at full or limited range, as [argbThrough] gives
 * it beside the primaries and transfer core reads for [picture].
 */
internal fun List<Int>.argbThrough(
  matrix: Int,
  fullRange: Boolean,
  picture: ByteArray,
): Int {
  val coding = checkNotNull(pictureYcbcrCoding(picture)) { "core reads no YCbCr coding for this picture" }
  return argbThrough(matrix, coding.primaries, coding.transfer, fullRange)
}

/**
 * The most any of red, green and blue differ between two packed colors.
 */
internal fun channelsOff(
  read: Int,
  want: Int,
): Int = intArrayOf(16, 8, 0).maxOf { abs((read ushr it and 0xFF) - (want ushr it and 0xFF)) }

internal fun Int.hex(): String = toUInt().toString(16)

/**
 * How Android's decoder on this device lands this picture, as [forAndroidDecoder] finds when it prepares it.
 *
 * Null when the picture is neither an AVIF nor a HEIC, or its landing is left to the decoder.
 */
internal fun ByteArray.conversionHere(): DecoderConversion? = forAndroidDecoder(Build.VERSION.SDK_INT).conversion

/**
 * How Android's decoder on this device lands the reading core gives this AVIF or HEIC, at [fullRange] or the range core
 * reads for it, as [decoderConversionOf] gives it.
 *
 * Null when core refuses the picture, as for one naming the identity matrix on subsampled samples or a matrix core
 * reads no picture through.
 */
internal fun ByteArray.ownConversion(fullRange: Boolean? = null): DecoderConversion? {
  val coding = checkNotNull(pictureYcbcrCoding(this)) { "core reads no YCbCr coding for this picture" }
  if (coding.refusal != null) return null
  val range = fullRange ?: coding.fullRange
  return decoderConversionOf(coding.reading, range, Build.VERSION.SDK_INT, coding.avif, coding.tenBitSamples)
}

/**
 * Whether a picture whose [ownConversion] is this is refused rather than drawn, because core refuses it when this is
 * null or because it lands past the bound.
 */
internal val DecoderConversion?.refused: Boolean
  get() = this == null || refusal != null

/**
 * What reading this picture as an overlay does misses when it should be refused, as [refused] says of [conversion], or
 * null when it comes to a [RefusedPicture] naming the [DecoderConversion.refusal] of [conversion], or core's own
 * refusal when [conversion] is null.
 */
internal fun ByteArray.refusalMiss(
  what: String,
  conversion: DecoderConversion?,
): String? {
  val want = conversion?.refusal ?: pictureYcbcrCoding(this)?.refusal
  val picture = ImageSource.Bytes(this).read(context = null)
  val reason = (picture as? RefusedPicture)?.reason
  return "$what came to $picture, ${reason ?: "drawn"}, where it should be refused as: $want".takeIf { reason != want }
}

/**
 * Reads this picture as an overlay does, or null when it comes to anything but a [DecodedPicture].
 */
internal fun ImageSource.decode(context: Context?): DecodedPicture? = read(context) as? DecodedPicture

private const val OPAQUE = 0xFF
