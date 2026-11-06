package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.media.HdrTransfer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import platform.CoreImage.CIColorKernel
import platform.CoreImage.CIImage
import platform.CoreImage.CIKernel
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObjectProtocol

/**
 * Compiled once per transfer and shared by every frame of every plan.
 *
 * Null when this device cannot compile the kernel, which is what [toneMapKernelLimit] reports.
 */
private val PQ_KERNEL: CIColorKernel? by lazy { compiledToneMapKernel(HdrTransfer.Pq) }

private val HLG_KERNEL: CIColorKernel? by lazy { compiledToneMapKernel(HdrTransfer.Hlg) }

/**
 * This image's raw BT.2020 signal codes, tone mapped to the encoded BT.709 SDR signal.
 *
 * @throws IllegalStateException When the kernel will not run, which [toneMapKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.toneMapped(transfer: HdrTransfer): CIImage {
  val kernel =
    when (transfer) {
      HdrTransfer.Pq -> PQ_KERNEL
      HdrTransfer.Hlg -> HLG_KERNEL
    } ?: error(TONE_MAP_KERNEL_LIMIT)
  return kernel.applyWithExtent(extent, arguments = listOf(this)) ?: error(TONE_MAP_KERNEL_LIMIT)
}

/**
 * Why this device cannot tone map, or null when it can.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernels really compile, only asked when [kernelsExist].
 */
internal fun toneMapKernelLimit(
  kernelsExist: Boolean = metalKernelsExist(),
  compiles: () -> Boolean = { PQ_KERNEL != null && HLG_KERNEL != null },
): String? = TONE_MAP_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * Whether this OS has the call that compiles a Metal kernel from source.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun metalKernelsExist(): Boolean =
  interpretObjCPointer<NSObjectProtocol>(
    CIKernel.objcPtr(),
  ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE))

/**
 * What any Core Image Metal kernel takes, the end of a sentence saying what runs one.
 */
internal const val METAL_KERNEL_NEED: String =
  "a Core Image Metal kernel, which takes iOS 15 or macOS 12 on an Apple6 GPU, and this device cannot compile one."

/**
 * What a plan is refused with on a device that cannot run the tone map kernel.
 */
internal const val TONE_MAP_KERNEL_LIMIT: String =
  "Tone mapping HDR to SDR on Apple runs $METAL_KERNEL_NEED Use HdrMode.KeepHdr to keep the grade instead."

@OptIn(ExperimentalForeignApi::class)
private fun compiledToneMapKernel(transfer: HdrTransfer): CIColorKernel? =
  CIKernel
    .kernelsWithMetalString(toneMapMetal(transfer), error = null)
    ?.filterIsInstance<CIColorKernel>()
    ?.firstOrNull { it.name == TONE_MAP_KERNEL }

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"
