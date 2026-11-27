package dev.jordond.filmstrip.compose.ui

/**
 * The bounds a live composed overlay element holds its gestures to, and the size of one wheel or key step.
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

  /**
   * What one wheel notch or one press of plus multiplies the scale by, an eighth of a doubling.
   *
   * A notch the other way and a press of minus divide by it.
   */
  public const val WHEEL_STEP: Float = 1.0905077f

  /**
   * How many degrees one wheel notch turns an element while Shift is held.
   *
   * The sign follows the wheel, so a notch back turns the other way.
   */
  public const val TURN_STEP: Float = 5f

  /**
   * How far one arrow key press moves an element's centre, as a share of the frame along that axis.
   *
   * Half a percent of the frame, so two hundred presses cross it.
   */
  public const val NUDGE: Float = 0.005f

  /**
   * How far an arrow key press moves an element's centre while Shift is held, ten times [NUDGE].
   *
   * Twenty presses cross the frame.
   */
  public const val COARSE_NUDGE: Float = 0.05f

  /**
   * How many degrees one press of a bracket key turns an element.
   *
   * The closing bracket turns clockwise and the opening one the other way.
   */
  public const val KEY_TURN_STEP: Float = 1f

  /**
   * How many degrees one press of a bracket key turns an element while Shift is held.
   *
   * Six presses make a quarter turn.
   */
  public const val COARSE_KEY_TURN_STEP: Float = 15f
}
