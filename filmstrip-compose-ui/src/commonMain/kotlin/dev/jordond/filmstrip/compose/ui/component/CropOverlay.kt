package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.jordond.filmstrip.compose.VideoContentScale
import dev.jordond.filmstrip.compose.ui.CropColors
import dev.jordond.filmstrip.compose.ui.CropOverlayDefaults
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.compose.ui.geometry.CropFrame
import dev.jordond.filmstrip.compose.ui.interaction.CropConstraint
import dev.jordond.filmstrip.compose.ui.interaction.CropHandle
import dev.jordond.filmstrip.compose.videoContentRect
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * A drag-to-crop rectangle over a video frame.
 *
 * Controlled, so a drag reports the rectangle it wants through [onRectChange] and draws whatever [rect] is next passed
 * back in. A caller that drops the change draws a rectangle that does not move.
 *
 * The overlay measures itself and builds its own [CropFrame] from [output] and [contentScale], so a host cannot hand it
 * a rectangle sized against a mismatched letterbox. Inside a [dev.jordond.filmstrip.compose.ui.VideoStage] the stage has measured that already, and the
 * overload on `VideoStageScope` reads its letterbox rather than taking an [output] of its own.
 *
 * A [constraint] that arrives after the rectangle was drawn corrects it once, through [onRectChange], so a lock picked
 * from a chip reaches the rectangle without waiting for the next drag.
 *
 * ```
 * val surface = rememberVideoSurfaceState(player)
 * var rect by remember(source) { mutableStateOf(NormalizedRect.Full) }
 *
 * Box(Modifier.videoAspect(surface, VideoContentScale.Fit)) {
 *   VideoSurface(player, Modifier.fillMaxSize())
 *
 *   CropOverlay(
 *     rect = rect,
 *     onRectChange = { rect = it },
 *     output = surface.outputSize,
 *     constraint = CropConstraint.FixedAspect(ratio = 1f, minWidth = CropOverlayDefaults.MinWidth),
 *   )
 * }
 *
 * // The rectangle addresses the frame the overlay is drawn over, so the preview under it is the
 * // uncropped one and the crop joins the edit on the way out of the tool.
 * val cropped = remember(source, rect) {
 *   filmstrip.composition {
 *     clip(source)
 *     effects { crop(rect) }
 *   }
 * }
 * ```
 *
 * @param rect Where the crop currently sits, in the frame's own normalized space.
 * @param onRectChange Called with the rectangle a gesture asks for, already constrained.
 * @param output The frame size the composition outputs, which the video is letterboxed against.
 * @param modifier Modifier for the overlay, which fills whatever it is placed in.
 * @param contentScale How the video fills the space the overlay measures.
 * @param constraint What the gesture is allowed to produce.
 * @param colors What the overlay paints with.
 * @param handleLength How long a corner bracket's arms and an edge grip are drawn.
 * @param touchSize How wide a handle answers to a finger, which is wider than it is drawn.
 * @param showGrid Whether rule-of-thirds guides are drawn while a drag is in flight.
 */
@Composable
public fun CropOverlay(
  rect: NormalizedRect,
  onRectChange: (NormalizedRect) -> Unit,
  output: Size,
  modifier: Modifier = Modifier,
  contentScale: VideoContentScale = VideoContentScale.Fit,
  constraint: CropConstraint = CropOverlayDefaults.Constraint,
  colors: CropColors = CropOverlayDefaults.Palette,
  handleLength: Dp = CropOverlayDefaults.HandleLength,
  touchSize: Dp = CropOverlayDefaults.TouchSize,
  showGrid: Boolean = true,
) {
  if (output.width <= 0 || output.height <= 0) return

  CropOverlay(
    rect = rect,
    onRectChange = onRectChange,
    frameFor =
      remember(output, contentScale) {
        { size: ComposeSize -> CropFrame(videoContentRect(output, size, contentScale)) }
      },
    modifier = modifier,
    constraint = constraint,
    colors = colors,
    handleLength = handleLength,
    touchSize = touchSize,
    showGrid = showGrid,
  )
}

