package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.ComposedOverlayHandles
import dev.jordond.filmstrip.compose.ui.interaction.composedOverlayKeys
import dev.jordond.filmstrip.sample.stories.ELEMENT_BINDINGS
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryShortcut
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.stories.StoryToolButton
import dev.jordond.filmstrip.sample.stories.allows
import dev.jordond.filmstrip.sample.stories.press
import dev.jordond.filmstrip.sample.stories.storyKeys
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * The editor as a desktop lays it out: a rail of tools on the left, the picture in the middle, an inspector for the
 * selected element on the right and a legend of every key and mouse binding along the bottom.
 *
 * The picture holds the keyboard whenever no tool is open, so the bindings in the legend always reach it. The
 * selected element shows its size and turn handles whatever is pointing at it.
 *
 * @param editor The session being edited.
 * @param preview What plays the clip and draws its frames, or null to show the elements over an empty stage.
 * @param modifier Modifier for the editor.
 */
@Composable
internal fun PointerEditor(
  editor: StoryEditor,
  preview: StoryPreview?,
  modifier: Modifier = Modifier,
) {
  val focus = remember { FocusRequester() }
  val selected = editor.story.selected
  val idle = editor.tool == StoryTool.None
  val moving = idle && selected != null
  Column(modifier.fillMaxSize().background(Tokens.ground)) {
    Row(Modifier.weight(1f).fillMaxWidth()) {
      ToolRail(editor)
      Box(
        modifier =
          Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(Tokens.screen)
            .pointerInput(focus) {
              awaitPointerEventScope {
                while (true) {
                  val event = awaitPointerEvent(PointerEventPass.Initial)
                  if (event.type == PointerEventType.Press && editor.tool == StoryTool.None) focus.requestFocus()
                }
              }
            }.focusRequester(focus)
            .storyKeys(editor) { preview?.togglePlay() }
            .composedOverlayKeys(
              transform = selected?.transform ?: OverlayTransform(),
              onTransformChange = { next -> selected?.let { editor.moved(it.id, next) } },
              enabled = moving,
            )
            // The element keys bring their own focus target, so the picture needs one only while they are off.
            .then(if (moving) Modifier else Modifier.focusable())
            .testTag(StoryTags.POINTER_STAGE),
        contentAlignment = Alignment.Center,
      ) {
        Box(Modifier.fillMaxSize().padding(STAGE_INSET), contentAlignment = Alignment.Center) {
          StoryStage(editor, preview?.player, handles = ComposedOverlayHandles.Always)
        }
        when (editor.tool) {
          StoryTool.Text -> TextModeControls(editor)
          StoryTool.Stickers -> StickerTray(editor)
          StoryTool.Draw -> DrawModeControls(editor)
          StoryTool.Export -> ExportSheet(editor, preview)
          StoryTool.None -> Unit
        }
      }
      Inspector(editor, preview)
    }
    Legend()
  }
  LaunchedEffect(editor.story.selectedId, idle) { if (idle) focus.requestFocus() }
}

@Composable
private fun ToolRail(editor: StoryEditor) {
  Column(
    modifier =
      Modifier
        .width(RAIL_WIDTH)
        .fillMaxHeight()
        .background(Tokens.panel)
        .padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    StoryToolButton.entries.forEach { button ->
      val enabled = editor.allows(button)
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .border(1.dp, Tokens.line)
            .clickable(enabled = enabled, role = Role.Button) { editor.press(button) }
            .semantics { contentDescription = button.label }
            .padding(horizontal = 10.dp, vertical = 8.dp),
      ) {
        Text(button.title, style = MaterialTheme.typography.labelMedium, color = Tokens.text)
        button.shortcut?.let { Text(it.keys, style = MaterialTheme.typography.bodySmall, color = Tokens.amber) }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
  FlowRow(
    modifier = Modifier.fillMaxWidth().background(Tokens.groundDeep).padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    StoryShortcut.entries.forEach { LegendLine(it.keys, it.does, Modifier.testTag(StoryTags.legend(it.name))) }
    ELEMENT_BINDINGS.forEach { LegendLine(it.keys, it.does, Modifier.testTag(StoryTags.legend(it.keys))) }
  }
}

@Composable
private fun LegendLine(
  keys: String,
  does: String,
  modifier: Modifier,
) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(keys, style = MaterialTheme.typography.bodySmall, color = Tokens.amber)
    Text(does, style = MaterialTheme.typography.bodySmall, color = Tokens.muted)
  }
}

private val RAIL_WIDTH = 116.dp
private val STAGE_INSET = 20.dp
private const val DISABLED_ALPHA = 0.4f
