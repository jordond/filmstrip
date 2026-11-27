package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.IntSize
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.OverlayHandleColors
import dev.jordond.filmstrip.compose.ui.OverlayHandleDefaults
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which pointer was last seen on a stage's composed elements, shared by every element of that stage.
 */
internal class StagePointer {
  var fine by mutableStateOf(false)
    private set

  // A stylus that touches neither shows the handles nor hides them, so a handle it is dragging stays under it.
  fun note(event: PointerEvent) {
    for (change in event.changes) {
      when (change.type) {
        PointerType.Mouse -> fine = true
        PointerType.Stylus, PointerType.Eraser -> if (!change.pressed && !change.previousPressed) fine = true
        PointerType.Touch -> if (change.pressed) fine = false
        else -> Unit
      }
    }
  }
}

internal val LocalStagePointer = compositionLocalOf<StagePointer?> { null }

internal enum class HandleKind { Size, Turn }

/**
 * Where a selected element's handles sit on the stage, the one place both the drawing and the hit boxes read.
 */
internal class HandlePoints(
  val size: Offset,
  val turn: Offset,
  val stalkFoot: Offset,
)

// The turn handle goes above the top edge, and below the bottom one when above would put it off the stage, where no
// pointer reaches it.
internal fun ElementOnStage.handlePoints(
  laidOut: IntSize,
  stalkPx: Float,
  rtl: Boolean,
  stage: Size,
): HandlePoints? {
  val halfWidth = laidOut.width * drawnScale / 2f
  val halfHeight = laidOut.height * drawnScale / 2f
  if (halfWidth <= 0f || halfHeight <= 0f) return null
  val degrees = transform.rotationDegrees
  val bounds = Rect(Offset.Zero, stage)
  val above = centre + Offset(0f, -halfHeight - stalkPx).turnedBy(degrees)
  val below = centre + Offset(0f, halfHeight + stalkPx).turnedBy(degrees)
  val side = if (!bounds.contains(above) && bounds.contains(below)) 1f else -1f
  return HandlePoints(
    size = centre + Offset(if (rtl) -halfWidth else halfWidth, halfHeight).turnedBy(degrees),
    turn = if (side > 0f) below else above,
    stalkFoot = centre + Offset(0f, side * halfHeight).turnedBy(degrees),
  )
}

internal fun Offset.turnedBy(degrees: Float): Offset {
  val radians = degrees * PI / 180.0
  val cos = cos(radians).toFloat()
  val sin = sin(radians).toFloat()
  return Offset(x * cos - y * sin, x * sin + y * cos)
}

internal fun DrawScope.drawHandles(
  points: HandlePoints,
  colors: OverlayHandleColors,
  knobPx: Float,
) {
  drawLine(colors.outline, points.stalkFoot, points.turn, strokeWidth = KNOB_OUTLINE)
  for (knob in listOf(points.size, points.turn)) {
    drawCircle(colors.handle, knobPx, knob)
    drawCircle(colors.outline, knobPx, knob, style = Stroke(width = KNOB_OUTLINE))
  }
}

/**
 * The box a handle is grabbed by, which draws nothing and is placed by the element's layout on its handle point.
 *
 * A drag that starts in it reports the transform it asks for the way a touch on the element does.
 */
@Composable
internal fun HandleTarget(
  kind: HandleKind,
  placed: Placed,
  element: () -> ElementOnStage,
  onTransformChange: (OverlayTransform) -> Unit,
  onGestureChange: (Boolean) -> Unit,
) {
  val currentElement by rememberUpdatedState(element)
  val currentOnChange by rememberUpdatedState(onTransformChange)
  val currentOnGesture by rememberUpdatedState(onGestureChange)
  val target = remember { arrayOfNulls<LayoutCoordinates>(1) }
  Box(
    Modifier
      .size(OverlayHandleDefaults.HandleTouchSize)
      .onPlaced { target[0] = it }
      .pointerInput(kind) {
        awaitEachGesture {
          val down = awaitFirstDown()
          val start = placed.onStage(down.position, target[0]) ?: return@awaitEachGesture
          down.consume()
          currentOnGesture(true)
          try {
            followHandle(kind, currentElement(), down.id, start, { placed.onStage(it, target[0]) }) { next ->
              currentOnChange(next)
            }
          } finally {
            currentOnGesture(false)
          }
        }
      },
  )
}

// Sums from the down the way a touch on the element does. The size handle reads the pointer's distance from the
// centre over its distance at the down, and the turn handle adds up the change of its angle about the centre, so a
// drag round more than half a turn keeps counting.
private suspend fun AwaitPointerEventScope.followHandle(
  kind: HandleKind,
  element: ElementOnStage,
  pointer: PointerId,
  start: Offset,
  onStage: (Offset) -> Offset?,
  report: (OverlayTransform) -> Unit,
) {
  val reach = (start - element.centre).getDistance()
  var last = start
  var turn = 0.0
  while (true) {
    val event = awaitPointerEvent()
    val change = event.changes.firstOrNull { it.id == pointer } ?: break
    if (change.isConsumed) break
    val now = onStage(change.position)
    if (change.positionChanged()) change.consume()
    if (now != null && now != last) {
      when (kind) {
        HandleKind.Size -> {
          if (reach > 0f) report(element.moved(Offset.Zero, (now - element.centre).getDistance() / reach, 0f))
        }
        HandleKind.Turn -> {
          turn += ((now - element.centre).angle() - (last - element.centre).angle()).wrapped()
          report(element.moved(Offset.Zero, 1f, turn.toFloat()))
        }
      }
      last = now
    }
    if (!change.pressed) break
  }
}

private const val KNOB_OUTLINE = 1.5f
