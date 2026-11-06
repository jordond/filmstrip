package dev.jordond.filmstrip.avfoundation.internal.composition

import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.color.SD_PRIMARIES
import dev.jordond.filmstrip.color.sdPrimariesToBt709
import dev.jordond.filmstrip.internal.copyFormatDescription
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreVideo.CVBufferCopyAttachment
import platform.CoreVideo.CVColorPrimariesGetIntegerCodePointForString
import platform.CoreVideo.CVImageBufferRef
import platform.CoreVideo.CVTransferFunctionGetIntegerCodePointForString
import platform.CoreVideo.CVYCbCrMatrixGetIntegerCodePointForString
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import platform.Foundation.numberWithUnsignedInt

/**
 * The colour an SDR clip's frames decode with when SD primaries or an SD matrix is part of it, as ITU-T H.273 code
 * points.
 *
 * @property primaries The color primaries code point.
 * @property transfer The transfer characteristics code point.
 * @property matrix The matrix coefficients code point.
 */
internal data class SdSourceColor(
  val primaries: Int,
  val transfer: Int,
  val matrix: Int,
) {
  /**
   * Whether the primaries are SMPTE C's or EBU's, which sit outside BT.709.
   */
  val hasSdPrimaries: Boolean get() = primaries in SD_PRIMARIES

  /**
   * Whether the matrix is BT.601's.
   */
  val hasSdMatrix: Boolean get() = matrix in SD_MATRICES

  /**
   * The matrix taking linear light on these primaries to linear light on BT.709's, or null on primaries that are not
   * SD.
   */
  val primariesToBt709: DoubleArray? = if (hasSdPrimaries) sdPrimariesToBt709(primaries) else null
}

/**
 * The SD colour this asset's first video track decodes with, or null when it decodes with none.
 *
 * A track tagged with primaries, transfer and matrix answers from its format description. Anything else takes what
 * VideoToolbox fills in on the first frame, the colour the clip plays with on this OS, which costs one frame's decode.
 * A track with no tags at all and a side past [UNTAGGED_READ_LONGEST_SIDE] skips that frame and reads as having none. A
 * frame that cannot be read also gives null.
 *
 * @param longestSide The longer of the track's width and height.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun AVAsset.sdSourceColor(longestSide: Int): SdSourceColor? {
  val track = tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return null
  val tagged = track.formatTags()
  val tags =
    when {
      tagged.isComplete -> tagged
      tagged == ColorTags() && longestSide > UNTAGGED_READ_LONGEST_SIDE -> return null
      else -> firstFrameTags(track) ?: return null
    }
  return tags.sdColor()
}

/**
 * Whether this asset's first video track draws through VideoToolbox's pixel transfer beside an HDR clip.
 *
 * A BT.709 clip and an SD clip do not, and anything else does. A track that names part of its colour or none of it
 * answers from its first frame, whatever its size, and one with no video or no frame that reads counts as needing it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun AVAsset.needsPixelTransfer(): Boolean {
  val track = tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return true
  val tagged = track.formatTags()
  val tags = (if (tagged.isComplete) tagged else firstFrameTags(track)) ?: return true
  return !tags.isBt709 && tags.sdColor() == null
}

@OptIn(ExperimentalForeignApi::class)
private fun AVAssetTrack.formatTags(): ColorTags {
  val format = copyFormatDescription()
  try {
    return format?.colorTags() ?: ColorTags()
  } finally {
    format?.let(::CFRelease)
  }
}

/**
 * What the first frame AVFoundation decodes from [track] says of its colour, or null when no frame reads.
 *
 * The reader's settings are a real dictionary, since a bridged Kotlin map is not one once it crosses into Apple's media
 * APIs.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun AVAsset.firstFrameTags(track: AVAssetTrack): ColorTags? {
  val reader = AVAssetReader.assetReaderWithAsset(this, error = null) ?: return null
  val settings =
    NSDictionary.dictionaryWithObject(
      NSNumber.numberWithUnsignedInt(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
      forKey = NSString.create(string = PIXEL_FORMAT_KEY),
    )
  val output = AVAssetReaderTrackOutput(track = track, outputSettings = settings)
  if (!reader.canAddOutput(output)) return null
  reader.addOutput(output)
  if (!reader.startReading()) return null
  try {
    val sample = output.copyNextSampleBuffer() ?: return null
    try {
      return CMSampleBufferGetImageBuffer(sample)?.colorTags()
    } finally {
      CFRelease(sample)
    }
  } finally {
    reader.cancelReading()
  }
}

/**
 * The code points a format description or a frame names, each null when it names nothing.
 */
private data class ColorTags(
  val primaries: Int? = null,
  val transfer: Int? = null,
  val matrix: Int? = null,
) {
  val isComplete: Boolean get() = primaries != null && transfer != null && matrix != null
  val isBt709: Boolean get() =
    primaries == BT709_CODE_POINT && transfer == BT709_CODE_POINT &&
      matrix == BT709_CODE_POINT

  fun sdColor(): SdSourceColor? {
    val color = SdSourceColor(primaries ?: return null, transfer ?: return null, matrix ?: return null)
    return color.takeIf { it.hasSdPrimaries || it.hasSdMatrix }
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CMFormatDescriptionRef.colorTags(): ColorTags {
  fun read(
    key: CFStringRef?,
    toCode: (CFStringRef?) -> Int,
  ) = CMFormatDescriptionGetExtension(this, key).codePoint(toCode)
  return ColorTags(
    primaries = read(kCVImageBufferColorPrimariesKey, ::CVColorPrimariesGetIntegerCodePointForString),
    transfer = read(kCVImageBufferTransferFunctionKey, ::CVTransferFunctionGetIntegerCodePointForString),
    matrix = read(kCVImageBufferYCbCrMatrixKey, ::CVYCbCrMatrixGetIntegerCodePointForString),
  )
}

@OptIn(ExperimentalForeignApi::class)
private fun CVImageBufferRef.colorTags(): ColorTags {
  fun read(
    key: CFStringRef?,
    toCode: (CFStringRef?) -> Int,
  ): Int? {
    val value = CVBufferCopyAttachment(this, key, null) ?: return null
    try {
      return value.codePoint(toCode)
    } finally {
      CFRelease(value)
    }
  }
  return ColorTags(
    primaries = read(kCVImageBufferColorPrimariesKey, ::CVColorPrimariesGetIntegerCodePointForString),
    transfer = read(kCVImageBufferTransferFunctionKey, ::CVTransferFunctionGetIntegerCodePointForString),
    matrix = read(kCVImageBufferYCbCrMatrixKey, ::CVYCbCrMatrixGetIntegerCodePointForString),
  )
}

@OptIn(ExperimentalForeignApi::class)
private fun CFTypeRef?.codePoint(toCode: (CFStringRef?) -> Int): Int? {
  if (this == null || CFGetTypeID(this) != CFStringGetTypeID()) return null
  return toCode(reinterpret()).takeIf { it != UNSPECIFIED_CODE_POINT }
}

private const val UNSPECIFIED_CODE_POINT = 2
private const val BT709_CODE_POINT = 1

// A bound on which clips decode a frame here. What the frame says still decides the colour.
private const val UNTAGGED_READ_LONGEST_SIDE = 1024

private val SD_MATRICES = setOf(5, 6)
