package dev.jordond.filmstrip.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.jordond.filmstrip.geometry.Size
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * The rectangle a frame of [output] fills inside [available], for [contentScale].
 *
 * Every surface centres what it gets back. [VideoContentScale.Crop] returns a rectangle larger than
 * [available] on one axis, which the caller clips. [available] may carry an infinite axis, for a
 * host that bounds the surface on one side only: a fit still contains the video against the bounded
 * axis, while a crop falls back to that same rectangle, since there is no finite far side left for
 * it to overflow into.
 *
 * @param output The frame size the composition outputs.
 * @param available The space the surface was given.
 * @param contentScale How the video fills that space.
 */
internal fun videoRect(
  output: Size,
  available: ComposeSize,
  contentScale: VideoContentScale,
): ComposeSize = videoRect(output.aspect, available, contentScale)

/**
 * The rectangle a frame of [outputAspect] fills inside [available], for [contentScale].
 *
 * The letterbox reads the output's aspect and nothing else, so this is the form for a caller that
 * has the shape of the frame without its pixel dimensions.
 *
 * @param outputAspect Width over height of the frame the composition outputs.
 * @param available The space the surface was given.
 * @param contentScale How the video fills that space.
 */
internal fun videoRect(
  outputAspect: Float,
  available: ComposeSize,
  contentScale: VideoContentScale,
): ComposeSize {
  if (available.width <= 0f || available.height <= 0f) return ComposeSize.Zero
  if (contentScale == VideoContentScale.Stretch) return available
  if (outputAspect <= 0f) return available

  val fromWidth = ComposeSize(available.width, available.width / outputAspect)
  val fromHeight = ComposeSize(available.height * outputAspect, available.height)
  val fits = fromWidth.height <= available.height

  val fit = if (fits) fromWidth else fromHeight
  val overflow = if (fits) fromHeight else fromWidth

  return when (contentScale) {
    VideoContentScale.Fit -> fit
    VideoContentScale.Crop -> overflow.takeIf { it.isFinite() } ?: fit
    VideoContentScale.Stretch -> available
  }
}

private fun ComposeSize.isFinite(): Boolean = width.isFinite() && height.isFinite()

/**
 * The rectangle [output] fills inside [available], for [contentScale].
 *
 * Built on [videoRect], so a caller outside this module reads the same letterbox an overlay would
 * otherwise have to re-derive. The rectangle is centred inside [available], which is what every
 * surface already does with the size [videoRect] returns.
 *
 * @param output The frame size the composition outputs.
 * @param available The space the surface was given.
 * @param contentScale How the video fills that space.
 * @return The centred rectangle the video is drawn into.
 */
public fun videoContentRect(
  output: Size,
  available: ComposeSize,
  contentScale: VideoContentScale,
): Rect = videoContentRect(output.aspect, available, contentScale)

/**
 * The rectangle a frame of [outputAspect] fills inside [available], for [contentScale].
 *
 * The same rectangle the [Size] form returns, for a caller holding the shape of the output frame
 * without its pixel dimensions. A host that has laid a box out to an aspect already knows only
 * that much, and rounding the aspect back into a pair of pixel sides to ask the question loses
 * precision the letterbox never needed.
 *
 * @param outputAspect Width over height of the frame the composition outputs.
 * @param available The space the surface was given.
 * @param contentScale How the video fills that space.
 * @return The centred rectangle the video is drawn into.
 */
public fun videoContentRect(
  outputAspect: Float,
  available: ComposeSize,
  contentScale: VideoContentScale,
): Rect {
  val size = videoRect(outputAspect, available, contentScale)
  val left = (available.width - size.width) / 2f
  val top = (available.height - size.height) / 2f
  return Rect(Offset(left, top), size)
}
