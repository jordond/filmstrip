package dev.jordond.filmstrip.compose

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
): ComposeSize {
  if (available.width <= 0f || available.height <= 0f) return ComposeSize.Zero
  if (contentScale == VideoContentScale.Stretch) return available

  val aspect = output.aspect
  if (aspect <= 0f) return available

  val fromWidth = ComposeSize(available.width, available.width / aspect)
  val fromHeight = ComposeSize(available.height * aspect, available.height)
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
