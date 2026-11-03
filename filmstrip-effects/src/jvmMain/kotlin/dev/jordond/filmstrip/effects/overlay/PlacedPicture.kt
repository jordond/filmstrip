package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureOrientation
import kotlin.math.roundToInt

/**
 * An overlay picture the filter graph draws, with where and when it lands.
 *
 * Every overlay the graph lowers reaches it as one of these, so a picture read from a file and one drawn here take the
 * same scale, gate and merge. [placement] is the overlay's shared placement on the frame entering the effect, and the
 * graph writes the pixels [drawnRect] reads off it.
 *
 * @property effect The overlay this draws, which names its window and its animation.
 * @property picture What the graph opens.
 * @property placement Where the picture lands on a frame it holds still on.
 * @property opacity The alpha the overlay is authored at, in `0f..1f`.
 * @property limits Each way the picture was read short of what its file stores.
 */
internal class PlacedPicture(
  val effect: OverlayEffect,
  val picture: GraphPicture,
  val placement: OverlayPlacement,
  val opacity: Float = 1f,
  val limits: List<Degradation> = emptyList(),
)

/**
 * The picture a [PlacedPicture] opens, in the form the grade it lands on draws it from.
 *
 * An SDR grade opens the picture itself from an [Opened], and an HDR grade blends a [Graded] already carried onto its
 * signal.
 */
internal sealed interface GraphPicture {
  /**
   * A picture an SDR grade opens and lays out by [orientation] before it scales it.
   *
   * @property image The picture's bytes or the file holding them.
   * @property orientation The turn the graph lays [image] out by.
   */
  class Opened(
    val image: ImageSource,
    val orientation: PictureOrientation = PictureOrientation.Normal,
  ) : GraphPicture

  /**
   * A picture already carried onto an HDR grade's signal, blended in linear display light.
   *
   * @property raster The picture on [transfer]'s signal.
   * @property transfer The grade's transfer.
   */
  class Graded(
    val raster: HdrOverlayRaster,
    val transfer: HdrTransfer,
  ) : GraphPicture
}

/**
 * Where [drawn] puts an overlay on a frame of [drawnOn]'s size, in whole pixels.
 *
 * The point [OverlayPlacement.overlayAnchor] names inside the overlay lands on the pixel [OverlayPlacement.frameAnchor]
 * names inside the frame, and [OverlayPlacement.size] is the rectangle's extent.
 */
internal fun drawnRect(
  drawn: OverlayPlacement,
  drawnOn: Size,
): PixelRect {
  val width = drawn.size.width
  val height = drawn.size.height
  val anchorX = (drawn.frameAnchor.x * drawnOn.width).roundToInt()
  val anchorY = (drawn.frameAnchor.y * drawnOn.height).roundToInt()

  return PixelRect(
    x = anchorX - (drawn.overlayAnchor.x * width).roundToInt(),
    y = anchorY - (drawn.overlayAnchor.y * height).roundToInt(),
    width = width,
    height = height,
  )
}
