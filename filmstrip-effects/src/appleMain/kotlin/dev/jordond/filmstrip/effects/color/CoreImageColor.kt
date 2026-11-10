package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.color.brightnessDisplayGain
import dev.jordond.filmstrip.color.displayLightCeiling
import dev.jordond.filmstrip.color.hlgSignalFromSdrSignal
import dev.jordond.filmstrip.color.sdrSignalCeiling
import dev.jordond.filmstrip.color.sdrSignalCeilingDouble
import dev.jordond.filmstrip.color.sdrSignalFromHlgSignal
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.kCGColorSpaceExtendedLinearITUR_2020
import platform.CoreGraphics.kCGColorSpaceITUR_2020
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import platform.CoreVideo.CVImageBufferCreateColorSpaceFromAttachments
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSData
import platform.Foundation.NSNull
import platform.Foundation.create

/**
 * Recombines the color channels through [matrix], in the encoded domain the matrix is written for.
 *
 * A `CIContext` works in linear light, so a bare color matrix would mix light rather than signal
 * and land somewhere the three backends that mix what the encoder writes never do. An SDR frame is
 * carried into the codes of its own [colorSpace] for the matrix and back out again, so the matrix
 * mixes the values the encoder writes. The clamp runs once here rather than around each effect,
 * since a run of color effects has already been folded into this one matrix by the time it arrives.
 *
 * On a kept grade the matrix runs on the SDR signal [inSignalOf] carries the frame into, clamped
 * at [sdrSignalCeiling] rather than at white, since that is where the format runs out.
 *
 * A matrix that scales all three channels by the same factor is the exception on a PQ grade: the
 * frame there is display light, which the scale commutes with, so it multiplies the light directly
 * and clamps at [displayLightCeiling], the same ceiling in the domain it ran in. The clamp is the
 * reason it still moves into the frame's own primaries first: a scale reads the same in either
 * basis, and a ceiling does not, so clamping in the working space would cut a saturated channel
 * the frame's own primaries leave alone. Core Image's HLG decode ties the channels together, so
 * on an HLG grade a scale takes the same road as any other matrix.
 *
 * Each row's offset rides in the fourth component of its vector instead of in the bias vector, so
 * it is multiplied by alpha. Core Image holds a frame premultiplied, and a bias vector would lift a
 * transparent pixel off black into a color a later blend then reads as real.
 *
 * @param colorSpace The color space the frame is written in.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.withColorMatrix(
  matrix: ColorMatrix,
  transfer: HdrTransfer?,
  colorSpace: ColorSpace,
): CIImage {
  if (matrix.isIdentity) return this
  val scale = matrix.uniformScale
  if (transfer == HdrTransfer.Pq && scale != null) {
    val gain = brightnessDisplayGain(scale)

    return inPrimariesOf(colorSpace) {
      recombined(scaleMatrix(gain, gain, gain)).clampedTo(transfer.displayLightCeiling)
    }
  }
  if (transfer != null) return inSignalOf(transfer, colorSpace) { signal -> signal.recombined(matrix) }

  return inCodesOf(colorSpace) { toCodes ->
    toCodes(this).recombined(matrix).imageByApplyingFilter("CIColorClamp")
  }
}

/**
 * Runs [block] on the encoded signal a color effect reads, and carries what it returns back to the working space.
 *
 * An SDR frame is carried into the codes of its own [colorSpace], as [inCodesOf] does. On a kept grade [block] runs on
 * the SDR signal the other backends grade in, and what it returns is clamped at [sdrSignalCeiling] and taken back.
 *
 * A PQ frame is display light with reference white at one, so the signal is a bare power of it, taken in the frame's
 * own primaries. An HLG frame is display light Core Image decoded through an opto-optical transfer of its own, which
 * reads all three channels at once, so a power of it would not be the signal. The frame is matched into BT.2100's HLG
 * space instead, which gives back the codes it was decoded from, and each code is carried to [sdrSignalFromHlgSignal]
 * and back through [hlgSignalFromSdrSignal]. A kept HLG grade is read in BT.2020 primaries, the ones that space holds.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.inSignalOf(
  transfer: HdrTransfer?,
  colorSpace: ColorSpace,
  block: (CIImage) -> CIImage,
): CIImage =
  when (transfer) {
    null -> {
      inCodesOf(colorSpace) { toCodes -> block(toCodes(this)) }
    }
    HdrTransfer.Pq -> {
      inPrimariesOf(colorSpace) {
        block(raisedTo(1.0 / SDR_DISPLAY_GAMMA))
          .clampedTo(transfer.sdrSignalCeiling)
          .raisedTo(SDR_DISPLAY_GAMMA)
      }
    }
    HdrTransfer.Hlg -> {
      inHlgCodes { codes ->
        block(codes.curvedBy(SDR_SIGNAL_FROM_HLG))
          .clampedTo(transfer.sdrSignalCeiling)
          .curvedBy(HLG_FROM_SDR_SIGNAL)
      }
    }
  }

/**
 * Runs [block] on the code values an SDR frame in [colorSpace] is written with, and carries what it
 * returns back to the working space.
 *
 * Core Image decodes a frame into its linear working space through the color space the frame is
 * tagged with. [block] is handed the step that inverts that decode, so every image it moves through
 * that step carries the frame's own codes, the values the other backends read straight off the
 * buffer.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public inline fun inCodesOf(
  colorSpace: ColorSpace,
  block: (toCodes: (CIImage) -> CIImage) -> CIImage,
): CIImage {
  val space = colorSpace.createCodeSpace()

  try {
    val encoded = block { image -> image.imageByColorMatchingWorkingSpaceToColorSpace(space) ?: image }
    return encoded.imageByColorMatchingColorSpaceToWorkingSpace(space) ?: encoded
  } finally {
    CGColorSpaceRelease(space)
  }
}

/**
 * Creates the color space an SDR frame in this space holds its codes in. The caller releases it.
 *
 * BT.2020 gets Core Graphics' named BT.2020 space. Every other space gets the one CoreVideo builds
 * from a buffer tagged with BT.709 primaries, transfer and matrix, the space AVFoundation decodes an
 * SDR source frame through and encodes an SDR composition's output through. A color tagged with it
 * lands in the buffer as the codes it was given, so an SDR effect, fill or overlay uses it to work on
 * the frame's own codes.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public fun ColorSpace.createCodeSpace(): CGColorSpaceRef? {
  if (this == ColorSpace.Bt2020) return CGColorSpaceCreateWithName(kCGColorSpaceITUR_2020)

  val attachments =
    CFDictionaryCreateMutable(null, BT709_TAGS, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
  CFDictionarySetValue(attachments, kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_709_2)
  CFDictionarySetValue(attachments, kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_ITU_R_709_2)
  CFDictionarySetValue(attachments, kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_709_2)
  try {
    return CVImageBufferCreateColorSpaceFromAttachments(attachments)
  } finally {
    CFRelease(attachments)
  }
}

private const val BT709_TAGS = 3L

// Both spaces are linear and extended, so the trip each way is one matrix and a channel outside the
// sRGB gamut survives it as a negative rather than being cut off. Anything but BT.2020 already
// shares the working space's primaries and runs in place.
@OptIn(ExperimentalForeignApi::class)
private inline fun CIImage.inPrimariesOf(
  colorSpace: ColorSpace,
  block: CIImage.() -> CIImage,
): CIImage {
  if (colorSpace != ColorSpace.Bt2020) return block()
  val space = CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearITUR_2020) ?: return block()

  try {
    val moved = imageByColorMatchingWorkingSpaceToColorSpace(space) ?: return block()

    return moved.block().imageByColorMatchingColorSpaceToWorkingSpace(space) ?: block()
  } finally {
    CGColorSpaceRelease(space)
  }
}

// Matching into the space Core Image decoded the frame through inverts that decode exactly, so [block] is handed the
// HLG codes the frame arrived as.
@OptIn(ExperimentalForeignApi::class)
private inline fun CIImage.inHlgCodes(block: (CIImage) -> CIImage): CIImage {
  val space = CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_HLG) ?: error(NO_HLG_SPACE)

  try {
    val codes = imageByColorMatchingWorkingSpaceToColorSpace(space) ?: error(NO_HLG_SPACE)

    return block(codes).imageByColorMatchingColorSpaceToWorkingSpace(space) ?: error(NO_HLG_SPACE)
  } finally {
    CGColorSpaceRelease(space)
  }
}

private const val NO_HLG_SPACE = "Core Image has no BT.2100 HLG color space to read an HLG grade through."

/**
 * One channel's curve as the table `CIColorCurves` reads, the same curve on red, green and blue.
 *
 * @property data [CURVE_ENTRIES] red, green and blue float triplets, evenly spaced across the domain.
 * @property end Where the domain ends. It starts at zero.
 */
