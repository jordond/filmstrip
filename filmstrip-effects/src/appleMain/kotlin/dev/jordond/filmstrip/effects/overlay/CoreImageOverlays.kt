package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.atOrigin
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.color.inCodesOf
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.authoredHdrTexels
import dev.jordond.filmstrip.media.signalFromNits
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGAffineTransform
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import platform.CoreImage.kCIContextWorkingColorSpace
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBA8
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.numberWithInt
import platform.Foundation.setValue

/**
 * The transform that puts an overlay where this placement says.
 *
 * The rectangle comes from [rectOn], so the anchor pair is brought together in one place rather
 * than once per backend. Core Image measures real pixels from the bottom-left with +Y up and
 * filmstrip authors fractions from the top-left with +Y down, so the rectangle's bottom edge is
 * what the translation is taken from.
 *
 * @param frame The frame the overlay lands on, in pixels.
 * @param raster The overlay's own pixel size, which the drawn size is scaled from.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun OverlayPlacement.transformOnto(
  frame: Size,
  raster: Size,
): CValue<CGAffineTransform> {
  val rect = rectOn(frame)
  val scaleX = if (raster.width <= 0) 1.0 else size.width.toDouble() / raster.width
  val scaleY = if (raster.height <= 0) 1.0 else size.height.toDouble() / raster.height
  val translateX = rect.left.toDouble() * frame.width
  val translateY = (1.0 - rect.bottom.toDouble()) * frame.height

  return CGAffineTransformConcat(
    CGAffineTransformMakeScale(scaleX, scaleY),
    CGAffineTransformMakeTranslation(translateX, translateY),
  )
}

/**
 * An overlay's pixels, held as bare codes with no colour space attached.
 *
 * An SDR frame draws an authored colour as its own codes, so there the codes are tagged with the frame's own code
 * space and come out of the blend where they were authored. An HDR frame draws each texel at the light
 * [authoredHdrTexels] puts its colour at, encoded for the frame's transfer and tagged with the space that transfer is
 * written in, the way a fill hands Core Image its colour. An opaque texel then lands where a fill of the same colour
 * does.
 *
 * @property codes The pixels with no colour space attached.
 */
internal class OverlayPixels(
  val codes: CIImage,
) {
  val size: Size = codes.pixelSize()

  private val graded: Map<HdrTransfer, Lazy<CIImage?>> =
    HdrTransfer.entries.associateWith { transfer -> lazy { codes.inAuthoredLight(transfer, size) } }

  /**
   * The image to draw onto a frame written in [colorSpace] with [transfer], or with none when the
   * frame is SDR.
   *
   * The HDR image is built the first time a frame asks for it. A platform that cannot name the transfer's space, or
   * cannot read the codes back, draws the codes the way an SDR frame does, dimmer than authored rather than failing.
   */
  fun enteringFrame(
    transfer: HdrTransfer?,
    colorSpace: ColorSpace,
  ): CIImage = transfer?.let { graded.getValue(it).value } ?: codes.taggedWithCodesOf(colorSpace)
}

/**
 * This image's texels at the light [authoredHdrTexels] puts them at, as a float image carrying [transfer]'s signal.
 *
 * The mapping runs on each texel's straight colour and the signal is premultiplied after, since Core Image takes
 * bitmap data as premultiplied and unpremultiplies before it matches a colour out of the space.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun CIImage.inAuthoredLight(
  transfer: HdrTransfer,
  size: Size,
): CIImage? {
  val argb = straightCodes(size) ?: return null
  val texels = authoredHdrTexels(argb)
  val signals = HashMap<Int, FloatArray>()
  for (index in argb.indices) {
    val offset = index * TEXEL_FLOATS
    val alpha = texels[offset + ALPHA]
    val signal =
      signals.getOrPut(argb[index] and RGB_MASK) {
        transfer.signalFromNits(texels.copyOfRange(offset, offset + ALPHA))
      }
    repeat(ALPHA) { texels[offset + it] = signal[it] * alpha }
  }

  val space = transfer.createSignalSpace() ?: return null
  try {
    val data =
      texels.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = (texels.size * Float.SIZE_BYTES).toULong())
      }
    return CIImage.imageWithBitmapData(
      data = data,
      bytesPerRow = (size.width * TEXEL_FLOATS * Float.SIZE_BYTES).toULong(),
      size = CGSizeMake(size.width.toDouble(), size.height.toDouble()),
      format = kCIFormatRGBAf,
      colorSpace = space,
    )
  } finally {
    CGColorSpaceRelease(space)
  }
}

/**
 * This image's pixels as straight ARGB codes, top row first, or null when no context can read them.
 *
 * The reader has no working space and writes whatever it is handed as though it were premultiplied, so the image is
 * unpremultiplied first and every code comes back as the source holds it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.straightCodes(size: Size): IntArray? {
  val reader = codeReader ?: return null
  val rowBytes = size.width * RGBA
  val bytes = UByteArray(rowBytes * size.height)
  bytes.usePinned { pinned ->
    reader.render(
      image = imageByUnpremultiplyingAlpha(),
      toBitmap = pinned.addressOf(0),
      rowBytes = rowBytes.toLong(),
      bounds = extent,
      format = kCIFormatRGBA8,
      colorSpace = null,
    )
  }

  return IntArray(size.width * size.height) { index ->
    val offset = index * RGBA
    (bytes[offset + ALPHA].toInt() shl ALPHA_SHIFT) or
      (bytes[offset].toInt() shl RED_SHIFT) or
      (bytes[offset + 1].toInt() shl GREEN_SHIFT) or
      bytes[offset + 2].toInt()
  }
}

// Kotlin/Native raises at the constructor when Core Image answers nil, which a headless process can.
@OptIn(ExperimentalForeignApi::class)
private val codeReader: CIContext? by lazy {
  try {
    CIContext.contextWithOptions(codeReaderOptions())
  } catch (absent: NullPointerException) {
    null
  }
}

/**
 * The options the code reader's context is made with, no working space and a float working format.
 *
 * Built as an `NSMutableDictionary` and handed over as the dictionary itself, so Core Image reads the keys it was
 * given rather than whatever a bridged Kotlin map turns into on the way.
 */
