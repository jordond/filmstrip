package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.YcbcrEvaluator
import dev.jordond.filmstrip.media.evaluator
import dev.jordond.filmstrip.media.ycbcrReading
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetMutableBytePtr
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDataSetLength
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
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
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.posix.memcpy
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Holds [evaluatedImage] to core's evaluator on every texel of an image laid out each way ImageIO hands out stored
 * samples, with every texel its own colour and every row padded past its texels, and to refusing any other layout.
 *
 * The result is sixteen-bit little endian RGBA, its alpha straight and kept from an eight-bit layout that stores one.
 * A picture whose result would not fit in a [Long] count of bytes, or whose peak passes the ceiling it is read under,
 * is refused before any of it is read. Every depth in [EVALUATED_DEPTHS] has a layout read.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
class YcbcrBitmapTest {
  @Test
  fun `every texel of a padded eight bit image is read in order at its own stride`() {
    val misses =
      EIGHT_BIT_LAYOUTS.mapNotNull { layout ->
        val image = image(WIDTH, HEIGHT, PADDED_ROW, 8, layout.info) { x, y -> layout.bytes(texelOf(x, y)) }
        miss(layout.name, image, LINEAR_EIGHT, 8, stored = layout.stored)
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `every texel of a padded ten bit image is read in order at its own stride`() {
    val image = image(WIDTH, HEIGHT, PADDED_ROW, TEN, TEN_BIT_INFO) { x, y -> packed(tenBitTexelOf(x, y)) }

    assertNull(miss("the packed ten-bit layout", image, CONSTANT_LUMINANCE_TEN, TEN, stored = false))
  }

  @Test
  fun `a premultiplied eight bit image is refused naming the layouts read`() {
    val premultiplied =
      listOf(CGImageAlphaInfo.kCGImageAlphaPremultipliedLast, CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst)
    for (alpha in premultiplied) {
      val image = image(WIDTH, HEIGHT, PADDED_ROW, 8, alpha.value) { x, y -> texelOf(x, y).toByteArray() }
      val read = image.use { it.evaluatedImage(LINEAR_EIGHT, 8) }

      assertEquals(unreadLayout(8), assertIs<PictureRead.Refused>(read, "$alpha was read").reason)
    }
  }

  @Test
  fun `an image at another depth than the picture's is refused`() {
    val tenBit = image(WIDTH, HEIGHT, PADDED_ROW, TEN, TEN_BIT_INFO) { x, y -> packed(tenBitTexelOf(x, y)) }
    val eightBit =
      image(WIDTH, HEIGHT, PADDED_ROW, 8, CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value) { x, y ->
        texelOf(x, y).toByteArray()
      }

    val asEight = tenBit.use { it.evaluatedImage(LINEAR_EIGHT, 8) }
    val asTen = eightBit.use { it.evaluatedImage(CONSTANT_LUMINANCE_TEN, TEN) }

    assertEquals(unreadLayout(8), assertIs<PictureRead.Refused>(asEight, "ten bits were read as eight").reason)
    assertEquals(unreadLayout(TEN), assertIs<PictureRead.Refused>(asTen, "eight bits were read as ten").reason)
  }

  @Test
  fun `a result whose size or row does not fit is refused before it is read`() {
    assertEquals(48_000_000L * 8, sixteenBitRgbaBytes(8_000, 6_000))
    assertEquals(0L, sixteenBitRgbaBytes(0, 6_000))
    assertNull(sixteenBitRgbaBytes(1L shl 31, 1L shl 31))
    assertNull(sixteenBitRgbaBytes(Long.MAX_VALUE, 1))
    assertNull(sixteenBitRgbaBytes(-1, 1))
    assertEquals(Int.MAX_VALUE / 8 * 8L, sixteenBitRgbaBytes(Int.MAX_VALUE / 8L, 1))
    assertNull(sixteenBitRgbaBytes(Int.MAX_VALUE / 8L + 1, 1))
  }

  @Test
  fun `a picture whose peak passes the ceiling is refused before it is read`() {
    val result = assertNotNull(sixteenBitRgbaBytes(WIDTH.toLong(), HEIGHT.toLong()))
    val peak = assertNotNull(evaluatedPeakBytes(result, PADDED_ROW.toLong(), HEIGHT.toLong()))
    val padded = { image(WIDTH, HEIGHT, PADDED_ROW, 8, RGBX) { x, y -> texelOf(x, y).toByteArray() } }

    val past = padded().use { it.evaluatedImage(LINEAR_EIGHT, 8, ceiling = peak - 1) }
    val at = padded().use { it.evaluatedImage(LINEAR_EIGHT, 8, ceiling = peak) }

    val refused = assertIs<PictureRead.Refused>(past, "a read past the ceiling went through")
    assertEquals(tooLarge(WIDTH.toLong(), HEIGHT.toLong(), peak - 1), refused.reason)
    CGImageRelease(at.orFail("the picture at the ceiling"))
  }

  @Test
  fun `the peak counts the result beside two copies of the samples and a 48 mp picture fits under the ceiling`() {
    val peak = assertNotNull(evaluatedPeakBytes(assertNotNull(sixteenBitRgbaBytes(8_000, 6_000)), 8_000L * 4, 6_000))

    assertEquals(768_000_000L, peak)
    assertTrue(peak <= PICTURE_PEAK_CEILING)
    assertEquals(1L shl 30, PICTURE_PEAK_CEILING)
    assertEquals(0L, evaluatedPeakBytes(0, 32, 0))
    assertNull(evaluatedPeakBytes(8, Long.MAX_VALUE / 2, 2))
    assertNull(evaluatedPeakBytes(-1, 32, 1))
  }

  @Test
  fun `every evaluated depth has a layout its stored samples are read from`() {
    for (depth in EVALUATED_DEPTHS) {
      val image =
        when (depth) {
          8 -> image(WIDTH, HEIGHT, PADDED_ROW, 8, RGBX) { x, y -> texelOf(x, y).toByteArray() }
          TEN -> image(WIDTH, HEIGHT, PADDED_ROW, TEN, TEN_BIT_INFO) { x, y -> packed(tenBitTexelOf(x, y)) }
          else -> fail("no stored layout is read at $depth bits")
        }
      val evaluator = ycbcrReading(SMPTE_240M, 1, BT709_TRANSFER).evaluator(depth, fullRange = true)

      CGImageRelease(image.use { it.evaluatedImage(evaluator, depth) }.orFail("the $depth-bit image"))
    }
  }

  // The first texel of image whose result is not evaluator on its own samples, or null when every one is.
  private fun miss(
    name: String,
    image: CGImageRef,
    evaluator: YcbcrEvaluator,
    bitDepth: Int,
    stored: Boolean,
  ): String? {
    val read = image.use { it.evaluatedImage(evaluator, bitDepth) }
    val result = read.orFail(name)
    try {
      val alpha = if (stored) CGImageAlphaInfo.kCGImageAlphaLast else CGImageAlphaInfo.kCGImageAlphaNoneSkipLast
      val info = kCGBitmapByteOrder16Little or alpha.value
      val layout =
        Triple(CGImageGetBitmapInfo(result), CGImageGetBitsPerComponent(result), CGImageGetBitsPerPixel(result))
      if (layout != Triple(info, 16uL, 64uL)) return "$name came back laid out as $layout, not sixteen-bit RGBA"
      if (CGImageGetBytesPerRow(result) != (WIDTH * RGBA_BYTES).toULong()) return "$name came back with padded rows"
      return result.withSamples { samples ->
        (0 until HEIGHT).firstNotNullOfOrNull { y ->
          (0 until WIDTH).firstNotNullOfOrNull { x ->
            val texel = if (bitDepth == TEN) tenBitTexelOf(x, y) else texelOf(x, y)
            val at = (y * WIDTH + x) * RGBA_BYTES
            val got = List(CHANNELS) { sample(samples, at + 2 * it) }
            val want = expected(evaluator, texel, stored)
            if (got == want) null else "$name read $got at $x, $y, where core's evaluator gives $want"
          }
        }
      }
    } finally {
      CGImageRelease(result)
    }
  }

  // The sixteen-bit red, green, blue and alpha evaluator gives a texel whose red, green and blue hold the red
  // difference, luma and blue difference, with its alpha kept when it is stored.
  private fun expected(
    evaluator: YcbcrEvaluator,
    texel: Texel,
    stored: Boolean,
  ): List<Int> {
    val rgb = evaluator.rgb(texel.green, texel.blue, texel.red)
    val alpha = if (stored) texel.alpha * EIGHT_BIT_STEP else MAX
    return rgb.map { (it * MAX).roundToInt() } + alpha
  }

  /**
   * One stored texel, red, green, blue and alpha at the image's depth.
   */
  private data class Texel(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
  ) {
    fun toByteArray(): ByteArray = byteArrayOf(red.toByte(), green.toByte(), blue.toByte(), alpha.toByte())
  }

  /**
   * An eight-bit layout ImageIO may hand out, the bitmap info that names it and where it stores each channel.
   */
  private class EightBitLayout(
    val name: String,
    val info: UInt,
    val stored: Boolean,
    val bytes: (Texel) -> ByteArray,
  )

  // An eight-bit texel each of the first five columns and three rows gives its own value on every channel, repeating
  // past them.
  private fun texelOf(
    x: Int,
    y: Int,
  ): Texel {
    val column = x % WIDTH
    val line = y % ROW_PERIOD
    val green = 220 - 30 * column - 40 * line
    return Texel(10 + 40 * column + 7 * line, green, 30 + 20 * column + 60 * line, 64 + 45 * column)
  }

  // A ten-bit texel each of the first five columns and three rows gives its own value on every channel.
  private fun tenBitTexelOf(
    x: Int,
    y: Int,
  ): Texel = Texel(40 + 160 * x + 30 * y, 900 - 100 * x - 150 * y, 120 + 80 * x + 240 * y, 0)

  // A ten-bit texel packed into a little endian word, red highest and two unused bits above it.
  private fun packed(texel: Texel): ByteArray {
    val word = (texel.red shl 20) or (texel.green shl TEN) or texel.blue or (3 shl 30)
    return ByteArray(TEXEL) { (word shr (8 * it)).toByte() }
  }

  /**
   * A [width] by [height] image of [bitDepth]-bit samples laid out as [info] says, [row] bytes a row, each texel the
   * four bytes [texel] gives and the bytes past them in a row set to 0xEE. Rows repeat every three, so [texel] is read
   * for the first three only.
   */
  private fun image(
    width: Int,
    height: Int,
    row: Int,
    bitDepth: Int,
    info: UInt,
    texel: (Int, Int) -> ByteArray,
  ): CGImageRef {
    val size = row.toLong() * height
    val data = assertNotNull(CFDataCreateMutable(null, size))
    CFDataSetLength(data, size)
    val bytes = assertNotNull(CFDataGetMutableBytePtr(data))
    val rows = HashMap<Int, ByteArray>()
    for (y in 0 until height) {
      val pattern =
        rows.getOrPut(y % ROW_PERIOD) {
          ByteArray(row) { PADDING }.also { line -> for (x in 0 until width) texel(x, y).copyInto(line, x * TEXEL) }
        }
      pattern.usePinned { memcpy(bytes + y.toLong() * row, it.addressOf(0), row.convert()) }
    }
    val provider = CGDataProviderCreateWithCFData(data)
    CFRelease(data)
    val space = CGColorSpaceCreateDeviceRGB()
    val image =
      CGImageCreate(
        width.toULong(),
        height.toULong(),
        bitDepth.toULong(),
        (TEXEL * 8).toULong(),
        row.toULong(),
        space,
        info,
        provider,
        null,
        false,
        CGColorRenderingIntent.kCGRenderingIntentDefault,
      )
    CGColorSpaceRelease(space)
    CGDataProviderRelease(provider)
    return assertNotNull(image, "Core Graphics made no $width by $height image with bitmap info $info")
  }

  private inline fun <T> CGImageRef.use(read: (CGImageRef) -> T): T =
    try {
      read(this)
    } finally {
      CGImageRelease(this)
    }

  private inline fun <T> CGImageRef.withSamples(read: (CPointer<UByteVar>) -> T): T {
    val copy: CFDataRef = assertNotNull(CGDataProviderCopyData(CGImageGetDataProvider(this)))
    try {
      return read(assertNotNull(CFDataGetBytePtr(copy)))
    } finally {
      CFRelease(copy)
    }
  }

  private fun sample(
    samples: CPointer<UByteVar>,
    at: Long,
  ): Int = samples[at].toInt() or (samples[at + 1].toInt() shl 8)

  private companion object {
    const val WIDTH = 5
    const val HEIGHT = 3
    const val TEXEL = 4
    const val CHANNELS = 4
    const val RGBA_BYTES = 8L
    const val PADDED_ROW = WIDTH * TEXEL + 12
    const val TEN = 10
    const val MAX = 65535
    const val EIGHT_BIT_STEP = 257
    const val SMPTE_240M = 7
    const val BT2020_CONSTANT_LUMINANCE = 10
    const val BT2020 = 9
    const val BT709_TRANSFER = 1
    const val ROW_PERIOD = 3
    const val PADDING = 0xEE.toByte()

    val LINEAR_EIGHT = ycbcrReading(SMPTE_240M, 1, BT709_TRANSFER).evaluator(8, fullRange = true)
    val CONSTANT_LUMINANCE_TEN =
      ycbcrReading(BT2020_CONSTANT_LUMINANCE, BT2020, BT709_TRANSFER).evaluator(TEN, fullRange = true)

    val RGBX = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value
    val TEN_BIT_INFO = CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value or kCGBitmapByteOrder32Little or 0x30000u

    val EIGHT_BIT_LAYOUTS =
      listOf(
        EightBitLayout("RGBX", RGBX, stored = false) { byteArrayOf(r(it), g(it), b(it), 7) },
        EightBitLayout("RGBA straight", CGImageAlphaInfo.kCGImageAlphaLast.value, stored = true) {
          byteArrayOf(r(it), g(it), b(it), a(it))
        },
        EightBitLayout("XRGB", CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value, stored = false) {
          byteArrayOf(7, r(it), g(it), b(it))
        },
        EightBitLayout("ARGB straight", CGImageAlphaInfo.kCGImageAlphaFirst.value, stored = true) {
          byteArrayOf(a(it), r(it), g(it), b(it))
        },
        EightBitLayout(
          "BGRX little endian",
          CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value or kCGBitmapByteOrder32Little,
          stored = false,
        ) { byteArrayOf(b(it), g(it), r(it), 7) },
        EightBitLayout(
          "BGRA straight little endian",
          CGImageAlphaInfo.kCGImageAlphaFirst.value or kCGBitmapByteOrder32Little,
          stored = true,
        ) { byteArrayOf(b(it), g(it), r(it), a(it)) },
        EightBitLayout(
          "XBGR little endian",
          CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value or kCGBitmapByteOrder32Little,
          stored = false,
        ) { byteArrayOf(7, b(it), g(it), r(it)) },
      )

    private fun r(texel: Texel): Byte = texel.red.toByte()

    private fun g(texel: Texel): Byte = texel.green.toByte()

    private fun b(texel: Texel): Byte = texel.blue.toByte()

    private fun a(texel: Texel): Byte = texel.alpha.toByte()
  }
}
