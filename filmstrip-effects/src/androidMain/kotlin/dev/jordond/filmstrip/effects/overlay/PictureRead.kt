package dev.jordond.filmstrip.effects.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.heif.RestatedHeif
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithRestatedColor
import dev.jordond.filmstrip.picture.heif.heifWithoutOrientation
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureContainerOf
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.picture.pictureOrientationOf
import dev.jordond.filmstrip.picture.pictureWithoutOrientationTag
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.picture.withColorDescriptionsHidden
import java.io.File

/**
 * What reading an overlay picture for Android's decoder comes to: a [DecodedPicture], a [RefusedPicture], an
 * [UnreadablePicture] or a [ContextlessPicture].
 */
internal sealed interface PictureRead

/**
 * A decoded overlay picture, as the samples its file stores and how they describe their color.
 *
 * @property bitmap The stored samples, straight rather than premultiplied, untouched by any color conversion. A
 * sixteen-bit PNG decodes to half floats on API 26 and up, and a ten-bit AVIF on API 34 and up.
 * @property color How the picture describes its color, or null when it lands as an untagged one does.
 * @property limits Each way Android's decoder lands the picture short of what its file stores, as [decoderLimits]
 * decides, or empty when it lands as stored.
 * @property orientation The turn the picture's own tag asks for, as [pictureOrientationOf] reads it out of the file.
 * Android's decoder applies none, so [bitmap] holds the picture as stored and [rasterFor] lays it out by this.
 * @property crop The texels of [bitmap] an AVIF or HEIC's `clap` keeps, as [pictureCropOf] reads them out of the file,
 * or null for any other picture. Android's decoder is handed the file with its `clap` dropped, so [bitmap] holds the
 * whole stored frame and [rasterFor] cuts it to this before it turns it.
 */
internal class DecodedPicture(
  val bitmap: Bitmap,
  val color: PictureColor?,
  val limits: List<DecoderLimit> = emptyList(),
  val orientation: PictureOrientation = PictureOrientation.Normal,
  val crop: PictureCrop? = null,
) : PictureRead {
  /**
   * The size the picture is shown at, [bitmap] cut to [crop] and laid out by [orientation].
   */
  val shownSize: Size get() = orientation.orientedSize(crop?.size ?: bitmap.size())
}

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
 * A `content://` overlay picture read with no [Context] to open its provider through.
 */
internal data object ContextlessPicture : PictureRead

/**
 * Reads [this] and decodes it to its stored samples and its color.
 *
 * The decoder is handed the picture as [forAndroidDecoder] prepares it, so Android converts nothing, the color is
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
 * @param context Needed only to open a `content://` [ImageSource.Uri], which reads as a [ContextlessPicture] without
 * one. Every other form reads without it.
 */
