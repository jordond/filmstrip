package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.YcbcrEvaluator
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreateWithBytesNoCopy
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorMalloc
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetBitmapInfo
import platform.CoreGraphics.CGImageGetBitsPerComponent
import platform.CoreGraphics.CGImageGetBitsPerPixel
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapAlphaInfoMask
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGBitmapByteOrderDefault
import platform.CoreGraphics.kCGBitmapByteOrderMask
import platform.CoreGraphics.kCGBitmapFloatComponents
import platform.Foundation.NSData
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import kotlin.math.roundToInt

/**
 * The primary image ImageIO decodes from this data under the identity matrix, each texel's stored samples turned into
 * colour by [landing]'s evaluator and handed to Core Image with no colour space as sixteen-bit RGBA.
 *
 * It is [PictureRead.Unreadable] when ImageIO decodes no image, and refused when [evaluatedCodes] cannot read the
 * samples it hands out.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
internal fun NSData.evaluatedCodes(landing: ImageIoLanding.Evaluated): PictureRead<HeldCodes> =
  withImageSource { source ->
    val decoded =
      CGImageSourceCreateImageAtIndex(source, CGImageSourceGetPrimaryImageIndex(source), null)
        ?: return@withImageSource PictureRead.Unreadable
    try {
      decoded.evaluatedCodes(landing.evaluator, landing.bitDepth)
    } finally {
      CGImageRelease(decoded)
    }
  } ?: PictureRead.Unreadable

/**
 * This image's samples, green holding luma, blue the blue difference and red the red difference at [bitDepth] bits,
 * turned into red, green and blue by [evaluator] and handed to Core Image with no colour space as sixteen-bit RGBA,
 * the way [evaluatedImage] makes them.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
internal fun CGImageRef.evaluatedCodes(
  evaluator: YcbcrEvaluator,
  bitDepth: Int,
): PictureRead<HeldCodes> =
  evaluatedImage(evaluator, bitDepth).map { image -> image.heldCodes().also { CGImageRelease(image) } }

/**
 * This image's samples, green holding luma, blue the blue difference and red the red difference at [bitDepth] bits,
 * turned into red, green and blue by [evaluator] as a sixteen-bit little endian RGBA image in device RGB. The caller
 * releases it.
 *
 * ImageIO hands a 4:4:4 picture stated under the identity matrix at full range out as eight-bit RGB for an eight-bit
 * picture and as ten-bit RGB packed into 32-bit little endian words for a ten-bit one. Those two layouts are read, rows
 * at the image's own stride, with the alpha of an eight-bit one kept when it is not premultiplied. Any other is refused
 * by [unreadLayout].
 *
 * The result is written a row at a time straight into the one buffer Core Graphics reads it from, eight bytes a texel.
 * It is held beside ImageIO's own decode and the copy of its samples this reads, which [evaluatedPeakBytes] counts. A
 * picture whose peak passes [ceiling], whose result does not fit in a [Long] count of bytes or one row of it in a
 * [ByteArray], or whose result cannot be allocated is refused by [tooLarge]. At 48 MP the result is 384 MB and the peak
 * 768 MB.
 *
 * On an Apple M4 Max under macOS 26.6.2 (25G83), an eight-bit 8000 by 6000 picture takes 0.65 s under the linear
 * SMPTE 240M reading and 3.2 s under BT.2020 constant luminance in a release build, and 8.5 s and 23 s in a debug one.
 *
 * @param ceiling The most bytes the read may hold at its peak, [PICTURE_PEAK_CEILING] unless a test sets it.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
internal fun CGImageRef.evaluatedImage(
  evaluator: YcbcrEvaluator,
  bitDepth: Int,
  ceiling: Long = PICTURE_PEAK_CEILING,
): PictureRead<CGImageRef> {
  val layout = storedLayout(bitDepth) ?: return PictureRead.Refused(unreadLayout(bitDepth))
  val width = CGImageGetWidth(this).toLong()
  val height = CGImageGetHeight(this).toLong()
  val row = CGImageGetBytesPerRow(this).toLong()
  val tooLargeRead = PictureRead.Refused(tooLarge(width, height, ceiling))
  val size = sixteenBitRgbaBytes(width, height) ?: return tooLargeRead
  val peak = evaluatedPeakBytes(size, row, height)
  if (peak == null || peak > ceiling) return tooLargeRead
  val samples = CGDataProviderCopyData(CGImageGetDataProvider(this)) ?: return PictureRead.Refused(SAMPLES_WITHHELD)
  val rgba: CPointer<UByteVar>
  try {
    val bytes = CFDataGetBytePtr(samples) ?: return PictureRead.Refused(SAMPLES_WITHHELD)
    if (CFDataGetLength(samples) < row * (height - 1) + width * TEXEL_BYTES) {
      return PictureRead.Refused(SAMPLES_SHORT)
    }
    rgba = malloc(size.convert())?.reinterpret() ?: return tooLargeRead
    try {
      evaluate(bytes, row, layout, evaluator, width.toInt(), height, rgba)
    } catch (thrown: Throwable) {
      free(rgba)
      throw thrown
    }
  } finally {
    CFRelease(samples)
  }
  val data = CFDataCreateWithBytesNoCopy(null, rgba, size, kCFAllocatorMalloc)
  if (data == null) {
    free(rgba)
    return PictureRead.Refused(NO_COPY)
  }
  val alpha = if (layout.alpha) CGImageAlphaInfo.kCGImageAlphaLast else CGImageAlphaInfo.kCGImageAlphaNoneSkipLast
  try {
    return imageOf(
      data,
      (RGBA * SIXTEEN).toULong(),
      (width * RGBA_BYTES).toULong(),
      kCGBitmapByteOrder16Little or alpha.value,
      width.toInt(),
      height.toInt(),
      SIXTEEN,
    )
  } finally {
    CFRelease(data)
  }
}

/**
 * The bytes a sixteen-bit RGBA copy of [width] by [height] texels takes, or null when that count does not fit in a
 * [Long] or one row of it does not fit in a [ByteArray].
 */
