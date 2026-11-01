package dev.jordond.filmstrip.effects.overlay

/**
 * What drawing a text overlay got, either the raster drawn or why there is none.
 *
 * Apple, Android and the browser each draw their text to this, Apple as `OverlayPixels`, Android as `TextSamples` and
 * the browser as `LaidOutText`, and lower [Empty] and [Refused] to an unsupported effect with the reason it gives.
 */
internal sealed interface TextRaster<out T> {
  /**
   * A raster that was laid out and then either drawn or refused, never [Empty].
   *
   * A backend step that runs only once the text has something to draw returns this, so its caller has no [Empty] to
   * handle.
   */
  sealed interface Made<out T> : TextRaster<T>

  /**
   * The text was drawn.
   *
   * @property pixels The raster, in the form the backend composites it from.
   */
  class Drawn<out T>(
    val pixels: T,
  ) : Made<T>

  /**
   * The text and its style leave nothing to draw, or the platform could not lay them out.
   */
  data object Empty : TextRaster<Nothing>

  /**
   * The raster is refused, past its ceiling before it is allocated or when the platform could not allocate it.
   *
   * @property reason What the overlay reports. A raster past its ceiling gives the sentences [textTooLargeToDraw]
   * gives, and one the platform would not allocate gives the sentence [textAllocationFailed] gives.
   */
  class Refused(
    val reason: String,
  ) : Made<Nothing>
}