/**
 * A drag-to-crop rectangle over the video a [dev.jordond.filmstrip.compose.ui.VideoStage] is showing.
 *
 * Everything the [Size] form does, reading the stage's own letterbox instead of measuring for one. A host that has
 * already been letterboxed has no honest output size left to hand over, only the shape it was letterboxed to, and the
 * rectangle this authors addresses the frame the stage is drawing.
 *
 * ```
 * var rect by remember(source) { mutableStateOf(NormalizedRect.Full) }
 * var lock: AspectRatio? by remember { mutableStateOf(null) }
 *
 * VideoStage(player = player, outputAspect = outputAspect, modifier = Modifier.padding(16.dp)) {
 *   CropOverlay(
 *     rect = rect,
 *     onRectChange = { rect = it },
 *     constraint = lock?.let { CropConstraint.lockedTo(it, aspect) } ?: CropOverlayDefaults.Constraint,
 *   )
 * }
 *
 * // The rectangle addresses the frame the overlay is drawn over, so the preview under it is the
 * // uncropped one and the crop joins the edit on the way out of the tool.
 * filmstrip.composition {
 *   clip(source)
 *   effects { crop(rect) }
 * }
 * ```
 *
 * @param rect Where the crop currently sits, in the frame's own normalized space.
 * @param onRectChange Called with the rectangle a gesture asks for, already constrained.
 * @param modifier Modifier for the overlay, which fills the stage.
 * @param constraint What the gesture is allowed to produce.
 * @param colors What the overlay paints with.
 * @param handleLength How long a corner bracket's arms and an edge grip are drawn.
 * @param touchSize How wide a handle answers to a finger, which is wider than it is drawn.
 * @param showGrid Whether rule-of-thirds guides are drawn while a drag is in flight.
 */
@Composable
public fun VideoStageScope.CropOverlay(
  rect: NormalizedRect,
  onRectChange: (NormalizedRect) -> Unit,
  modifier: Modifier = Modifier,
  constraint: CropConstraint = CropOverlayDefaults.Constraint,
  colors: CropColors = CropOverlayDefaults.Palette,
  handleLength: Dp = CropOverlayDefaults.HandleLength,
  touchSize: Dp = CropOverlayDefaults.TouchSize,
  showGrid: Boolean = true,
) {
  val stageFrame = frame

  CropOverlay(
    rect = rect,
    onRectChange = onRectChange,
    frameFor = remember(stageFrame) { { _: ComposeSize -> stageFrame } },
    modifier = modifier,
    constraint = constraint,
    colors = colors,
    handleLength = handleLength,
    touchSize = touchSize,
    showGrid = showGrid,
  )
}

/**
 * The drawing and the gestures both entry points share, over the frame [frameFor] builds for the size the overlay was
 * laid out at.
 */
@Composable
private fun CropOverlay(
  rect: NormalizedRect,
  onRectChange: (NormalizedRect) -> Unit,
  frameFor: (ComposeSize) -> CropFrame,
  modifier: Modifier,
  constraint: CropConstraint,
  colors: CropColors,
  handleLength: Dp,
  touchSize: Dp,
  showGrid: Boolean,
) {
  val density = LocalDensity.current
  val handleLengthPx = with(density) { handleLength.toPx() }
  val touchRadiusPx = with(density) { touchSize.toPx() } / 2f

  val currentRect by rememberUpdatedState(rect)
  val currentOnRectChange by rememberUpdatedState(onRectChange)
  val currentConstraint by rememberUpdatedState(constraint)
  var isDragging by remember { mutableStateOf(false) }

  // Keyed on the constraint alone, so a host that drops the correction is asked once rather than on every
  // recomposition that follows.
  LaunchedEffect(constraint) {
    val snapped = constraint.constrain(currentRect, CropHandle.BottomRight)
    if (snapped != currentRect) currentOnRectChange(snapped)
  }

  Canvas(
    modifier =
      modifier
        .fillMaxSize()
        .pointerInput(frameFor, touchRadiusPx) {
          var handle: CropHandle? = null
          var origin = NormalizedRect.Full
          var totalDrag = Offset.Zero

          detectDragGestures(
            onDragStart = { position ->
              val boxSize = size.toSize()
              if (boxSize.width <= 0f || boxSize.height <= 0f) return@detectDragGestures

              origin = currentRect
              totalDrag = Offset.Zero
              handle = frameFor(boxSize).handleAt(position, origin, touchRadiusPx)
              isDragging = handle != null
            },
            onDragEnd = { isDragging = false },
            onDragCancel = { isDragging = false },
          ) { change, dragAmount ->
            val activeHandle = handle ?: return@detectDragGestures
            change.consume()
            totalDrag += dragAmount

            val proposed = origin.draggedBy(activeHandle, totalDrag, frameFor(size.toSize()))
            currentOnRectChange(currentConstraint.constrain(proposed, activeHandle))
          }
        },
  ) {
    if (size.width <= 0f || size.height <= 0f) return@Canvas

    val view = frameFor(size).toView(rect)

    drawScrim(view, colors.scrim)
    drawRect(colors.outline, view.topLeft, view.size, style = Stroke(width = OUTLINE_WIDTH))
    if (showGrid && isDragging) drawThirds(view, colors.grid)
    drawHandles(view, handleLengthPx, colors.handle)
  }
}

/**
 * [this] with the edge or edges [handle] touches moved by [totalDragPx], converted into the normalized frame through
 * [frame]. Edges [handle] does not touch keep their original value.
 */
