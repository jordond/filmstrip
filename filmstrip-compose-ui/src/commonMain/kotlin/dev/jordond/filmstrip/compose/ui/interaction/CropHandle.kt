package dev.jordond.filmstrip.compose.ui.interaction

/**
 * Which part of a crop rectangle a gesture is touching.
 *
 * A closed type: the four corners, the four edges and the rectangle's body is all there is.
 */
public enum class CropHandle {
  /**
   * The top-left corner.
   */
  TopLeft,

  /**
   * The top edge.
   */
  Top,

  /**
   * The top-right corner.
   */
  TopRight,

  /**
   * The right edge.
   */
  Right,

  /**
   * The bottom-right corner.
   */
  BottomRight,

  /**
   * The bottom edge.
   */
  Bottom,

  /**
   * The bottom-left corner.
   */
  BottomLeft,

  /**
   * The left edge.
   */
  Left,

  /**
   * Inside the rectangle and away from every edge. A drag from here translates the whole rectangle rather than resizing
   * it.
   */
  Body,
}
