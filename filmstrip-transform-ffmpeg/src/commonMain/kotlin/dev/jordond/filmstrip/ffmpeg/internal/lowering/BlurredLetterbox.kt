package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.sigmaFor
import kotlin.math.roundToInt

/**
 * The background half of a blurred letterbox.
 *
 * The frame is scaled up until it covers the output, centre-cropped to that size, then blurred at
 * [fill]'s radius. A non-zero [Fill.Blurred.dim] darkens the result afterward, as a gain on the
 * color channels rather than an offset, so it never shifts hue.
 */
internal fun coverBlurNodes(
  outputSize: Size,
  fill: Fill.Blurred,
): List<FilterNode> =
  buildList {
    val width = outputSize.width.toString()
    val height = outputSize.height.toString()
    add(FilterNode("scale", "w" to width, "h" to height, "force_original_aspect_ratio" to "increase"))
    add(FilterNode("crop", "w" to width, "h" to height))
    add(FilterNode("gblur", "sigma" to blurSigma(fill, outputSize).toString()))
    val gain = fill.backgroundGain
    if (gain != 1f) {
      add(FilterNode("colorchannelmixer", "rr" to gain.toString(), "gg" to gain.toString(), "bb" to gain.toString()))
    }
  }

/**
 * What a clip's tail ends on, the arguments of [trailingNodes].
 */
internal class TrailingFrames(
  val frameRate: Int,
  val pixelFormat: String,
  val slots: Long,
)

/**
 * The blurred tail of a clip that leaves bars: a sharp copy of the frame placed at [rect], laid over a blurred copy
 * that covers the whole [outputSize]. Names its pads from [label] and writes the frame to [label].
 *
 * `tailNodes` writes a single chain, but this needs the frame twice, once for each copy, so [pending] is flushed
 * onto a pad of its own and fanned out with [FilterGraphBuilder.split] instead. The sharp copy lands on [grid], the
 * chroma grid of the chain both copies come from.
 */
internal fun FilterGraphBuilder.blurredTail(
  pad: String,
  pending: List<FilterNode>,
  fill: Fill.Blurred,
  rect: PixelRect,
  outputSize: Size,
  label: String,
  tail: TrailingFrames,
  grid: ChromaGrid,
): String {
  val pre = "${label}pre"
  chain(listOf(pad), pending.ifEmpty { listOf(FilterNode("null")) }, pre)

  val background = "${label}bg"
  val foreground = "${label}fg"
  split(pre, listOf(background, foreground))

  val blurred = "${label}blur"
  chain(listOf(background), coverBlurNodes(outputSize, fill), blurred)

  val sharp = "${label}sharp"
  chain(listOf(foreground), scaledInto(rect, outputSize), sharp)

  val (x, y) = rect.drawnAt(grid)
  chain(
    listOf(blurred, sharp),
    overlayNodes(x, y, tail.pixelFormat, grid) + trailingNodes(tail.frameRate, tail.pixelFormat, tail.slots),
    label,
  )
  return label
}

// 1024 is gblur's own ceiling on sigma, not a limit filmstrip picked.
private fun blurSigma(
  fill: Fill.Blurred,
  outputSize: Size,
): Int = fill.sigmaFor(outputSize).roundToInt().coerceIn(1, 1024)

/**
 * Lays the sharp foreground over the blurred background at [x], [y], for a tail ending on [pixelFormat] on [grid].
 *
 * ffmpeg's `overlay` reads its first input as the base and its second as what is drawn on top, so
 * the blurred pad has to be listed before the sharp one wherever this is chained.
 */
internal fun overlayNodes(
  x: Int,
  y: Int,
  pixelFormat: String,
  grid: ChromaGrid,
): List<FilterNode> = listOf(overlayNode(pixelFormat, grid, "x" to x.toString(), "y" to y.toString()))
