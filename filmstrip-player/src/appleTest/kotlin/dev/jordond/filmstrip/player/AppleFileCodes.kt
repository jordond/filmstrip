package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AppleBufferCodes
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSURL
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An RGB reading in eight-bit code values, unrounded so a table of them shows where a channel sits between two codes.
 */
internal data class Codes(
  val red: Float,
  val green: Float,
  val blue: Float,
) {
  override fun toString(): String = "(${red.shown()}, ${green.shown()}, ${blue.shown()})"

  private fun Float.shown(): String = ((this * TENTHS).toInt() / TENTHS).toString()
}

/**
 * The average color of a [PATCH] square centred on ([xFraction], [yFraction]) of this RGBA frame, read as it sits.
 */
internal fun TestFrame.codesAt(
  xFraction: Float,
  yFraction: Float,
): Codes {
  val sums = FloatArray(RGB)
  patchAround(size, xFraction, yFraction) { x, y ->
    val offset = (y * size.width + x) * RGBA
    repeat(RGB) { sums[it] += (pixels[offset + it].toInt() and BYTE_MASK).toFloat() }
  }
  return Codes(sums[0] / PATCH_AREA, sums[1] / PATCH_AREA, sums[2] / PATCH_AREA)
}

/**
 * The codes the file at [path] holds at [position], read off its decoded Y'CbCr planes with no color matching.
 *
 * The luma and chroma samples of a [PATCH] square are averaged and carried to R'G'B' through the BT.709 video range
 * matrix an SDR export is written with, so what comes back is the codes the encoder was handed.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun fileCodesAt(
  path: String,
  position: Duration,
  xFraction: Float,
  yFraction: Float,
): Codes {
  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
  val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: fail("$path has no video")
  val output =
    AVAssetReaderTrackOutput(
      track = track,
      outputSettings = mapOf(PIXEL_FORMAT_KEY to kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange.toInt()),
    )
  val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: fail("no reader for $path")
  reader.addOutput(output)
  if (!reader.startReading()) fail("the file reader refused to start: ${reader.error?.localizedDescription}")

  try {
    while (true) {
      val buffer = output.copyNextSampleBuffer() ?: fail("$path ran out of frames before $position")
      try {
        val at = CMSampleBufferGetPresentationTimeStamp(buffer).toDuration()
        val image = CMSampleBufferGetImageBuffer(buffer)
        if (image != null && at >= position - HALF_FRAME) return image.planeCodesAt(xFraction, yFraction)
      } finally {
        CFRelease(buffer)
      }
    }
  } finally {
    reader.cancelReading()
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.planeCodesAt(
  xFraction: Float,
  yFraction: Float,
): Codes {
  val size = Size(CVPixelBufferGetWidth(this).toInt(), CVPixelBufferGetHeight(this).toInt())
  val (left, top) = patchCorner(size, xFraction, yFraction)
  val codes = AppleBufferCodes.videoRangeCodesIn(this, left, top, width = PATCH, height = PATCH)
  return Codes(codes[0], codes[1], codes[2])
}

/**
 * The file at [path] read the way the avfoundation authored color tests read it, as buffer codes.
 *
 * An image generator decodes the frame at [position], it is drawn into device RGB, a [PATCH] square is averaged, and
 * the result is color matched from device RGB into the BT.709 code space an SDR buffer is written in.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal fun probeCodesAt(
  path: String,
  position: Duration,
  xFraction: Float,
  yFraction: Float,
): Codes {
  val generator = AVAssetImageGenerator(asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null))
  generator.appliesPreferredTrackTransform = true
  generator.requestedTimeToleranceBefore = Duration.ZERO.toCMTime()
  generator.requestedTimeToleranceAfter = Duration.ZERO.toCMTime()
  val image = generator.copyCGImageAtTime(position.toCMTime(), actualTime = null, error = null) ?: fail("no frame")

  val drawn =
    try {
      val width = CGImageGetWidth(image).toInt()
      val height = CGImageGetHeight(image).toInt()
      val pixels = ByteArray(width * height * RGBA)
      val device = CGColorSpaceCreateDeviceRGB()
      try {
        pixels.usePinned { pinned ->
          val context =
            CGBitmapContextCreate(
              pinned.addressOf(0),
              width.toULong(),
              height.toULong(),
              BITS_PER_CHANNEL,
              (width * RGBA).toULong(),
              device,
              CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: fail("no bitmap context")
          try {
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
          } finally {
            CGContextRelease(context)
          }
        }
      } finally {
        CGColorSpaceRelease(device)
      }
      TestFrame(pixels, Size(width, height))
    } finally {
      CGImageRelease(image)
    }

  val read = drawn.codesAt(xFraction, yFraction)
  val rounded = listOf(read.red, read.green, read.blue).map { kotlin.math.round(it) / FULL }
  val buffer = AppleBufferCodes.fromDevice(rounded.toFloatArray())
  return Codes(buffer[0] * FULL, buffer[1] * FULL, buffer[2] * FULL)
}

private inline fun patchAround(
  size: Size,
  xFraction: Float,
  yFraction: Float,
  visit: (x: Int, y: Int) -> Unit,
) {
  val (left, top) = patchCorner(size, xFraction, yFraction)
  for (y in top until top + PATCH) {
    for (x in left until left + PATCH) visit(x, y)
  }
}

private fun patchCorner(
  size: Size,
  xFraction: Float,
  yFraction: Float,
): Pair<Int, Int> =
  ((size.width * xFraction).toInt() - PATCH / 2).coerceIn(0, size.width - PATCH) to
    ((size.height * yFraction).toInt() - PATCH / 2).coerceIn(0, size.height - PATCH)

private const val PATCH = 4
private const val PATCH_AREA = (PATCH * PATCH).toFloat()
private const val RGB = 3
private const val RGBA = 4
private const val BYTE_MASK = 0xFF
private const val FULL = 255f
private const val TENTHS = 10f
private const val BITS_PER_CHANNEL = 8uL
private val HALF_FRAME = 1.seconds / 60
