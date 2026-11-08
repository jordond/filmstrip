package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureColorFromDecodedProfile
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.picture.pictureOrientationOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGColorSpaceCopyICCData
import platform.CoreGraphics.CGColorSpaceGetNumberOfComponents
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIImage
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.CGImageSourceRef
import platform.posix.memcpy

/**
 * Reads this source into Core Image. An image overlay whose picture is [PictureRead.Unreadable] here resolves as
 * unsupported, saying the image could not be decoded, and one refused resolves as unsupported with the reason.
 *
 * The source is read with no color management at all, as the codes it holds, and the color description it carries
 * goes along with them so the codes can be read as the color it gives them. A path or a uri is copied into memory
 * once, here, and both the codes and the description come from that copy, so a file removed, replaced or rewritten in
 * place later changes neither.
 *
 * A picture ImageIO decodes as a HEIF is handed to it the way [imageIoRouteOf] says, so it lands through the matrix
 * core reads it with, through its stored samples and core's evaluator, or through the nearest matrix ImageIO reads,
 * degraded by [unreadMatrix]. A picture that cannot land is unsupported with the reason [imageIoRouteOf] gives. Any
 * other picture goes through [heifWithExplicitCodestreamColor], which leaves all but an AVIF or HEIC as it is. The
 * color description always comes from the bytes as given.
 *
 * The codes come from [overlayCodes], and neither of its routes turns a picture by its orientation tag, a JPEG XL's
 * codestream orientation included. [OverlayPixels] lays them out by the orientation [pictureOrientationOf] reads out
 * of the bytes as given instead, so the picture draws the same way up as on every other backend. Neither route sees a
 * `clap` either, since the drop takes it off, and [OverlayPixels] cuts the codes to the rectangle [pictureCropOf]
 * reads before it turns them. Codes that rectangle does not lie within are refused with
 * [dev.jordond.filmstrip.picture.PictureCrop.refusalFor].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ImageSource.read(): PictureRead<OverlayPixels> = decodedPicture(encoded())

/**
 * The bytes of [this] as one read gives them, or null when they cannot be read.
 */
internal fun ImageSource.encoded(): ByteArray? = encodedRead().bytes

/**
 * One read of the bytes of [this], refused past [ceiling] with the reason [pictureTooLargeToRead] gives.
 *
 * Read unmapped and copied, since a mapped file rewritten in place would change the bytes under a lazy decode.
 *
 * @param ceiling The most encoded bytes the read may hold, [PICTURE_PEAK_CEILING] by default.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ImageSource.encodedRead(ceiling: Long = PICTURE_PEAK_CEILING): HeldBytes {
  val url =
    when (this) {
      is ImageSource.Path -> NSURL.fileURLWithPath(path)
      is ImageSource.Uri -> NSURL.URLWithString(uri)
      is ImageSource.Bytes -> return HeldBytes(bytes)
    } ?: return HeldBytes(null)
  val tooLarge = HeldBytes(null, pictureTooLargeToRead(this, ceiling))
  if ((url.fileLength() ?: 0L) > ceiling) return tooLarge
  // NSData's limit: a remote URL gives no length ahead of the read and no way to stop it, so it is only counted after.
  val data = NSData.dataWithContentsOfURL(url, options = 0u, error = null) ?: return HeldBytes(null)
  return if (data.length.toLong() > ceiling) tooLarge else HeldBytes(data.toByteArray())
}

// The length of the file this URL names, or null when it names none.
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.fileLength(): Long? {
  if (!fileURL) return null
  val path = path ?: return null
  val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
  return (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue
}

/**
 * Decodes one read of a picture, as [ImageSource.read] does, with null for a read that found nothing.
 */
internal fun decodedPicture(bytes: ByteArray?): PictureRead<OverlayPixels> =
  bytes?.let(::decoded) ?: PictureRead.Unreadable

