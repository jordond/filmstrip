package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.stories.StoryToolButton
import dev.jordond.filmstrip.sample.stories.allows
import dev.jordond.filmstrip.sample.stories.press
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * The editor as a phone lays it out: the picture fills the screen and every tool floats over it.
 *
 * A row of round buttons sits along the top and the share button at the bottom. Both hide while an element is held,
 * so nothing sits between the finger and the picture.
 *
 * @param editor The session being edited.
 * @param preview What plays the clip and draws its frames, or null to show the elements over an empty stage.
 * @param output The frame the story is exported at, in pixels.
 * @param modifier Modifier for the editor.
 */
@Composable
internal fun TouchEditor(
  editor: StoryEditor,
  preview: StoryPreview?,
  output: Size,
  modifier: Modifier = Modifier,
) {
  Box(modifier.fillMaxSize().background(Tokens.screen), contentAlignment = Alignment.Center) {
    StoryStage(editor, preview?.player, output)
    when {
      editor.tool == StoryTool.Text -> TextModeControls(editor)
      editor.tool == StoryTool.Stickers -> StickerTray(editor)
      editor.tool == StoryTool.Draw -> DrawModeControls(editor)
      editor.tool == StoryTool.Export -> ExportSheet(editor, preview)
      !editor.gesturing && editor.tool == StoryTool.None -> TouchChrome(editor, preview)
    }
  }
}

@Composable
private fun TouchChrome(
  editor: StoryEditor,
  preview: StoryPreview?,
) {
  val story = editor.story
  Box(Modifier.fillMaxSize().padding(EDGE).testTag(StoryTags.CHROME)) {
    Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(GAP)) {
      listOf(StoryToolButton.ChangeClip, StoryToolButton.Undo, StoryToolButton.Redo).forEach { ToolButton(editor, it) }
    }
    Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(GAP)) {
      listOf(StoryToolButton.Text, StoryToolButton.Stickers, StoryToolButton.Draw).forEach { ToolButton(editor, it) }
    }
    Column(Modifier.align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(GAP)) {
      if (preview != null && story.selected != null) TimingBar(editor, preview.renderer, preview.composition)
      Pill(
        label = StoryToolButton.Share.title,
        modifier = Modifier.align(Alignment.End).semantics { contentDescription = StoryToolButton.Share.label },
        selected = true,
      ) { editor.press(StoryToolButton.Share) }
    }
  }
}

@Composable
private fun ToolButton(
  editor: StoryEditor,
  button: StoryToolButton,
) {
  RoundButton(button.label, { editor.press(button) }, icon = button.icon(), enabled = editor.allows(button))
}

/**
 * The icon a button is drawn with, or null for one that writes its label.
 */
internal fun StoryToolButton.icon(): StoryIcon? =
  when (this) {
    StoryToolButton.ChangeClip -> StoryIcon.Close
    StoryToolButton.Undo -> StoryIcon.Undo
    StoryToolButton.Redo -> StoryIcon.Redo
    StoryToolButton.Stickers -> StoryIcon.Sticker
    StoryToolButton.Draw -> StoryIcon.Draw
    StoryToolButton.Text, StoryToolButton.Share -> null
  }

private val EDGE = 12.dp
private val GAP = 8.dp
