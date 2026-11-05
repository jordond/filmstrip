package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature

/**
 * What drawing a text overlay got, either the raster drawn or why there is none.
 *
 * Apple, Android and the browser each draw their text to this, Apple as `OverlayPixels`, Android as `TextSamples` and
 * the browser as `LaidOutText`, and lower it through [resolvedFrom].
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
   * The platform laid the text and its style out to nothing, or could not lay them out at all.
   *
   * Empty text does not reach a backend through a plan, since the planner leaves it out as never drawn, so this is
   * text the platform was handed and could not draw.
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

/**
 * Resolves this text overlay from the raster [rasterize] draws, or as unsupported when there is none.
 *
 * Where [capabilities] has no [RenderFeature.TextRendering] the overlay is refused with [NO_TEXT_RENDERING] and nothing
 * is drawn. [TextRaster.Empty] is refused with [EMPTY_TEXT] and [TextRaster.Refused] with the reason it gives. A drawn
 * raster is handed to [resolve].
 */
internal inline fun <T> TextOverlay.resolvedFrom(
  capabilities: RenderCapabilities,
  rasterize: () -> TextRaster<T>,
  resolve: (T) -> EffectResolution,
): EffectResolution {
  if (!capabilities.has(RenderFeature.TextRendering)) return EffectResolution.Unsupported(id, NO_TEXT_RENDERING)
  return when (val drawn = rasterize()) {
    is TextRaster.Drawn -> resolve(drawn.pixels)
    is TextRaster.Empty -> EffectResolution.Unsupported(id, EMPTY_TEXT)
    is TextRaster.Refused -> EffectResolution.Unsupported(id, drawn.reason)
  }
}

/**
 * What a text overlay is refused with on a device that cannot draw text.
 */
internal const val NO_TEXT_RENDERING = "This device cannot rasterise text into a frame."

/**
 * What a text overlay is refused with when its text and style leave nothing to draw.
 */
internal const val EMPTY_TEXT = "The text and its style leave nothing to draw."
