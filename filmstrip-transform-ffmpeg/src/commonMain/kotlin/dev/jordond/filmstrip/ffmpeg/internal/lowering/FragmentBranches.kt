package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Every filter this fragment puts in a graph by name, those the backend adds around it included.
 *
 * The planner refuses a fragment whose filters a build does not have, rather than handing ffmpeg a graph it fails on.
 */
internal fun FilterFragment.filterNames(): Set<String> =
  buildSet {
    val nodes =
      chain + listOfNotNull(merge) + post + branches.flatMap { it.nodes } + auxInputs.flatMap { it.chain }
    nodes.forEach { node ->
      // A name past an @ is that node's own instance, which a sendcmd sidecar addresses. The filter comes before it.
      if (node.name == PlaneLanes.NAME) addAll(PLANE_LANES_FILTERS) else add(node.name.substringBefore('@'))
    }
    if (branches.any { it.writes.size > 1 }) add("split")
    if (branches.isNotEmpty()) add(if (readsCompositionClock) "setpts" else "null")
    if (crop != null) addAll(CROPPED_FILTERS)
  }

// What a plane lanes node and a cropped fragment are expanded to.
private val PLANE_LANES_FILTERS = setOf("format", "extractplanes", "mergeplanes")
private val CROPPED_FILTERS = setOf("split", "crop", "overlay")

/**
 * Lays a fragment's own graph onto this one, reading [read] and writing [output].
 *
 * A fragment that reads the composition clock runs with its frames' timestamps moved onto it, then moved back. [origin]
 * is where the frames handed to it land in the composition, and [WINDOW_CLOCK] adds where a windowed preview opened.
 */
internal fun FilterGraphBuilder.branches(
  read: String,
  fragment: FilterFragment,
  output: String,
  origin: Duration,
) {
  val clock = "(${formatSeconds(origin.toDouble(DurationUnit.SECONDS))}+$WINDOW_CLOCK)/TB"
  val input =
    if (fragment.readsCompositionClock) {
      "${output}clock".also { chain(listOf(read), listOf(FilterNode("setpts", "expr" to "PTS+$clock")), it) }
    } else {
      read
    }
  val pad = { name: String -> if (name == FilterBranch.INPUT) input else "$output$name" }
  fragment.branches.forEach { branch ->
    if (branch.writes.size > 1) {
      split(pad(branch.reads.single()), branch.writes.map(pad))
    } else if (branch.reads.isEmpty()) {
      source(branch.nodes, pad(branch.writes.single()))
    } else {
      chain(branch.reads.map(pad), branch.nodes, pad(branch.writes.single()))
    }
  }
  val tail =
    if (fragment.readsCompositionClock) FilterNode("setpts", "expr" to "PTS-$clock") else FilterNode("null")
  chain(
    listOf(
      pad(
        fragment.branches
          .last()
          .writes
          .single(),
      ),
    ),
    listOf(tail),
    output,
  )
}
