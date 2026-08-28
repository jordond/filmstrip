package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Fill

// Renders the filter vocabulary in filmstrip-core into the text a filter graph is written in.
// The escaping lives here rather than in core because it is a property of the tool, not of the
// effect. Only one level of it is needed: the graph reaches the child as one element of an
// argument list, so no shell ever sees it.

private const val ESCAPED = "\\'[],;:"

internal fun escapeFilterValue(value: String): String =
  buildString(value.length) {
    value.forEach { character ->
      if (character in ESCAPED) append('\\')
      append(character)
    }
  }

internal fun FilterNode.render(): String =
  if (arguments.isEmpty()) {
    name
  } else {
    name + "=" + arguments.joinToString(":") { "${it.key}=${escapeFilterValue(it.value)}" }
  }

internal fun List<FilterNode>.render(): String = joinToString(",") { it.render() }

// Only Fill.Solid names a colour of its own. A gap or a plain pad has no frame to blur, so a
// Fill.Blurred, and anything this backend does not recognise yet, falls back to black.
internal fun fillColor(fill: Fill): String =
  when (fill) {
    is Fill.Solid -> renderColor(fill.color)
    else -> renderColor(BLACK)
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
 * Every pad label is written exactly once as an output and read exactly once as an input, and
 * [build] proves it. Reading a label twice is not an error in ffmpeg: the parser matches it once
 * and silently connects the second reference to an unused input stream, so a graph that looks
 * right renders the wrong thing at exit code 0. Fan out with `split` instead.
 */
internal class FilterGraphBuilder {
  private val chains = mutableListOf<String>()
  private val produced = mutableSetOf<String>()
  private val consumed = mutableListOf<String>()

  fun chain(
    inputs: List<String>,
    nodes: List<FilterNode>,
    output: String,
  ) {
    require(nodes.isNotEmpty()) { "A chain needs at least one filter" }
    require(produced.add(output)) { "Pad [$output] is written twice" }
    consumed += inputs
    chains += inputs.joinToString("") { "[$it]" } + nodes.render() + "[$output]"
  }

  fun split(
    input: String,
    outputs: List<String>,
  ) {
    require(outputs.size >= 2) { "A split needs at least two outputs" }
    outputs.forEach { output -> require(produced.add(output)) { "Pad [$output] is written twice" } }
    consumed += input
    chains += "[$input]split=${outputs.size}" + outputs.joinToString("") { "[$it]" }
  }

  fun build(vararg terminals: String): String {
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
