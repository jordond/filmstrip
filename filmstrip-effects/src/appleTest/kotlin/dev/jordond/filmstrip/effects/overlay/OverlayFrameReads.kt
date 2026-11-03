package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AuthoredColors
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import kotlin.math.abs

/**
 * Decodes [png], draws it onto an SDR BT.709 frame and returns why its centre misses the sRGB codes [expected], or
 * null when it lands there.
 *
 * The codes are read unrounded, so a texel premultiplied on the eight-bit grid shows up even at half alpha.
 */
internal fun sdrMiss(
  name: String,
  png: ByteArray,
  expected: Int,
  context: CIContext,
  tolerance: Float = SDR_TOLERANCE,
): String? =
  sdrMiss(
    name,
    png,
    AuthoredColors.channelsOf(expected).map { it.toFloat() },
    (expected ushr ALPHA_SHIFT) / MAX_CODE,
    context,
    tolerance,
  )

/**
 * Decodes [png], draws it onto an SDR BT.709 frame and returns why its centre misses the unrounded red, green and blue
 * codes [color] at [alpha], or null when it lands there.
 */
internal fun sdrMiss(
  name: String,
  png: ByteArray,
  color: List<Float>,
  alpha: Float,
  context: CIContext,
  tolerance: Float = SDR_TOLERANCE,
): String? {
  val read = drawnSdr(name, png, context)
  val codes = read.take(ALPHA)
  return when {
    codes.indices.any { abs(codes[it] - color[it]) > tolerance } -> "$name drew $codes, where its color is $color"
    abs(read[ALPHA] - alpha) > ALPHA_TOLERANCE -> "$name drew alpha ${read[ALPHA]}, not $alpha"
    else -> null
  }
}

/**
 * Decodes [png], named [name] when it fails to read, and draws it onto an SDR BT.709 frame, returning the unrounded
 * red, green and blue codes at its centre and then its alpha.
 */
internal fun drawnSdr(
  name: String,
  png: ByteArray,
  context: CIContext,
): List<Float> {
  val pixels = ImageSource.ofBytes(png).read().orFail(name)
  val read = pixels.enteringFrame(transfer = null, ColorSpace.Bt709).centreIn(context, ColorSpace.Bt709)
  return List(ALPHA) { read[it] / read[ALPHA] * MAX_CODE } + read[ALPHA]
}

/**
 * Decodes [png], named [name] when it fails to read, and draws it onto an HDR frame written with [transfer], returning
 * the ten-bit codes at its centre and then its alpha.
 */
internal fun drawnHdr(
  name: String,
  png: ByteArray,
  transfer: HdrTransfer,
  context: CIContext,
): Pair<List<Int>, Float> {
  val pixels = ImageSource.ofBytes(png).read().orFail(name)
  val read = pixels.enteringFrame(transfer, ColorSpace.Bt2020).centreIn(context, transfer)
  return tenBitCodesFromSignal(FloatArray(ALPHA) { read[it] / read[ALPHA] }).toList() to read[ALPHA]
}

/**
 * Decodes [png], draws it onto an HDR frame written with [transfer] and returns why its centre misses the signal
 * [light] encodes to, or null when it lands there.
 */
internal fun hdrMiss(
  name: String,
  png: ByteArray,
  transfer: HdrTransfer,
  light: FloatArray,
  context: CIContext,
): String? {
  val (codes, alpha) = drawnHdr(name, png, transfer, context)
  val expected = tenBitCodesFromSignal(transfer.signalFromNits(light.copyOf(ALPHA))).toList()
  return when {
    codes.indices.any { abs(codes[it] - expected[it]) > HDR_TOLERANCE } -> "$name carried $codes, not $expected"
    abs(alpha - light[ALPHA]) > ALPHA_TOLERANCE -> "$name carried alpha $alpha, not ${light[ALPHA]}"
    else -> null
  }
}

/**
 * One texel of these sixteen-bit samples, red, green, blue and alpha, as the shared functions read them.
 */
internal fun dev.jordond.filmstrip.test.HdrPngPicture.samples(): ShortArray =
  ShortArray(CHANNELS) { texel[it].toShort() }

/**
 * The premultiplied red, green, blue and alpha at the centre of the image, rendered into the codes of
 * [colorSpace].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.centreIn(
  context: CIContext,
  colorSpace: ColorSpace,
): FloatArray = centreIn(context, checkNotNull(colorSpace.createCodeSpace()))

/**
 * The premultiplied red, green, blue and alpha at the centre of the image, rendered into [transfer]'s own space so
 * the color reads as that transfer's signal.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.centreIn(
  context: CIContext,
  transfer: HdrTransfer,
): FloatArray =
  centreIn(
    context,
    checkNotNull(
      CGColorSpaceCreateWithName(
        when (transfer) {
          HdrTransfer.Pq -> kCGColorSpaceITUR_2100_PQ
          HdrTransfer.Hlg -> kCGColorSpaceITUR_2100_HLG
        },
      ),
    ),
  )

// Releases space.
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.centreIn(
  context: CIContext,
  space: CGColorSpaceRef,
): FloatArray =
  memScoped {
    val size = pixelSize()
    val rowFloats = size.width * CHANNELS
    val bitmap = allocArray<FloatVar>(rowFloats * size.height)
    context.render(
      image = this@centreIn,
      toBitmap = bitmap,
      rowBytes = (rowFloats * Float.SIZE_BYTES).toLong(),
      bounds = extent,
      format = kCIFormatRGBAf,
      colorSpace = space,
    )
    CGColorSpaceRelease(space)
    val centre = size.height / 2 * rowFloats + size.width / 2 * CHANNELS
    FloatArray(CHANNELS) { bitmap[centre + it] }
  }

internal fun Int.hex(): String = "0x${toUInt().toString(HEX)}"

private const val CHANNELS = 4
private const val ALPHA = 3
private const val ALPHA_SHIFT = 24
private const val MAX_CODE = 255f
private const val HEX = 16

// Unrounded codes, so this reads the half alpha premultiply that rounding to whole codes would hide.
internal const val SDR_TOLERANCE = 0.1f

// The trip through Core Image rounds the signal, one ten-bit code either way.
internal const val HDR_TOLERANCE = 1
private const val ALPHA_TOLERANCE = 1f / MAX_CODE
