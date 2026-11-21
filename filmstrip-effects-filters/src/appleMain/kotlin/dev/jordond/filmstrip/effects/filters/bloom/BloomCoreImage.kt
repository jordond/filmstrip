package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inCodesOf
import dev.jordond.filmstrip.effects.filters.internal.GlowField
import dev.jordond.filmstrip.effects.spatial.blurredWithin
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIKernel
import platform.CoreImage.CIVector
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObjectProtocol

/**
 * Compiled once and shared by every frame of every plan.
 *
 * Empty when this device cannot compile the kernels, which is what [bloomKernelLimit] reports.
 */
@OptIn(ExperimentalForeignApi::class)
private val KERNELS: Map<String, CIKernel> by lazy {
  CIKernel
    .kernelsWithMetalString(bloomMetal(), error = null)
    ?.filterIsInstance<CIKernel>()
    ?.associateBy { it.name }
    .orEmpty()
}

/**
 * [bloom] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun bloomResolution(
  bloom: Bloom,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(bloom.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(bloomStep(bloom)))
  }

/**
 * The Core Image step that draws [bloom], on the frame's own codes.
 *
 * A frame's look comes from its extent alone, so a seek or a smaller render shows the same one. The response runs at
 * the size [BloomSignal.blurPlan] picks, [blurredWithin] blurs it with Core Image's own Gaussian, which a test holds to
 * the plan's kernel, and the last kernel screens it over the frame.
 *
 * @throws IllegalStateException When a kernel will not run, which [bloomKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun bloomStep(bloom: Bloom): CoreImageEffect {
  val params = BloomParams.of(bloom)
  return CoreImageEffect { image, frame ->
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val plan = GlowField.blurPlan(params.sigma, width.toInt())
    val blurWidth = plan.width.toDouble()
    val blurHeight = plan.heightOf(height.toInt()).toDouble()

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      val box = CIVector.vectorWithX(x, Y = y, Z = width, W = height)
      val edge = toCodes(image).imageByClampingToExtent()
      val small = CGRectMake(0.0, 0.0, blurWidth, blurHeight)
      val response =
        kernel(BLOOM_RESPONSE_KERNEL).applyWithExtent(
          small,
          roiCallback = { _, _ -> extent },
          arguments =
            listOf(
              edge,
              box,
              CIVector.vectorWithX(blurWidth, Y = blurHeight, Z = plan.factor.toDouble()),
              CIVector.vectorWithX(params.threshold, Y = params.kneeEnd),
            ),
        ) ?: error(BLOOM_KERNEL_LIMIT)
      val glow = response.blurredWithin(plan.sigmaPx, small)
      kernel(BLOOM_SCREEN_KERNEL).applyWithExtent(
        extent,
        roiCallback = { index, rect -> if (index == 0) rect else glow.extent },
        arguments = listOf(edge, glow, box, CIVector.vectorWithX(blurWidth, Y = blurHeight, Z = params.gain)),
      ) ?: error(BLOOM_KERNEL_LIMIT)
    }
  }
}

/**
 * Why this device cannot draw a [Bloom], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernels really compile, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun bloomKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { listOf(BLOOM_RESPONSE_KERNEL, BLOOM_SCREEN_KERNEL).all { it in KERNELS } },
): String? = BLOOM_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernels.
 */
internal const val BLOOM_KERNEL_LIMIT: String =
  "Bloom on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this device " +
    "cannot compile one."

private fun kernel(name: String): CIKernel = KERNELS[name] ?: error(BLOOM_KERNEL_LIMIT)

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"
