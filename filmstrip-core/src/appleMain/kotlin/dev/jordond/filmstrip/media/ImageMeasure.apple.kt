package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFURLRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth

/**
 * `CGImageSourceCopyPropertiesAtIndex` answers from the file's metadata without decoding it, and
 * the orientation it reports is the EXIF tag a camera writes rather than one already applied.
 *
 * A URL-backed source is read lazily by ImageIO, so a path or a URI never pulls the whole file in.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun measureImage(image: ImageSource): ImageMeasurement? =
  withContext(Dispatchers.Default) {
    val source = image.openImageSource() ?: return@withContext null

    try {
      val properties = CGImageSourceCopyPropertiesAtIndex(source, FIRST_IMAGE, null) ?: return@withContext null
      try {
        val width = properties.intValue(kCGImagePropertyPixelWidth) ?: return@withContext null
        val height = properties.intValue(kCGImagePropertyPixelHeight) ?: return@withContext null

        ImageMeasurement(
          size = Size(width, height),
          exifOrientation = properties.intValue(kCGImagePropertyOrientation) ?: EXIF_ORIENTATION_NORMAL,
          format = source.formatName(),
        )
      } finally {
        CFRelease(properties)
      }
    } finally {
      CFRelease(source)
    }
  }

/**
 * The trailing component of the uniform type identifier, so `public.jpeg` reads as `jpeg` the way
 * the other targets spell it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CGImageSourceRef.formatName(): String {
  val identifier = CGImageSourceGetType(this) ?: return ""
  val bridged = CFBridgingRelease(CFRetain(identifier)) as? String ?: return ""
  return bridged.substringAfterLast('.')
}

@OptIn(ExperimentalForeignApi::class)
private fun ImageSource.openImageSource(): CGImageSourceRef? =
  when (this) {
    is ImageSource.Path -> NSURL.fileURLWithPath(path).openImageSource()
    is ImageSource.Uri -> NSURL.URLWithString(uri)?.openImageSource()
    is ImageSource.Bytes -> bytes.openImageSource()
  }

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.openImageSource(): CGImageSourceRef? {
  val url: CFURLRef = CFBridgingRetain(this)?.reinterpret() ?: return null

  return try {
    CGImageSourceCreateWithURL(url, null)
  } finally {
    CFRelease(url)
  }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.openImageSource(): CGImageSourceRef? {
  if (isEmpty()) return null
  val bytes = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
  val data: CFDataRef = CFBridgingRetain(bytes)?.reinterpret() ?: return null

  return try {
    CGImageSourceCreateWithData(data, null)
  } finally {
    CFRelease(data)
  }
}

private const val FIRST_IMAGE = 0uL
