package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.color.VIGNETTE_GAIN_MSL
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inCodesOf
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.GRAIN_CELLS_ACROSS
import dev.jordond.filmstrip.effects.filters.film.GRAIN_CELL_SCALE
import dev.jordond.filmstrip.effects.filters.film.PASS_GRAIN_FIRST
import dev.jordond.filmstrip.effects.filters.film.PASS_GRAIN_SECOND
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import dev.jordond.filmstrip.effects.filters.internal.HASH_MSL
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import platform.CoreImage.CIKernel
import platform.CoreImage.CIVector
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObjectProtocol
import kotlin.time.DurationUnit

/**
 * Compiled once and shared by every frame of every plan.
 *
 * Null when this device cannot compile the kernel, which is what [nightVisionKernelLimit] reports.
 */
private val KERNEL: CIKernel? by lazy { compiledKernel() }

/**
 * [nightVision] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun nightVisionResolution(
  nightVision: NightVision,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(nightVision.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(nightVisionStep(nightVision)))
  }

/**
 * The Core Image step that draws [nightVision], on the frame's own codes.
 *
 * A frame's look comes from its composition time and its extent alone, so a seek or a smaller render shows the same
 * one. Every pixel reads only itself.
 *
 * @throws IllegalStateException When the kernel will not run, which [nightVisionKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun nightVisionStep(nightVision: NightVision): CoreImageEffect {
  val params = NightVisionParams.of(nightVision)
  return CoreImageEffect { image, frame ->
    val kernel = KERNEL ?: error(NIGHT_VISION_KERNEL_LIMIT)
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val tick = FilmSignal.grainTickAt(frame.compositionTime.toDouble(DurationUnit.SECONDS)) and HASH_MASK
    val span = FilmSignal.grainCellSpan(width.toInt(), height.toInt())

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      kernel.applyWithExtent(
        extent,
        roiCallback = { _, rect -> rect },
        arguments =
          listOf(
            toCodes(image).imageByClampingToExtent(),
            CIVector.vectorWithX(x, Y = y, Z = width, W = height),
            CIVector.vectorWithX(
              (params.seed and HASH_MASK).toDouble(),
              Y = tick.toDouble(),
              Z = span.toDouble(),
              W = params.intensity,
            ),
          ),
      ) ?: error(NIGHT_VISION_KERNEL_LIMIT)
    }
  }
}

/**
 * Why this device cannot draw a [NightVision], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernel really compiles, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun nightVisionKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { KERNEL != null },
): String? = NIGHT_VISION_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernel.
 */
internal const val NIGHT_VISION_KERNEL_LIMIT: String =
  "NightVision on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this " +
    "device cannot compile one."

/**
 * The name of the Core Image kernel in [nightVisionMetal].
 */
internal const val NIGHT_VISION_KERNEL: String = "filmstripNightVision"

/**
 * Metal source for the Core Image kernel [NIGHT_VISION_KERNEL], the same steps [NightVisionShader] takes.
 *
 * Every fixed number is written in from [NightVisionSignal]'s constants and film's grain, and everything a control or
 * a frame changes is a kernel argument. Core Image counts rows up from the bottom, so the kernel turns them to count
 * from the top the way the signal does. It reads the frame's own codes premultiplied, draws the look on the straight
 * colour, and returns it premultiplied by the alpha it read, so a clear bar stays clear.
 */
internal fun nightVisionMetal(): String {
  val params = NightVisionParams.at(1.0)
  val knee = metal(NIGHT_VISION_KNEE)
  val room = metal(1.0 - NIGHT_VISION_KNEE)
  val vignette =
    "${metal(NIGHT_VISION_VIGNETTE_STRENGTH)}, ${metal(NIGHT_VISION_VIGNETTE_RADIUS)}, " +
      metal(NIGHT_VISION_VIGNETTE_SOFTNESS)
  return """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

  constant float3 NIGHT_LUMA = float3(${metal(KR)}, ${metal(1.0 - KR - KB)}, ${metal(KB)});
  constant float3 RAMP_MID_COLOUR = ${metal3(RAMP_MID_COLOUR)};
  constant float3 RAMP_TOP_COLOUR = ${metal3(RAMP_TOP_COLOUR)};

$HASH_MSL

  float nightLift(float luma) {
    float lifted = ${metal(params.gain)} * clamp(luma, 0.0f, 1.0f);
    if (lifted <= $knee) return lifted;
    return $knee + $room * (1.0f - exp(-(lifted - $knee) / $room));
  }

  float3 nightRamp(float level) {
    float at = clamp(level, 0.0f, 1.0f);
    if (at <= ${metal(RAMP_MID)}) return RAMP_MID_COLOUR * (at / ${metal(RAMP_MID)});
    return mix(RAMP_MID_COLOUR, RAMP_TOP_COLOUR, (at - ${metal(RAMP_MID)}) / ${metal(1.0 - RAMP_MID)});
  }

${VIGNETTE_GAIN_MSL.prependIndent("  ")}

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $NIGHT_VISION_KERNEL(
      sampler frame,
      float4 extent,
      float4 look,
      destination dest
    ) {
      float2 p = dest.coord();
      float4 held = frame.sample(frame.transform(p));
      float3 straight = held.a > 0.0f ? held.rgb / held.a : float3(0.0f);
      uint seed = uint(look.x + 0.5f);
      uint tick = uint(look.y + 0.5f);
      uint span = uint(look.z + 0.5f);
      float column = clamp(floor(p.x - extent.x), 0.0f, extent.z - 1.0f);
      float row = clamp(extent.w - 1.0f - floor(p.y - extent.y), 0.0f, extent.w - 1.0f);
      uint across = uint(column) * ${GRAIN_CELL_SCALE}u / span;
      uint cell = (uint(row) * ${GRAIN_CELL_SCALE}u / span) * ${GRAIN_CELLS_ACROSS}u + across;
      float first = filterDraw(seed, ${PASS_GRAIN_FIRST}u, tick, cell);
      float grain = first + filterDraw(seed, ${PASS_GRAIN_SECOND}u, tick, cell) - 1.0f;
      float3 glow = nightRamp(nightLift(dot(straight, NIGHT_LUMA) + ${metal(params.noise)} * grain));
      float gain = vignetteGain((float2(column, row) + 0.5f) / extent.zw, extent.z / extent.w, $vignette);
      float3 mixed = mix(straight, glow * gain, look.w);
      return float4(mixed * held.a, held.a);
    }
  }}
    """.trimIndent()
}

@OptIn(ExperimentalForeignApi::class)
private fun compiledKernel(): CIKernel? =
  CIKernel
    .kernelsWithMetalString(nightVisionMetal(), error = null)
    ?.filterIsInstance<CIKernel>()
    ?.firstOrNull { it.name == NIGHT_VISION_KERNEL }

private fun metal(value: Double): String = "${value}f"

private fun metal3(colour: DoubleArray): String =
  "float3(${metal(colour[0])}, ${metal(colour[1])}, ${metal(colour[2])})"

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"
