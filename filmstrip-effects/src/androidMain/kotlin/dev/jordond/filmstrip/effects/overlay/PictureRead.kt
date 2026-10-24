package dev.jordond.filmstrip.effects.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import androidx.core.net.toUri
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.heifWithRestatedColour
import java.io.File

/**
 * What reading an overlay picture for Android's decoder comes to: a [DecodedPicture], a [RefusedPicture] or
 * [UnreadablePicture].
 */
internal sealed interface PictureRead

/**
 * A decoded overlay picture, as the samples its file stores and how they describe their colour.
 *
 * @property bitmap The stored samples, straight rather than premultiplied, untouched by any colour conversion. A
 * sixteen-bit PNG decodes to half floats on API 26 and up, and a ten-bit AVIF on API 34 and up.
 * @property colour How the picture describes its colour, or null when it lands as an untagged one does.
 * @property limits Each way Android's decoder lands the picture short of what its file stores, as [decoderLimits]
 * decides, or empty when it lands as stored.
 */
internal class DecodedPicture(
  val bitmap: Bitmap,
  val colour: PictureColour?,
  val limits: List<DecoderLimit> = emptyList(),
) : PictureRead

/**
 * An overlay picture that is refused before or by Android's decoder, for the reason [reason] gives.
 *
 * @property reason What the refused overlay reports. It is the [DecoderInput.refusal] of a picture refused before the
 * decoder, and otherwise the message of the [DecoderRefusal] [noFrameRefusal] names.
 */
internal class RefusedPicture(
  val reason: String,
) : PictureRead

/**
 * An overlay picture whose bytes could not be read from its path, Uri or `content://` provider.
 */
internal data object UnreadablePicture : PictureRead

/**
 * Why Android's decoder hands back no frame for an overlay picture, each Android's limit rather than filmstrip's.
 *
 * @property message What the refused overlay reports, naming Android's decoder.
 */
internal enum class DecoderRefusal(
  val message: String,
) {
  /**
   * A picture in a format this API level does not decode, or one too damaged for its decoder to read.
   */
  NoFrame(
    "Android's decoder on this API level hands back no picture for the overlay image. It decodes PNG, JPEG, WebP, " +
      "GIF and BMP, HEIC from API 28 and AVIF from API 31, and nothing it cannot parse.",
  ),

  /**
   * A ten-bit AVIF decoded on API 33, where Android's AVIF decoder hands back no frame for it.
   */
  AvifTenBitNoFrame(
    "Android's AVIF decoder on API 33 hands back no frame for a ten-bit AVIF, so this overlay picture cannot be " +
      "drawn on this API level.",
  ),

  /**
   * A twelve-bit HEIC, which Android's HEIC decoder on API 30, 31 and 33 to 36 hands back no frame for. API 28 and 29
   * are given API 30's answer and API 32 is given API 31's.
   */
  HeicTwelveBitNoFrame(
    "Android's HEIC decoder hands back no frame for a twelve-bit HEIC on API 30, 31 and 33 to 36, and API 28, 29 and " +
      "32 are taken to do as those do, so this overlay picture cannot be drawn on this API level.",
  ),

  /**
   * A twelve-bit AVIF below API 36. Android's AVIF decoder on API 31, 33 and 34 hands back no frame for it and on API
   * 35 hands back the wrong colours, so it is refused before the decoder sees it. API 32 is given API 31's answer.
   */
  AvifTwelveBit(
    "Android's AVIF decoder draws a twelve-bit AVIF only from API 36. On API 31, 33 and 34 it hands back no frame " +
      "for one and on API 35 the wrong colours, and API 32 is taken to do as API 31 does, so this overlay picture " +
      "cannot be drawn on this API level.",
  ),
}