private fun NormalizedRect.draggedBy(
  handle: CropHandle,
  totalDragPx: Offset,
  frame: CropFrame,
): NormalizedRect {
  val width = frame.contentRect.width
  val height = frame.contentRect.height
  if (width <= 0f || height <= 0f) return this

  val dx = totalDragPx.x / width
  val dy = totalDragPx.y / height
  val movedLeft = left + dx
  val movedTop = top + dy
  val movedRight = right + dx
  val movedBottom = bottom + dy

  return when (handle) {
    CropHandle.TopLeft -> NormalizedRect(movedLeft, movedTop, right, bottom)
    CropHandle.Top -> NormalizedRect(left, movedTop, right, bottom)
    CropHandle.TopRight -> NormalizedRect(left, movedTop, movedRight, bottom)
    CropHandle.Right -> NormalizedRect(left, top, movedRight, bottom)
    CropHandle.BottomRight -> NormalizedRect(left, top, movedRight, movedBottom)
    CropHandle.Bottom -> NormalizedRect(left, top, right, movedBottom)
    CropHandle.BottomLeft -> NormalizedRect(movedLeft, top, right, movedBottom)
    CropHandle.Left -> NormalizedRect(movedLeft, top, right, bottom)
    CropHandle.Body -> NormalizedRect(movedLeft, movedTop, movedRight, movedBottom)
  }
}

/**
 * Four bands covering everything in the canvas outside [view].
 */
private fun DrawScope.drawScrim(
  view: Rect,
  color: Color,
) {
  if (view.top > 0f) drawRect(color, Offset.Zero, ComposeSize(size.width, view.top))
  if (view.left > 0f) drawRect(color, Offset(0f, view.top), ComposeSize(view.left, view.height))
  if (view.right < size.width) {
    drawRect(color, Offset(view.right, view.top), ComposeSize(size.width - view.right, view.height))
  }
  if (view.bottom < size.height) {
    drawRect(color, Offset(0f, view.bottom), ComposeSize(size.width, size.height - view.bottom))
  }
}

/**
 * Two lines dividing [view] into thirds on each axis.
 */
private fun DrawScope.drawThirds(
  view: Rect,
  color: Color,
) {
  for (i in 1..2) {
    val x = view.left + view.width * i / 3f
    drawLine(color, Offset(x, view.top), Offset(x, view.bottom), strokeWidth = GRID_WIDTH)
    val y = view.top + view.height * i / 3f
    drawLine(color, Offset(view.left, y), Offset(view.right, y), strokeWidth = GRID_WIDTH)
  }
}

/**
 * A bracket at each corner of [view] and a grip at the middle of each edge.
 */
private fun DrawScope.drawHandles(
  view: Rect,
  length: Float,
  color: Color,
) {
  drawCorner(view.topLeft, Offset(1f, 1f), length, color)
  drawCorner(Offset(view.right, view.top), Offset(-1f, 1f), length, color)
  drawCorner(Offset(view.right, view.bottom), Offset(-1f, -1f), length, color)
  drawCorner(Offset(view.left, view.bottom), Offset(1f, -1f), length, color)

  val centerX = (view.left + view.right) / 2f
  val centerY = (view.top + view.bottom) / 2f
  drawGrip(Offset(centerX, view.top), Offset(length, 0f), color)
  drawGrip(Offset(centerX, view.bottom), Offset(length, 0f), color)
  drawGrip(Offset(view.left, centerY), Offset(0f, length), color)
  drawGrip(Offset(view.right, centerY), Offset(0f, length), color)
}

/**
 * A short line of [span] centred on [mid].
 */
private fun DrawScope.drawGrip(
  mid: Offset,
  span: Offset,
  color: Color,
) {
  drawLine(color, mid - span / 2f, mid + span / 2f, strokeWidth = HANDLE_WIDTH)
}

/**
 * Two arms of [length] meeting at [corner], pointing inward along [direction].
 */
private fun DrawScope.drawCorner(
  corner: Offset,
  direction: Offset,
  length: Float,
  color: Color,
) {
  drawLine(color, corner, corner + Offset(direction.x * length, 0f), strokeWidth = HANDLE_WIDTH)
  drawLine(color, corner, corner + Offset(0f, direction.y * length), strokeWidth = HANDLE_WIDTH)
}

@Preview
@Composable
private fun CropOverlayPreview() {
  PreviewSurface(height = PreviewCropHeight) {
    CropOverlay(
      rect = NormalizedRect(0.15f, 0.1f, 0.85f, 0.9f),
      onRectChange = { },
      output = Size(1920, 1080),
    )
  }
}

@Preview
@Composable
private fun CropOverlayFixedAspectPreview() {
  PreviewSurface(height = PreviewCropHeight) {
    CropOverlay(
      rect = NormalizedRect(0.2f, 0.1f, 0.8f, 0.7f),
      onRectChange = { },
      output = Size(1920, 1080),
      constraint = CropConstraint.FixedAspect(ratio = 1f, minWidth = 0.15f),
    )
  }
}

private val PreviewCropHeight = 300.dp
private const val OUTLINE_WIDTH = 1.5f
private const val GRID_WIDTH = 1f
private const val HANDLE_WIDTH = 3f
