package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.color.RgbAdjustment
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.isDiagonal
import dev.jordond.filmstrip.effects.color.isIdentity
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HLG_A
import dev.jordond.filmstrip.media.HLG_B
import dev.jordond.filmstrip.media.HLG_C
import dev.jordond.filmstrip.media.HLG_SCENE_TO_SDR_SIGNAL_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_C1
import dev.jordond.filmstrip.media.PQ_C2
import dev.jordond.filmstrip.media.PQ_C3
import dev.jordond.filmstrip.media.PQ_M1
import dev.jordond.filmstrip.media.PQ_M2
import dev.jordond.filmstrip.media.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.media.SDR_SIGNAL_TO_HLG_SCENE_GAMMA
import dev.jordond.filmstrip.media.sdrSignalCeiling
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Lowers the built-in catalogue onto a filter graph.
 *
 * Every lowering is a pure function of the spec and the resolved [Attributes], so it is assertable
 * in a unit test with no toolchain installed, which neither of the other two resolvers can manage.
 */
@OptIn(ExperimentalFilmstripApi::class)
public actual class BuiltInEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.FilterGraph) return null

    return when (spec) {
      is Rotate -> fragment(rotate(spec.degrees))
      is Flip -> fragment(listOf(FilterNode(if (spec.axis == FlipAxis.Horizontal) "hflip" else "vflip")))
      is Crop -> fragment(crop(spec.retainedRect(attributes.inputSize), attributes.inputSize))
      is CropRect -> fragment(crop(spec.rect, attributes.inputSize))
      is KenBurns -> EffectResolution.Unsupported(spec.id, PAN_PENDING)
      // The size stage is the tail the backend pins to the resolved output frame, so the effect
      // that decides that frame contributes no node of its own. Claimed rather than declined,
      // because an unclaimed spec is refused by name at plan time.
      is Scale -> fragment(emptyList())
      is Brightness,
      is RgbAdjustment,
      is Contrast,
      is Saturation,
      is HueRotate,
      is Sepia,
      is Invert,
      is ColorMatrix,
      -> colorMatrix(spec, attributes.hdrTransfer)
      is ImageOverlay -> imageOverlay(spec, attributes.outputSize)
      is TextOverlay -> EffectResolution.Unsupported(spec.id, textMessage(capabilities))
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

  // A graded table runs the transfer function either side of the matrix: decode the code value
  // to the SDR signal its light would have been on a display at reference white, apply the
  // channel's line there, encode it again. A gbrp10le conversion is forced ahead of it because a
  // per-channel nonlinear function has no YUV form, and ten bits is the only depth this backend
  // writes HDR at. Neither zscale nor libplacebo is involved, so it runs on a stock build.
  private fun gradedDiagonalLut(
    matrix: ColorMatrix,
    transfer: HdrTransfer,
  ): List<FilterNode> =
    listOf(
      FilterNode("format", "pix_fmts" to HDR_PLANAR_RGB),
      FilterNode(
        "lutrgb",
        "r" to gradedChannel(matrix.rr, matrix.rBias, transfer, HDR_PLANAR_PEAK),
        "g" to gradedChannel(matrix.gg, matrix.gBias, transfer, HDR_PLANAR_PEAK),
        "b" to gradedChannel(matrix.bb, matrix.bBias, transfer, HDR_PLANAR_PEAK),
      ),
    )

  // The signal the line runs on is scaled so the format's peak reads as one, so the bias is scaled
  // the same way. The floor is the matrix's own, and the ceiling is the format's, in the clip on
  // the way back rather than in the line.
  private fun gradedChannel(
    scale: Float,
    bias: Float,
    transfer: HdrTransfer,
    peak: Int,
  ): String {
    val graded = "max($scale*${scaledSdrSignal(transfer, peak)}+${bias / transfer.sdrSignalCeiling},0)"

    return codeValue(signalFromScaledSdr(graded, transfer), peak)
  }

  // The SDR signal the code value's light decodes to, divided by the transfer's ceiling. PQ's light
  // is a fraction of its peak already. HLG's is scene light, which reaches display light through
  // the per-channel opto-optical transfer first.
  private fun scaledSdrSignal(
    transfer: HdrTransfer,
    peak: Int,
  ): String =
    when (transfer) {
      HdrTransfer.Pq -> "pow(${pqLight(normalized(peak))},${1.0 / SDR_DISPLAY_GAMMA})"
      HdrTransfer.Hlg -> "pow(${hlgScene(normalized(peak))},$HLG_SCENE_TO_SDR_SIGNAL_GAMMA)"
    }

  // The way back from a scaled SDR signal to the transfer's own, in the range zero to one. The clip
  // is where the format runs out.
  private fun signalFromScaledSdr(
    scaled: String,
    transfer: HdrTransfer,
  ): String =
    when (transfer) {
      HdrTransfer.Pq -> pqSignal("clip(pow($scaled,$SDR_DISPLAY_GAMMA),0,1)")
      HdrTransfer.Hlg -> hlgSignal("clip(pow($scaled,$SDR_SIGNAL_TO_HLG_SCENE_GAMMA),0,1)")
    }

  // ST 2084 decoded, as a fraction of its peak.
  private fun pqLight(signal: String): String {
    val encoded = "pow($signal,${1.0 / PQ_M2})"

    return "pow(max($encoded-$PQ_C1,0)/($PQ_C2-$PQ_C3*$encoded),${1.0 / PQ_M1})"
  }

  private fun pqSignal(light: String): String {
    val scaled = "pow($light,$PQ_M1)"

    return "pow(($PQ_C1+$PQ_C2*$scaled)/(1+$PQ_C3*$scaled),$PQ_M2)"
  }

  // Only the inverse OETF, so what comes out is scene light rather than display light.
  private fun hlgScene(signal: String): String =
    "if(lte($signal,0.5),$signal*$signal/3,(exp(($signal-$HLG_C)/$HLG_A)+$HLG_B)/12)"

  private fun hlgSignal(scene: String): String =
    "if(lte($scene,1/12),sqrt(3*$scene),$HLG_A*log(12*$scene-$HLG_B)+$HLG_C)"

  // The graded path forces the pixel format, so the table is scaled by the depth's own top code
  // rather than by lutrgb's maxval. maxval is 255 shifted up to the depth, which reads 1020 on
  // gbrp10le, and scaling by that puts every graded code value about a code low.
  private fun normalized(peak: Int): String = "clip(val/$peak,0,1)"

  // Rounded for the same reason channelExpression rounds: lutrgb keeps the whole part. The clip is
  // lutrgb's own ceiling rather than the format's: the filter refuses to emit above maxval, so on
  // gbrp10le the three codes above 1020 come out as 1020 whatever the table asks for.
  private fun codeValue(
    signal: String,
    peak: Int,
  ): String = "clip(round($peak*($signal)),minval,maxval)"

  // Every colour effect in the catalogue is one affine map of the encoded signal, and it reaches
  // the graph through whichever of two filters spells that map exactly. colorchannelmixer is
  // neither: it fails the graph above a gain of 2 rather than clamping, and it carries no bias.
  //
  // On a kept grade the same map runs on the SDR signal the code value's light decodes to, with
  // the transfer function either side of it, so each of the two filters has a graded form.
  private fun colorMatrix(
    spec: EffectSpec,
    transfer: HdrTransfer?,
  ): EffectResolution {
    val matrix = checkNotNull(colorMatrixOf(spec)) { "${spec.id} has no matrix to lower." }
    return when {
      // Claimed rather than declined, the way Scale is, because an unclaimed spec is refused by
      // name at plan time.
      matrix.isIdentity -> fragment(emptyList())
      !matrix.isDiagonal -> cubeLut(matrix, transfer)
      transfer == null -> fragment(listOf(diagonalLut(matrix)))
      else -> fragment(gradedDiagonalLut(matrix, transfer))
    }
  }

  // Each output channel reads its own input channel alone, so the same lutrgb a brightness lowers
  // to spells the whole matrix. An RGB format's minval is zero, so val carries the signal itself
  // and a bias scaled by maxval pivots a contrast on the middle of the range.
  private fun diagonalLut(matrix: ColorMatrix): FilterNode =
    FilterNode(
      "lutrgb",
      "r" to channelExpression(matrix.rr, matrix.rBias),
      "g" to channelExpression(matrix.gg, matrix.gBias),
      "b" to channelExpression(matrix.bb, matrix.bBias),
    )

  // lutrgb keeps the whole part of what the expression comes to, so the expression rounds first and
  // lands on the code value a shader's output would.
  private fun channelExpression(
    scale: Float,
    bias: Float,
  ): String = "clip(round(val*$scale+$bias*maxval),minval,maxval)"

  // A matrix that mixes channels has no per-channel filter to lower to, so it travels as a
  // two-point table instead. lut3d interpolates an affine map exactly, so eight corners reproduce
  // the matrix rather than sampling it, and it clamps once on the way out, which is where the
  // matrix puts the clamp too. It keeps the code value below the interpolated one rather than the
  // nearest, its own limit, so a mixed matrix here can land one code value under the other backends.
  //
  // On a kept grade the table reads the SDR signal scaled so the format's peak is one, which is
  // the range lut3d interpolates over, so the bias is written against that ceiling too.
  private fun cubeLut(
    matrix: ColorMatrix,
    transfer: HdrTransfer?,
  ): EffectResolution {
    val ceiling = transfer?.sdrSignalCeiling ?: SDR_CEILING
    val sidecar = Sidecar(cube(matrix, ceiling).encodeToByteArray(), "cube")
    val lut = FilterNode("lut3d", "file" to sidecar.placeholder)

    return EffectResolution.Resolved(
      PlatformEffect(
        FilterFragment(
          chain = if (transfer == null) listOf(lut) else betweenTransfers(lut, transfer),
          sidecars = listOf(sidecar),
        ),
      ),
    )
  }

  // lut3d reads a signal in the range zero to one, so on a grade it runs between a table that
  // decodes the code value to the scaled SDR signal and one that encodes it again. Sixteen bits
  // between the two keeps a ten-bit output exact. The two extra format conversions are a cost per
  // frame on a graded export with a mixing matrix, not a change to what it writes: swscale carries
  // rgb48le to gbrp10le and back with no drift, which the planar sixteen-bit format it replaced did
  // not.
  private fun betweenTransfers(
    lut: FilterNode,
    transfer: HdrTransfer,
  ): List<FilterNode> {
    val decode = codeValue(scaledSdrSignal(transfer, WIDE_RGB_PEAK), WIDE_RGB_PEAK)
    val encode = codeValue(signalFromScaledSdr(normalized(WIDE_RGB_PEAK), transfer), WIDE_RGB_PEAK)

    return listOf(
      FilterNode("format", "pix_fmts" to WIDE_RGB),
      FilterNode("lutrgb", "r" to decode, "g" to decode, "b" to decode),
      lut,
      FilterNode("lutrgb", "r" to encode, "g" to encode, "b" to encode),
      FilterNode("format", "pix_fmts" to HDR_PLANAR_RGB),
    )
  }

  // The eight corners of the unit cube, red varying fastest and blue slowest, which is the order a
  // .cube file is read in. Entries are left unclamped so that a corner the matrix pushes past white
  // still lands on the line the interpolation between it and its neighbour needs. The bias is
  // divided by the ceiling of the signal the table reads, which is one for an SDR signal.
  private fun cube(
    matrix: ColorMatrix,
    ceiling: Float,
  ): String =
    buildString {
      appendLine("LUT_3D_SIZE $CUBE_SIZE")
      for (blue in 0..1) {
        for (green in 0..1) {
          for (red in 0..1) {
            val r = matrix.rr * red + matrix.rg * green + matrix.rb * blue + matrix.rBias / ceiling
            val g = matrix.gr * red + matrix.gg * green + matrix.gb * blue + matrix.gBias / ceiling
            val b = matrix.br * red + matrix.bg * green + matrix.bb * blue + matrix.bBias / ceiling
            appendLine("${entry(r)} ${entry(g)} ${entry(b)}")
          }
        }
      }
    }

  // Fixed decimals in the root locale. A comma for the decimal point, or an exponent on a small
  // enough entry, is a file ffmpeg reads as something else.
  private fun entry(value: Float): String = String.format(Locale.ROOT, CUBE_ENTRY_FORMAT, value)

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
  private fun imageOverlay(
    spec: ImageOverlay,
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
}

// The only depth this backend writes HDR at, so the lut runs on planar RGB of the same depth
// rather than on whatever the auto-negotiated conversion would have picked.
private const val HDR_PLANAR_RGB = "gbrp10le"

// The format a graded signal is held in between the two halves of a transfer function, so that a
// table read at ten bits still lands on the code value. Packed rather than planar because swscale
// converts gbrp16le to gbrp10le a code out on a third of all colours, while rgb48le round trips
// exactly, and because lutrgb's maxval on this one is the format's own peak.
private const val WIDE_RGB = "rgb48le"

// The top code each of the two formats holds, which is what a graded table is scaled by. lutrgb's
// own maxval is 255 shifted up to the depth, so it agrees on rgb48le and reads three low on
// gbrp10le.
private const val HDR_PLANAR_PEAK = 1023
private const val WIDE_RGB_PEAK = 65535

// White is the top of an SDR signal, so a table written for one scales its bias by nothing.
private const val SDR_CEILING = 1f

// Two points a side is the whole of an affine map, and every entry between them is interpolated
// rather than stored.
private const val CUBE_SIZE = 2
private const val CUBE_ENTRY_FORMAT = "%.7f"

private const val FULL_TURN = 360
private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270

private const val PAN_PENDING =
  "A pan moves the region it shows on every frame, and this backend resolves a crop to whole " +
    "pixels once at plan time so that the plan and the export cannot disagree about the frame. " +
    "The time-varying form has not landed here yet."

private const val TEXT_NO_FILTER =
  "This ffmpeg build has no drawtext filter, so text cannot be burned in. drawtext needs " +
    "--enable-libfreetype and --enable-libharfbuzz, which the common prebuilt packages leave " +
    "out. Export without the caption, or install a build that has it."

private const val TEXT_CANNOT_WRAP =
  "drawtext breaks lines only on a literal newline, so TextStyle.maxWidth cannot be honoured " +
    "and line breaks would land on different words than every other backend. TextOverlay layout is " +
    "required to be exact, so it is refused here rather than rendered differently."