internal fun sixteenBitRgbaBytes(
  width: Long,
  height: Long,
): Long? {
  if (width < 0 || height < 0 || width > Int.MAX_VALUE / RGBA_BYTES) return null
  if (height != 0L && width > Long.MAX_VALUE / RGBA_BYTES / height) return null
  return width * height * RGBA_BYTES
}

/**
 * The bytes [evaluatedImage] holds at its peak for a picture of [height] rows, [row] bytes a row, whose result takes
 * [result] bytes, as [picturePeakBytes] counts it. ImageIO's decode is held, the copy of it read is the band and the
 * result is what is made. It is null when that count does not fit in a [Long].
 */
internal fun evaluatedPeakBytes(
  result: Long,
  row: Long,
  height: Long,
): Long? {
  val decode = bytesOf(row, height)
  return picturePeakBytes(held = decode, band = decode, made = result)
}

/**
 * The reason a [width] by [height] picture is refused when its sixteen-bit RGBA copy does not fit, held to [ceiling]
 * bytes at the read's peak.
 */
internal fun tooLarge(
  width: Long,
  height: Long,
  ceiling: Long,
): String =
  "The $width by $height picture's sixteen-bit RGBA copy, eight bytes a texel, is too large to read. One row of it " +
    "must fit in an array, and the copy, ImageIO's decode and the samples read from it must fit in $ceiling bytes " +
    "and in the memory this process can allocate."

// Writes each texel of the samples at bytes, row bytes a row, into rgba as sixteen-bit little endian RGBA, one row at a
// time through a row of samples and a row of results.
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
private fun evaluate(
  bytes: CPointer<UByteVar>,
  row: Long,
  layout: StoredLayout,
  evaluator: YcbcrEvaluator,
  width: Int,
  height: Long,
  rgba: CPointer<UByteVar>,
) {
  val stored = ByteArray(width * TEXEL_BYTES)
  val written = ByteArray(width * RGBA * 2)
  val texel = IntArray(RGBA)
  val rgb = DoubleArray(3)
  stored.usePinned { from ->
    written.usePinned { to ->
      for (y in 0 until height) {
        memcpy(from.addressOf(0), bytes + y * row, stored.size.convert())
        for (x in 0 until width) {
          layout.read(stored, x * TEXEL_BYTES, texel)
          evaluator.rgb(texel[1], texel[2], texel[0], rgb)
          val at = x * RGBA * 2
          for (channel in 0 until RGBA) {
            val value = if (channel < 3) (rgb[channel] * MAX_SAMPLE).roundToInt() else texel[3]
            written[at + 2 * channel] = value.toByte()
            written[at + 2 * channel + 1] = (value shr BYTE_BITS).toByte()
          }
        }
        memcpy(rgba + y * written.size, to.addressOf(0), written.size.convert())
      }
    }
  }
}

/**
 * Where one texel's red, green, blue and alpha sit in a decoded buffer, and whether its alpha is stored.
 */
