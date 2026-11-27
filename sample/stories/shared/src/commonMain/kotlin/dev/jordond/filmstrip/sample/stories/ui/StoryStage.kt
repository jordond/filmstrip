package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.ComposedOverlayHandles
import dev.jordond.filmstrip.compose.ui.OverlayHandleColors
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.sample.stories.BIN_CENTER
import dev.jordond.filmstrip.sample.stories.BIN_RADIUS
import dev.jordond.filmstrip.sample.stories.STORY_ASPECT
import dev.jordond.filmstrip.sample.stories.Snap
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryElement
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.stories.liveFrame
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * The story's picture with its elements over it, the guides a held element snaps to and the bin it can be dropped on.
 *
 * @param editor The session being edited.
 * @param player Plays the clip under the elements, or null to draw the elements over an empty stage.
 * @param output The frame the elements are baked onto, in pixels.
 * @param modifier Modifier for the stage.
 * @param handles When the selected element shows the handles a mouse sizes and turns it with.
 */
@Composable
internal fun StoryStage(
  editor: StoryEditor,
  player: VideoPlayer?,
  output: Size,
  modifier: Modifier = Modifier,
  handles: ComposedOverlayHandles = ComposedOverlayHandles.Auto,
) {
  VideoStage(player = player, outputAspect = STORY_ASPECT.value, modifier = modifier.testTag(StoryTags.STAGE)) {
    Box(
      Modifier
        .matchParentSize()
        .pointerInput(editor) {
          detectTapGestures { if (editor.story.selectedId == null) editor.openText() else editor.story.select(null) }
        }.lookSwipe(editor.story),
    )
    StoryDrawing(editor, output)
    StoryElements(editor, output, handles)
    when (editor.tool) {
      StoryTool.Text -> TextModeField(editor, output)
      StoryTool.Draw -> DrawInput(editor)
      else -> Unit
    }
    editor.guides?.let { StoryGuides(it, frame.contentRect) }
    if (editor.gesturing) StoryBin(editor.overBin, frame.contentRect)
    LookName(editor.story.look)
  }
}

@Composable
private fun VideoStageScope.StoryElements(
  editor: StoryEditor,
  output: Size,
  handles: ComposedOverlayHandles,
) {
  val story = editor.story
  val colors = storyHandleColors()
  val writing = editor.draft?.id
  story.elements.filter { it.id != writing }.forEach { element ->
    key(element.id) { StoryElementOnStage(editor, element, output, handles, colors) }
  }
}

@Composable
private fun VideoStageScope.StoryElementOnStage(
  editor: StoryEditor,
  element: StoryElement,
  output: Size,
  handles: ComposedOverlayHandles,
  colors: OverlayHandleColors,
) {
  val picture = frame.contentRect
  val selected = editor.story.selectedId == element.id
  // Only an element that the playhead can change reads it, so the rest are not redrawn as the clip plays.
  val playhead = if (element.window == null && element.animation == null) Duration.ZERO else editor.playhead
  val drawn = liveFrame(element, playhead, editor.duration, selected) ?: return
  ComposedOverlay(
    transform = element.transform,
    canvas = editor.canvas,
    output = output,
    onTransformChange = { editor.moved(element.id, it) },
    modifier = Modifier.drawnAs(drawn, element.transform.center.on(picture), picture),
    selected = selected,
    onSelect = { editor.pressed(element.id) },
    onGestureChange = { editor.gesture(element.id, it) },
    colors = colors,
    enabled = editor.tool == StoryTool.None,
    handles = handles,
  ) {
    Box(Modifier.alpha(if (selected && editor.overBin) OVER_BIN_ALPHA else 1f)) { StoryElementContent(element) }
  }
}

@Composable
private fun VideoStageScope.StoryGuides(
  snap: Snap,
  picture: Rect,
) {
  val center = snap.transform.center.on(picture)
  if (snap.vertical) {
    Guide(StoryTags.GUIDE_VERTICAL, Offset(picture.center.x, picture.top), Offset(picture.center.x, picture.bottom))
  }
  if (snap.horizontal) {
    Guide(StoryTags.GUIDE_HORIZONTAL, Offset(picture.left, picture.center.y), Offset(picture.right, picture.center.y))
  }
  if (snap.level) {
    val reach = picture.width * LEVEL_REACH
    Guide(StoryTags.GUIDE_LEVEL, center - Offset(reach, 0f), center + Offset(reach, 0f))
  }
}

@Composable
private fun VideoStageScope.Guide(
  tag: String,
  from: Offset,
  to: Offset,
) {
  Canvas(Modifier.matchParentSize().testTag(tag)) {
    val dash = GUIDE_DASH.toPx()
    drawLine(
      color = Tokens.amber,
      start = from,
      end = to,
      strokeWidth = GUIDE_WIDTH.toPx(),
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
    )
  }
}

@Composable
private fun StoryBin(
  over: Boolean,
  picture: Rect,
) {
  val grow by animateFloatAsState(if (over) BIN_GROWN else 1f)
  val diameter = picture.width * BIN_RADIUS * 2
  val center = BIN_CENTER.on(picture)
  Box(
    Modifier
      .offset { IntOffset((center.x - diameter / 2).roundToInt(), (center.y - diameter / 2).roundToInt()) }
      .size(with(LocalDensity.current) { diameter.toDp() })
      .graphicsLayer {
        scaleX = grow
        scaleY = grow
      }.background(if (over) Tokens.red else Tokens.backdrop, CircleShape)
      .testTag(StoryTags.BIN),
  ) {
    Canvas(Modifier.matchParentSize()) { drawBin(Color.White) }
  }
}

// Applies an animation's frame to an element's layer: its opacity, its offset as a fraction of the picture and its
// scale about the element's own centre.
private fun Modifier.drawnAs(
  frame: OverlayFrame,
  center: Offset,
  picture: Rect,
): Modifier =
  if (frame == OverlayFrame.Identity) {
    this
  } else {
    graphicsLayer {
      alpha = frame.opacity
      translationX = frame.offset.x * picture.width
      translationY = frame.offset.y * picture.height
      scaleX = frame.scale
      scaleY = frame.scale
      if (size.width > 0f && size.height > 0f) {
        transformOrigin = TransformOrigin(center.x / size.width, center.y / size.height)
      }
    }
  }

private fun Anchor.on(picture: Rect): Offset =
  Offset(picture.left + x * picture.width, picture.top + y * picture.height)

private val GUIDE_WIDTH = 1.dp
private val GUIDE_DASH = 6.dp
private const val LEVEL_REACH = 0.25f
private const val BIN_GROWN = 1.3f
private const val OVER_BIN_ALPHA = 0.5f
