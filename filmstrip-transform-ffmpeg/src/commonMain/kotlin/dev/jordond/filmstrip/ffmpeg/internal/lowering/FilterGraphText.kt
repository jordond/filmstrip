package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.geometry.Fill

// Renders the filter vocabulary in filmstrip-core into the text a filter graph is written in.

internal fun FilterNode.render(): String =
  if (arguments.isEmpty()) {
    name
  } else {
    name + "=" + arguments.joinToString(":") { "${it.key}=${escapeFilterValue(it.value)}" }
  }

internal fun List<FilterNode>.render(): String = joinToString(",") { it.render() }

// Only Fill.Solid names a color of its own. A gap or a plain pad has no frame to blur, so a
// Fill.Blurred falls back to black.
internal fun fillColor(fill: Fill): String = renderColor(fillArgb(fill))

internal fun fillArgb(fill: Fill): Int =
  when (fill) {
    is Fill.Solid -> fill.color
    is Fill.Blurred -> BLACK
  }

private fun renderColor(argb: Int): String = "0x" + (argb and 0xFFFFFF).toString(16).padStart(HEX_DIGITS, '0')

// The same vocabulary as fillColor, for a pad or tpad frame that has to render as empty rather than
// as the fill: fully transparent, so a flatten further down the graph can tell an empty pixel from
// one the fill actually painted.
internal fun transparentColor(): String = "black@0"

private const val HEX_DIGITS = 6
private const val BLACK = 0xFF000000.toInt()

/**
 * Accumulates the `[in]filters[out]` chains a graph is made of.
 *
 * Every pad label is written exactly once as an output and read exactly once as an input, and [build] proves it.
 * Reading a label twice is not an error in ffmpeg: the parser matches it once and silently connects the second
 * reference to an unused input stream, so a graph that looks right renders the wrong thing at exit code 0. Fan out
 * with `split` instead.
 *
 * A [PlaneLanes] node in a chain is expanded rather than written. The filters before it end in a `format` that pins the
 * frame to the sixteen-bit planar format of the named planes' family, with alpha when alpha is named, and an
 * `extractplanes`. Each plane is carried to a gray float format on a lane of its own, and a `mergeplanes`
 * puts each lane into the slot its plane owns in the node's format: green, blue, red and alpha for RGB planes, y, u, v
 * and alpha for Y'CbCr planes. The merged frame is tagged video range and runs the filters after it. The pads in
 * between are named after the chain's output and plane, and go through the same proof as any other.
 */
internal class FilterGraphBuilder {
  private val chains = mutableListOf<String>()
  private val produced = mutableSetOf<String>()
  private val consumed = mutableListOf<String>()
  private val sources = linkedMapOf<List<FilterNode>, MutableList<String>>()

  /**
   * A chain that reads no pad, such as a still a filter reads from a file, writing [output].
   *
   * Sources with the same [nodes] share one run of them, fanned out when the graph is built, so what a filter holds on
   * to for the whole run is held once however many clips read it.
   */
  fun source(
    nodes: List<FilterNode>,
    output: String,
  ) {
    require(nodes.isNotEmpty()) { "A source needs at least one filter" }
    require(produced.add(output)) { "Pad [$output] is written twice" }
    sources.getOrPut(nodes) { mutableListOf() } += output
  }

  fun chain(
    inputs: List<String>,
    nodes: List<FilterNode>,
    output: String,
  ) {
    require(nodes.isNotEmpty()) { "A chain needs at least one filter" }
    require(output !in produced) { "Pad [$output] is written twice" }
    var reads = inputs
    val pending = mutableListOf<FilterNode>()
    var lanes = 0
    nodes.forEach { node ->
      if (node.name == PlaneLanes.NAME) {
        val target = laneTarget(node)
        reads = planeLanes(reads, pending, target, "$output$LANE_PAD${lanes++}")
        pending.clear()
        pending += target.merge
        // mergeplanes writes an untagged frame, so the merged frame is tagged with the range its lanes
        // were read in. setrange only relabels, so no sample moves.
        pending += FilterNode("setrange", "range" to "limited")
      } else {
        pending += node
      }
    }
    write(reads, pending, listOf(output))
  }

  // swscale pulls the planes against each other by up to half a ten-bit code on any three plane int
  // to float RGB step, and leaves a single gray plane exact, so each plane goes to float alone.
  private fun planeLanes(
    reads: List<String>,
    pending: List<FilterNode>,
    target: LaneTarget,
    prefix: String,
  ): List<String> {
    require(pending.isNotEmpty() || reads.size == 1) { "A plane lanes node reads one pad, not $reads" }
    // extractplanes only picks its formats once its input is down to one depth, and filters such as
    // pad, crop and colorchannelmixer leave every format open, so the frame is pinned first.
    val pin = FilterNode("format", "pix_fmts" to target.pin)
    val extract = FilterNode("extractplanes", "planes" to target.extracted.joinToString("+"))
    write(reads, pending + pin + extract, target.extracted.map { "$prefix$it" })
    return target.slots.map { plane ->
      "$prefix$plane$FLOAT_PAD".also {
        write(listOf("$prefix$plane"), listOf(FilterNode("format", "pix_fmts" to target.lane)), listOf(it))
      }
    }
  }