private sealed class StoredLayout(
  val alpha: Boolean,
) {
  /**
   * Reads the texel at [at] into [into] as red, green and blue at the picture's depth and alpha at sixteen bits.
   */
  abstract fun read(
    bytes: ByteArray,
    at: Int,
    into: IntArray,
  )

  // Eight bits a channel, each channel at its own byte of the four.
  class EightBit(
    private val offsets: IntArray,
    alpha: Boolean,
  ) : StoredLayout(alpha) {
    override fun read(
      bytes: ByteArray,
      at: Int,
      into: IntArray,
    ) {
      for (channel in 0 until 3) into[channel] = bytes[at + offsets[channel]].toInt() and BYTE_MASK
      into[3] = if (alpha) (bytes[at + offsets[3]].toInt() and BYTE_MASK) * EIGHT_BIT_STEP else MAX_SAMPLE.toInt()
    }
  }

  // Ten bits a channel packed into a little endian word, red highest and two unused bits above it.
  data object TenBit : StoredLayout(alpha = false) {
    override fun read(
      bytes: ByteArray,
      at: Int,
      into: IntArray,
    ) {
      var word = 0
      for (byte in 0 until TEXEL_BYTES) word = word or ((bytes[at + byte].toInt() and BYTE_MASK) shl (BYTE_BITS * byte))
      into[0] = (word shr (2 * TEN)) and TEN_BIT_MASK
      into[1] = (word shr TEN) and TEN_BIT_MASK
      into[2] = word and TEN_BIT_MASK
      into[3] = MAX_SAMPLE.toInt()
    }
  }
}

// The layout of this image's texels when it is one evaluatedCodes reads at bitDepth, or null.
@OptIn(ExperimentalForeignApi::class)
private fun CGImageRef.storedLayout(bitDepth: Int): StoredLayout? {
  val info = CGImageGetBitmapInfo(this)
  val alpha = info and kCGBitmapAlphaInfoMask
  val order = info and kCGBitmapByteOrderMask
  val format = info and PIXEL_FORMAT_MASK
  val integer = info and kCGBitmapFloatComponents == 0u
  val depth = CGImageGetBitsPerComponent(this).toInt()
  if (!integer || depth != bitDepth || CGImageGetBitsPerPixel(this).toInt() != TEXEL_BYTES * BYTE_BITS) return null
  if (depth == TEN) {
    val skipFirst = CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value
    val packed = format == RGB101010 && order == kCGBitmapByteOrder32Little && alpha == skipFirst
    return StoredLayout.TenBit.takeIf { packed }
  }
  if (depth != EIGHT || format != 0u) return null
  val first = alpha in ALPHA_FIRST
  if (!first && alpha !in ALPHA_LAST) return null
  val big = listOf(1, 2, 3, 0).takeIf { first } ?: listOf(0, 1, 2, 3)
  val offsets =
    when (order) {
      kCGBitmapByteOrderDefault, kCGBitmapByteOrder32Big -> big
      kCGBitmapByteOrder32Little -> big.map { TEXEL_BYTES - 1 - it }
      else -> return null
    }
  val stored = alpha == CGImageAlphaInfo.kCGImageAlphaFirst.value || alpha == CGImageAlphaInfo.kCGImageAlphaLast.value
  return StoredLayout.EightBit(offsets.toIntArray(), stored)
}

/**
 * The reason a picture is refused when ImageIO decodes its [bitDepth]-bit samples to a layout [evaluatedImage] does not
 * read.
 */
internal fun unreadLayout(bitDepth: Int): String =
  "ImageIO decoded the $bitDepth-bit picture to a layout its stored samples are not read from. Eight-bit samples " +
    "are read four bytes a texel without premultiplied alpha, and ten-bit ones packed into 32-bit little endian words."

private val ALPHA_LAST =
  setOf(CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value, CGImageAlphaInfo.kCGImageAlphaLast.value)
private val ALPHA_FIRST =
  setOf(CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value, CGImageAlphaInfo.kCGImageAlphaFirst.value)

// Core Graphics' pixel format bits in a bitmap info, and the format packing ten-bit red, green and blue into a word.
private const val PIXEL_FORMAT_MASK = 0xF0000u
private const val RGB101010 = 0x30000u

private const val RGBA = 4
private const val RGBA_BYTES = 8L
private const val TEXEL_BYTES = 4
private const val EIGHT = 8
private const val TEN = 10
private const val SIXTEEN = 16
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFF
private const val TEN_BIT_MASK = 0x3FF
private const val EIGHT_BIT_STEP = 257
private const val MAX_SAMPLE = 65535.0