internal fun ImageSource.read(context: Context?): PictureRead {
  val bytes =
    when (this) {
      is ImageSource.Path -> {
        readFile(path)
      }
      is ImageSource.Bytes -> {
        bytes
      }
      is ImageSource.Uri -> {
        val parsed = runCatching { uri.toUri() }.getOrNull()
        when {
          parsed == null -> null
          parsed.scheme == null || parsed.scheme == "file" -> parsed.path?.let(::readFile)
          context == null -> return ContextlessPicture
          else -> readContent(parsed, context)
        }
      }
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
  val crop = pictureCropOf(bytes)
  if (crop != null && !crop.liesWithin(bitmap.size())) return RefusedPicture(crop.refusalFor(bitmap.size()))
  return DecodedPicture(bitmap, pictureColor(bytes), limits, pictureOrientationOf(bytes), crop)
}

/**
 * A picture as [forAndroidDecoder] hands it to Android's decoder, with what is known of how the decoder lands it.
 *
 * @property bytes The picture the decoder is handed.
 * @property coding The coding core reads from the picture before it was rewritten, or null when it is not YCbCr coded.
 * @property conversion How the decoder lands the picture, as [YcbcrCoding.decoderConversionOf] gives it, or null when
 * that is left to the decoder or the picture is refused before it.
 * @property refusal What the overlay reports when the picture is refused before the decoder sees it, as
 * [refusalBeforeDecoder] gives it or as [DecoderConversion.refusal] gives it past the bound, or null when it is handed
 * over.
 */
internal class DecoderInput(
  val bytes: ByteArray,
  val coding: YcbcrCoding?,
  val conversion: DecoderConversion?,
  val refusal: String? = conversion?.refusal,
)

/**
 * This picture as Android's decoder on API level [sdk] is handed it, with every color description hidden by
 * [withColorDescriptionsHidden] and an AVIF or HEIC's codestream rewritten by [heifWithRestatedColor].
 *
 * A picture [refusalBeforeDecoder] refuses comes back as it is, with that refusal. Otherwise the rewrite makes the
 * codestream, and the `nclx` of the primary item and its tiles, state the range core reads for the picture and the
 * matrix and primaries [restatedForDecoder] gives for the [YcbcrCoding.reading] core reads from this picture as it is.
 * It runs while the `nclx` still holds its primaries and after its transfer is hidden, so the codestream states no
 * transfer the decoder could convert from. The `nclx` primaries are hidden after it. A tagged picture whose `irot` or
 * `imir` the rewrite refuses to drop comes back as it is, refused with [UnturnedHeif.Refused.message].
 *
 * A codestream core cannot rewrite, in a file with a `moov` box or with a unit that does not parse, reaches the decoder
 * as [RestatedHeif.Unrestated] gives it, with only its `irot`, `imir` and `clap` dropped. Its landing is then known
 * only as far as [YcbcrCoding.decoderConversionOf] can tell from what it states, and is null otherwise. A picture whose
 * landing is null that way is refused with [YcbcrCoding.unrestatedRefusal] when even the nearest matrix the decoder
 * reads lands it past the bound. A picture core reads no coding for has only its `irot`, `imir` and `clap` dropped, by
 * [heifWithoutOrientation].
 *
 * A JPEG, PNG, WebP or TIFF is handed over with its orientation tag written as 1 by [pictureWithoutOrientationTag], so
 * the decoder never sees the turn [rasterFor] already lays the picture out by.
 */
internal fun ByteArray.forAndroidDecoder(sdk: Int): DecoderInput {
  val coding = pictureYcbcrCoding(this)
  refusalBeforeDecoder(coding, sdk)?.let { return DecoderInput(this, coding, conversion = null, refusal = it) }
  val described = withColorDescriptionsHidden(keepNclxPrimaries = true)
  var handedFullRange: Boolean? = null
  val rewrite: RestatedHeif =
    if (coding == null) {
      when (val unturned = heifWithoutOrientation(described)) {
        is UnturnedHeif.Refused -> unturned
        is UnturnedHeif.Handed -> RestatedHeif.Unrestated(unturned.bytes)
      }
    } else {
      heifWithRestatedColor(described) { _, fullRange, _ ->
        handedFullRange = fullRange
        checkNotNull(restatedForDecoder(coding.reading, fullRange, sdk, coding.avif, coding.tenBitSamples))
      }
    }
  val handed =
    when (rewrite) {
      is UnturnedHeif.Refused -> return DecoderInput(this, coding, conversion = null, refusal = rewrite.message)
      is RestatedHeif.Restated -> rewrite.bytes
      is RestatedHeif.Unrestated -> rewrite.bytes
    }
  val unrestated = rewrite is RestatedHeif.Unrestated
  val conversion = coding?.decoderConversionOf(sdk, handedFullRange, restated = !unrestated)
  val refusal =
    conversion?.refusal ?: coding?.takeIf { unrestated && conversion == null }?.unrestatedRefusal(sdk, handedFullRange)
  return DecoderInput(pictureWithoutOrientationTag(handed.withColorDescriptionsHidden()), coding, conversion, refusal)
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
  ) + input.conversion?.limits.orEmpty() +
    listOfNotNull(DecoderLimit.AvifTwelveBitCut.takeIf { !halfFloat && input.coding?.twelveBitAvif == true })

// The bit depth sits in IHDR, the first chunk, right after the signature, the chunk's length and type, and the width
// and height.
private fun ByteArray.isSixteenBitPng(): Boolean =
  size > PNG_BIT_DEPTH &&
    pictureContainerOf(this) == PictureContainer.Png &&
    String(this, PNG_IHDR_TYPE, 4, Charsets.ISO_8859_1) == "IHDR" &&
    this[PNG_BIT_DEPTH].toInt() == SIXTEEN_BITS

private fun readFile(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

private fun readContent(
  uri: Uri,
  context: Context,
): ByteArray? =
  runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
      stream.readBytes()
    }
  }.getOrNull()

private const val PNG_IHDR_TYPE = 12
private const val PNG_BIT_DEPTH = 24
private const val SIXTEEN_BITS = 16
