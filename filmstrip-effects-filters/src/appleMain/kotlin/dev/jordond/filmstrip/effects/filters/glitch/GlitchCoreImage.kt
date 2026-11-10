package dev.jordond.filmstrip.effects.filters.glitch

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
import platform.CoreGraphics.CGRectInset
import platform.CoreImage.CIKernel
import platform.CoreImage.CIVector
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObjectProtocol
import kotlin.time.DurationUnit

/**
 * Compiled once and shared by every frame of every plan.
 *
 * Null when this device cannot compile the kernel, which is what [glitchKernelLimit] reports.
 */
private val KERNEL: CIKernel? by lazy { compiledKernel() }

/**
 * [glitch] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun glitchResolution(
  glitch: Glitch,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(glitch.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(glitchStep(glitch)))
  }

/**
 * The Core Image step that draws [glitch], on the frame's own codes.
 *
 * A frame's bursts come from its composition time and its extent alone, so a seek or a smaller render shows the same
 * glitch. The kernel reads only as far along its row as [glitchSourceRegion] reaches, and the frame is held at its
 * edge past that.
 *
 * @throws IllegalStateException When the kernel will not run, which [glitchKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun glitchStep(glitch: Glitch): CoreImageEffect {
  val params = GlitchParams.of(glitch)
  val seed = (glitch.seed and HASH_MASK).toDouble()

  return CoreImageEffect { image, frame ->
    val kernel = KERNEL ?: error(GLITCH_KERNEL_LIMIT)
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val exactTick = GlitchSignal.tickAt(frame.compositionTime.toDouble(DurationUnit.SECONDS))
    val strength = GlitchSignal.burstAt(glitch.seed, exactTick, params)?.strength ?: 0.0
    val split = GlitchSignal.channelSplit(glitch.seed, exactTick, params)

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      kernel.applyWithExtent(
        extent,
        roiCallback = { _, rect -> glitchSourceRegion(rect, width, params) },
        arguments =
          listOf(
            toCodes(image).imageByClampingToExtent(),
            CIVector.vectorWithX(x, Y = y, Z = width, W = height),
            CIVector.vectorWithX(seed, Y = (exactTick and HASH_MASK).toDouble()),
            CIVector.vectorWithX(strength, Y = split, Z = params.bandShift, W = params.tearShift),
          ),
      ) ?: error(GLITCH_KERNEL_LIMIT)
    }
  }
}

/**
 * The region of the source a tile [rect] of a frame [width] pixels wide reads, the tile widened along its rows by
 * [GlitchSignal.widestReach] and a pixel either side for a read that lands between two.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun glitchSourceRegion(
  rect: CValue<CGRect>,
  width: Double,
  params: GlitchParams,
): CValue<CGRect> = CGRectInset(rect, -(GlitchSignal.widestReach(params) * width + SAMPLE_MARGIN), 0.0)

/**
 * Why this device cannot draw a [Glitch], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernel really compiles, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun glitchKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { KERNEL != null },
): String? = GLITCH_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernel.
 */
internal const val GLITCH_KERNEL_LIMIT: String =
  "Glitch on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this device " +
    "cannot compile one."

@OptIn(ExperimentalForeignApi::class)
private fun compiledKernel(): CIKernel? =
  CIKernel
    .kernelsWithMetalString(glitchMetal(), error = null)
    ?.filterIsInstance<CIKernel>()
    ?.firstOrNull { it.name == GLITCH_KERNEL }

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"

// One more pixel than a read's reach, since a read lands between two pixels and takes both.
private const val SAMPLE_MARGIN = 2.0
