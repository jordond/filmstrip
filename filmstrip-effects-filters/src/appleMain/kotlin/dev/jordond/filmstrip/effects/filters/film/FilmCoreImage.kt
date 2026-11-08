package dev.jordond.filmstrip.effects.filters.film

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
import platform.CoreGraphics.CGRect
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
 * Empty when this device cannot compile the kernels, which is what [filmKernelLimit] reports.
 */
@OptIn(ExperimentalForeignApi::class)
private val KERNELS: Map<String, CIKernel> by lazy {
  CIKernel
    .kernelsWithMetalString(filmMetal(), error = null)
    ?.filterIsInstance<CIKernel>()
    ?.associateBy { it.name }
    .orEmpty()
}

/**
 * [film] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun filmResolution(
  film: Film,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(film.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(filmStep(film)))
  }

/**
 * The Core Image step that draws [film], on the frame's own codes.
 *
 * A frame's look comes from its composition time and its extent alone, so a seek or a smaller render shows the same
 * one. The response runs at the size [FilmSignal.halationBlurPlan] picks, and its blur is Core Image's own Gaussian,
 * which a test holds to the plan's kernel.
 *
 * @throws IllegalStateException When a kernel will not run, which [filmKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun filmStep(film: Film): CoreImageEffect {
  val params = FilmParams.of(film)
  return CoreImageEffect { image, frame ->
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val pixelsWide = width.toInt()
    val pixelsTall = height.toInt()
    val plan = FilmSignal.halationBlurPlan(pixelsWide)
    val blurWidth = plan.width.toDouble()
    val blurHeight = plan.heightOf(pixelsTall).toDouble()
    val tick = FilmSignal.grainTickAt(frame.compositionTime.toDouble(DurationUnit.SECONDS)) and HASH_MASK

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      val edge = toCodes(image).imageByClampingToExtent()
      val box = CIVector.vectorWithX(x, Y = y, Z = width, W = height)
      val glow =
        if (params.halationGain > 0.0) {
          val small = CGRectMake(0.0, 0.0, blurWidth, blurHeight)
          val response =
            kernel(FILM_RESPONSE_KERNEL).applyWithExtent(
              small,
              roiCallback = { _, _ -> extent },
              arguments =
                listOf(
                  edge,
                  box,
                  CIVector.vectorWithX(blurWidth, Y = blurHeight, Z = plan.factor.toDouble()),
                ),
            ) ?: error(FILM_KERNEL_LIMIT)
          blurredGlow(response, plan.sigmaPx, small)
        } else {
          edge
        }
      kernel(FILM_GRADE_KERNEL).applyWithExtent(
        extent,
        roiCallback = { index, rect -> if (index == 0) rect else glow.extent },
        arguments =
          listOf(
            edge,
            glow,
            box,
            CIVector.vectorWithX(blurWidth, Y = blurHeight, Z = params.halationGain, W = params.grainStrength),
            CIVector.vectorWithX(
              (GRAIN_SEED and HASH_MASK).toDouble(),
              Y = tick.toDouble(),
              Z = FilmSignal.grainCellSpan(pixelsWide, pixelsTall).toDouble(),
            ),
          ),
      ) ?: error(FILM_KERNEL_LIMIT)
    }
  }
}

/**
 * The response blurred at [sigma] of its own pixels and held at its edge past [small], the way the plan's blur clamps.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun blurredGlow(
  response: CIImage,
  sigma: Double,
  small: CValue<CGRect>,
): CIImage =
  response
    .imageByClampingToExtent()
    .imageByApplyingGaussianBlurWithSigma(sigma)
    .imageByClampingToRect(small)

/**
 * Why this device cannot draw a [Film], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernels really compile, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun filmKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { FILM_RESPONSE_KERNEL in KERNELS && FILM_GRADE_KERNEL in KERNELS },
): String? = FILM_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernels.
 */
internal const val FILM_KERNEL_LIMIT: String =
  "Film on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this device " +
    "cannot compile one."

private fun kernel(name: String): CIKernel = KERNELS[name] ?: error(FILM_KERNEL_LIMIT)

@OptIn(ExperimentalForeignApi::class)
private fun CIImage.imageByClampingToRect(rect: CValue<CGRect>): CIImage =
  imageByCroppingToRect(rect).imageByClampingToExtent()

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"
