package dev.jordond.filmstrip.effects.overlay

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.CoreFoundation.CFDataCreateWithBytesNoCopy
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorMalloc
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceGetNumberOfComponents
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageGetBitmapInfo
import platform.CoreGraphics.CGImageGetBitsPerComponent
import platform.CoreGraphics.CGImageGetBitsPerPixel
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetRenderingIntent
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapAlphaInfoMask
import platform.CoreGraphics.kCGBitmapByteOrder16Big
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGBitmapByteOrderDefault
import platform.CoreGraphics.kCGBitmapByteOrderMask
import platform.CoreGraphics.kCGBitmapFloatComponents
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import platform.ImageIO.CGImageSourceRef
import platform.posix.free
import platform.posix.malloc

/**
 * What reading an overlay picture got, either the value read or why it could not be read.
 */
internal sealed interface PictureRead<out T> {
  /**
   * The picture was read.
   *
   * @property value What was read out of it.
   */
  class Read<out T>(
    val value: T,
  ) : PictureRead<T>

  /**
   * The bytes could not be read, or hold no picture ImageIO or Core Image decodes.
   */
  data object Unreadable : PictureRead<Nothing>

  /**
   * The picture is refused before it is decoded, since filmstrip cannot land it the way its file describes.
   *
   * @property message What the overlay reports, as one or more sentences.
   */
  class Unsupported(
    val message: String,
  ) : PictureRead<Nothing>

  /**
   * The picture decoded, and its samples could not be read out of the decode.
   *
   * @property reason What ImageIO handed back that could not be read, as a sentence.
   */
  class Refused(
    val reason: String,
  ) : PictureRead<Nothing>
}

/**
 * The primary image ImageIO decodes from this source, its samples copied out and handed to Core Image with no color
 * space. It is [PictureRead.Unreadable] when ImageIO decodes no image, and refused when [bitmapCodes] cannot copy its
 * samples out.
 *
 * ImageIO decodes the picture once, here, and Core Image draws those samples and never decodes the picture itself.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CGImageSourceRef.bitmapCodes(): PictureRead<HeldCodes> {
  val decoded =
    CGImageSourceCreateImageAtIndex(this, CGImageSourceGetPrimaryImageIndex(this), null)
      ?: return PictureRead.Unreadable
  try {
    return decoded.bitmapCodes()
  } finally {
    CGImageRelease(decoded)
  }
}

/**
 * This image's samples copied into an image of their own and handed to Core Image with no color space, or refused
 * saying why when they cannot be copied out or the image is neither RGB nor eight or sixteen-bit grey.
 *
 * An RGB image is copied in the layout it is stored in, whatever its depth, packing, byte order and alpha. A grey one
 * is copied as RGBA at the same depth, byte order and alpha, its grey sample written to red, green and blue and a
 * missing alpha written opaque. Core Image reads every copy as four channels.
 *
 * ImageIO decodes the picture into the buffer the samples are copied out of, and the image holds the copy for as long
 * as it lives, four bytes a texel for an eight-bit RGB picture. A 48 megapixel eight-bit still holds 192 MB here.
 * Before anything is copied, ImageIO's decode, the copy and for grey the RGBA written from it are counted by
 * [picturePeakBytes], and a picture whose count passes [ceiling] is refused with the reason [tooLargeToCopy] gives.
 * Drawing it at its color holds the copy beside another 768 MB of premultiplied floats, made a band of rows at a time
 * by [StoredBands], and on macOS the footprint peaks at 1,252 MB with what Core Image holds while it renders.
 *
 * @param ceiling The most bytes the copy may hold at its peak, [PICTURE_PEAK_CEILING] by default.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CGImageRef.bitmapCodes(ceiling: Long = PICTURE_PEAK_CEILING): PictureRead<HeldCodes> {
  val components = CGColorSpaceGetNumberOfComponents(CGImageGetColorSpace(this)).toInt()
  val copy =
    when (components) {
      RGB -> copied(ceiling)
      GREY -> greyAsRgba(ceiling)
      else -> return PictureRead.Refused("ImageIO decoded it to $components color components. $GREY_OR_RGB")
    }
  return copy.map { image -> image.heldCodes().also { CGImageRelease(image) } }
}

/**
 * The value read turned by [transform], or this when nothing was read.
 */
