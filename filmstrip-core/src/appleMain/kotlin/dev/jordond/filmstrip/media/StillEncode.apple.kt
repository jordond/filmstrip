package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSMutableData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.posix.memcpy

/**
 * ImageIO writes by uniform type identifier, and `CGImageDestinationCreateWithData` hands back null
 * for one this system will not encode. That null is what refuses WebP, which ImageIO reads on
 * systems where it will not write it, rather than finalizing an empty file and calling it a still.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun PlatformImage.encode(
  spec: StillSpec,
  size: Size,
): StillBytes =
  withContext(Dispatchers.Default) {
    val source = cgImage() ?: return@withContext StillBytes.Failure(closedFrame())
    val scaled = source.scaledTo(size) ?: return@withContext StillBytes.Failure(noContext(size))

    try {
      encodeImage(scaled, spec, size)
    } finally {
      if (scaled !== source) CGImageRelease(scaled)
    }
  }

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun writeStill(
  bytes: ByteArray,
  to: MediaSink,
  format: StillFormat,
): StillWrite =
  withContext(Dispatchers.Default) {
    when (to) {
      is MediaSink.Path -> {
        writeStillFile(bytes, to.path, MediaSink.Path(to.path))
      }
      is MediaSink.Temporary -> {
        val path = NSTemporaryDirectory() + "filmstrip-still-" + NSUUID().UUIDString() + "." + format.fileExtension
        writeStillFile(bytes, path, MediaSink.Path(path))
      }
      is MediaSink.Uri -> {
        val parsed = NSURL.URLWithString(to.uri)
        val path = if (parsed?.isFileURL() == true) parsed.path else null
        if (path == null) {
          StillWrite.Failure(ExportError.SinkUnwritable(to.uri, NOT_A_FILE_URL))
        } else {
          writeStillFile(bytes, path, MediaSink.Uri(to.uri))
        }
      }
    }
  }

/**
 * Runs [image] through an ImageIO destination.
 *
 * A destination this system will not open for the format's uniform type identifier is the refusal.
 * One that opens and then will not finalize is an encoder failure rather than a missing format, and
 * is reported as one.
 */
@OptIn(ExperimentalForeignApi::class)
private fun encodeImage(
  image: CGImageRef,
  spec: StillSpec,
  size: Size,
): StillBytes {
  val data = NSMutableData()
  val target =
    CFBridgingRetain(data)?.asData()
      ?: return StillBytes.Failure(ExportError.Underlying(NO_CODE, NO_BUFFER))
  val uti = CFBridgingRetain(spec.format.uti)?.asString()
  val properties = CFBridgingRetain(spec.properties())?.asDictionary()

  try {
    val destination =
      CGImageDestinationCreateWithData(target, uti, 1u, null)
        ?: return StillBytes.Failure(unsupportedStillFormat(spec.format, TARGET))

    try {
      CGImageDestinationAddImage(destination, image, properties)
      if (!CGImageDestinationFinalize(destination)) {
        return StillBytes.Failure(ExportError.Underlying(NO_CODE, NOT_FINALIZED))
      }
    } finally {
      CFRelease(destination)
    }
  } finally {
    properties?.let { CFRelease(it) }
    uti?.let { CFRelease(it) }
    CFRelease(target)
  }

  return StillBytes.Success(data.toByteArray(), size, spec.format)
}

/**
 * The destination properties, carrying the lossy quality for a format that reads one. PNG ignores
 * the key, so it costs nothing to pass everywhere.
 *
 * The key is spelled out because ImageIO's constant is a `CFStringRef` and does not bridge into a
 * Kotlin map as an `NSString` key. The literal is its documented value.
 */
private fun StillSpec.properties(): Map<Any?, Any?> = mapOf(LOSSY_QUALITY_KEY to qualityFraction)

/**
 * The frame at [size], or the frame itself when it is already that size.
 *
 * Null when Core Graphics will not open a context for the size that was asked for, which is a
 * caller asking for a still with no area.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CGImageRef.scaledTo(size: Size): CGImageRef? {
  if (CGImageGetWidth(this).toInt() == size.width && CGImageGetHeight(this).toInt() == size.height) return this

  val space = CGColorSpaceCreateDeviceRGB()
  try {
    val context =
      CGBitmapContextCreate(
        data = null,
        width = size.width.toULong(),
        height = size.height.toULong(),
        bitsPerComponent = BITS_PER_COMPONENT,
        bytesPerRow = 0uL,
        space = space,
        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
      ) ?: return null

    return try {
      CGContextDrawImage(context, CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble()), this)
      CGBitmapContextCreateImage(context)
    } finally {
      CGContextRelease(context)
    }
  } finally {
    CGColorSpaceRelease(space)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSMutableData.toByteArray(): ByteArray {
  val size = length.toInt()
  val source = bytes
  if (size == 0 || source == null) return ByteArray(0)

  val out = ByteArray(size)
  out.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length) }
  return out
}

// The CoreFoundation reference types are pointer typealiases, so the reinterpret's type argument
// comes from the declared return type.

@OptIn(ExperimentalForeignApi::class)
private fun COpaquePointer.asData(): CFMutableDataRef = reinterpret()

@OptIn(ExperimentalForeignApi::class)
private fun COpaquePointer.asString(): CFStringRef = reinterpret()

@OptIn(ExperimentalForeignApi::class)
private fun COpaquePointer.asDictionary(): CFDictionaryRef = reinterpret()

private val StillFormat.uti: String
  get() =
    when (this) {
      StillFormat.Png -> "public.png"
      StillFormat.Jpeg -> "public.jpeg"
      StillFormat.Webp -> "org.webmproject.webp"
    }

private fun closedFrame() = ExportError.SourceUnreadable("PlatformImage", "The frame has been closed.")

private fun noContext(size: Size) =
  ExportError.SourceUnreadable(
    "PlatformImage",
    "Core Graphics refused a bitmap context for a ${size.width}x${size.height} still.",
  )

private const val TARGET = "Apple's ImageIO on this system"

private const val NOT_A_FILE_URL = "This target writes to file URLs only."

private const val NO_BUFFER = "Core Foundation refused a buffer to encode the still into."

private const val NOT_FINALIZED = "ImageIO opened a destination for the still and then would not finalize it."

private const val NO_CODE = ExportError.Underlying.NO_PLATFORM_CODE

private const val LOSSY_QUALITY_KEY = "kCGImageDestinationLossyCompressionQuality"

private const val BITS_PER_COMPONENT = 8uL
