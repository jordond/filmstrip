package dev.jordond.filmstrip.avfoundation.internal.composition

import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.toNSURL
import dev.jordond.filmstrip.internal.copyFormatDescription
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCompare
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFCompareEqualTo
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2

/**
 * Whether this platform's AVFoundation keeps a BT.601 clip's codes when a composition names BT.709.
 *
 * macOS relabels such a frame as BT.709 and leaves its YCbCr alone, and iOS converts it. A clip drawn beside HDR has to
 * read what the same clip reads on its own, so the tone map compositor follows whichever this platform does.
 */
internal expect val relabelsBt601AsBt709: Boolean

/**
 * The pixel format an SDR clip's frame is converted into on its way to BT.709.
 *
 * This is BGRA on macOS and eight-bit YCbCr on iOS, each the format whose read lands closest to what AVFoundation makes
 * of the same clip under a composition naming BT.709. The simulator reads the same codes and macOS reads within two,
 * until a BT.2020 colour falls far outside BT.709. There macOS's own conversion pulls the colour in where this one
 * clips it, up to 20 codes apart, and a compositor cannot reach that conversion.
 */
internal expect val sdrTransferFormat: UInt

/**
 * Whether this OS has VideoToolbox's pixel transfer session.
 */
internal expect val hasPixelTransferSession: Boolean

/**
 * Why this OS cannot draw an SDR clip that is not BT.709 beside an HDR clip, or null when it can.
 *
 * @param available Whether the pixel transfer that draws it exists here.
 */
internal fun sdrTransferLimit(available: Boolean = hasPixelTransferSession): String? =
  SDR_TRANSFER_LIMIT.takeUnless { available }

/**
 * Why this OS cannot draw [sources] beside an HDR clip, or null when it can.
 *
 * A source is only opened on an OS without the pixel transfer. One that says nothing of its colour, or that cannot be
 * opened here, counts as not BT.709, since the compositor would convert its frames too. A buffer that can't be written
 * to a file is one that cannot be opened.
 *
 * @param bytesRoot The directory an in-memory source lands under.
 * @param available Whether the pixel transfer that draws such a clip exists here.
 */
internal fun sdrTransferLimit(
  sources: List<MediaSource>,
  bytesRoot: String,
  available: Boolean = hasPixelTransferSession,
): String? = if (available || sources.all { it.isTaggedBt709(bytesRoot) }) null else sdrTransferLimit(available = false)

@OptIn(ExperimentalForeignApi::class)
private fun MediaSource.isTaggedBt709(bytesRoot: String): Boolean {
  val url =
    try {
      toNSURL(bytesRoot)
    } catch (_: AvLoweringFailure) {
      return false
    } catch (_: MediaBytesFileFailure) {
      return false
    }
  val track = AVURLAsset(uRL = url, options = null).tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
  val format = track?.copyFormatDescription() ?: return false
  try {
    return format.carries(kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_709_2) &&
      format.carries(kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_ITU_R_709_2) &&
      format.carries(kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_709_2)
  } finally {
    CFRelease(format)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CMFormatDescriptionRef.carries(
  key: CFStringRef?,
  value: CFStringRef?,
): Boolean {
  val found = CMFormatDescriptionGetExtension(this, key) ?: return false
  if (CFGetTypeID(found) != CFStringGetTypeID()) return false
  return CFStringCompare(found.reinterpret(), value, 0uL) == kCFCompareEqualTo
}

private const val SDR_TRANSFER_LIMIT =
  "Drawing an SDR clip that is not BT.709 in a tone mapped export takes VideoToolbox's pixel transfer session, which " +
    "takes iOS 16."
