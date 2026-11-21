package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inCodesOf
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
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
import platform.CoreGraphics.CGRectApplyAffineTransform
import platform.CoreGraphics.CGRectInset
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.CoreImage.CIKernel
import platform.CoreImage.CIVector
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObjectProtocol
import kotlin.time.DurationUnit

/**
 * Compiled once and shared by every frame of every plan.
 *
 * Empty when this device cannot compile the kernels, which is what [digicamKernelLimit] reports.
 */
@OptIn(ExperimentalForeignApi::class)
private val KERNELS: Map<String, CIKernel> by lazy {
  DIGICAM_KERNELS
    .mapNotNull { name ->
      CIKernel
        .kernelsWithMetalString(digicamMetal(name), error = null)
        ?.filterIsInstance<CIKernel>()
        ?.firstOrNull { it.name == name }
    }.associateBy { it.name }
}

/**
 * [digicam] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun digicamResolution(
  digicam: Digicam,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(digicam.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(digicamStep(digicam)))
  }

/**
 * The Core Image step that draws [digicam], on the frame's own codes.
 *
 * A frame's look comes from its composition time and its extent alone, so a seek or a smaller render shows the same
 * one. The frame is boxed down to the size [DigicamSignal.blurPlan] picks, blurred across and then up by the plan's
 * own weights, and the last kernel sharpens the frame against that, clips it and adds the noise.
 *
 * @throws IllegalStateException When a kernel will not run, which [digicamKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun digicamStep(digicam: Digicam): CoreImageEffect {
  val params = DigicamParams.of(digicam)
  return CoreImageEffect { image, frame ->
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val tick = DigicamSignal.tickAt(frame.compositionTime.toDouble(DurationUnit.SECONDS)) and HASH_MASK
    val plan = DigicamSignal.blurPlan(width.toInt())
    val factor = plan.factor.toDouble()
    val blurWidth = plan.width.toDouble()
    val blurHeight = plan.heightOf(height.toInt()).toDouble()
    val small = CGRectMake(0.0, 0.0, blurWidth, blurHeight)
    val toSmall =
      CGAffineTransformConcat(
        CGAffineTransformMakeTranslation(-x, -y),
        CGAffineTransformMakeScale(blurWidth / width, blurHeight / height),
      )

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      val box = CIVector.vectorWithX(x, Y = y, Z = width, W = height)
      val edge = toCodes(image).imageByClampingToExtent()
      val down =
        kernel(DIGICAM_DOWN_KERNEL).applyWithExtent(
          small,
          roiCallback = { _, _ -> extent },
          arguments = listOf(edge, box, CIVector.vectorWithX(blurWidth, Y = blurHeight, Z = factor)),
        ) ?: error(DIGICAM_KERNEL_LIMIT)
      val blurred = down.blurredAlong(plan, small, 1.0, 0.0).blurredAlong(plan, small, 0.0, 1.0)
      kernel(DIGICAM_KERNEL).applyWithExtent(
        extent,
        roiCallback = { index, rect ->
          if (index == 0) rect else CGRectInset(CGRectApplyAffineTransform(rect, toSmall), -1.0, -1.0)
        },
        arguments =
          listOf(
            edge,
            blurred.imageByClampingToExtent(),
            box,
            CIVector.vectorWithX(blurWidth, Y = blurHeight),
            CIVector.vectorWithX(params.haloGain, Y = params.clipGain, Z = params.noiseAmplitude),
            CIVector.vectorWithX(
              (digicam.seed and HASH_MASK).toDouble(),
              Y = tick.toDouble(),
              Z = DigicamSignal.cellPx(height.toInt()).toDouble(),
            ),
          ),
      ) ?: error(DIGICAM_KERNEL_LIMIT)
    }
  }
}

/**
 * This image over [small] blurred by [plan]'s weights along the axis [stepX], [stepY] steps one pixel of, with its
 * edge held.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.blurredAlong(
  plan: BlurPlan,
  small: CValue<CGRect>,
  stepX: Double,
  stepY: Double,
): CIImage {
  val reach = plan.taps.toDouble()
  val weights =
    List(DIGICAM_WEIGHT_VECTORS) { vector ->
      val at = { index: Int -> plan.weights.getOrElse(vector * 4 + index) { 0.0 } }
      CIVector.vectorWithX(at(0), Y = at(1), Z = at(2), W = at(3))
    }
  return kernel(DIGICAM_BLUR_KERNEL).applyWithExtent(
    small,
    roiCallback = { _, rect -> CGRectInset(rect, -reach * stepX, -reach * stepY) },
    arguments = listOf(imageByClampingToExtent(), CIVector.vectorWithX(stepX, Y = stepY, Z = reach)) + weights,
  ) ?: error(DIGICAM_KERNEL_LIMIT)
}

/**
 * Why this device cannot draw a [Digicam], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernels really compile, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun digicamKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { DIGICAM_KERNELS.all { it in KERNELS } },
): String? = DIGICAM_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernels.
 */
internal const val DIGICAM_KERNEL_LIMIT: String =
  "Digicam on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this device " +
    "cannot compile one."

private fun kernel(name: String): CIKernel = KERNELS[name] ?: error(DIGICAM_KERNEL_LIMIT)

private val DIGICAM_KERNELS = listOf(DIGICAM_DOWN_KERNEL, DIGICAM_BLUR_KERNEL, DIGICAM_KERNEL)

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"
