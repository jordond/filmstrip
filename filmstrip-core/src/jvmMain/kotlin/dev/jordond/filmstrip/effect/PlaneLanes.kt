package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * Builds and names the plane lanes node, which carries a pad to a planar float format one plane at a time.
 *
 * The node is not a filter of its own. It means "take these planes of the pad, convert each one to float on its own,
 * and put them back together in the target format", so no step ever converts all planes at once. Every other plane of
 * the pad is dropped. The target is one of the planar float formats in [TARGETS].
 *
 * Each named plane goes to the slot its name owns in the target format, whatever order it is named in. The slots are
 * [RGB_SLOTS], and the Y'CbCr planes of [YCBCR_SLOTS] fill the same slots in the same order. So `r`, `g`, `b` into
 * `gbrpf32le` puts red in the red slot, the same as `g`, `b`, `r`. The merged frame is tagged limited range.
 *
 * Effects build the node with [node]. A backend recognises it by [NAME] and reads its arguments by [PLANES] and
 * [FORMAT].
 */
@InternalFilmstripApi
public object PlaneLanes {
  /**
   * The name a plane lanes node carries.
   *
   * No real filter is called this, so a backend that does not expand the node fails on it rather than running
   * something else.
   */
  public const val NAME: String = "filmstrip_planelanes"

  /**
   * The key of the argument that holds the source planes.
   *
   * Its value is the plane names joined with `+` in slot order, such as `y+u+v` or `g+b+r+a`. Each plane still goes to
   * the slot its name owns, so the order carries no meaning of its own.
   */
  public const val PLANES: String = "planes"

  /**
   * The key of the argument that holds the target format.
   *
   * Its value is the planar float pixel format the planes are put back together in, one of [TARGETS].
   */
  public const val FORMAT: String = "format"

  private const val ALPHA = "a"

  /**
   * The RGB planes in the slots they fill in every target, green, blue, red and then alpha.
   *
   * This is the order planar `gbr` formats store their planes in, and a backend reads the order from here.
   */
  public val RGB_SLOTS: List<String> = listOf("g", "b", "r", ALPHA)

  /**
   * The Y'CbCr planes in the slots they fill in every target, luma, Cb, Cr and then alpha.
   *
   * Luma lands in the slot green holds in [RGB_SLOTS], Cb in blue's and Cr in red's, so a Y'CbCr pad carried to a `gbr`
   * format keeps luma in its green plane.
   */
  public val YCBCR_SLOTS: List<String> = listOf("y", "u", "v", ALPHA)

  /**
   * The target with no alpha, which fills the three color slots.
   *
   * A pad carried here keeps its color planes and drops the rest.
   */
  public val OPAQUE: Target = Target("gbrpf32le", RGB_SLOTS - ALPHA)

  /**
   * The target with alpha, which fills every slot of [RGB_SLOTS].
   *
   * A pad carried here keeps its color planes and its alpha.
   */
  public val WITH_ALPHA: Target = Target("gbrapf32le", RGB_SLOTS)

  /**
   * The planar float pixel formats a plane lanes node may target, each with the slots it fills.
   *
   * This list is the one place they are spelled. [node] refuses any other format, and a backend reads the list rather
   * than keeping its own.
   */
  public val TARGETS: List<Target> = listOf(OPAQUE, WITH_ALPHA)

  // Every plane name, the color planes of each family in slot order and alpha last, which is the order a node writes.
  private val slotOrder = (RGB_SLOTS + YCBCR_SLOTS).distinct().sortedBy { it == ALPHA }

  /**
   * Builds a node that carries the pad to [format] one plane at a time.
   *
   * The planes are written in slot order, so naming the same planes in any order builds an equal node.
   *
   * @param planes The source planes, such as `y`, `u`, `v` or `r`, `g`, `b`. Each is one of `y`, `u`, `v`, `r`, `g`,
   * `b` or `a`, and none repeats.
   * @param format The planar float pixel format the planes are put back together in, one of [TARGETS].
   * @return The plane lanes node.
   */
  public fun node(
    planes: List<String>,
    format: String,
  ): FilterNode {
    require(planes.isNotEmpty()) { "A plane lanes node needs at least one plane" }
    require(planes.all { it in slotOrder }) { "Unknown plane in $planes, expected any of $slotOrder" }
    require(planes.distinct().size == planes.size) { "A plane appears twice in $planes" }
    require(target(format) != null) { "A plane lanes node targets $format, expected one of $TARGETS" }
    val slotted = planes.sortedBy { slotOrder.indexOf(it) }
    return FilterNode(NAME, PLANES to slotted.joinToString("+"), FORMAT to format)
  }

  /**
   * Finds the target spelled [format], or null when [TARGETS] holds no such format.
   *
   * A backend reads a node's [FORMAT] argument through this to learn which slots the node fills.
   */
  public fun target(format: String): Target? = TARGETS.firstOrNull { it.format == format }

  /**
   * One planar float pixel format a plane lanes node may target, and the slots it fills.
   *
   * The slots are named as in [RGB_SLOTS], and a Y'CbCr pad fills the same slots by position. A backend reads the
   * plane count and whether alpha is carried from here.
   *
   * @property format The pixel format's name.
   * @property slots The slots the format holds, in the order it stores them.
   */
  @InternalFilmstripApi
  public class Target internal constructor(
    public val format: String,
    public val slots: List<String>,
  ) {
    /**
     * Whether the format holds an alpha plane.
     *
     * Alpha is the last slot of both families, so a target carries it only when it fills every slot.
     */
    public val hasAlpha: Boolean get() = ALPHA in slots

    override fun toString(): String = format
  }
}
