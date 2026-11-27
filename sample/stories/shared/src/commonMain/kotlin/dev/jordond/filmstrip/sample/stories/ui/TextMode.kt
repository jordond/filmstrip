package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.sample.SIZE_STEPS
import dev.jordond.filmstrip.sample.scaleOfStep
import dev.jordond.filmstrip.sample.sizeStepOf
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryFace
import dev.jordond.filmstrip.sample.stories.next
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * The caption being written, over a dimmed picture.
 *
 * The field is an element on the stage like any placed caption, laid out on the same canvas, so its lines break
 * where they will once it is placed. A tap on the dimmed picture is Done.
 */
@Composable
internal fun VideoStageScope.TextModeField(
  editor: StoryEditor,
  output: Size,
) {
  val draft = editor.draft ?: return
  val focus = remember { FocusRequester() }
  Box(
    Modifier.matchParentSize().background(Scrim).pointerInput(editor) { detectTapGestures { editor.closeText() } },
  )
  ComposedOverlay(
    transform = OverlayTransform(WRITING_AT, draft.scale),
    canvas = editor.canvas,
    output = output,
    onTransformChange = { },
    enabled = false,
  ) {
    BasicTextField(
      value = draft.caption.text,
      onValueChange = { text -> editor.editDraft { it.copy(text = text) } },
      textStyle = captionStyle(draft.caption),
      cursorBrush = SolidColor(Tokens.amber),
      modifier =
        Modifier
          .captionPlate(draft.caption)
          .widthIn(min = FIELD_MIN)
          .focusRequester(focus)
          .testTag(StoryTags.TEXT_FIELD),
    )
  }
  LaunchedEffect(focus) { focus.requestFocus() }
}

/**
 * Text mode's controls around the caption: Done, the align, plate and entrance buttons, the size slider on the left
 * edge, and the rows of faces and colours along the bottom, above the keyboard.
 */
@Composable
internal fun TextModeControls(
  editor: StoryEditor,
  modifier: Modifier = Modifier,
) {
  val draft = editor.draft ?: return
  val caption = draft.caption
  Box(modifier.fillMaxSize().imePadding().padding(EDGE)) {
    Row(Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(GAP)) {
      Pill(caption.align.name, Modifier.testTag(StoryTags.TEXT_ALIGN)) {
        editor.editDraft { it.copy(align = it.align.next()) }
      }
      Pill("Plate ${caption.plate.name}", Modifier.testTag(StoryTags.TEXT_PLATE)) {
        editor.editDraft { it.copy(plate = it.plate.next()) }
      }
      Pill(caption.entrance.name, Modifier.testTag(StoryTags.TEXT_ENTRANCE)) {
        editor.editDraft { it.copy(entrance = it.entrance.next()) }
      }
    }
    Pill(
      label = "Done",
      modifier = Modifier.align(Alignment.TopEnd).testTag(StoryTags.TEXT_DONE),
      selected = true,
      onClick = editor::closeText,
    )
    StepSlider(
      step = sizeStepOf(draft.scale),
      steps = SIZE_STEPS,
      label = "Size",
      modifier = Modifier.align(Alignment.CenterStart).testTag(StoryTags.TEXT_SIZE),
    ) { step -> editor.draft = editor.draft?.copy(scale = scaleOfStep(step)) }
    Column(Modifier.align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(GAP)) {
      FaceRow(caption) { face -> editor.editDraft { it.copy(face = face) } }
      ColorRow(caption.color) { color -> editor.editDraft { it.copy(color = color) } }
    }
  }
}

@Composable
private fun FaceRow(
  caption: StoryContent.Caption,
  onPick: (StoryFace) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterHorizontally),
  ) {
    StoryFace.entries.forEach { face ->
      val picked = face == caption.face
      Text(
        text = "Aa",
        style = captionStyle(StoryContent.Caption("", face = face)),
        modifier =
          Modifier
            .clip(CircleShape)
            .background(Tokens.backdrop)
            .border(1.dp, if (picked) Tokens.amber else Color.Transparent, CircleShape)
            .clickable(role = Role.Button) { onPick(face) }
            .semantics { contentDescription = "Face ${face.name}" }
            .padding(horizontal = 14.dp, vertical = 4.dp),
      )
    }
  }
}

/**
 * A row of colour dots, the picked one ringed.
 *
 * @param picked The colour in use, as ARGB.
 * @param onPick Called with the colour of the dot pressed.
 */
@Composable
internal fun ColorRow(
  picked: Int,
  modifier: Modifier = Modifier,
  onPick: (Int) -> Unit,
) {
  Row(
    modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterHorizontally),
  ) {
    StoryContent.PALETTE.forEach { color ->
      Box(
        Modifier
          .size(DOT)
          .clip(CircleShape)
          .background(Color(color))
          .border(DOT_RING, if (color == picked) Tokens.amber else Color.White, CircleShape)
          .clickable(role = Role.Button) { onPick(color) }
          .semantics { contentDescription = "Colour ${color.toUInt().toString(HEX)}" },
      )
    }
  }
}

/**
 * A vertical slider over [steps], the top the largest. A press anywhere on it jumps there and a drag follows.
 *
 * @param step Where the slider sits.
 * @param steps The range it covers.
 * @param label What it sets, read out by a screen reader.
 * @param onStep Called with the step under the finger.
 */
@Composable
internal fun StepSlider(
  step: Float,
  steps: ClosedFloatingPointRange<Float>,
  label: String,
  modifier: Modifier = Modifier,
  onStep: (Float) -> Unit,
) {
  val currentOnStep by rememberUpdatedState(onStep)
  val span = steps.endInclusive - steps.start
  Canvas(
    modifier
      .width(Tokens.sizeTouch)
      .height(SLIDER_HEIGHT)
      .semantics { contentDescription = label }
      .pointerInput(steps) {
        fun report(y: Float) {
          val share = 1f - (y / size.height).coerceIn(0f, 1f)
          currentOnStep(steps.start + share * span)
        }
        awaitEachGesture {
          val down = awaitFirstDown()
          down.consume()
          report(down.position.y)
          drag(down.id) { change ->
            change.consume()
            report(change.position.y)
          }
        }
      },
  ) {
    val x = size.width / 2
    val share = ((step - steps.start) / span).coerceIn(0f, 1f)
    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = SLIDER_TRACK.toPx())
    drawCircle(Tokens.amber, SLIDER_KNOB.toPx(), Offset(x, size.height * (1f - share)))
  }
}

/**
 * A pill button floating over the picture.
 *
 * @param label What the button says.
 * @param selected Whether it is drawn filled, as the one that stands out.
 * @param onClick Called on a press.
 */
@Composable
internal fun Pill(
  label: String,
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  onClick: () -> Unit,
) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelMedium,
    color = if (selected) Tokens.ground else Color.White,
    modifier =
      modifier
        .clip(CircleShape)
        .background(if (selected) Tokens.amber else Tokens.backdrop)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

private val Scrim = Color(0x99000000)
private val WRITING_AT = Anchor(0.5f, 0.38f)
private val FIELD_MIN = 24.dp
private val EDGE = 12.dp
private val GAP = 8.dp
private val DOT = 28.dp
private val DOT_RING = 2.dp
private val SLIDER_HEIGHT = 220.dp
private val SLIDER_TRACK = 2.dp
private val SLIDER_KNOB = 9.dp
private const val HEX = 16
