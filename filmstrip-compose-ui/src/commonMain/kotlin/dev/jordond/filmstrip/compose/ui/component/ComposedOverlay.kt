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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.compose.overlay.OverlayLayoutDensity
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.constraintsFor
import dev.jordond.filmstrip.compose.overlay.drawnScale
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.compose.ui.ComposedOverlayHandles
import dev.jordond.filmstrip.compose.ui.OverlayHandleColors
import dev.jordond.filmstrip.compose.ui.OverlayHandleDefaults
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
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
 * rotation. A mouse does the same through the handles a selected element shows, a drag on the corner one scaling it
 * and a drag on the one above it turning it. Over a selected element the wheel scales by
 * [ComposedOverlayDefaults.WHEEL_STEP] a notch, and turns by [ComposedOverlayDefaults.TURN_STEP] a notch with Shift
 * held. Only a touch that lands on the turned element where it is drawn on the picture is taken, so a stage can
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
 * @param onGestureChange Called with true when a touch takes the element and with false when its last finger lifts
 * or the gesture is cancelled.
 * @param colors What the selection is painted with.
 * @param handleLength How long a corner bracket's arms are drawn.
 * @param enabled Whether the element answers a touch.
 * @param handles When a selected element shows the size and turn handles a mouse sizes and turns it with.
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
  onGestureChange: (Boolean) -> Unit = { },
  colors: OverlayHandleColors = OverlayHandleDefaults.Palette,
  handleLength: Dp = OverlayHandleDefaults.HandleLength,
  enabled: Boolean = true,
  handles: ComposedOverlayHandles = ComposedOverlayHandles.Auto,
  content: @Composable () -> Unit,
) {
  val picture = frame.contentRect
  val density = LocalDensity.current
  val handleLengthPx = with(density) { handleLength.toPx() }
  val knobPx = with(density) { OverlayHandleDefaults.KnobRadius.toPx() }
  val stalkPx = with(density) { OverlayHandleDefaults.StalkLength.toPx() }
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val pointer = LocalStagePointer.current ?: remember { StagePointer() }
  val handled =
    enabled &&
      selected &&
      when (handles) {
        ComposedOverlayHandles.Auto -> pointer.fine
        ComposedOverlayHandles.Always -> true
        ComposedOverlayHandles.Never -> false
      }
  var laidOut by remember { mutableStateOf(IntSize.Zero) }
  val element = ElementOnStage(transform, picture, transform.drawnScale(canvas, picture.width))

  val current by rememberUpdatedState(element)
  val currentOnChange by rememberUpdatedState(onTransformChange)
  val currentOnSelect by rememberUpdatedState(onSelect)
  val currentOnGesture by rememberUpdatedState(onGestureChange)
  val currentSelected by rememberUpdatedState(selected)
  // The element is laid out at the layout density, so the screen's own is carried in for the wheel.
  val stageDensity by rememberUpdatedState(density.density)
  val placed = remember { Placed() }
  // The gesture sits on the turned element itself, so the touch is hit tested against what is drawn and the topmost
  // element under the finger takes it. Positions are read back on the stage, since the element turns under the finger.
  val gestures =
    if (!enabled) {
      Modifier
    } else {
      Modifier
        .pointerInput(pointer) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              pointer.note(event)
              if (event.type == PointerEventType.Scroll && currentSelected) {
                event.wheelOn(current, placed, stageDensity)?.let { next -> currentOnChange(next) }
              }
            }
          }
        }.pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown()
            // The part of an element off the picture is clipped away, so a touch there is not on the element.
            val onStage = placed.onStage(down.position) ?: return@awaitEachGesture
            if (!current.picture.contains(onStage)) return@awaitEachGesture
            down.consume()
            currentOnSelect()
            currentOnGesture(true)
            try {
              follow(current, placed, mutableMapOf(down.id to onStage)) { next -> currentOnChange(next) }
            } finally {
              currentOnGesture(false)
            }
          }
        }
    }

  Layout(
    content = {
      CompositionLocalProvider(LocalDensity provides OverlayLayoutDensity) {
        Box(Modifier.onPlaced { placed.element = it }.then(gestures)) { content() }
      }
      if (handled) {
        HandleTarget(HandleKind.Size, placed, { current }, { currentOnChange(it) }, { currentOnGesture(it) })
        HandleTarget(HandleKind.Turn, placed, { current }, { currentOnChange(it) }, { currentOnGesture(it) })
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
          if (selected) drawSelection(current, laidOut, colors, handleLengthPx)
          if (handled) current.handlePoints(laidOut, stalkPx, rtl, size)?.let { drawHandles(it, colors, knobPx) }
        },
  ) { measurables, constraints ->
    val placeable = measurables.first().measure(canvas.constraintsFor(output))
    val targets = measurables.drop(1).map { it.measure(Constraints()) }
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
      val stage = DrawSize(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
      val points = element.handlePoints(laidOut, stalkPx, rtl, stage) ?: return@layout
      targets.zip(listOf(points.size, points.turn)).forEach { (target, point) ->
        target.place((point.x - target.width / 2f).roundToInt(), (point.y - target.height / 2f).roundToInt())
      }
    }
  }
}

