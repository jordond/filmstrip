package dev.jordond.filmstrip.compose.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The drawn raster and the bounds it was drawn for.
 */
internal class Captured(
  val bitmap: ImageBitmap,
  val bounds: ElementBounds,
)

/**
 * Replays [layer] onto [output] under [transform] and reads back the pixels of the frame the element covers.
 *
 * [layer] holds content laid out at [size] on [canvas]. It is drawn scaled and turned into a second layer the size of
 * the element's raster, so nothing is laid out again.
 *
 * @return The raster with its bounds, or null when none of the element falls on the frame.
 */
@OptIn(InternalFilmstripApi::class)
internal suspend fun bakeRecorded(
  layer: GraphicsLayer,
  size: IntSize,
  transform: OverlayTransform,
  canvas: OverlayCanvas,
  output: Size,
  graphics: GraphicsContext,
): Captured? {
  val bounds = transform.boundsOn(size, canvas, output) ?: return null
  val raster = bounds.raster
  val baked = graphics.createGraphicsLayer()
  try {
    baked.record(OverlayLayoutDensity, LayoutDirection.Ltr, IntSize(raster.width, raster.height)) {
      drawElement(layer, size, transform, canvas, output, raster.left.toFloat(), raster.top.toFloat())
    }
    return Captured(baked.toImageBitmap(), bounds)
  } finally {
    graphics.releaseGraphicsLayer(baked)
  }
}

/**
 * Encodes the raster and wraps it as an overlay centred on the raster's centre and as wide as the raster on [output].
 *
 * The picture is encoded off the calling thread.
 */
internal suspend fun Captured.toImageOverlay(
  output: Size,
  opacity: Float,
  visibleDuring: TimeRange?,
  animation: OverlayAnimation?,
): ImageOverlay {
  val png = withContext(Dispatchers.Default) { bitmap.encodePng() }
  return ImageOverlay(
    image = ImageSource.ofBytes(png),
    position = OverlayPosition.At(bounds.center),
    scale = bounds.raster.width.toFloat() / output.width,
    opacity = opacity,
    visibleDuring = visibleDuring,
    animation = animation,
  )
}

// Draws the recorded content with its centre on the transform's point inside the raster, scaled to the output frame
// and turned about its own centre.
@OptIn(InternalFilmstripApi::class)
private fun DrawScope.drawElement(
  content: GraphicsLayer,
  size: IntSize,
  transform: OverlayTransform,
  canvas: OverlayCanvas,
  output: Size,
  rasterLeft: Float,
  rasterTop: Float,
) {
  val perLayoutPx = transform.drawnScale(canvas, output.width.toFloat())
  withTransform({
    translate(transform.center.x * output.width - rasterLeft, transform.center.y * output.height - rasterTop)
    rotate(transform.rotationDegrees, Offset.Zero)
    scale(perLayoutPx, perLayoutPx, Offset.Zero)
    translate(-size.width / 2f, -size.height / 2f)
  }) {
    drawLayer(content)
  }
}
