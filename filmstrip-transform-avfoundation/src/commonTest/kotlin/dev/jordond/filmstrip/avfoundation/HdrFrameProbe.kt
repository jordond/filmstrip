package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.videoReaderSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceExtendedLinearITUR_2020
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.Foundation.NSURL

/**
 * One decoded frame of an HDR file, held as linear BT.2020 light.
 *
 * [FrameProbe] draws through an eight-bit device RGB context, which tone-maps a grade away before
 * anything can be measured on it. This reads the frame Core Image's own way instead: display
 * referred linear light, normalised so `1f` is [HDR_DISPLAY_UNIT_NITS], which is the domain a
 * Core Image effect runs in and so the one a lowering has to be right in.
 */
internal class HdrFrameProbe(
  private val width: Int,
  private val height: Int,
  private val pixels: FloatArray,
) {
  /**
   * The linear red, green and blue at ([xFraction], [yFraction]), in cd/m2.
   */
  fun nitsAt(
    xFraction: Float,
    yFraction: Float,
  ): List<Float> {
    val x = (width * xFraction).toInt().coerceIn(0, width - 1)
    val y = (height * yFraction).toInt().coerceIn(0, height - 1)
    val offset = (y * width + x) * CHANNELS

    return List(3) { pixels[offset + it] * HDR_DISPLAY_UNIT_NITS }
  }

  private companion object {
    const val CHANNELS = 4
  }
}

/**
 * Decodes the first video frame of [path] as linear light, or null when there is no video track.
 *
 * The reader is asked for a ten-bit buffer rather than whatever the file happens to carry, so a
 * frame that arrives eight-bit is the decode being wrong rather than the measurement.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun hdrFrameOf(path: String): HdrFrameProbe? {
  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
  val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return null
  val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: return null
  val output =
    AVAssetReaderTrackOutput(
      track = track,
      // The backend's own reader settings, so the frame measured here is decoded the way the one
      // the effect ran on was.
      outputSettings = videoReaderSettings(encodesHdr = true),
    )
  reader.addOutput(output)
  if (!reader.startReading()) return null

  val sample = output.copyNextSampleBuffer() ?: return null
  val buffer = CMSampleBufferGetImageBuffer(sample) ?: return null
  val image = CIImage.imageWithCVPixelBuffer(buffer)
  val extent = image.extent().useContents { size.width.toInt() to size.height.toInt() }
  val (width, height) = extent
  val pixels = FloatArray(width * height * CHANNELS)

  val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearITUR_2020)
  try {
    pixels.usePinned { pinned ->
      CIContext().render(
        image,
        toBitmap = pinned.addressOf(0),
        rowBytes = (width * CHANNELS * Float.SIZE_BYTES).toLong(),
        bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = colorSpace,
      )
    }
  } finally {
    CGColorSpaceRelease(colorSpace)
  }
  reader.cancelReading()

  return HdrFrameProbe(width, height, pixels)
}

/**
 * The luminance Core Image normalises an HDR frame against, in cd/m2.
 *
 * Apple's linear spaces put `1.0` at reference white rather than at the format's peak, for PQ and
 * HLG alike, so this is the unit a value read out of one carries.
 */
internal const val HDR_DISPLAY_UNIT_NITS: Float = 203f

private const val CHANNELS = 4