@OptIn(ExperimentalForeignApi::class)
private fun decoded(bytes: ByteArray): PictureRead<OverlayPixels> {
  if (bytes.isEmpty()) return PictureRead.Unreadable
  val original = bytes.nsData()
  val imageIo = original.withImageSource { it.typeIdentifier() } in IMAGE_IO_DECODED
  val route = if (imageIo) imageIoRouteOf(bytes) else explicitRoute(bytes)
  val (data, limits) =
    when (route) {
      is PictureRoute.Refused -> return PictureRead.Unsupported(route.message)
      is PictureRoute.Decoded -> route.bytes.nsData() to route.limits
      is PictureRoute.Evaluated -> route.bytes.nsData() to emptyList()
    }
  val codes = if (route is PictureRoute.Evaluated) data.evaluatedCodes(route) else data.overlayCodes()
  val held =
    when (codes) {
      is PictureRead.Read -> codes.value
      is PictureRead.Unreadable -> return codes
      is PictureRead.Unsupported -> return codes
      is PictureRead.Refused -> return codes
    }
  val crop = pictureCropOf(bytes)
  val stored = held.image.pixelSize()
  if (crop != null && !crop.liesWithin(stored)) return PictureRead.Unsupported(crop.refusalFor(stored))
  val orientation = pictureOrientationOf(bytes)
  val pixels =
    OverlayPixels(held, { colorOf(bytes, original) }, limits = limits, orientation = orientation, crop = crop)
  return PictureRead.Read(pixels)
}

/**
 * These bytes copied into an [NSData], the form ImageIO and Core Image read a picture from.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.nsData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * The encoded picture in this data as Core Image codes with no color space, [PictureRead.Unreadable] when it holds no
 * picture ImageIO or Core Image decodes.
 *
 * A HEIF ImageIO names `public.heic`, `public.heif`, `public.heics` or `public.avif` is decoded here, eagerly, by
 * ImageIO, and Core Image draws the samples [bitmapCodes] copies out of it, so it reads the same samples every time.
 * ImageIO converts the codestream's YCbCr to whole codes at the depth it decodes to, eight bits for an eight-bit
 * picture, ten for a ten-bit one and sixteen for a twelve-bit one. On the HEIC fixtures that puts an eight-bit sample
 * up to 1.38 codes from its ITU-T H.273 reading. Such a picture is refused with [bitmapCodes]'s reason when ImageIO
 * hands back samples it cannot copy out, which a grey HEIC with alpha and a twelve-bit HEIC at limited range do.
 *
 * Any other picture is a Core Image recipe that decodes the first time a context renders it. That includes a HEIF coded
 * with H.264, which ImageIO names `public.avci`. How Core Image decodes one, and whether it reads the same samples
 * every time, is unmeasured, since ImageIO writes no such HEIF to measure it with.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.overlayCodes(): PictureRead<HeldCodes> =
  withImageSource { source ->
    if (source.typeIdentifier() in IMAGE_IO_DECODED) {
      source.bitmapCodes()
    } else {
      val codes = CIImage.imageWithData(this, unmanagedOptions()) ?: return@withImageSource PictureRead.Unreadable
      PictureRead.Read(HeldCodes(codes, source.decodedBytes()))
    }
  } ?: PictureRead.Unreadable

@OptIn(ExperimentalForeignApi::class)
private fun CGImageSourceRef.typeIdentifier(): String? =
  CGImageSourceGetType(this)?.let { CFBridgingRelease(CFRetain(it)) as? String }

/**
 * How the encoded picture in [bytes] describes its color, or null when it is untagged. [data] holds the same bytes.
 *
 * The container's own description comes from [pictureColor]. A JPEG XL keeps its color inside the codestream, so
 * its description is the profile ImageIO decodes it with.
 */
private fun colorOf(
  bytes: ByteArray,
  data: NSData,
): PictureColor? = pictureColor(bytes) ?: data.codestreamColor()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.codestreamColor(): PictureColor? =
  withImageSource { source ->
    if (source.typeIdentifier() != JPEG_XL) return@withImageSource null
    val image = CGImageSourceCreateImageAtIndex(source, 0u, null) ?: return@withImageSource null
    val space = CGImageGetColorSpace(image)
    val channels = CGColorSpaceGetNumberOfComponents(space).toInt()
    val icc = CGColorSpaceCopyICCData(space)
    CGImageRelease(image)
    val profile = icc?.let { CFBridgingRelease(it) as? NSData }?.toByteArray() ?: return@withImageSource null
    pictureColorFromDecodedProfile(profile, channels)
  }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
  val copy = ByteArray(length.toInt())
  if (copy.isNotEmpty()) copy.usePinned { memcpy(it.addressOf(0), bytes, length) }
  return copy
}

// The uniform type identifier ImageIO names a JPEG XL by.
private const val JPEG_XL = "public.jpeg-xl"

// The uniform type identifiers ImageIO names a HEIF coded with HEVC or AV1 by.
private val IMAGE_IO_DECODED = setOf("public.heic", "public.heif", "public.heics", "public.avif")
