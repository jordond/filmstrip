package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.compose.overlay.OverlayLayoutDensity
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.constraintsFor
import dev.jordond.filmstrip.compose.overlay.drawnScale
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.compose.ui.OverlayHandleColors
import dev.jordond.filmstrip.compose.ui.OverlayHandleDefaults
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import androidx.compose.ui.geometry.Size as DrawSize

/**
 * A composable element over the video a [VideoStage] is showing, drawn where an [OverlayCapturer] bakes it.
 *
 * [content] is laid out on [canvas] the way a capture lays it out, then drawn scaled and turned about its own centre
 * with that centre on [OverlayTransform.center]. The part of the element off the picture is clipped, as it is in the
 * export. [output] is the frame the element is baked onto, and only its proportions are read here.
 *
 * Controlled, so a gesture reports the transform it wants through [onTransformChange] and the element moves only when
 * a [transform] carrying it comes back. A drag moves the centre and holds it on the picture, a pinch multiplies the
 * scale inside [ComposedOverlayDefaults.MIN_SCALE] and [ComposedOverlayDefaults.MAX_SCALE], and a twist adds to the
 * rotation. Only a touch that lands on the turned element where it is drawn on the picture is taken, so a stage can
 * stack several and the touch goes to the topmost one under the finger. Every other touch passes to what lies beneath.
 *
 * ```
 * var transform by remember { mutableStateOf(OverlayTransform(Anchor(0.5f, 0.3f))) }
 *
 * VideoStage(player = player, outputAspect = outputAspect) {
 *   ComposedOverlay(
 *     transform = transform,
 *     canvas = canvas,
 *     output = outputSize,
 *     onTransformChange = { transform = it },
 *   ) {
 *     Text("Day one", fontSize = 32.sp)
 *   }
 * }
 * ```
 *
 * @param transform Where the element sits, how big and how far turned.
 * @param canvas The canvas [content] is laid out on, the same one the capture is given.
 * @param output The output frame the element is baked onto, in pixels.
 * @param onTransformChange Called with the transform a gesture asks for.
 * @param modifier Modifier for the element's layer, which fills the stage.
 * @param selected Whether the element draws the wash, outline and corner brackets around itself.
 * @param onSelect Called when a touch lands on the element.
 * @param colors What the selection is painted with.
 * @param handleLength How long a corner bracket's arms are drawn.
 * @param enabled Whether the element answers a touch.
 * @param content The element, drawn the way it is baked.
 */
@OptIn(InternalFilmstripApi::class)
@Composable
public fun VideoStageScope.ComposedOverlay(
  transform: OverlayTransform,
  canvas: OverlayCanvas,
  output: Size,
  onTransformChange: (OverlayTransform) -> Unit,
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  onSelect: () -> Unit = { },
  colors: OverlayHandleColors = OverlayHandleDefaults.Palette,
  handleLength: Dp = OverlayHandleDefaults.HandleLength,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val picture = frame.contentRect
  val density = LocalDensity.current
  val handleLengthPx = with(density) { handleLength.toPx() }
  var laidOut by remember { mutableStateOf(IntSize.Zero) }
  val element = ElementOnStage(transform, picture, transform.drawnScale(canvas, picture.width), laidOut)

  val current by rememberUpdatedState(element)
  val currentOnChange by rememberUpdatedState(onTransformChange)
  val currentOnSelect by rememberUpdatedState(onSelect)
  val placed = remember { Placed() }
  // The gesture sits on the turned element itself, so the touch is hit tested against what is drawn and the topmost
  // element under the finger takes it. Positions are read back on the stage, since the element turns under the finger.
  val gestures =
    if (!enabled) {
      Modifier
    } else {
      Modifier.pointerInput(Unit) {
        awaitEachGesture {
          val down = awaitFirstDown()
          // The part of an element off the picture is clipped away, so a touch there is not on the element.
          val onStage = placed.onStage(down.position) ?: return@awaitEachGesture
          if (!current.picture.contains(onStage)) return@awaitEachGesture
          down.consume()
          currentOnSelect()
          follow(current, placed) { next -> currentOnChange(next) }
        }
      }
    }

  Layout(
    content = {
      CompositionLocalProvider(LocalDensity provides OverlayLayoutDensity) {
        Box(Modifier.onPlaced { placed.element = it }.then(gestures)) { content() }
      }
    },
    modifier =
      modifier
        .matchParentSize()
        .onPlaced { placed.stage = it }
        .drawWithContent {
          clipRect(picture.left, picture.top, picture.right, picture.bottom) {
            this@drawWithContent.drawContent()
          }
          if (selected) drawSelection(current, colors, handleLengthPx)
        },
  ) { measurables, constraints ->
    val placeable = measurables.single().measure(canvas.constraintsFor(output))
    laidOut = IntSize(placeable.width, placeable.height)
    layout(constraints.maxWidth, constraints.maxHeight) {
      if (picture.width <= 0f || picture.height <= 0f) return@layout
      val left = element.centre.x - placeable.width / 2f
      val top = element.centre.y - placeable.height / 2f
      placeable.placeWithLayer(floor(left).toInt(), floor(top).toInt()) {
        translationX = left - floor(left)
        translationY = top - floor(top)
        scaleX = element.drawnScale
        scaleY = element.drawnScale
        rotationZ = transform.rotationDegrees
        transformOrigin = TransformOrigin.Center
      }
    }
  }
}