@Suppress("CAST_NEVER_SUCCEEDS", "UNCHECKED_CAST")
@OptIn(ExperimentalForeignApi::class)
internal fun codeReaderOptions(): Map<Any?, *> {
  val options = NSMutableDictionary()
  kCIContextWorkingColorSpace?.let { options.setValue(NSNull.`null`(), forKey = it) }
  kCIContextWorkingFormat?.let { options.setValue(NSNumber.numberWithInt(kCIFormatRGBAf), forKey = it) }
  return options as Map<Any?, *>
}

/**
 * Creates the colour space a frame written with this transfer carries its signal in. The caller releases it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun HdrTransfer.createSignalSpace(): CGColorSpaceRef? =
  CGColorSpaceCreateWithName(
    when (this) {
      HdrTransfer.Pq -> kCGColorSpaceITUR_2100_PQ
      HdrTransfer.Hlg -> kCGColorSpaceITUR_2100_HLG
    },
  )

private const val RGBA = 4
private const val TEXEL_FLOATS = 4
private const val ALPHA = 3
private const val RGB_MASK = 0xFFFFFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

// Decoded through the same space inCodesOf encodes into, so carrying the result into those codes
// again hands back the values it started with.
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.taggedWithCodesOf(colorSpace: ColorSpace): CIImage {
  val space = colorSpace.createCodeSpace()

  try {
    return imageByColorMatchingColorSpaceToWorkingSpace(space) ?: this
  } finally {
    CGColorSpaceRelease(space)
  }
}

/**
 * Draws this overlay over [background], placed and faded as asked.
 *
 * Core Image's working space is linear light. An SDR frame, where [transfer] is null, blends on the
 * codes of its own [colorSpace] instead, the values every other backend reads off the buffer and
 * draws an overlay on, so both images are carried into those codes for the source over and the
 * result is carried back to the working space. An HDR frame blends in the working space as it is.
 *
 * Colour matching unpremultiplies before it moves a colour and premultiplies after, so the soft
 * edge of a glyph or an image blends by its own alpha rather than fringing.
 *
 * Overlays are not batched the way Android's are. Core Image fuses a run of composites into one
 * pass over the frame, so there is no sampler budget to spend and no analogue of
 * [MAX_OVERLAYS_PER_EFFECT].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.compositedOnto(
  background: CIImage,
  placement: OverlayPlacement,
  frame: Size,
  opacity: Float,
  transfer: HdrTransfer?,
  colorSpace: ColorSpace,
): CIImage {
  val placed = atOrigin().imageByApplyingTransform(placement.transformOnto(frame, pixelSize()))
  if (transfer != null) return placed.withAlpha(opacity).imageByCompositingOverImage(background)

  return inCodesOf(colorSpace) { toCodes ->
    toCodes(placed).withAlpha(opacity).imageByCompositingOverImage(toCodes(background))
  }
}

/**
 * Scales the whole image's alpha.
 *
 * `CIColorMatrix` unpremultiplies its input and premultiplies its output, so scaling the alpha row
 * is all it takes to scale how much of the frame the overlay covers. Scaling the colour rows as
 * well dims the colour a second time on the way back, which draws the overlay at the square of the
 * alpha it was asked for.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.withAlpha(alpha: Float): CIImage {
  if (alpha >= 1f) return this
  val scale = alpha.coerceIn(0f, 1f).toDouble()

  return imageByApplyingFilter(
    "CIColorMatrix",
    mapOf("inputAVector" to CIVector.vectorWithX(0.0, 0.0, 0.0, scale)),
  )
}
