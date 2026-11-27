package dev.jordond.filmstrip.compose.ui

/**
 * The bounds a live composed overlay element holds its gestures to.
 *
 * These are limits of the editing gesture only. A transform built in code may use any positive scale.
 */
public object ComposedOverlayDefaults {
  /**
   * The smallest scale a pinch takes an element to, a tenth of its canvas size.
   *
   * A pinch that would shrink the element further holds it here.
   */
  public const val MIN_SCALE: Float = 0.1f

  /**
   * The largest scale a pinch takes an element to, ten times its canvas size.
   *
   * A pinch that would grow the element further holds it here.
   */
  public const val MAX_SCALE: Float = 10f
}
