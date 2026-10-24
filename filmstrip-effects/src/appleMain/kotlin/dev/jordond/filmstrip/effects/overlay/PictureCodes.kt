package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGColorSpaceCopyICCData
import platform.CoreGraphics.CGColorSpaceGetNumberOfComponents
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIImage
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.CGImageSourceRef
import platform.posix.memcpy

/**
 * Reads this source into Core Image. An image overlay whose picture is [PictureRead.Unreadable] here resolves as
 * unsupported, saying the image could not be decoded, and one refused resolves as unsupported with the reason.
 *
 * The source is read with no colour management at all, as the codes it holds, and the colour description it carries
 * goes along with them so the codes can be read as the colour it gives them. A path or a uri is copied into memory
 * once, here, and both the codes and the description come from that copy, so a file removed, replaced or rewritten in
 * place later changes neither.
 *
 * A picture ImageIO decodes as a HEIF is handed to it the way [imageIoLanding] says, so it lands through the matrix
 * core reads it with, through its stored samples and core's evaluator, or through the nearest matrix ImageIO reads,
 * degraded by [unreadMatrix]. A picture that cannot land is unsupported with the reason [imageIoLanding] gives. Any
 * other picture goes through [heifWithExplicitCodestreamColour], which leaves all but an AVIF or HEIC as it is. The
 * colour description always comes from the bytes as given.
 *
 * The codes come from [overlayCodes], and neither of its routes turns a picture by its EXIF or `irot` orientation.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ImageSource.read(): PictureRead<OverlayPixels> =
  when (this) {
    is ImageSource.Path -> {
      decoded(NSURL.fileURLWithPath(path))
    }
    is ImageSource.Uri -> {
      NSURL.URLWithString(uri)?.let(::decoded) ?: PictureRead.Unreadable
    }
    is ImageSource.Bytes -> {
      decoded(bytes)
    }
  }

// Read unmapped and copied, since a mapped file rewritten in place would change the bytes under a lazy decode.
@OptIn(ExperimentalForeignApi::class)
private fun decoded(url: NSURL): PictureRead<OverlayPixels> =
  NSData.dataWithContentsOfURL(url, options = 0u, error = null)?.toByteArray()?.let(::decoded)
    ?: PictureRead.Unreadable

@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
private fun decoded(bytes: ByteArray): PictureRead<OverlayPixels> {
  if (bytes.isEmpty()) return PictureRead.Unreadable
  val original = bytes.nsData()
  val imageIo = original.withImageSource { it.typeIdentifier() } in IMAGE_IO_DECODED
  val landing = if (imageIo) imageIoLanding(bytes) else ImageIoLanding.Decoded(heifWithExplicitCodestreamColour(bytes))
  val (data, limits) =
    when (landing) {
      is ImageIoLanding.Refused -> return PictureRead.Unsupported(landing.message)
      is ImageIoLanding.Decoded -> landing.bytes.nsData() to landing.limits
      is ImageIoLanding.Evaluated -> landing.bytes.nsData() to emptyList()
    }
  val codes = if (landing is ImageIoLanding.Evaluated) data.evaluatedCodes(landing) else data.overlayCodes()
  return codes.map { OverlayPixels(it, readColour = { colourOf(bytes, original) }, limits = limits) }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.nsData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * The encoded picture in this data as Core Image codes with no colour space, [PictureRead.Unreadable] when it holds no
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
 * What [read] makes of an ImageIO image source over this data, or null when ImageIO opens no source for it.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> NSData.withImageSource(read: (CGImageSourceRef) -> T?): T? {
  val data: CFDataRef = CFBridgingRetain(this)?.reinterpret() ?: return null
  val source = CGImageSourceCreateWithData(data, null)
  CFRelease(data)
  if (source == null) return null
  try {
    return read(source)
  } finally {
    CFRelease(source)
  }
}

/**
 * How the encoded picture in [bytes] describes its colour, or null when it is untagged. [data] holds the same bytes.
 *
 * The container's own description comes from [pictureColour]. A JPEG XL keeps its colour inside the codestream, so
 * its description is the profile ImageIO decodes it with.
 */
@OptIn(InternalFilmstripApi::class)
private fun colourOf(
  bytes: ByteArray,
  data: NSData,
): PictureColour? = pictureColour(bytes) ?: data.codestreamColour()

@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
private fun NSData.codestreamColour(): PictureColour? =
  withImageSource { source ->
    if (source.typeIdentifier() != JPEG_XL) return@withImageSource null
    val image = CGImageSourceCreateImageAtIndex(source, 0u, null) ?: return@withImageSource null
    val space = CGImageGetColorSpace(image)
    val channels = CGColorSpaceGetNumberOfComponents(space).toInt()
    val icc = CGColorSpaceCopyICCData(space)
    CGImageRelease(image)
    val profile = icc?.let { CFBridgingRelease(it) as? NSData }?.toByteArray() ?: return@withImageSource null
    PictureColour.fromDecodedProfile(profile, channels)
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
