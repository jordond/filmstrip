package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.PlatformContext
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.ExecutionContext
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HLG_A
import dev.jordond.filmstrip.media.HLG_B
import dev.jordond.filmstrip.media.HLG_C
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_C1
import dev.jordond.filmstrip.media.PQ_C2
import dev.jordond.filmstrip.media.PQ_C3
import dev.jordond.filmstrip.media.PQ_M1
import dev.jordond.filmstrip.media.PQ_M2
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.brightnessSceneGain
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Lowers the built-in catalogue onto a filter graph.
 *
 * Every lowering is a pure function of the spec and the resolved [Attributes], so it is assertable
 * in a unit test with no toolchain installed, which neither of the other two resolvers can manage.
 */
public actual class BuiltInEffectResolver actual constructor(
  @Suppress("unused") private val context: PlatformContext,
) : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    context: ExecutionContext,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.FilterGraph) return null

    return when (spec) {
      is Rotate -> fragment(rotate(spec.degrees))
      is Flip -> fragment(listOf(FilterNode(if (spec.axis == FlipAxis.Horizontal) "hflip" else "vflip")))
      is Crop -> fragment(crop(spec.retainedRect(attributes.inputSize), attributes.inputSize))
      is CropRect -> fragment(crop(spec.rect, attributes.inputSize))
      // The size stage is the tail the backend pins to the resolved output frame, so the effect
      // that decides that frame contributes no node of its own. Claimed rather than declined,
      // because an unclaimed spec is refused by name at plan time.
      is Scale -> fragment(emptyList())
      is Brightness -> fragment(brightness(spec.scale, attributes.hdrTransfer))
      is Watermark -> watermark(spec, attributes.outputSize)
      is Text -> EffectResolution.Unsupported(spec.id, textMessage(capabilities))
      else -> null
    }
  }

  private fun fragment(chain: List<FilterNode>): EffectResolution =
    EffectResolution.Resolved(PlatformEffect(FilterFragment(chain = chain)))

  // transpose=dir=cclock turns a frame red-on-the-left into red-on-the-bottom, which is what
  // Rotate documents. Both flips are memory operations, so they are the cheaper half turn.
  private fun rotate(degrees: Int): List<FilterNode> =
    when (((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN) {
      QUARTER_TURN -> listOf(FilterNode("transpose", "dir" to "cclock"))
      HALF_TURN -> listOf(FilterNode("hflip"), FilterNode("vflip"))
      THREE_QUARTER_TURN -> listOf(FilterNode("transpose", "dir" to "clock"))
      else -> emptyList()
    }

  // lutrgb rather than colorchannelmixer, whose gains are capped at 2 and fail the graph rather
  // than clamping above it, and rather than eq, whose brightness is an offset and which is GPL.
  // The expression is evaluated once into a table, not per pixel. minval and maxval are the
  // filter's own, so the clip follows the format's depth rather than assuming eight bits.
  //
  // An HDR grade takes the same table through the transfer function instead: decode the code value
  // to light, scale it, encode it again. A gbrp10le conversion is forced ahead of it because a
  // per-channel nonlinear function has no YUV form, and ten bits is the only depth this backend
  // writes HDR at. Neither zscale nor libplacebo is involved, so it runs on a stock build.
  private fun brightness(
    scale: Float,
    transfer: HdrTransfer?,
  ): List<FilterNode> {
    if (scale == 1f) return emptyList()
    val expression =
      when (transfer) {
        null -> "clip(val*$scale,minval,maxval)"
        HdrTransfer.Pq -> pqBrightness(brightnessDisplayGain(scale))
        HdrTransfer.Hlg -> hlgBrightness(brightnessSceneGain(scale))
      }
    val lut = FilterNode("lutrgb", "r" to expression, "g" to expression, "b" to expression)

    return if (transfer == null) listOf(lut) else listOf(FilterNode("format", "pix_fmts" to HDR_PLANAR_RGB), lut)
  }

  // ST 2084's signal is absolute, so scaling the light it decodes to is the whole of the effect.
  private fun pqBrightness(gain: Float): String {
    val encoded = "pow(${normalized()},${1.0 / PQ_M2})"
    val light = "pow(max($encoded-$PQ_C1,0)/($PQ_C2-$PQ_C3*$encoded),${1.0 / PQ_M1})"
    val scaled = "pow(clip($light*$gain,0,1),$PQ_M1)"

    return codeValue("pow(($PQ_C1+$PQ_C2*$scaled)/(1+$PQ_C3*$scaled),$PQ_M2)")
  }

  // Only the inverse OETF runs here, so what sits between the two halves is scene light rather
  // than display light, and the gain has to be the one that belongs to it.
  private fun hlgBrightness(gain: Float): String {
    val signal = normalized()
    val scene = "if(lte($signal,0.5),$signal*$signal/3,(exp(($signal-$HLG_C)/$HLG_A)+$HLG_B)/12)"
    val scaled = "clip(($scene)*$gain,0,1)"

    return codeValue("if(lte($scaled,1/12),sqrt(3*$scaled),$HLG_A*log(12*$scaled-$HLG_B)+$HLG_C)")
  }

  private fun normalized(): String = "clip((val-minval)/(maxval-minval),0,1)"

  private fun codeValue(signal: String): String = "clip(minval+(maxval-minval)*($signal),minval,maxval)"

  // Pixels are multiplied out here rather than emitted as an iw/ih expression. An expression that
  // rounds differently from the planner's arithmetic makes plan() and the export disagree about
  // the output frame, which is the one thing plan() exists to prevent.
  private fun crop(
    rect: NormalizedRect,
    inputSize: Size,
  ): List<FilterNode> {
    if (rect == NormalizedRect.Full) return emptyList()

    val width = (inputSize.width * rect.width).toInt().coerceAtLeast(1)
    val height = (inputSize.height * rect.height).toInt().coerceAtLeast(1)
    return listOf(
      FilterNode(
        "crop",
        "w" to width.toString(),
        "h" to height.toString(),
        "x" to (inputSize.width * rect.left).toInt().toString(),
        "y" to (inputSize.height * rect.top).toInt().toString(),
      ),
    )
  }

  // W and H inside overlay are the main frame, w and h the overlay, so the corner arithmetic is
  // written as an expression rather than resolved here. The margin is not: it is a fraction of the
  // output frame's shorter side and the planner already knows both numbers.
  private fun watermark(
    spec: Watermark,
    outputSize: Size,
  ): EffectResolution {
    val margin = (minOf(outputSize.width, outputSize.height) * spec.margin).roundToInt()
    val overlayWidth = (outputSize.width * spec.scale).roundToInt().coerceAtLeast(1)

    val prepare =
      buildList {
        add(FilterNode("scale", "w" to overlayWidth.toString(), "h" to "-1"))
        add(FilterNode("format", "pix_fmts" to "rgba"))
        if (spec.opacity < 1f) add(FilterNode("colorchannelmixer", "aa" to spec.opacity.toString()))
      }

    val placement =
      buildList {
        add(FilterArgument("x", if (spec.corner.isTrailing) "W-w-$margin" else "$margin"))
        add(FilterArgument("y", if (spec.corner.isBottom) "H-h-$margin" else "$margin"))
        add(FilterArgument("format", "auto"))
        // The overlay is a still, so it ends on its first frame. Without this the main video ends
        // with it.
        add(FilterArgument("eof_action", "pass"))
        spec.visibleDuring?.let { range ->
          val end = range.endExclusive ?: return@let
          add(FilterArgument("enable", "between(t,${range.start.toSeconds()},${end.toSeconds()})"))
        }
      }

    return EffectResolution.Resolved(
      PlatformEffect(
        FilterFragment(
          auxInputs = listOf(AuxInput(spec.image, prepare)),
          merge = FilterNode("overlay", placement),
        ),
      ),
    )
  }

  private fun textMessage(capabilities: RenderCapabilities): String =
    if (capabilities.has(RenderFeature.TextRendering)) TEXT_CANNOT_WRAP else TEXT_NO_FILTER

  private val Corner.isTrailing: Boolean
    get() = this == Corner.TopEnd || this == Corner.BottomEnd

  private val Corner.isBottom: Boolean
    get() = this == Corner.BottomStart || this == Corner.BottomEnd

  private fun Duration.toSeconds(): String = toDouble(DurationUnit.SECONDS).toString()

  private companion object {
    // The only depth this backend writes HDR at, so the lut runs on planar RGB of the same depth
    // rather than on whatever the auto-negotiated conversion would have picked.
    const val HDR_PLANAR_RGB = "gbrp10le"

    const val FULL_TURN = 360
    const val QUARTER_TURN = 90
    const val HALF_TURN = 180
    const val THREE_QUARTER_TURN = 270

    const val TEXT_NO_FILTER =
      "This ffmpeg build has no drawtext filter, so text cannot be burned in. drawtext needs " +
        "--enable-libfreetype and --enable-libharfbuzz, which the common prebuilt packages leave " +
        "out. Export without the caption, or install a build that has it."

    const val TEXT_CANNOT_WRAP =
      "drawtext breaks lines only on a literal newline, so TextStyle.maxWidth cannot be honoured " +
        "and line breaks would land on different words than every other backend. Text layout is " +
        "required to be exact, so it is refused here rather than rendered differently."
  }
}
