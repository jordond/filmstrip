package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode

/**
 * Runs [fragment] on the region [crop] cuts out of [read] and lays the result back where it was cut from, on a pad
 * named [label].
 *
 * [read] is split in two, the way the blurred tail splits its frame. One copy passes through untouched as the base,
 * the other is cropped and runs the fragment's chain, merge and post. An `overlay` then puts the worked region back
 * on the base at the crop's `x` and `y`, or centred where the crop names neither, which is where `crop` itself cuts
 * by default. Those positions are handed to `overlay` as written, so they have to read the same to both filters.
 * [auxPads] prepares the extra inputs the merge reads, the same way an uncropped fragment's are.
 *
 * `overlay` places its input on the chroma grid of the format it runs in, so on a 4:2:0 base an odd `x` or `y`
 * lands a pixel up and to the left of where it was asked for. `crop` without `exact` rounds the same way, but by
 * the grid of its own input, and the two grids differ whenever `overlay` negotiates a format the base did not
 * arrive in. The crop is therefore made `exact` unless it says otherwise, and both positions are moved down onto
 * an even pixel first, which is on the grid of every 4:2:0, 4:2:2 and 4:4:4 format. That snap is `overlay`'s limit,
 * and it means a region asked for at an odd position sits one pixel off from where the other backends put it.
 */
internal fun FilterGraphBuilder.cropped(
  read: String,
  fragment: FilterFragment,
  crop: FilterNode,
  label: String,
  auxPads: (auxInputs: List<AuxInput>, label: String) -> List<String>,
): String {
  require(crop.name == "crop") { "A fragment's crop must be a crop node, not ${crop.name}" }

  val base = "${label}base"
  val region = "${label}crop"
  split(read, listOf(base, region))

  fun position(
    key: String,
    centred: String,
  ): String = onChromaGrid(crop.arguments.lastOrNull { it.key == key }?.value ?: centred)

  val x = position("x", "(in_w-out_w)/2")
  val y = position("y", "(in_h-out_h)/2")
  val named = crop.arguments.map { it.key }.toSet()
  val snapped =
    crop.arguments.map { argument ->
      when (argument.key) {
        "x" -> FilterArgument("x", x)
        "y" -> FilterArgument("y", y)
        else -> argument
      }
    }
  val missing =
    listOf(FilterArgument("x", x), FilterArgument("y", y), FilterArgument("exact", "1")).filterNot { it.key in named }
  val cut = FilterNode(crop.name, snapped + missing)

  val worked = "${label}fx"
  val merge = fragment.merge
  if (merge == null) {
    chain(listOf(region), listOf(cut) + fragment.chain + fragment.post, worked)
  } else {
    val prepared = "${label}pre".also { chain(listOf(region), listOf(cut) + fragment.chain, it) }
    chain(listOf(prepared) + auxPads(fragment.auxInputs, label), listOf(merge) + fragment.post, worked)
  }

  val recombine =
    overlayNode(
      "x" to position("x", "(W-w)/2"),
      "y" to position("y", "(H-h)/2"),
    )
  chain(listOf(base, worked), listOf(recombine), label)
  return label
}

// Moves a position down onto the even pixel at or before it. A whole number is snapped here, anything
// else is wrapped so the filter snaps it once it has evaluated the expression.
private fun onChromaGrid(position: String): String =
  position.toIntOrNull()?.let { (it - it.mod(2)).toString() } ?: "2*floor(($position)/2)"
