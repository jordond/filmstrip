package dev.jordond.filmstrip.effects.filters.internal

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.spatial.SpatialBlurGraph
import dev.jordond.filmstrip.geometry.Size

internal const val RGB_FORMAT = "gbrap"

// A sixteen bit frame widened from gbrap holds a code at 256 to the code, so full scale sits at 255 of them.
internal const val WIDE_FORMAT = "gbrap16le"
internal const val WIDE_FULL_SCALE = 65280

/**
 * The branches a look that glows shares on ffmpeg, so every glow is blurred the way [GlowField] works it out.
 *
 * [light] takes a `gbrap` frame's colour weighed by its alpha, boxes it to the blur's size, draws the look's response
 * there, and blurs it and widens it back through [SpatialBlurGraph] onto sixteen bit planes. [screened] lays full scale
 * over a sixteen bit frame through a mask with `maskedmerge`, which is a screen with the mask as the light.
 */
internal object GlowGraph {
  /**
   * The branches that blur [response] of the `gbrap` frame on [reads] by [plan] and write it to [writes] as
   * `gbrap16le` at 256 a code.
   *
   * The frame's colour is weighed by its alpha first, through a `lut2` reading alpha on every plane, so a clear pixel
   * gives no light.
   *
   * @param response The `geq` that draws the look's response from a frame already boxed to the blur's size.
   */
  fun light(
    plan: BlurPlan,
    frame: Size,
    response: FilterNode,
    reads: String,
    writes: String,
  ): List<FilterBranch> {
    // The frame reaching a look has square pixels, so one plan blurs both ways.
    val light =
      SpatialBlurGraph.boxedDown(plan, plan, frame, RGB_FORMAT) +
        response +
        SpatialBlurGraph.blurredUp(plan, plan, frame, WIDE_FORMAT, COLOUR_PLANES)
    return listOf(
      FilterBranch(listOf(reads), emptyList(), listOf("weighedlight", "weighedcover")),
      FilterBranch(listOf("weighedcover"), listOf(ALPHA_ON_EVERY_PLANE), listOf("weighedalpha")),
      FilterBranch(listOf("weighedlight", "weighedalpha"), listOf(PREMULTIPLIED), listOf("weighed")),
      FilterBranch(listOf("weighed"), light, listOf(writes)),
    )
  }

  /**
   * The branches that screen the light on [mask] over the `gbrap16le` frame on [reads] and write it to [writes], with
   * [after] run on the result.
   *
   * The frame holds a code at 256 and `maskedmerge` reads its mask over 65536, so [toMask] has to land a light of 1 on
   * the top of the range, which [MASK_GAIN] does for a mask at 256 a code. Alpha comes through from the frame.
   *
   * @param toMask The nodes that turn the pad on [mask] into a `gbrap16le` mask the size of the frame.
   */
  fun screened(
    reads: String,
    mask: String,
    toMask: List<FilterNode>,
    after: List<FilterNode>,
    writes: String,
  ): List<FilterBranch> {
    val full = FilterNode("lutrgb", "r" to "$WIDE_FULL_SCALE", "g" to "$WIDE_FULL_SCALE", "b" to "$WIDE_FULL_SCALE")
    val merge = FilterNode("maskedmerge", "planes" to "$COLOUR_PLANES")
    return listOf(
      FilterBranch(listOf(reads), emptyList(), listOf("screenUnder", "screenFullIn")),
      FilterBranch(listOf("screenFullIn"), listOf(full), listOf("screenFull")),
      FilterBranch(listOf(mask), toMask, listOf("screenMask")),
      FilterBranch(listOf("screenUnder", "screenFull", "screenMask"), listOf(merge) + after, listOf(writes)),
    )
  }

  /**
   * The `colorchannelmixer` that scales a mask at 256 a code by [gain] and onto the range `maskedmerge` reads, held at
   * the top of it.
   */
  fun maskOf(gain: Double = 1.0): FilterNode {
    val scale = "${gain * MASK_GAIN}"
    return FilterNode("colorchannelmixer", "rr" to scale, "gg" to scale, "bb" to scale)
  }

  // A widened code is 256 wide and maskedmerge reads its mask over 65536, so a code of 255 has to land on the top.
  private const val MASK_GAIN = 65536.0 / WIDE_FULL_SCALE
  private const val COLOUR_PLANES = 7
  private const val RGBA_PLANES = 4

  // Each colour code weighed by its alpha code over full scale and rounded, so an opaque pixel keeps its code. ffmpeg's
  // own premultiply divides by 256 and takes a code off every bright pixel, which a response with a narrow knee reads
  // as less light.
  private const val WEIGHED_CODE = "floor(x*y/255+0.5)"
  private val PREMULTIPLIED =
    FilterNode(
      "lut2",
      "c0" to WEIGHED_CODE,
      "c1" to WEIGHED_CODE,
      "c2" to WEIGHED_CODE,
      "c3" to "x",
    )
  private val ALPHA_ON_EVERY_PLANE =
    FilterNode(
      "mergeplanes",
      *(0 until RGBA_PLANES).flatMap { listOf("map${it}s" to "0", "map${it}p" to "${RGBA_PLANES - 1}") }.toTypedArray(),
      "format" to RGB_FORMAT,
    )
}
