package dev.jordond.filmstrip.effects.filters.pixel

import dev.jordond.filmstrip.InternalFilmstripApi
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
 * Null when this device cannot compile the kernel, which is what [pixelKernelLimit] reports.
 */
private val KERNEL: CIKernel? by lazy { compiledKernel() }

/**
 * [pixel] as a Core Image step, or refused by name with [limit] when this device has one.
 */
internal fun pixelResolution(
  pixel: Pixel,
  limit: String?,
): EffectResolution =
  if (limit != null) {
    EffectResolution.Unsupported(pixel.id, limit)
  } else {
    EffectResolution.Resolved(PlatformEffect(pixelStep(pixel)))
  }

/**
 * The Core Image step that draws [pixel], on the frame's own codes.
 *
 * The grid comes from the frame's extent alone, so a seek or a smaller render draws the same cells. A palette travels
 * as [PixelPalette.MAX_COLORS] kernel arguments, the unused ones holding the first entry again, which a strict less
 * than never picks over the entry already held.
 *
 * @throws IllegalStateException When the kernel will not run, which [pixelKernelLimit] refused at plan time.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
internal fun pixelStep(pixel: Pixel): CoreImageEffect {
  val palette = pixel.palette?.let(PixelSignal::codesOf)
  val entries =
    List(PixelPalette.MAX_COLORS) { index ->
      val codes = palette?.getOrNull(index) ?: palette?.first() ?: DoubleArray(CHANNELS)
      CIVector.vectorWithX(codes[0], Y = codes[1], Z = codes[2])
    }
  val spread = palette?.let { PixelSignal.paletteSpread(it.size) } ?: 0.0
  return CoreImageEffect { image, frame ->
    val kernel = KERNEL ?: error(PIXEL_KERNEL_LIMIT)
    val extent = image.extent
    val (x, y, width, height) = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val pixelWidth = width.toInt()
    val pixelHeight = height.toInt()
    val columns = PixelSignal.columns(pixelWidth, pixelHeight, pixel.cells)

    inCodesOf(frame.attributes.colorSpace) { toCodes ->
      kernel.applyWithExtent(
        extent,
        roiCallback = { _, rect -> pixelSourceRegion(rect, pixelWidth, pixelHeight, columns, pixel.cells) },
        arguments =
          listOf(
            toCodes(image).imageByClampingToExtent(),
            CIVector.vectorWithX(x, Y = y, Z = width, W = height),
            CIVector.vectorWithX(columns.toDouble(), Y = pixel.cells.toDouble()),
            CIVector.vectorWithX(
              pixel.levels.toDouble(),
              Y = pixel.dither.toDouble(),
              Z = spread,
              W = if (palette == null) 0.0 else 1.0,
            ),
          ) + entries,
      ) ?: error(PIXEL_KERNEL_LIMIT)
    }
  }
}

/**
 * The part of the source a render of [rect] reads, on a frame [width] by [height] cut into [columns] by [rows] cells.
 *
 * A pixel reads the centre of its own cell, so no read moves further than one cell, plus what a read between two
 * pixels takes.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun pixelSourceRegion(
  rect: CValue<CGRect>,
  width: Int,
  height: Int,
  columns: Int,
  rows: Int,
): CValue<CGRect> {
  val across = (width + columns - 1) / columns
  val down = (height + rows - 1) / rows
  return CGRectInset(rect, -(across + SAMPLE_MARGIN), -(down + SAMPLE_MARGIN))
}

/**
 * Why this device cannot draw a [Pixel], or null when it can.
 *
 * A compile is what settles it, since a device's GPU family and its dynamic library support do not agree with what the
 * simulator really renders.
 *
 * @param kernelsExist Whether this OS has the call that compiles a Metal kernel from source.
 * @param compiles Whether the kernel really compiles, only asked when [kernelsExist].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun pixelKernelLimit(
  kernelsExist: Boolean =
    interpretObjCPointer<NSObjectProtocol>(
      CIKernel.objcPtr(),
    ).respondsToSelector(NSSelectorFromString(KERNELS_FROM_SOURCE)),
  compiles: () -> Boolean = { KERNEL != null },
): String? = PIXEL_KERNEL_LIMIT.takeUnless { kernelsExist && compiles() }

/**
 * What a plan is refused with on a device that cannot run the kernel.
 */
