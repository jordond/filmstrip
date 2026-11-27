package dev.jordond.filmstrip.compose.overlay

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Anchor

/**
 * Where a composed overlay element sits on the output frame, how big it is drawn and how far it is turned.
 *
 * The element's content is laid out once on an [OverlayCanvas], then drawn scaled by [scale] and turned by
 * [rotationDegrees] about its own centre, with that centre on [center]. The live element and the baked raster both
 * read this, so an element lands on the same pixels while editing and in the export, give or take how each backend
 * places an image overlay, like ffmpeg's half chroma resolution putting an odd position on the even pixel above it.
 *
 * @property center Where the element's centre lands, as a fraction of the output frame. A point near or past an edge
 * leaves the element hanging off it.
 * @property scale How much bigger than its canvas size the element is drawn. Must be positive and finite.
 * @property rotationDegrees How far the element is turned clockwise about its centre, in degrees. Must be finite.
 */
@Poko
@Immutable
public class OverlayTransform(
  public val center: Anchor = Anchor.Center,
  public val scale: Float = 1f,
  public val rotationDegrees: Float = 0f,
) {
  init {
    require(scale > 0f && scale.isFinite()) { "An overlay's scale must be positive and finite, but was $scale." }
    require(rotationDegrees.isFinite()) { "An overlay's rotation must be finite, but was $rotationDegrees." }
  }
}

/**
 * How many pixels of a frame [frameWidth] pixels wide one layout pixel of content on [canvas] covers.
 *
 * The capture draws with it on the output frame and the live element on the stage's picture, so both scale the same
 * layout the same way.
 *
 * @param canvas The canvas the content was laid out on.
 * @param frameWidth How wide the frame the element is drawn on is, in that frame's own pixels.
 */
@InternalFilmstripApi
public fun OverlayTransform.drawnScale(
  canvas: OverlayCanvas,
  frameWidth: Float,
): Float = scale * frameWidth / canvas.widthPx()
