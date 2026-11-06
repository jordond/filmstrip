package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inCodesOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectInset
import platform.CoreImage.CIImage
import platform.CoreImage.CIKernel
import platform.CoreImage.CIVector
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObjectProtocol
import kotlin.time.DurationUnit

/**
 * Compiled once and shared by every frame of every plan.
 *
 * Null when this device cannot compile the kernel, which is what [vhsKernelLimit] reports.
 */
private val KERNEL: CIKernel? by lazy { compiledKernel() }

/**
 * [vhs] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun vhsResolution(
  vhs: Vhs,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(vhs.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(vhsStep(vhs)))
  }

/**
 * The Core Image step that draws [vhs], on the frame's own codes.
 *
 * A frame's pattern comes from its composition time and its extent alone, so a seek or a smaller render shows the same
 * tape. The kernel reads only as far along its row as [VhsParams.widestReach] goes, and the frame is held at its last
 * column past that edge, where [vhsMetal] paints black.
 *
 * @throws IllegalStateException When the kernel will not run, which [vhsKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun vhsStep(vhs: Vhs): CoreImageEffect {
  val params = VhsParams.of(vhs)
  val blur = CIVector.vectorWithX(params.lumaSigma, Y = params.chromaSigma, Z = params.wideSigma, W = params.haloGain)
  val shifts = CIVector.vectorWithX(params.jitter, Y = params.wave, Z = params.headShift, W = params.chromaShift)
  val noise =
    CIVector.vectorWithX(params.lumaNoise, Y = params.chromaNoise, Z = params.dropoutChance, W = params.desaturation)
  val track =
    CIVector.vectorWithX(params.trackingChance, Y = params.trackingShift, Z = params.trackingNoise, W = params.lift)
  val seed = (vhs.seed and HASH_MASK).toDouble()

  return CoreImageEffect { image, frame ->
    val kernel = KERNEL ?: error(VHS_KERNEL_LIMIT)
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val reach = params.widestReach * width + SAMPLE_MARGIN
    val tick = VhsSignal.tickAt(frame.compositionTime.toDouble(DurationUnit.SECONDS)) and HASH_MASK

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      kernel.applyWithExtent(
        extent,
        roiCallback = { _, rect -> CGRectInset(rect, -reach, 0.0) },
        arguments =
          listOf(
            toCodes(image).imageByClampingToExtent(),
            CIVector.vectorWithX(x, Y = y, Z = width, W = height),
            CIVector.vectorWithX(seed, Y = tick.toDouble()),
            blur,
            shifts,
            noise,
            track,
          ),
      ) ?: error(VHS_KERNEL_LIMIT)
    }
  }
}

/**
 * Why this device cannot draw a [Vhs], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernel really compiles, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun vhsKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { KERNEL != null },
): String? = VHS_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernel.
 */
internal const val VHS_KERNEL_LIMIT: String =
  "VHS on Apple runs a Core Image Metal kernel, which takes iOS 15 or macOS 12 on an Apple6 GPU, and this device " +
    "cannot compile one."

@OptIn(ExperimentalForeignApi::class)
private fun compiledKernel(): CIKernel? =
  CIKernel
    .kernelsWithMetalString(vhsMetal(), error = null)
    ?.filterIsInstance<CIKernel>()
    ?.firstOrNull { it.name == VHS_KERNEL }

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"

// One more pixel than a tap's reach, since a tap lands between two pixels and reads both.
private const val SAMPLE_MARGIN = 2.0
