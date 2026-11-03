package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.sigmaFor
import kotlin.math.roundToInt

/**
 * The sharp half of a blurred letterbox: the frame scaled down to fit inside the output, with no
 * crop and no pad of its own.
 *
 * Centring it onto the output is left to the overlay that reads this alongside
 * [coverBlurNodes]'s background.
 */
internal fun containNodes(outputSize: Size): List<FilterNode> =
  listOf(
    FilterNode(
      "scale",
      "w" to outputSize.width.toString(),
      "h" to outputSize.height.toString(),
      "force_original_aspect_ratio" to "decrease",
    ),
  )

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

// 1024 is gblur's own ceiling on sigma, not a limit filmstrip picked.
private fun blurSigma(
  fill: Fill.Blurred,
  outputSize: Size,
): Int = fill.sigmaFor(outputSize).roundToInt().coerceIn(1, 1024)

/**
 * Centres the sharp foreground over the blurred background.
 *
 * ffmpeg's `overlay` reads its first input as the base and its second as what is drawn on top, so
 * the blurred pad has to be listed before the sharp one wherever this is chained.
 */
internal fun overlayNodes(): List<FilterNode> = listOf(overlayNode("x" to "(W-w)/2", "y" to "(H-h)/2"))