internal inline fun <T, R> PictureRead<T>.map(transform: (T) -> R): PictureRead<R> =
  when (this) {
    is PictureRead.Read -> PictureRead.Read(transform(value))
    is PictureRead.Refused -> this
    is PictureRead.Unsupported -> this
    PictureRead.Unreadable -> PictureRead.Unreadable
  }

// ImageIO's decode is held while its samples are copied out, and the copy is the image made of them.
@OptIn(ExperimentalForeignApi::class)
private fun CGImageRef.copied(ceiling: Long): PictureRead<CGImageRef> {
  val width = CGImageGetWidth(this).toLong()
  val height = CGImageGetHeight(this).toLong()
  val decode = bytesOf(CGImageGetBytesPerRow(this).toLong(), height)
  val peak = picturePeakBytes(held = decode, band = 0L, made = decode)
  if (peak == null || peak > ceiling) return PictureRead.Refused(tooLargeToCopy(width, height, ceiling))
  val samples = CGDataProviderCopyData(CGImageGetDataProvider(this)) ?: return PictureRead.Refused(SAMPLES_WITHHELD)
  try {
    return imageOf(samples, CGImageGetBitsPerPixel(this), CGImageGetBytesPerRow(this), CGImageGetBitmapInfo(this))
  } finally {
    CFRelease(samples)
  }
}

/**
 * This grey image's samples as RGBA, or refused saying why when it is not eight or sixteen-bit integer grey with no
 * alpha or alpha stored last, its samples cannot be copied out, or ImageIO's decode, the copy of it read and the RGBA
 * written from it pass [ceiling] together.
 *
 * The RGBA is written straight into the one buffer Core Graphics reads it from.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CGImageRef.greyAsRgba(ceiling: Long): PictureRead<CGImageRef> {
  val info = CGImageGetBitmapInfo(this)
  val alpha = info and kCGBitmapAlphaInfoMask
  val depth = CGImageGetBitsPerComponent(this).toInt()
  val hasAlpha = alpha != CGImageAlphaInfo.kCGImageAlphaNone.value
  val sample = depth / BYTE_BITS
  val texel = if (hasAlpha) 2 * sample else sample
  val readable =
    (depth == EIGHT || depth == SIXTEEN) && info and kCGBitmapFloatComponents == 0u &&
      CGImageGetBitsPerPixel(this).toInt() == texel * BYTE_BITS && (!hasAlpha || alpha in ALPHA_LAST)
  if (!readable) {
    val samples = if (info and kCGBitmapFloatComponents == 0u) "integer" else "float"
    return PictureRead.Refused(
      "ImageIO decoded it to $depth-bit $samples grey with ${alphaName(alpha)} in ${byteOrderName(info)}. " +
        GREY_LAYOUTS,
    )
  }

  val width = CGImageGetWidth(this).toLong()
  val height = CGImageGetHeight(this).toLong()
  val row = CGImageGetBytesPerRow(this).toLong()
  val rgbaRow = width * RGBA * sample
  val size = bytesOf(rgbaRow, height)
  val decode = bytesOf(row, height)
  val peak = picturePeakBytes(held = decode, band = decode, made = size)
  val tooLarge = PictureRead.Refused(tooLargeToCopy(width, height, ceiling))
  if (size == null || peak == null || peak > ceiling) return tooLarge
  val samples = CGDataProviderCopyData(CGImageGetDataProvider(this)) ?: return PictureRead.Refused(SAMPLES_WITHHELD)
  val rgba: CPointer<ByteVar>
  try {
    val bytes = CFDataGetBytePtr(samples) ?: return PictureRead.Refused(SAMPLES_WITHHELD)
    if (CFDataGetLength(samples) < row * (height - 1) + width * texel) return PictureRead.Refused(SAMPLES_SHORT)
    rgba = malloc(size.convert())?.reinterpret() ?: return tooLarge
    for (y in 0 until height) {
      for (x in 0 until width) {
        val from = y * row + x * texel
        val to = y * rgbaRow + x * RGBA * sample
        repeat(sample) { byte ->
          val grey = bytes[from + byte].toByte()
          rgba[to + byte] = grey
          rgba[to + sample + byte] = grey
          rgba[to + 2 * sample + byte] = grey
          rgba[to + RGB * sample + byte] = if (hasAlpha) bytes[from + sample + byte].toByte() else OPAQUE_BYTE
        }
      }
    }
  } finally {
    CFRelease(samples)
  }
  val opaque = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value
  val rgbaInfo = (info and kCGBitmapAlphaInfoMask.inv()) or if (hasAlpha) alpha else opaque
  val data = CFDataCreateWithBytesNoCopy(null, rgba.reinterpret(), size, kCFAllocatorMalloc)
  if (data == null) {
    free(rgba)
    return PictureRead.Refused(NO_COPY)
  }
  try {
    return imageOf(data, (RGBA * depth).toULong(), rgbaRow.toULong(), rgbaInfo, width.toInt(), height.toInt(), depth)
  } finally {
    CFRelease(data)
  }
}

/**
 * The reason a [width] by [height] picture is refused when ImageIO's decode, the copy of its samples read out and the
 * image made of them would hold more than [ceiling] bytes together.
 */
