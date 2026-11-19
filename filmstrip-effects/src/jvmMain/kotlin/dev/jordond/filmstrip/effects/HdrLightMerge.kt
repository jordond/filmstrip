package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BT2020_CHROMA_SHIFT
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * The branches that lay a frame with alpha over an opaque frame on a kept HDR grade, blended in linear display light.
 *
 * [under] carries the opaque frame and [over] the frame with alpha onto planar float display light, each from the
 * grade's ten-bit Y'CbCr. [mask] carries the alpha of the frame with alpha onto every plane. `maskedmerge` reads them
 * in that order, so the result is `a·over + (1 - a)·under` per channel, the rule every backend lands a source with
 * alpha on. [merged] encodes that light back to the grade's ten-bit 4:2:0 Y'CbCr with no alpha, tagged with no color
 * attributes, so the caller sets the grade on it again.
 *
 * `maskedmerge` has no `shortest`, and holds the last frame of a branch that ends while another still runs, so the
 * three branches have to carry the same frames.
 *
 * Every table the branches read is in [sidecars].
 *
 * @property under The nodes for the opaque frame.
 * @property over The nodes for the frame with alpha, for its color.
 * @property mask The nodes for the frame with alpha, for its alpha.
 * @property merge The node that blends the three branches.
 * @property merged The nodes after [merge].
 * @property sidecars Every table the nodes read.
 */
@InternalFilmstripApi
public class HdrLightOver internal constructor(
  public val under: List<FilterNode>,
  public val over: List<FilterNode>,
  public val mask: List<FilterNode>,
  public val merge: FilterNode,
  public val merged: List<FilterNode>,
  public val sidecars: List<Sidecar>,
)

/**
 * The [HdrLightOver] branches for a grade on [transfer].
 *
 * Both frames are brought to the grade's ten-bit Y'CbCr with alpha first, so a frame of any depth or chroma grid is
 * read through the same tables.
 */
@InternalFilmstripApi
public fun hdrLightOver(transfer: HdrTransfer): HdrLightOver {
  val tables = mutableListOf<Sidecar>()
  val toLight = listOf(GRADE_WITH_ALPHA_FORMAT) + GRADE_WITH_ALPHA_TO_FLOAT + tables.signalFromGrade()
  val light = tables.displayLightFromSignal(transfer)
  val encode = geq(signalFromDisplayLight(transfer, PIXEL).mapIndexed { channel, signal -> shifted(signal, channel) })
  return HdrLightOver(
    under = toLight + light,
    over = toLight + light,
    mask = listOf(GRADE_WITH_ALPHA_FORMAT, FilterNode("format", "pix_fmts" to HDR_RGBA)) + ALPHA_TO_RGB + RGBA_TO_FLOAT,
    merge = FilterNode("maskedmerge"),
    merged = listOf(encode) + tables.shiftedSignalToGrade(),
    sidecars = tables.distinct(),
  )
}

/**
 * The nodes that decode each channel of a signal on planar float to linear display light, as a fraction of the
 * transfer's peak, adding the tables they read to this list.
 *
 * HLG's scene light reaches display light through a gain read off all three channels, which lut1d's per-channel tables
 * cannot apply, so geq applies it.
 */
internal fun MutableList<Sidecar>.displayLightFromSignal(transfer: HdrTransfer): List<FilterNode> =
  buildList {
    add(lut1d(HdrGradeTables.LIGHT_WARP, LINEAR))
    add(lut1d(HdrGradeTables.lightCurve(transfer), LINEAR))
    if (transfer == HdrTransfer.Hlg) add(geq(displayLightFromHlgScene(PIXEL)).keepingAlpha())
  }

/**
 * This geq with its alpha read back from the frame, since geq writes an alpha it is given no expression for as opaque.
 */
internal fun FilterNode.keepingAlpha(): FilterNode = FilterNode(name, arguments + FilterArgument("a", "alpha(X,Y)"))

/**
 * A signal expression moved by its [channel]'s share of the chroma offset, which the encode rows read it at.
 */
internal fun shifted(
  signal: String,
  channel: Int,
): String = "($signal)+(${BT2020_CHROMA_SHIFT[channel]})"

/**
 * Sixteen-bit RGBA, the depth a picture with alpha is read at on its way onto planar float.
 */
internal const val HDR_RGBA: String = "rgba64le"

/**
 * A sixteen-bit RGBA branch carried onto planar float RGBA one plane at a time.
 */
internal val RGBA_TO_FLOAT: List<FilterNode> =
  listOf(
    FilterNode("format", "pix_fmts" to HDR_RGBA),
    PlaneLanes.node(listOf("r", "g", "b", "a"), PlaneLanes.WITH_ALPHA.format),
  )

/**
 * Every color channel replaced by the alpha, which is what maskedmerge reads plane by plane.
 */
internal val ALPHA_TO_RGB: FilterNode =
  FilterNode("colorchannelmixer", "rr" to "0", "ra" to "1", "gg" to "0", "ga" to "1", "bb" to "0", "ba" to "1")

// The grade's ten-bit 4:2:0 Y'CbCr with its alpha, which GRADE_WITH_ALPHA_TO_FLOAT reads its codes from.
private val GRADE_WITH_ALPHA_FORMAT = FilterNode("format", "pix_fmts" to "yuva420p10le")
