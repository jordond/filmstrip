package dev.jordond.filmstrip.compose.ui

/**
 * When a selected composed overlay element shows its size and turn handles.
 *
 * The handles are for a pointer that cannot pinch or twist. A drag on the size handle scales the element about its
 * centre and a drag on the turn handle turns it. The turn handle sits above the element's top edge, and below its
 * bottom edge when the one above would fall off the stage. A handle that still falls off the stage cannot be grabbed.
 */
public enum class ComposedOverlayHandles {
  /**
   * Shows the handles once a mouse or a hovering stylus has been seen on an element of the stage, and hides them
   * again when a finger touches one.
   */
  Auto,

  /**
   * Shows the handles on every selected element, whatever it is touched with.
   *
   * A finger can drag them too, which suits an editor that wants one set of controls everywhere.
   */
  Always,

  /**
   * Never shows the handles, leaving the drag, the pinch and the twist.
   *
   * The wheel still scales and turns a selected element.
   */
  Never,
}
