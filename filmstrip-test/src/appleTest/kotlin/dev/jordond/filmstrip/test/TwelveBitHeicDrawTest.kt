package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import kotlin.test.Test

/**
 * Pins that ImageIO's decode of a [HeicColorPictures.twelveBit] HEIC at limited range draws nothing into a sixteen-bit
 * bitmap, the way [AppleStillSamples.twelveBit] says, so a platform build that starts drawing it fails here.
 *
 * Each still is handed over through [heifWithExplicitCodestreamColor] under a matrix ImageIO reads, drawn over a
 * bitmap filled with one color, and read back at its centre. At limited range the fill is still there. At full range
 * the same still replaces it, which shows the draw itself works.
 */
@OptIn(ExperimentalForeignApi::class)
class TwelveBitHeicDrawTest {
  @Test
  fun aSixteenBitDrawOfATwelveBitLimitedRangeHeicDrawsNothing() {
    for (matrix in DRAWN_MATRICES) {
      val (fill, drawn) = drawnCentre(CicpTag(BT709, SRGB_TRANSFER, matrix, false))

      withClue("the limited range HEIC under matrix $matrix drew over the fill") { drawn shouldBe fill }
    }
  }

  @Test
  fun aSixteenBitDrawOfATwelveBitFullRangeHeicDrawsOverTheFill() {
    for (matrix in DRAWN_MATRICES) {
      val (fill, drawn) = drawnCentre(CicpTag(BT709, SRGB_TRANSFER, matrix, true))

      withClue("the full range HEIC under matrix $matrix drew nothing") { drawn shouldNotBe fill }
    }
  }

  // The sixteen-bit RGBA at the centre of a bitmap filled with one color, before and after the HEIC with nclx is
  // drawn over it.
  @OptIn(BetaInteropApi::class)
  private fun drawnCentre(nclx: CicpTag): Pair<List<Int>, List<Int>> {
    val bytes = heifWithExplicitCodestreamColor(HeicColorPictures.twelveBit(nclx)).handed
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val cf: CFDataRef = CFBridgingRetain(data).shouldNotBeNull().reinterpret()
    val source = CGImageSourceCreateWithData(cf, null).shouldNotBeNull()
    CFRelease(cf)
    val image = CGImageSourceCreateImageAtIndex(source, CGImageSourceGetPrimaryImageIndex(source), null)
    CFRelease(source)
    withClue("ImageIO decoded no image from the HEIC with nclx $nclx") { image.shouldNotBeNull() }
    val width = CGImageGetWidth(image)
    val height = CGImageGetHeight(image)
    val space = CGColorSpaceCreateDeviceRGB()
    val info = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder16Little
    val context = CGBitmapContextCreate(null, width, height, DEPTH, width * TEXEL_BYTES, space, info)
    CGColorSpaceRelease(space)
    try {
      withClue("Core Graphics made no sixteen-bit bitmap") { context.shouldNotBeNull() }
      val rect = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())
      CGContextSetRGBFillColor(context, 0.25, 0.5, 0.75, 1.0)
      CGContextFillRect(context, rect)
      val samples: CPointer<UShortVar> = CGBitmapContextGetData(context).shouldNotBeNull().reinterpret()
      val centre = (height.toLong() / 2 * width.toLong() + width.toLong() / 2) * CHANNELS
      val fill = List(CHANNELS) { samples[centre + it].toInt() }
      CGContextDrawImage(context, rect, image)
      return fill to List(CHANNELS) { samples[centre + it].toInt() }
    } finally {
      CGContextRelease(context)
      CGImageRelease(image)
    }
  }

  private companion object {
    const val BT709 = 1
    const val SRGB_TRANSFER = 13
    const val DEPTH = 16uL
    const val CHANNELS = 4
    const val TEXEL_BYTES = 8uL

    // BT.709, BT.601 and BT.2020 non-constant luminance, each one ImageIO reads, so the still is handed over as it is.
    val DRAWN_MATRICES = listOf(1, 6, 9)
  }
}