  private fun laneTarget(node: FilterNode): LaneTarget {
    val arguments = node.arguments.associate { it.key to it.value }
    val planes = requireNotNull(arguments[PlaneLanes.PLANES]) { "A plane lanes node names no planes" }.split("+")
    val format = requireNotNull(arguments[PlaneLanes.FORMAT]) { "A plane lanes node names no format" }
    val target =
      requireNotNull(PlaneLanes.target(format)) {
        "A plane lanes node targets $format, expected one of ${PlaneLanes.TARGETS}"
      }
    val family =
      requireNotNull(PLANE_FAMILIES.firstOrNull { it.slots.containsAll(planes) }) { "$planes mixes Y'CbCr and RGB" }
    val slots = family.slots.take(target.slots.size)
    require(planes.size == slots.size && planes.toSet() == slots.toSet()) {
      "$format holds the planes $slots, the node names $planes"
    }
    val pin = if (target.hasAlpha) family.withAlpha else family.opaque
    return LaneTarget(slots, planes.sortedBy(EXTRACTED_ORDER::indexOf), family.lane, pin, format)
  }

  private fun write(
    inputs: List<String>,
    nodes: List<FilterNode>,
    outputs: List<String>,
  ) {
    outputs.forEach { output -> require(produced.add(output)) { "Pad [$output] is written twice" } }
    consumed += inputs
    chains += inputs.joinToString("") { "[$it]" } + nodes.render() + outputs.joinToString("") { "[$it]" }
  }

  fun split(
    input: String,
    outputs: List<String>,
  ): Unit = fanOut("split", input, outputs)

  fun asplit(
    input: String,
    outputs: List<String>,
  ): Unit = fanOut("asplit", input, outputs)

  private fun fanOut(
    filter: String,
    input: String,
    outputs: List<String>,
  ) {
    require(outputs.size >= 2) { "A split needs at least two outputs" }
    outputs.forEach { output -> require(produced.add(output)) { "Pad [$output] is written twice" } }
    consumed += input
    chains += "[$input]$filter=${outputs.size}" + outputs.joinToString("") { "[$it]" }
  }

  fun build(vararg terminals: String): String {
    sources.forEach { (nodes, outputs) ->
      val fanned = if (outputs.size > 1) ",split=${outputs.size}" else ""
      chains += nodes.render() + fanned + outputs.joinToString("") { "[$it]" }
    }
    val readTwice =
      consumed
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    require(readTwice.isEmpty()) { "Pads read more than once: $readTwice" }
    val dangling = produced - consumed.toSet() - terminals.toSet()
    require(dangling.isEmpty()) { "Pads written but never read: $dangling" }
    return chains.joinToString(";")
  }
}

// The planes a plane lanes node carries, in the order of the slots they fill and in the order
// extractplanes writes them, the gray float format each lane is carried in, the sixteen-bit format
// the frame is pinned to before it is cut, and the planar float format the merge puts them back
// together in, slot i of it from the lane of the i-th slot plane.
private class LaneTarget(
  val slots: List<String>,
  val extracted: List<String>,
  val lane: String,
  val pin: String,
  format: String,
) {
  val merge =
    FilterNode(
      "mergeplanes",
      *(slots.indices.flatMap { listOf("map${it}s" to "$it", "map${it}p" to "0") } + ("format" to format))
        .toTypedArray(),
    )
}

// The planes of each family in the slots they fill, as PlaneLanes orders them, the gray float format
// each of its lanes is carried in, and the sixteen-bit planar formats a frame of the family is pinned
// to before its planes are cut, without and with alpha. Every lane is gray float, and PlaneLanes lists
// no sixteen-bit target, because of swscale 9.0.2: it reads a gray float plane as video range and
// writes gray16 in full range whatever the tag, so a float lane lands up to 5375 of 65535 off on its
// way to gray16.
private class PlaneFamily(
  val slots: List<String>,
  val lane: String,
  val opaque: String,
  val withAlpha: String,
)

private const val GRAY_FLOAT = "grayf32le"

private val PLANE_FAMILIES =
  listOf(
    PlaneFamily(PlaneLanes.YCBCR_SLOTS, GRAY_FLOAT, "yuv444p16le", "yuva444p16le"),
    PlaneFamily(PlaneLanes.RGB_SLOTS, GRAY_FLOAT, "gbrp16le", "gbrap16le"),
  )

// extractplanes writes its pads in this order, whatever order the planes are named in.
private val EXTRACTED_ORDER = listOf("y", "u", "v", "r", "g", "b", "a")

private const val LANE_PAD = "lane"
private const val FLOAT_PAD = "f"