private class ChannelCurve(
  val data: NSData,
  val end: Double,
)

private val SDR_SIGNAL_FROM_HLG: ChannelCurve by lazy { channelCurve(1.0, ::sdrSignalFromHlgSignal) }

private val HLG_FROM_SDR_SIGNAL: ChannelCurve by lazy {
  channelCurve(HdrTransfer.Hlg.sdrSignalCeilingDouble, ::hlgSignalFromSdrSignal)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun channelCurve(
  end: Double,
  curve: (Double) -> Double,
): ChannelCurve {
  val table = FloatArray(CURVE_ENTRIES * CHANNELS) { curve(end * (it / CHANNELS) / (CURVE_ENTRIES - 1)).toFloat() }
  val data =
    table.usePinned {
      NSData.create(bytes = it.addressOf(0), length = (table.size * Float.SIZE_BYTES).convert())
    }

  return ChannelCurve(data, end)
}

private const val CURVE_ENTRIES = 1_024
private const val CHANNELS = 3

// With no color space of its own the curve reads the values it is handed as they are.
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.curvedBy(curve: ChannelCurve): CIImage =
  imageByApplyingFilter(
    "CIColorCurves",
    mapOf(
      "inputCurvesData" to curve.data,
      "inputCurvesDomain" to CIVector.vectorWithX(0.0, Y = curve.end),
      "inputColorSpace" to NSNull.`null`(),
    ),
  )

// CIGammaAdjust reads a negative channel as black rather than handing pow a sign, so nothing has to
// floor the frame before the first power.
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.raisedTo(power: Double): CIImage =
  imageByApplyingFilter(
    "CIGammaAdjust",
    mapOf("inputPower" to power),
  )

@OptIn(ExperimentalForeignApi::class)
private fun CIImage.clampedTo(ceiling: Float): CIImage =
  imageByApplyingFilter(
    "CIColorClamp",
    mapOf(
      "inputMinComponents" to vector(0f, 0f, 0f, 0f),
      "inputMaxComponents" to vector(ceiling, ceiling, ceiling, 1f),
    ),
  )

@OptIn(ExperimentalForeignApi::class)
private fun CIImage.recombined(matrix: ColorMatrix): CIImage =
  imageByApplyingFilter(
    "CIColorMatrix",
    mapOf(
      "inputRVector" to vector(matrix.rr, matrix.rg, matrix.rb, matrix.rBias),
      "inputGVector" to vector(matrix.gr, matrix.gg, matrix.gb, matrix.gBias),
      "inputBVector" to vector(matrix.br, matrix.bg, matrix.bb, matrix.bBias),
      "inputAVector" to vector(0f, 0f, 0f, 1f),
      "inputBiasVector" to vector(0f, 0f, 0f, 0f),
    ),
  )

@OptIn(ExperimentalForeignApi::class)
private fun vector(
  x: Float,
  y: Float,
  z: Float,
  w: Float,
): CIVector = CIVector.vectorWithX(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble())
