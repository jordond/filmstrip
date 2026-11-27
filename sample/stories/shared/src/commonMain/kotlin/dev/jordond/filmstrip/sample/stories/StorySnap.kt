package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.geometry.Anchor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A transform after snapping, and which guides the stage draws for it.
 *
 * @property transform The transform to keep.
 * @property vertical Whether the centre sits on the frame's vertical centre line.
 * @property horizontal Whether the centre sits on the frame's horizontal centre line.
 * @property level Whether the turn was pulled onto a quarter turn.
 */
internal data class Snap(
  val transform: OverlayTransform,
  val vertical: Boolean = false,
  val horizontal: Boolean = false,
  val level: Boolean = false,
)

/**
 * Pulls [transform] onto the frame's centre lines and onto a quarter turn when it is near them.
 *
 * Each axis of the centre snaps on its own within [CENTER_SNAP] of the middle, and the turn within [TURN_SNAP] degrees
 * of a quarter turn. A turn already on a quarter is not a snap and draws no guide. It reads nothing but the
 * transform, so a drag, a handle, the wheel and a key all snap alike.
 */
internal fun snapped(transform: OverlayTransform): Snap {
  val vertical = abs(transform.center.x - MIDDLE) <= CENTER_SNAP
  val horizontal = abs(transform.center.y - MIDDLE) <= CENTER_SNAP
  val quarter = (transform.rotationDegrees / QUARTER_TURN).roundToInt() * QUARTER_TURN
  val level = transform.rotationDegrees != quarter && abs(transform.rotationDegrees - quarter) <= TURN_SNAP
  if (!vertical && !horizontal && !level) return Snap(transform)
  return Snap(
    transform =
      OverlayTransform(
        center =
          Anchor(
            if (vertical) MIDDLE else transform.center.x,
            if (horizontal) MIDDLE else transform.center.y,
          ),
        scale = transform.scale,
        rotationDegrees = if (level) quarter else transform.rotationDegrees,
      ),
    vertical = vertical,
    horizontal = horizontal,
    level = level,
  )
}

/**
 * How near the middle a centre snaps onto it, as a fraction of the frame.
 */
internal const val CENTER_SNAP: Float = 0.015f

/**
 * How near a quarter turn a turn snaps onto it, in degrees.
 */
internal const val TURN_SNAP: Float = 3f

private const val MIDDLE = 0.5f
private const val QUARTER_TURN = 90f
