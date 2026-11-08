package dev.jordond.filmstrip.effects.filters.crt

import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inCodesOf
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

/**
 * Compiled once and shared by every frame of every plan.
 *
 * Null when this device cannot compile the kernel, which is what [crtKernelLimit] reports.
 */
private val KERNEL: CIKernel? by lazy { compiledKernel() }

/**
 * [crt] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun crtResolution(
  crt: Crt,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(crt.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(crtStep(crt)))
  }

/**
 * The Core Image step that draws [crt], on the frame's own codes.
 *
 * A frame's pattern comes from its extent alone, so a seek or a smaller render shows the same picture. The kernel reads
 * as far out as [CrtSignal.curveReach] goes, and the frame is held at its edge past that, where the picture fades by
 * [CrtSignal.coverageAt].
 *
 * @throws IllegalStateException When the kernel will not run, which [crtKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun crtStep(crt: Crt): CoreImageEffect {
  val params = CrtParams.of(crt)
  return CoreImageEffect { image, frame ->
    val kernel = KERNEL ?: error(CRT_KERNEL_LIMIT)
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val pixelWidth = width.toInt()
    val pixelHeight = height.toInt()
    val fit = CrtSignal.tubeFit(pixelWidth, pixelHeight)

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      kernel.applyWithExtent(
        extent,
        roiCallback = { _, rect -> crtSourceRegion(rect, pixelWidth, pixelHeight, params.barrelK) },
        arguments =
          listOf(
            toCodes(image).imageByClampingToExtent(),
            CIVector.vectorWithX(x, Y = y, Z = width, W = height),
            CIVector.vectorWithX(fit[0], Y = fit[1], Z = params.barrelK, W = params.scanlineDepth),
            CIVector.vectorWithX(params.maskDepth, Y = params.vignette, Z = params.gain),
            CIVector.vectorWithX(
              CrtSignal.footprintAverage(CRT_LINES, 1.0 / pixelHeight),
              Y = CrtSignal.footprintAverage(CRT_TRIADS, 1.0 / pixelWidth),
            ),
          ),
      ) ?: error(CRT_KERNEL_LIMIT)
    }
  }
}

/**
 * The part of the source a render of [rect] reads under a curve of [k], on a frame [width] by [height].
 *
 * No read moves further than [CrtSignal.curveReach] from its pixel, plus what a read between two pixels takes.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun crtSourceRegion(
  rect: CValue<CGRect>,
  width: Int,
  height: Int,
  k: Double,
): CValue<CGRect> {
  val reach = CrtSignal.curveReach(width, height, k)
  return CGRectInset(rect, -(reach * width + SAMPLE_MARGIN), -(reach * height + SAMPLE_MARGIN))
}

/**
 * Why this device cannot draw a [Crt], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernel really compiles, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun crtKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { KERNEL != null },
): String? = CRT_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernel.
 */
internal const val CRT_KERNEL_LIMIT: String =
  "CRT on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this device " +
    "cannot compile one."

/**
 * The name of the Core Image kernel in [crtMetal].
 */
internal const val CRT_KERNEL: String = "filmstripCrt"

/**
 * Metal source for the Core Image kernel [CRT_KERNEL], the same steps [CrtShader] takes.
 *
 * Every fixed number is written in from [CrtSignal]'s constants, and everything an intensity or a frame changes is a
 * kernel argument. The kernel reads the frame's own codes, premultiplied the way Core Image holds them, and returns
 * them premultiplied, so a clear bar stays clear.
 */
internal fun crtMetal(): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

  float crtStripe(float phase, float average, float depth) {
    return 1.0f - depth * (0.5f - 0.5f * cos(phase) * average);
  }

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $CRT_KERNEL(
      sampler frame,
      float4 extent,
      float4 tube,
      float3 look,
      float2 averages,
      destination dest
    ) {
      float2 fit = tube.xy;
      float k = tube.z;
      float2 centred = (dest.coord() - extent.xy) / extent.zw - 0.5f;
      float2 fitted = 2.0f * centred * fit;
      float radius = dot(fitted, fitted);
      float2 source = 0.5f + centred * (1.0f + k * radius);
      float4 texel = frame.sample(frame.transform(extent.xy + source * extent.zw));

      float2 fitSquared = fit * fit;
      float2 footprint = 1.0f / extent.zw;
      float turn = abs(${metal(CURVE_SLOPE)} * k * centred.x * centred.y);
      float2 stretch = 1.0f + k * radius + ${metal(CURVE_SLOPE)} * k * centred * centred * fitSquared;
      float2 reach = stretch * footprint + turn * fitSquared.yx * footprint.yx;
      float2 share = clamp(min(source, 1.0f - source) / reach + 0.5f, 0.0f, 1.0f);

      float scanline = crtStripe(${metal(TWO_PI * CRT_LINES)} * source.y, averages.x, tube.w);
      float across = ${metal(TWO_PI * CRT_TRIADS)} * source.x;
      float3 mask = float3(
        crtStripe(across - ${metal(CrtSignal.maskPhase(0))}, averages.y, look.x),
        crtStripe(across - ${metal(CrtSignal.maskPhase(1))}, averages.y, look.x),
        crtStripe(across - ${metal(CrtSignal.maskPhase(2))}, averages.y, look.x)
      );
      float vignette = 1.0f - look.y * radius / dot(fit, fit);
      float3 drawn = min(texel.rgb * scanline * mask * vignette * look.z, float3(texel.a));
      return float4(drawn * share.x * share.y, texel.a);
    }
  }}
  """.trimIndent()

@OptIn(ExperimentalForeignApi::class)
private fun compiledKernel(): CIKernel? =
  CIKernel
    .kernelsWithMetalString(crtMetal(), error = null)
    ?.filterIsInstance<CIKernel>()
    ?.firstOrNull { it.name == CRT_KERNEL }

private fun metal(value: Double): String = "${value}f"

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"

// One more pixel than a read's reach, since a read lands between two pixels and takes both.
private const val SAMPLE_MARGIN = 2.0