/**
 * Where the stage's layer and the turned element were last placed, to carry a touch from one to the other.
 */
private class Placed {
  var stage: LayoutCoordinates? = null
  var element: LayoutCoordinates? = null

  fun onStage(position: Offset): Offset? {
    val stage = stage?.takeIf { it.isAttached } ?: return null
    val element = element?.takeIf { it.isAttached } ?: return null
    return stage.localPositionOf(element, position)
  }
}

/**
 * The element as the stage shows it, in the stage's own pixels.
 */
private class ElementOnStage(
  val transform: OverlayTransform,
  val picture: Rect,
  val drawnScale: Float,
  laidOut: IntSize,
) {
  val centre =
    Offset(
      picture.left + transform.center.x * picture.width,
      picture.top + transform.center.y * picture.height,
    )
  val drawn = DrawSize(laidOut.width * drawnScale, laidOut.height * drawnScale)

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
}

// Sums the gesture from the touch down, so the reported transform never drifts from where the fingers have gone.
private suspend fun AwaitPointerEventScope.follow(
  element: ElementOnStage,
  placed: Placed,
  report: (OverlayTransform) -> Unit,
) {
  var pan = Offset.Zero
  var zoom = 1f
  var turn = 0f
  while (true) {
    val event = awaitPointerEvent(PointerEventPass.Main)
    if (event.changes.none { it.pressed } || event.changes.any { it.isConsumed }) break
    val step = event.stepOnStage(placed)
    event.consumeMoves()
    if (step == null || element.picture.width <= 0f || element.picture.height <= 0f) continue
    pan += step.pan
    zoom *= step.zoom
    turn += step.turn
    report(element.moved(pan, zoom, turn))
  }
}

private class Step(
  val pan: Offset,
  val zoom: Float,
  val turn: Float,
)

// How the fingers held down through this event moved, read on the stage. The pan is the centroid's move, the zoom the
// ratio of their mean distances from it, and the turn their angle about it averaged by distance.
private fun PointerEvent.stepOnStage(placed: Placed): Step? {
  val held = changes.filter { it.pressed && it.previousPressed }
  if (held.isEmpty()) return null
  val now = held.map { placed.onStage(it.position) ?: return null }
  val before = held.map { placed.onStage(it.previousPosition) ?: return null }
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

private fun List<Offset>.centroid(): Offset = fold(Offset.Zero) { sum, it -> sum + it } / size.toFloat()

private fun Offset.angle(): Double =
  if (x == 0f &&
    y == 0f
  ) {
    0.0
  } else {
    atan2(y.toDouble(), x.toDouble()) * DEGREES_HALF_TURN / PI
  }

private fun Double.wrapped(): Double =
  when {
    this > DEGREES_HALF_TURN -> this - 2 * DEGREES_HALF_TURN
    this <= -DEGREES_HALF_TURN -> this + 2 * DEGREES_HALF_TURN
    else -> this
  }

private fun PointerEvent.consumeMoves() {
  changes.forEach { change -> if (change.positionChanged()) change.consume() }
}

private fun DrawScope.drawSelection(
  element: ElementOnStage,
  colors: OverlayHandleColors,
  handleLengthPx: Float,
) {
  val drawn = element.drawn
  if (drawn.width <= 0f || drawn.height <= 0f) return
  val box = Rect(element.centre - Offset(drawn.width / 2f, drawn.height / 2f), drawn)
  rotate(element.transform.rotationDegrees, element.centre) {
    drawRect(colors.fill, box.topLeft, box.size)
    drawRect(colors.outline, box.topLeft, box.size, style = Stroke(width = OUTLINE_WIDTH))
    val length = min(handleLengthPx, min(box.width, box.height) / BRACKET_LIMIT)
    drawCornerBrackets(box, length, colors.handle, BRACKET_WIDTH)
  }
}

@Preview
@Composable
private fun ComposedOverlayPreview() {
  PreviewSurface(height = PreviewElementHeight) {
    VideoStage(player = null, outputAspect = 16f / 9f) {
      ComposedOverlay(
        transform = OverlayTransform(Anchor(0.4f, 0.5f), scale = 1.2f, rotationDegrees = -12f),
        canvas = OverlayCanvas(),
        output = Size(1920, 1080),
        onTransformChange = { },
        selected = true,
      ) {
        Box(Modifier.size(160.dp, 48.dp).background(PreviewPlate))
      }
    }
  }
}

private val PreviewElementHeight = 180.dp
private val PreviewPlate = Color(0xCCE8C15A)
private const val OUTLINE_WIDTH = 1.5f
private const val BRACKET_WIDTH = 3f
private const val DEGREES_HALF_TURN = 180.0

/**
 * How much of the box's shorter side a bracket arm may take, so a small element is not drawn as four crossing arms.
 */
private const val BRACKET_LIMIT = 3f
