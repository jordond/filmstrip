package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.avfoundation.internal.composition.SdSourceColor
import dev.jordond.filmstrip.color.BT709_ALPHA
import dev.jordond.filmstrip.color.BT709_BETA
import dev.jordond.filmstrip.color.BT709_SLOPE
import dev.jordond.filmstrip.color.POWER_SEGMENT_EXPONENT
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIColorKernel
import platform.CoreImage.CIImage
import platform.CoreImage.CIKernel
import platform.CoreImage.CIVector

/**
 * This image's raw signal codes, moved off [color]'s primaries onto BT.709's through the shared BT.709 curve.
 *
 * It answers with this image itself on primaries that are not SD.
 *
 * @throws IllegalStateException When the kernel will not run, which [sdPrimariesKernelLimit] refused when the
 * composition was laid.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.sdPrimariesMoved(color: SdSourceColor): CIImage {
  val matrix = color.primariesToBt709 ?: return this
  val kernel = SD_PRIMARIES_KERNEL ?: error(SD_PRIMARIES_KERNEL_LIMIT)
  val arguments = listOf(this, matrix.rowAt(0), matrix.rowAt(RGB_CHANNELS), matrix.rowAt(2 * RGB_CHANNELS))
  return kernel.applyWithExtent(extent, arguments = arguments) ?: error(SD_PRIMARIES_KERNEL_LIMIT)
}

/**
 * Why this device cannot draw an SD clip's primaries, or null when it can.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernel really compiles, only asked when [kernelsExist].
 */
internal fun sdPrimariesKernelLimit(
  kernelsExist: Boolean = metalKernelsExist(),
  compiles: () -> Boolean = { SD_PRIMARIES_KERNEL != null },
): String? = SD_PRIMARIES_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * Metal source for the Core Image colour kernel [SD_PRIMARIES_KERNEL_NAME], taking a BT.709 signal's linear light
 * through a 3x3 matrix given row by row.
 *
 * Every figure is read off the shared BT.709 curve, and the steps are those of `sdSourceDisplayed`. Signal and light
 * clamp onto zero to one on the way in and the way out.
 */
internal fun sdPrimariesMetal(): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;
  static float3 bt709Light(float3 signal) {
    float3 power = pow(
      (signal + ${BT709_ALPHA.metal()} - 1.0f) / ${BT709_ALPHA.metal()},
      float3(1.0f / ${POWER_SEGMENT_EXPONENT.metal()}));
    return mix(signal / ${BT709_SLOPE.metal()}, power, step(${BT709_SLOPE.metal()} * ${BT709_BETA.metal()}, signal));
  }
  static float3 bt709Signal(float3 light) {
    float3 power = ${BT709_ALPHA.metal()} * pow(light, float3(${POWER_SEGMENT_EXPONENT.metal()}))
      - (${BT709_ALPHA.metal()} - 1.0f);
    return mix(light * ${BT709_SLOPE.metal()}, power, step(${BT709_BETA.metal()}, light));
  }
  extern "C" { namespace coreimage {
    [[stitchable]] float4 $SD_PRIMARIES_KERNEL_NAME(sample_t pixel, float3 red, float3 green, float3 blue) {
      float3 light = bt709Light(clamp(pixel.rgb, 0.0f, 1.0f));
      float3 moved = float3(dot(red, light), dot(green, light), dot(blue, light));
      return float4(bt709Signal(clamp(moved, 0.0f, 1.0f)), pixel.a);
    }
  }}
  """.trimIndent()

/**
 * What a composition is refused with on a device that cannot run the SD primaries kernel.
 */
internal const val SD_PRIMARIES_KERNEL_LIMIT: String = "Drawing an SD clip's colour on Apple runs $METAL_KERNEL_NEED"

private const val SD_PRIMARIES_KERNEL_NAME = "sdPrimariesToBt709"

// Compiled once and shared by every frame of every plan. Null when this device cannot compile it.
@OptIn(ExperimentalForeignApi::class)
private val SD_PRIMARIES_KERNEL: CIColorKernel? by lazy {
  if (!metalKernelsExist()) return@lazy null
  CIKernel
    .kernelsWithMetalString(sdPrimariesMetal(), error = null)
    ?.filterIsInstance<CIColorKernel>()
    ?.firstOrNull { it.name == SD_PRIMARIES_KERNEL_NAME }
}

@OptIn(ExperimentalForeignApi::class)
private fun DoubleArray.rowAt(index: Int): CIVector =
  CIVector.vectorWithX(this[index], this[index + 1], this[index + 2])

private const val RGB_CHANNELS = 3