/**
 * Reads [this] and decodes it to its stored samples and its colour.
 *
 * The decoder is handed the picture as [forAndroidDecoder] prepares it, so Android converts nothing, the colour is
 * applied once, by [rasterFor], and an AVIF or HEIC lands with the matrix and range core reads for it when Android's
 * decoder can read them, or the nearest matrix it reads. One whose [DecoderInput.refusal] is set never reaches the
 * decoder.
 *
 * A picture Android decodes deeper than eight bits a sample, a sixteen-bit PNG or a ten-bit AVIF, is decoded to half
 * floats in extended sRGB instead, which keeps its samples where the file stores them. API 24 and 25 have no half float
 * bitmaps and cut a sixteen-bit PNG to eight bits.
 *
 * A twelve-bit AVIF decodes to eight bits a sample on every API level that draws it. [DecodedPicture.limits] reports
 * that and every other shortfall, and a picture the decoder hands back no frame for is a [RefusedPicture] naming why.
 *
 * @param context Needed only to open a `content://` [ImageSource.Uri]. Every other form reads without one.
 */
internal fun ImageSource.read(context: Context?): PictureRead {
  val bytes =
    when (this) {
      is ImageSource.Path -> readFile(path)
      is ImageSource.Bytes -> bytes
      is ImageSource.Uri -> readUri(uri, context)
    } ?: return UnreadablePicture
  val sdk = Build.VERSION.SDK_INT
  val input = bytes.forAndroidDecoder(sdk)
  input.refusal?.let { return RefusedPicture(it) }
  val prepared = input.bytes
  val options = BitmapFactory.Options().apply { inPremultiplied = false }
  val shape = options.readShape(prepared)
  options.keepWideSamples(shape)
  val bitmap =
    BitmapFactory.decodeByteArray(prepared, 0, prepared.size, options)
      ?: return RefusedPicture(noFrameRefusal(input.coding, sdk).message)
  val limits = decoderLimits(bytes, input, bitmap.halfFloatCurve() != null)
  return DecodedPicture(bitmap, pictureColour(bytes), limits)
}

/**
 * A picture as [forAndroidDecoder] hands it to Android's decoder, with what is known of how the decoder lands it.
 *
 * @property bytes The picture the decoder is handed.
 * @property coding The coding core reads from the picture before it was rewritten, or null when it is not YCbCr coded.
 * @property landing How the decoder lands the picture, as [YcbcrCoding.decoderLanding] gives it, or null when that is
 * left to the decoder or the picture is refused before it.
 * @property refusal What the overlay reports when the picture is refused before the decoder sees it, as
 * [refusalBeforeDecoder] gives it or as [DecoderLanding.refusal] gives it past the bound, or null when it is handed
 * over.
 */
internal class DecoderInput(
  val bytes: ByteArray,
  val coding: YcbcrCoding?,
  val landing: DecoderLanding?,
  val refusal: String? = landing?.refusal,
)

/**
 * This picture as Android's decoder on API level [sdk] is handed it, with every colour description hidden by
 * [withColourDescriptionsHidden] and an AVIF or HEIC's codestream rewritten by [heifWithRestatedColour].
 *
 * A picture [refusalBeforeDecoder] refuses comes back as it is, with that refusal. Otherwise the rewrite makes the
 * codestream, and the `nclx` of the primary item and its tiles, state the range core reads for the picture and the
 * matrix and primaries [restatedForDecoder] gives for the [YcbcrCoding.reading] core reads from this picture as it is.
 * It runs while the `nclx` still holds its primaries and after its transfer is hidden, so the codestream states no
 * transfer the decoder could convert from. The `nclx` primaries are hidden after it.
 *
 * A codestream core cannot rewrite, in a file with a `moov` box or with a unit that does not parse, reaches the decoder
 * as it was. Its landing is then known only as far as [YcbcrCoding.decoderLanding] can tell from what it states, and
 * is null otherwise. A picture whose landing is null that way is refused with [YcbcrCoding.unrestatedRefusal] when
 * even the nearest matrix the decoder reads lands it past the bound.
 */
internal fun ByteArray.forAndroidDecoder(sdk: Int): DecoderInput {
  val coding = pictureYcbcrCoding(this)
  refusalBeforeDecoder(coding, sdk)?.let { return DecoderInput(this, coding, landing = null, refusal = it) }
  val described = withColourDescriptionsHidden(keepNclxPrimaries = true)
  var handedFullRange: Boolean? = null
  val restated =
    if (coding == null) {
      described
    } else {
      heifWithRestatedColour(described) { _, fullRange, _ ->
        handedFullRange = fullRange
        checkNotNull(restatedForDecoder(coding.reading, fullRange, sdk, coding.avif, coding.tenBitSamples))
      }
    }
  val unrestated = restated === described
  val landing = coding?.decoderLanding(sdk, handedFullRange, restated = !unrestated)
  val refusal =
    landing?.refusal ?: coding?.takeIf { unrestated && landing == null }?.unrestatedRefusal(sdk, handedFullRange)
  return DecoderInput(restated.withColourDescriptionsHidden(), coding, landing, refusal)
}

