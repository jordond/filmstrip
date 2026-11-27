package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.layout.LayoutCoordinates
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.geometry.Anchor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.pow

/**
 * Where the stage's layer and the turned element were last placed, to carry a touch from one to the other.
 */
internal class Placed {
  var stage: LayoutCoordinates? = null
  var element: LayoutCoordinates? = null

  fun onStage(
    position: Offset,
    from: LayoutCoordinates? = element,
  ): Offset? {
    val stage = stage?.takeIf { it.isAttached } ?: return null
    val source = from?.takeIf { it.isAttached } ?: return null
    return stage.localPositionOf(source, position)
  }
}

/**
 * The element as the stage shows it, in the stage's own pixels.
 */
internal class ElementOnStage(
  val transform: OverlayTransform,
  val picture: Rect,
  val drawnScale: Float,
) {
  val centre =
    Offset(
      picture.left + transform.center.x * picture.width,
      picture.top + transform.center.y * picture.height,
    )

  fun moved(
    pan: Offset,
    zoom: Float,
    turn: Float,
  ): OverlayTransform =
    OverlayTransform(
      center =
        Anchor(
          x = (transform.center.x + pan.x / picture.width).coerceIn(0f, 1f),
          y = (transform.center.y + pan.y / picture.height).coerceIn(0f, 1f),
        ),
      scale = (transform.scale * zoom).coerceIn(ComposedOverlayDefaults.MIN_SCALE, ComposedOverlayDefaults.MAX_SCALE),
      rotationDegrees = transform.rotationDegrees + turn,
    )

  // A wheel moved back, which reads as a negative delta, grows the element. With the turn asked for it turns the way
  // the wheel went instead.
  fun wheeled(
    notches: Float,
    turning: Boolean,
  ): OverlayTransform =
    if (turning) {
      moved(Offset.Zero, 1f, ComposedOverlayDefaults.TURN_STEP * notches)
    } else {
      moved(Offset.Zero, ComposedOverlayDefaults.WHEEL_STEP.pow(-notches), 0f)
    }
}

// A step asked for in shares of the frame rather than stage pixels, which is how a key press reads.
internal fun OverlayTransform.stepped(
  pan: Offset = Offset.Zero,
  zoom: Float = 1f,
  turn: Float = 0f,
): OverlayTransform = ElementOnStage(this, UNIT_FRAME, 1f).moved(pan, zoom, turn)

private val UNIT_FRAME = Rect(0f, 0f, 1f, 1f)

internal class Step(
  val pan: Offset,
  val zoom: Float,
  val turn: Float,
) {
  val isStill: Boolean get() = pan == Offset.Zero && zoom == 1f && turn == 0f
}

// How the fingers down through this event moved, read on the stage. The pan is the centroid's move, the zoom the ratio
// of their mean distances from it, and the turn their angle about it averaged by distance. Each finger's last position
// is kept on the stage, since the element moves under the finger and no event comes for a move the element made too.
// A finger lifting counts, so a move held back that way lands on the release.
internal fun PointerEvent.stepOnStage(
  placed: Placed,
  last: MutableMap<PointerId, Offset>,
): Step? {
  val onStage = mutableMapOf<PointerId, Offset>()
  for (change in changes) {
    if (change.pressed || change.previousPressed) onStage[change.id] = placed.onStage(change.position) ?: return null
  }
  val held = onStage.keys.filter { it in last }
  val now = held.map { onStage.getValue(it) }
  val before = held.map { last.getValue(it) }
  last.clear()
  last.putAll(onStage)
  if (held.isEmpty()) return null
  val centreNow = now.centroid()
  val centreBefore = before.centroid()
  if (held.size < 2) return Step(centreNow - centreBefore, 1f, 0f)

  val spreadNow = now.sumOf { (it - centreNow).getDistance().toDouble() }
  val spreadBefore = before.sumOf { (it - centreBefore).getDistance().toDouble() }
  var weighted = 0.0
  var weight = 0.0
  for (index in held.indices) {
    val from = before[index] - centreBefore
    val to = now[index] - centreNow
    val distance = (from.getDistance() + to.getDistance()) / 2.0
    weighted += (to.angle() - from.angle()).wrapped() * distance
    weight += distance
  }
  return Step(
    pan = centreNow - centreBefore,
    zoom = if (spreadBefore > 0.0) (spreadNow / spreadBefore).toFloat() else 1f,
    turn = if (weight > 0.0) (weighted / weight).toFloat() else 0f,
  )
}

internal fun List<Offset>.centroid(): Offset = fold(Offset.Zero) { sum, it -> sum + it } / size.toFloat()

internal fun Offset.angle(): Double =
  if (x == 0f &&
    y == 0f
  ) {
    0.0
  } else {
    atan2(y.toDouble(), x.toDouble()) * DEGREES_HALF_TURN / PI
  }

internal fun Double.wrapped(): Double =
  when {
    this > DEGREES_HALF_TURN -> this - 2 * DEGREES_HALF_TURN
    this <= -DEGREES_HALF_TURN -> this + 2 * DEGREES_HALF_TURN
    else -> this
  }

private const val DEGREES_HALF_TURN = 180.0

/**
 * How many notches of a mouse wheel a scroll [delta] is, on a screen of [density].
 *
 * Platforms report a notch in different units, so a wheel step reads the delta through this and one notch is one step
 * on all of them.
 */
internal expect fun wheelNotches(
  delta: Float,
  density: Float,
): Float
