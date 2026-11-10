package dev.jordond.filmstrip.effects.spatial

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.VIGNETTE_GAIN_MSL
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inSignalOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColorKernel
import platform.CoreImage.CIImage
import platform.CoreImage.CIKernel
import platform.CoreImage.CIVector
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObjectProtocol
import kotlin.math.roundToInt

/**
 * Compiled once and shared by every frame of every plan.
 *
 * Empty when this device cannot compile the kernels, which is what [spatialKernelLimit] reports.
 */
@OptIn(ExperimentalForeignApi::class)
private val KERNELS: Map<String, CIKernel> by lazy {
  CIKernel
    .kernelsWithMetalString(SPATIAL_METAL, error = null)
    ?.filterIsInstance<CIKernel>()
    ?.associateBy { it.name }
    .orEmpty()
}

/**
 * This blur as a Core Image step, on the signal a color effect reads, or refused by name with [limit] when this device
 * has one.
 *
 * The frame is boxed down to the size [BlurPlan.of] picks for its width, blurred there by Core Image's own Gaussian at
 * the plan's sigma with its edge held, and read back up to the frame linearly. Core Image holds color premultiplied by
 * alpha, so each pixel is weighed by its alpha on the way. The result is divided by the blurred alpha and every pixel
 * keeps the alpha it came with, so a picture keeps its level beside pixels with none, the way
 * [dev.jordond.filmstrip.color.blurPlane] blurs.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun GaussianBlur.toStep(limit: String? = spatialKernelLimit()): EffectResolution {
  if (limit != null) return EffectResolution.Unsupported(id, limit)
  return EffectResolution.Resolved(
    PlatformEffect(
      CoreImageEffect { image, frame ->
        val extent = image.extent
        val (width, height) = extent.useContents { listOf(size.width, size.height) }
        if (sigma <= 0f || width < 1.0 || height < 1.0) {
          image
        } else {
          val plan = BlurPlan.of(sigma.toDouble(), width.roundToInt())
          image.inSignalOf(frame.attributes.hdrTransfer, frame.attributes.colorSpace) { signal ->
            val blurred = signal.blurredBy(plan, extent)
            val restore = KERNELS[BLUR_RESTORE_KERNEL] as? CIColorKernel ?: error(SPATIAL_KERNEL_LIMIT)
            restore.applyWithExtent(extent, arguments = listOf(blurred, signal)) ?: error(SPATIAL_KERNEL_LIMIT)
          }
        }
      },
    ),
  )
}

/**
 * This image over [extent] blurred by [plan], and read back up to [extent] linearly.
 *
 * The image is held at the edge of [extent], boxed down to the plan's size with [BOX_DOWN_MSL] and blurred there by
 * [blurredWithin]. Core Image holds color premultiplied by alpha, so a pixel is weighed by its alpha, and an image
 * with clear pixels is divided by the blurred alpha after.
 *
 * @throws IllegalStateException when this device cannot run the box down kernel, which [SPATIAL_KERNEL_LIMIT] names.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public fun CIImage.blurredBy(
  plan: BlurPlan,
  extent: CValue<CGRect>,
): CIImage {
  val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
  val smallWidth = plan.width.toDouble()
  val smallHeight = plan.heightOf(height.roundToInt()).toDouble()
  val small = CGRectMake(0.0, 0.0, smallWidth, smallHeight)
  val down =
    (KERNELS[BLUR_DOWN_KERNEL] ?: error(SPATIAL_KERNEL_LIMIT)).applyWithExtent(
      small,
      roiCallback = { _, _ -> extent },
      arguments =
        listOf(
          imageByClampingToExtent(),
          CIVector.vectorWithX(x, Y = y, Z = width, W = height),
          CIVector.vectorWithX(smallWidth, Y = smallHeight, Z = plan.factor.toDouble()),
        ),
    ) ?: error(SPATIAL_KERNEL_LIMIT)
  return down
    .blurredWithin(plan.sigmaPx, small)
    .imageByApplyingTransform(
      CGAffineTransformConcat(
        CGAffineTransformMakeScale(width / smallWidth, height / smallHeight),
        CGAffineTransformMakeTranslation(x, y),
      ),
    ).imageByCroppingToRect(extent)
}

/**
 * This image blurred by Core Image's own Gaussian at [sigmaPx] of its pixels, held at its edge past [small] the way
 * [BlurPlan.of]'s blur clamps.
 *
 * Every effect that blurs a frame boxes it down to [small] first, with [BOX_DOWN_MSL], and reads the result at its own
 * size after.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public fun CIImage.blurredWithin(
  sigmaPx: Double,
  small: CValue<CGRect>,
): CIImage =
  imageByClampingToExtent()
    .imageByApplyingGaussianBlurWithSigma(sigmaPx)
    .imageByCroppingToRect(small)
    .imageByClampingToExtent()

/**
 * Metal that boxes a frame down to one blurred pixel per factor by factor area, for a Core Image kernel.
 *
 * It defines `float4 boxDown(coreimage::sampler frame, float4 box, float3 small, float2 coord)`, the mean at
 * destination `coord` of factor by factor taps spread evenly over the frame pixels one blurred pixel covers. `box`
 * holds the frame's origin and size, and `small` the blurred size and the factor. Core Image samples color
 * premultiplied by alpha, so the mean weighs each pixel by its alpha.
 */
