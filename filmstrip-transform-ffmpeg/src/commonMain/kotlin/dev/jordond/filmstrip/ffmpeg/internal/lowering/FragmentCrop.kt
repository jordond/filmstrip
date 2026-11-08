package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.FilterCrop
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
 * [auxPads] prepares the extra inputs the merge reads, the same way an uncropped fragment's are. Both overlays run in
 * the format [overlayFormat] picks for [pixelFormat], the format the export writes. The one laying the region back
 * runs on [grid], and the merge runs on the grid the fragment's chain leaves, `grid.after(fragment.chain)`.
 *
 * `overlay` places its input on the chroma grid of the format it runs in, which follows the base, so on a 4:2:0 base
 * an odd `x` or `y` lands a pixel up and to the left of where it was asked for. `crop` without `exact` rounds the
 * same way, but by the grid of its own input. So the crop is cut exact unless [FilterCrop.exact] is off, and on a
 * [ChromaGrid.Subsampled] [grid] both positions are moved down onto an even pixel first, which is on the grid of
 * every 4:2:0 and 4:2:2 format. That snap is `overlay`'s limit, and it means a region asked for at an odd position
 * there sits one pixel off from where the other backends put it. A [ChromaGrid.Full] base takes both as written.
 */
internal fun FilterGraphBuilder.cropped(
  read: String,
  fragment: FilterFragment,
  crop: FilterCrop,
  label: String,
  grid: ChromaGrid,
  pixelFormat: String,
  auxPads: (auxInputs: List<AuxInput>, label: String) -> List<String>,
): String {
  val base = "${label}base"
  val region = "${label}crop"
  split(read, listOf(base, region))

  val cut =
    FilterNode(
      "crop",
      "w" to crop.width,
      "h" to crop.height,
      "x" to grid.snap(crop.x ?: "(in_w-out_w)/2"),
      "y" to grid.snap(crop.y ?: "(in_h-out_h)/2"),
      "exact" to if (crop.exact) "1" else "0",
    )

  val worked = "${label}fx"
  val merge = fragment.merge
  if (merge == null) {
    chain(listOf(region), listOf(cut) + fragment.chain + fragment.post, worked)
  } else {
    val prepared = "${label}pre".also { chain(listOf(region), listOf(cut) + fragment.chain, it) }
    val merged = merge.onFrame(pixelFormat, grid.after(fragment.chain))
    chain(listOf(prepared) + auxPads(fragment.auxInputs, label), listOf(merged) + fragment.post, worked)
  }

  val recombine =
    overlayNode(
      pixelFormat,
      grid,
      "x" to grid.snap(crop.x ?: "(W-w)/2"),
      "y" to grid.snap(crop.y ?: "(H-h)/2"),
    )
  chain(listOf(base, worked), listOf(recombine), label)
  return label
}