internal const val PIXEL_KERNEL_LIMIT: String =
  "Pixel on Apple runs a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, and this device " +
    "cannot compile one."

/**
 * The name of the Core Image kernel in [pixelMetal].
 */
internal const val PIXEL_KERNEL: String = "filmstripPixel"

/**
 * Metal source for the Core Image kernel [PIXEL_KERNEL], the same steps [PixelShader] takes.
 *
 * The dither table is written in from [PixelSignal.bayer], and everything a control or a frame changes is a kernel
 * argument. Core Image counts rows up from the bottom, so the kernel turns them to count from the top the way the
 * signal does. It reads the frame's own codes premultiplied, cuts the straight colour, and returns it premultiplied by
 * the alpha the cell read, so a clear bar stays clear.
 */
@OptIn(InternalFilmstripApi::class)
internal fun pixelMetal(): String {
  val thresholds =
    (0 until BAYER_SIZE * BAYER_SIZE).joinToString(", ") { metal(PixelSignal.bayer(it % BAYER_SIZE, it / BAYER_SIZE)) }
  val entries = (0 until PixelPalette.MAX_COLORS).joinToString(",\n") { "      float3 p$it" }
  val search =
    (1 until PixelPalette.MAX_COLORS).joinToString("\n") {
      "        gap = moved - p$it; if (dot(gap, gap) < best) { best = dot(gap, gap); colour = p$it; }"
    }
  return """
  |#include <metal_stdlib>
  |#include <CoreImage/CoreImage.h>
  |using namespace metal;
  |
  |extern "C" { namespace coreimage {
  |    [[stitchable]] float4 $PIXEL_KERNEL(
  |      sampler frame,
  |      float4 extent,
  |      float2 grid,
  |      float4 look,
  |$entries,
  |      destination dest
  |    ) {
  |      const float thresholds[16] = { $thresholds };
  |      int2 size = int2(extent.zw);
  |      int2 cells = int2(grid);
  |      int2 texel = clamp(int2(floor(dest.coord() - extent.xy)), int2(0), size - 1);
  |      int2 pixel = int2(texel.x, size.y - 1 - texel.y);
  |      int2 cell = ((2 * pixel + 1) * cells) / (2 * size);
  |      int2 centre = ((2 * cell + 1) * size) / (2 * cells);
  |      float2 at = extent.xy + float2(float(centre.x), float(size.y - 1 - centre.y)) + 0.5f;
  |      float4 read = frame.sample(frame.transform(at));
  |      float threshold = thresholds[(cell.y % $BAYER_SIZE) * $BAYER_SIZE + cell.x % $BAYER_SIZE];
  |      float3 straight = read.a > 0.0f ? read.rgb / read.a : float3(0.0f);
  |      float3 colour = straight;
  |      if (look.w > 0.5f) {
  |        float3 moved = straight + look.y * threshold * look.z;
  |        float3 gap = moved - p0;
  |        float best = dot(gap, gap);
  |        colour = p0;
  |$search
  |      } else if (look.x > 0.5f) {
  |        float steps = look.x - 1.0f;
  |        colour = floor(straight * steps + look.y * threshold + 0.5f) / steps;
  |      }
  |      return float4(colour * read.a, read.a);
  |    }
  |}}
    """.trimMargin()
}

@OptIn(ExperimentalForeignApi::class)
private fun compiledKernel(): CIKernel? =
  CIKernel
    .kernelsWithMetalString(pixelMetal(), error = null)
    ?.filterIsInstance<CIKernel>()
    ?.firstOrNull { it.name == PIXEL_KERNEL }

private fun metal(value: Double): String = "${value}f"

private const val KERNELS_FROM_SOURCE = "kernelsWithMetalString:error:"
private const val CHANNELS = 3

// One more pixel than a cell, since a read lands between two pixels and takes both.
private const val SAMPLE_MARGIN = 2.0