@InternalFilmstripApi
public val BOX_DOWN_MSL: String =
  """
  float4 boxDown(coreimage::sampler frame, float4 box, float3 small, float2 coord) {
    int factor = int(small.z + 0.5f);
    float2 at = coord / small.xy;
    float4 sum = float4(0.0f);
    for (int y = 0; y < factor; y++) {
      for (int x = 0; x < factor; x++) {
        float2 tap = (float2(float(x), float(y)) + 0.5f) / small.z - 0.5f;
        float2 read = box.xy + (at + tap / small.xy) * box.zw;
        sum += frame.sample(frame.transform(read));
      }
    }
    return sum / (small.z * small.z);
  }
  """.trimIndent()

/**
 * This vignette as a Core Image step, on the signal a color effect reads, or refused by name with [limit] when this
 * device has one.
 *
 * A Metal kernel multiplies each pixel by [dev.jordond.filmstrip.color.vignetteGain] at its centre, read as a fraction
 * of the frame the step is handed.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun Vignette.toStep(limit: String? = spatialKernelLimit()): EffectResolution {
  if (limit != null) return EffectResolution.Unsupported(id, limit)
  return EffectResolution.Resolved(
    PlatformEffect(
      CoreImageEffect { image, frame ->
        val kernel = KERNELS[VIGNETTE_KERNEL] as? CIColorKernel ?: error(SPATIAL_KERNEL_LIMIT)
        val extent = image.extent
        val box = extent.useContents { CIVector.vectorWithX(origin.x, Y = origin.y, Z = size.width, W = size.height) }
        val shape = CIVector.vectorWithX(strength.toDouble(), Y = radius.toDouble(), Z = softness.toDouble())
        image.inSignalOf(frame.attributes.hdrTransfer, frame.attributes.colorSpace) { signal ->
          kernel.applyWithExtent(extent, arguments = listOf(signal, box, shape)) ?: error(SPATIAL_KERNEL_LIMIT)
        }
      },
    ),
  )
}

/**
 * Why this device cannot draw a [GaussianBlur] or a [Vignette], or null when it can.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernel really compiles, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun spatialKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { listOf(BLUR_DOWN_KERNEL, BLUR_RESTORE_KERNEL, VIGNETTE_KERNEL).all { it in KERNELS } },
): String? = SPATIAL_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the blur and vignette kernels.
 */
internal const val SPATIAL_KERNEL_LIMIT: String =
  "Blur and vignette on Apple run a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and " +
    "this device cannot compile one."

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"
private const val BLUR_DOWN_KERNEL = "filmstripBlurDown"
private const val BLUR_RESTORE_KERNEL = "filmstripBlurRestore"
private const val VIGNETTE_KERNEL = "filmstripVignette"

// The blur's box down is BOX_DOWN_MSL. Core Image samples color premultiplied by alpha, so the box and the blur after
// it weigh each pixel by its alpha.
//
// The restore divides the blurred color by the blurred alpha and puts the alpha of the frame before the blur back.
//
// The vignette is vignetteGain at the centre of each pixel, with box as above and shape the strength, radius and
// softness. The frame is symmetric top to bottom, so Core Image's upward y reads the same gain.
private val SPATIAL_METAL =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

${VIGNETTE_GAIN_MSL.prependIndent("  ")}

${BOX_DOWN_MSL.prependIndent("  ")}

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $BLUR_DOWN_KERNEL(sampler frame, float4 box, float3 small, destination dest) {
      return boxDown(frame, box, small, dest.coord());
    }

    [[stitchable]] float4 $BLUR_RESTORE_KERNEL(sample_t blurred, sample_t original) {
      float3 straight = blurred.a > 0.0f ? blurred.rgb / blurred.a : float3(0.0f);
      return float4(straight * original.a, original.a);
    }

    [[stitchable]] float4 $VIGNETTE_KERNEL(sample_t pixel, float4 box, float3 shape, destination dest) {
      float2 at = (dest.coord() - box.xy) / box.zw;
      float gain = vignetteGain(at, box.z / box.w, shape.x, shape.y, shape.z);
      return float4(pixel.rgb * gain, pixel.a);
    }
  }}
  """.trimIndent()