// The transform a wheel over the element asks for, or null when the wheel is over a part the picture clips away,
// another node already took it, or it did not move. A wheel with Shift held often reports along x instead of y.
private fun PointerEvent.wheelOn(
  element: ElementOnStage,
  placed: Placed,
  density: Float,
): OverlayTransform? {
  val change = changes.firstOrNull()?.takeUnless { it.isConsumed } ?: return null
  val onStage = placed.onStage(change.position) ?: return null
  if (!element.picture.contains(onStage)) return null
  val delta = change.scrollDelta
  val notches = wheelNotches(if (delta.y != 0f) delta.y else delta.x, density)
  if (!notches.isFinite() || notches == 0f) return null
  change.consume()
  return element.wheeled(notches.coerceIn(-WHEEL_NOTCH_LIMIT, WHEEL_NOTCH_LIMIT), keyboardModifiers.isShiftPressed)
}

// Sums the gesture from the touch down, so the reported transform never drifts from where the fingers have gone.
private suspend fun AwaitPointerEventScope.follow(
  element: ElementOnStage,
  placed: Placed,
  last: MutableMap<PointerId, Offset>,
  report: (OverlayTransform) -> Unit,
) {
  var pan = Offset.Zero
  var zoom = 1f
  var turn = 0f
  while (true) {
    val event = awaitPointerEvent(PointerEventPass.Main)
    if (event.changes.any { it.isConsumed }) break
    val step = event.stepOnStage(placed, last)
    event.consumeMoves()
    if (step != null && !step.isStill && element.picture.width > 0f && element.picture.height > 0f) {
      pan += step.pan
      zoom *= step.zoom
      turn += step.turn
      report(element.moved(pan, zoom, turn))
    }
    if (event.changes.none { it.pressed }) break
  }
}

private fun PointerEvent.consumeMoves() {
  changes.forEach { change -> if (change.positionChanged()) change.consume() }
}

private fun DrawScope.drawSelection(
  element: ElementOnStage,
  laidOut: IntSize,
  colors: OverlayHandleColors,
  handleLengthPx: Float,
) {
  val drawn = DrawSize(laidOut.width * element.drawnScale, laidOut.height * element.drawnScale)
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

/**
 * The most notches one wheel event may ask for, so one hard flick cannot jump the element to a limit.
 */
private const val WHEEL_NOTCH_LIMIT = 4f
private const val BRACKET_WIDTH = 3f

/**
 * How much of the box's shorter side a bracket arm may take, so a small element is not drawn as four crossing arms.
 */
private const val BRACKET_LIMIT = 3f
