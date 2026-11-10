package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inCodesOf
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import dev.jordond.filmstrip.effects.spatial.blurredWithin
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectInset
import platform.CoreGraphics.CGRectMake
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
 * one. The weave reads the frame moved by [FilmSignal.weaveAt] first, and everything after it reads what it moved. The
 * response runs at the size [FilmSignal.halationBlurPlan] picks, and [blurredWithin] blurs it with Core Image's own
 * Gaussian, which a test holds to the plan's kernel.
 *
 * @throws IllegalStateException When a kernel will not run, which [filmKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun filmStep(film: Film): CoreImageEffect {
  val params = FilmParams.of(film)
  val seed = (params.seed and HASH_MASK).toDouble()
  return CoreImageEffect { image, frame ->
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val pixelsWide = width.toInt()
    val pixelsTall = height.toInt()
    val plan = FilmSignal.halationBlurPlan(pixelsWide)
    val blurWidth = plan.width.toDouble()
    val blurHeight = plan.heightOf(pixelsTall).toDouble()
    val tick = (FilmSignal.grainTickAt(frame.compositionTime.toDouble(DurationUnit.SECONDS)) and HASH_MASK).toDouble()

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      val box = CIVector.vectorWithX(x, Y = y, Z = width, W = height)
      val edge =
        if (params.weave > 0.0) {
          kernel(FILM_WEAVE_KERNEL)
            .applyWithExtent(
              extent,
              roiCallback = { _, rect -> filmSourceRegion(rect, pixelsWide, pixelsTall, params.weave) },
              arguments =
                listOf(
                  toCodes(image).imageByClampingToExtent(),
                  box,
                  CIVector.vectorWithX(seed, Y = tick, Z = params.weave),
                ),
            )?.imageByClampingToExtent() ?: error(FILM_KERNEL_LIMIT)
        } else {
          toCodes(image).imageByClampingToExtent()
        }
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
          response.blurredWithin(plan.sigmaPx, small)
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
              seed,
              Y = tick,
              Z = FilmSignal.grainCellSpan(pixelsWide, pixelsTall).toDouble(),
            ),
            CIVector.vectorWithX(params.flicker, Y = params.dust, Z = params.gate),
          ),
      ) ?: error(FILM_KERNEL_LIMIT)
    }
  }
}

/**
 * The part of the source a weave of [weave] reads under a render of [rect], on a frame [width] by [height].
 *
 * No read moves further than the weave's reach at [weave] from its pixel, plus the pixel past it a read between two
 * pixels takes.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun filmSourceRegion(
  rect: CValue<CGRect>,
  width: Int,
  height: Int,
  weave: Double,
): CValue<CGRect> =
  CGRectInset(rect, -(WEAVE_REACH_X * weave * width + SAMPLE_MARGIN), -(WEAVE_REACH_Y * weave * height + SAMPLE_MARGIN))

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
  compiles: () -> Boolean = {
    listOf(
      FILM_WEAVE_KERNEL,
      FILM_RESPONSE_KERNEL,
      FILM_GRADE_KERNEL,
    ).all { it in KERNELS }
  },
): String? = FILM_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernels.
 */
internal const val FILM_KERNEL_LIMIT: String =
  "Film on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this device " +
    "cannot compile one."

private fun kernel(name: String): CIKernel = KERNELS[name] ?: error(FILM_KERNEL_LIMIT)

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"

// A read between two pixels takes the pixel past it on either side.
private const val SAMPLE_MARGIN = 2.0
