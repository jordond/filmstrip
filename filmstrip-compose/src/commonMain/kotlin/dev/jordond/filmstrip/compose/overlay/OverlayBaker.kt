package dev.jordond.filmstrip.compose.overlay

import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Size

/**
 * Bakes composed overlay elements into the [ImageOverlay]s an export draws.
 *
 * A [ComposedOverlayState] is the baker a stage fills. Code that exports depends on this interface alone, so a test
 * passes a lambda in its place.
 */
public fun interface OverlayBaker {
  /**
   * Bakes every element for a frame of [output].
   *
   * @param output The output frame the overlays are drawn on, in pixels. This is the export plan's output size.
   * @return The overlays in the order they are drawn, bottom first. An element with none of itself on the frame is
   * left out.
   */
  public suspend fun bake(output: Size): List<ImageOverlay>
}