// What a bounds decode reads off a picture: whether the decoder hands it back at ten bits a sample, in RGBA_1010102,
// which it does from API 33, and whether it decodes to half floats.
private class PictureShape(
  val packedTenBit: Boolean,
  val halfFloat: Boolean,
)

private fun BitmapFactory.Options.readShape(picture: ByteArray): PictureShape {
  inJustDecodeBounds = true
  BitmapFactory.decodeByteArray(picture, 0, picture.size, this)
  inJustDecodeBounds = false
  val sdk = Build.VERSION.SDK_INT
  return PictureShape(
    packedTenBit = sdk >= Build.VERSION_CODES.TIRAMISU && outConfig == Bitmap.Config.RGBA_1010102,
    halfFloat = sdk >= Build.VERSION_CODES.O && outConfig == Bitmap.Config.RGBA_F16,
  )
}

// Android decodes a sixteen-bit picture to half floats, and into linear light on some versions unless asked for
// extended sRGB, whose curve matches the sRGB an undescribed picture is read as. halfFloatCurve reads which it got. A
// ten-bit picture decodes to RGBA_1010102 from API 33, which the raster reads only as eight-bit codes, so it is asked
// for half floats in extended sRGB as well, which hold its ten bits.
private fun BitmapFactory.Options.keepWideSamples(shape: PictureShape) {
  if (shape.halfFloat || shape.packedTenBit) {
    inPreferredConfig = Bitmap.Config.RGBA_F16
    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
  }
}

/**
 * Each way Android's decoder lands [picture] short of what its file stores, or empty when it lands as stored.
 *
 * A sixteen-bit PNG decoded into a bitmap that is not half floats is cut to eight bits. It is the only picture over
 * eight bits that API 24 and 25 decode at all, and they have no half float bitmap to decode it into. An AVIF or HEIC
 * that lands through another matrix or range than its own reports the limits its landing names. A
 * [YcbcrCoding.twelveBitAvif] picture decoded into a bitmap that is not half floats then reports
 * [DecoderLimit.AvifTwelveBitCut] as well.
 *
 * @param input The picture as [forAndroidDecoder] prepared it, with its coding and how the decoder lands it.
 * @param halfFloat Whether the bitmap it decoded into holds half floats.
 */
internal fun decoderLimits(
  picture: ByteArray,
  input: DecoderInput,
  halfFloat: Boolean,
): List<DecoderLimit> =
  listOfNotNull(
    DecoderLimit.EightBitCut.takeIf { !halfFloat && picture.isSixteenBitPng() },
  ) + input.landing?.limits.orEmpty() +
    listOfNotNull(DecoderLimit.AvifTwelveBitCut.takeIf { !halfFloat && input.coding?.twelveBitAvif == true })

// The bit depth sits in IHDR, the first chunk, right after the signature, the chunk's length and type, and the width
// and height.
private fun ByteArray.isSixteenBitPng(): Boolean =
  size > PNG_BIT_DEPTH &&
    PNG_SIGNATURE.indices.all { this[it] == PNG_SIGNATURE[it] } &&
    String(this, PNG_IHDR_TYPE, 4, Charsets.ISO_8859_1) == "IHDR" &&
    this[PNG_BIT_DEPTH].toInt() == SIXTEEN_BITS

private fun readFile(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

private fun readUri(
  uri: String,
  context: Context?,
): ByteArray? {
  val parsed = runCatching { uri.toUri() }.getOrNull() ?: return null
  if (parsed.scheme == null || parsed.scheme == "file") {
    return parsed.path?.let(::readFile)
  }
  val resolver = context?.contentResolver ?: return null
  return runCatching {
    resolver.openInputStream(parsed)?.use { stream -> stream.readBytes() }
  }.getOrNull()
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private const val PNG_IHDR_TYPE = 12
private const val PNG_BIT_DEPTH = 24
private const val SIXTEEN_BITS = 16