internal fun tooLargeToCopy(
  width: Long,
  height: Long,
  ceiling: Long,
): String =
  "The $width by $height picture is too large to read. ImageIO's decode, the copy of its samples read out and the " +
    "image made of them must fit in $ceiling bytes."

// The Core Graphics name of the alpha layout alpha holds, like kCGImageAlphaFirst.
private fun alphaName(alpha: UInt): String =
  CGImageAlphaInfo.entries.firstOrNull { it.value == alpha }?.name ?: "an unknown alpha layout"

// The Core Graphics name of the byte order the bitmap info holds, like kCGBitmapByteOrder16Little.
private fun byteOrderName(info: UInt): String =
  when (info and kCGBitmapByteOrderMask) {
    kCGBitmapByteOrderDefault -> "kCGBitmapByteOrderDefault"
    kCGBitmapByteOrder16Little -> "kCGBitmapByteOrder16Little"
    kCGBitmapByteOrder32Little -> "kCGBitmapByteOrder32Little"
    kCGBitmapByteOrder16Big -> "kCGBitmapByteOrder16Big"
    kCGBitmapByteOrder32Big -> "kCGBitmapByteOrder32Big"
    else -> "an unknown byte order"
  }

/**
 * An image of [samples] laid out as [bitsPerPixel], [bytesPerRow] and [bitmapInfo] say, in device RGB with this image's
 * rendering intent, or refused when Core Graphics cannot make one.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CGImageRef.imageOf(
  samples: CFDataRef,
  bitsPerPixel: ULong,
  bytesPerRow: ULong,
  bitmapInfo: UInt,
  width: Int = CGImageGetWidth(this).toInt(),
  height: Int = CGImageGetHeight(this).toInt(),
  depth: Int = CGImageGetBitsPerComponent(this).toInt(),
): PictureRead<CGImageRef> {
  val provider = CGDataProviderCreateWithCFData(samples) ?: return PictureRead.Refused(NO_COPY)
  val space = CGColorSpaceCreateDeviceRGB()
  val image =
    CGImageCreate(
      width = width.toULong(),
      height = height.toULong(),
      bitsPerComponent = depth.toULong(),
      bitsPerPixel = bitsPerPixel,
      bytesPerRow = bytesPerRow,
      space = space,
      bitmapInfo = bitmapInfo,
      provider = provider,
      decode = null,
      shouldInterpolate = false,
      intent = CGImageGetRenderingIntent(this),
    )
  CGColorSpaceRelease(space)
  CGDataProviderRelease(provider)
  return image?.let { PictureRead.Read(it) } ?: PictureRead.Refused(NO_COPY)
}

/**
 * The reason a picture is refused when ImageIO decodes it and hands out none of its samples, the way it does for a
 * grey HEIC or AVIF with alpha.
 */
internal const val SAMPLES_WITHHELD = "ImageIO decoded it and handed out none of its samples."

internal const val SAMPLES_SHORT: String = "ImageIO handed out fewer samples than the picture holds."
internal const val NO_COPY: String = "Core Graphics could not make an image of the samples ImageIO handed out."
private const val GREY_OR_RGB = "Only grey and RGB samples are read."
private const val GREY_LAYOUTS = "Grey is read at eight or sixteen bits, as integers, with no alpha or alpha last."

private val ALPHA_LAST =
  setOf(
    CGImageAlphaInfo.kCGImageAlphaLast.value,
    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
  )

private const val RGB = 3
private const val RGBA = 4
private const val GREY = 1
private const val EIGHT = 8
private const val SIXTEEN = 16
private const val BYTE_BITS = 8
private const val OPAQUE_BYTE: Byte = -1

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
