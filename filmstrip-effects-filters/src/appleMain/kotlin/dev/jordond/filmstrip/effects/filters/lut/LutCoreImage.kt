package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inCodesOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIImage
import platform.CoreImage.CIKernel
import platform.CoreImage.CIVector
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSSelectorFromString
import platform.Foundation.create
import platform.darwin.NSObjectProtocol

/**
 * Compiled once and shared by every frame of every plan.
 *
 * Null when this device cannot compile the kernel, which is what [lutKernelLimit] reports.
 */
private val KERNEL: CIKernel? by lazy { compiledKernel() }

/**
 * [lut] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun lutResolution(
  lut: Lut,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(lut.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(lutStep(lut)))
  }

/**
 * The Core Image step that draws [lut], on the frame's own codes.
 *
 * `CIColorCube` holds its table at 8 bits and stops short of the largest grid, so the step reads the table from a float
 * image in the [LutAtlas] layout through a kernel, with the intensity already in the table.
 *
 * @throws IllegalStateException When the kernel will not run, which [lutKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
internal fun lutStep(lut: Lut): CoreImageEffect {
  val table = lut.table.blended(lut.intensity)
  val atlas = floatAtlas(table)
  val atlasWidth = lutAtlasWidth(table.size) / 2
  val atlasHeight = lutAtlasHeight(table.size)
  val atlasRect = CGRectMake(0.0, 0.0, atlasWidth.toDouble(), atlasHeight.toDouble())
  val layout =
    CIVector.vectorWithX(
      table.size.toDouble(),
      Y = lutAtlasColumns(table.size).toDouble(),
      Z = atlasHeight.toDouble(),
    )
  return CoreImageEffect { image, frame ->
    val kernel = KERNEL ?: error(LUT_KERNEL_LIMIT)
    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      kernel.applyWithExtent(
        image.extent,
        roiCallback = { index, rect -> if (index == ATLAS_ARGUMENT) atlasRect else rect },
        arguments = listOf(toCodes(image), atlas, layout),
      ) ?: error(LUT_KERNEL_LIMIT)
    }
  }
}

/**
 * [table] on the grid a [LutAtlas]'s left half holds, as an unmanaged image of 32 bit floats read at texel centres.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, InternalFilmstripApi::class)
internal fun floatAtlas(table: CubeLut): CIImage {
  val size = table.size
  val columns = lutAtlasColumns(size)
  val width = lutAtlasWidth(size) / 2
  val height = lutAtlasHeight(size)
  val floats = FloatArray(width * height * RGBA)
  forEachEntry(size) { index, red, green, blue ->
    val x = (blue % columns) * size + red
    val y = (blue / columns) * size + green
    val at = (y * width + x) * RGBA
    floats[at] = table.entries[index * CHANNELS]
    floats[at + 1] = table.entries[index * CHANNELS + 1]
    floats[at + 2] = table.entries[index * CHANNELS + 2]
    floats[at + CHANNELS] = 1f
  }
  val data =
    floats.usePinned { pinned ->
      NSData.create(bytes = pinned.addressOf(0), length = (floats.size * Float.SIZE_BYTES).convert())
    }
  return CIImage
    .imageWithBitmapData(
      data,
      bytesPerRow = (width * RGBA * Float.SIZE_BYTES).convert(),
      size = CGSizeMake(width.toDouble(), height.toDouble()),
      format = kCIFormatRGBAf,
      colorSpace = null,
    ).imageBySamplingNearest()
}

/**
 * Why this device cannot draw a [Lut], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernel really compiles, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun lutKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { KERNEL != null },
): String? = LUT_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernel.
 */
internal const val LUT_KERNEL_LIMIT: String =
  "A LUT on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this device " +
    "cannot compile one."

/**
 * The name of the Core Image kernel in [lutMetal].
 */
internal const val LUT_KERNEL: String = "filmstripLut"

/**
 * Metal source for the Core Image kernel [LUT_KERNEL], the same trilinear read [LutShader] takes.
 *
 * The kernel reads the frame's own codes, premultiplied the way Core Image holds them, runs a pixel's straight colour
 * through the table and returns it premultiplied, so a clear bar stays clear. A row of the atlas is counted from the
 * top of the image and Core Image's `y` from the bottom, so a read turns it over.
 */
internal fun lutMetal(): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

  static float3 lutEntry(coreimage::sampler table, float3 layout, float red, float green, float blue) {
    float tile = fmod(blue, layout.y);
    float row = floor(blue / layout.y);
    float2 texel = float2(tile * layout.x + red + 0.5f, layout.z - (row * layout.x + green) - 0.5f);
    return table.sample(table.transform(texel)).rgb;
  }

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $LUT_KERNEL(sampler frame, sampler table, float3 layout) {
      float4 texel = frame.sample(frame.coord());
      float3 straight = texel.a > 0.0f ? texel.rgb / texel.a : float3(0.0f);
      float last = layout.x - 1.0f;
      float3 position = clamp(straight, 0.0f, 1.0f) * last;
      float3 corner = min(floor(position), float3(last - 1.0f));
      float3 part = position - corner;
      float r = corner.r;
      float g = corner.g;
      float b = corner.b;
      float3 near = mix(
        mix(lutEntry(table, layout, r, g, b), lutEntry(table, layout, r + 1.0f, g, b), part.r),
        mix(lutEntry(table, layout, r, g + 1.0f, b), lutEntry(table, layout, r + 1.0f, g + 1.0f, b), part.r),
        part.g
      );
      float3 far = mix(
        mix(lutEntry(table, layout, r, g, b + 1.0f), lutEntry(table, layout, r + 1.0f, g, b + 1.0f), part.r),
        mix(
          lutEntry(table, layout, r, g + 1.0f, b + 1.0f),
          lutEntry(table, layout, r + 1.0f, g + 1.0f, b + 1.0f),
          part.r
        ),
        part.g
      );
      return float4(mix(near, far, part.b) * texel.a, texel.a);
    }
  }}
  """.trimIndent()

@OptIn(ExperimentalForeignApi::class)
private fun compiledKernel(): CIKernel? =
  CIKernel
    .kernelsWithMetalString(lutMetal(), error = null)
    ?.filterIsInstance<CIKernel>()
    ?.firstOrNull { it.name == LUT_KERNEL }

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"
private const val RGBA = 4

// The kernel's second sampler, whose whole image any pixel may read.
private const val ATLAS_ARGUMENT = 1
