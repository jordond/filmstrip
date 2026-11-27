package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.stories.ExportPhase
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.stories.bakeStory
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlin.math.roundToInt

/**
 * The share sheet: starts the export, shows how far along it is, and says where it ended, saved or refused.
 *
 * Closing it stops an export that is still running.
 *
 * @param editor The session being exported.
 * @param preview Holds the edit and the export. With none the sheet has nothing to export and draws nothing.
 */
@Composable
internal fun ExportSheet(
  editor: StoryEditor,
  preview: StoryPreview?,
  modifier: Modifier = Modifier,
) {
  val export = preview?.export ?: return
  val close = {
    export.close()
    editor.tool = StoryTool.None
  }
  Box(
    modifier.fillMaxSize().background(Scrim).pointerInput(export) { detectTapGestures { close() } },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Column(
      modifier =
        Modifier
          .widthIn(max = SHEET_WIDTH)
          .fillMaxWidth()
          .clip(SheetShape)
          .background(Tokens.panelRaised)
          .pointerInput(Unit) { detectTapGestures { } }
          .padding(16.dp)
          .testTag(StoryTags.EXPORT_SHEET),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val phase = export.phase
      Text("Share", style = MaterialTheme.typography.headlineSmall, color = Tokens.text)
      Text(
        text = exportLine(phase),
        style = MaterialTheme.typography.bodyMedium,
        color = if (phase is ExportPhase.Failed) Tokens.red else Tokens.muted,
      )
      if (phase is ExportPhase.Running) ProgressBar(phase.fraction)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (phase is ExportPhase.Idle || phase is ExportPhase.Failed) {
          Pill("Export", selected = true) {
            preview.player.pause()
            export.start(preview.composition) { output ->
              val capturer = checkNotNull(editor.capturer) { "The story needs its capture host mounted to export." }
              bakeStory(capturer, editor.story.elements, editor.story.strokes, editor.canvas, output)
            }
          }
        }
        Pill(if (phase is ExportPhase.Done) "Done" else "Cancel", onClick = close)
      }
    }
  }
}

/**
 * What the sheet says for [phase].
 */
internal fun exportLine(phase: ExportPhase): String =
  when (phase) {
    ExportPhase.Idle -> "The text, stickers and drawing are burned into the video."
    ExportPhase.Preparing -> "Getting the story ready"
    is ExportPhase.Running -> "Exporting, ${(phase.fraction.coerceIn(0f, 1f) * PERCENT).roundToInt()}%"
    is ExportPhase.Done -> "Saved."
    is ExportPhase.Failed -> phase.message
  }

@Composable
private fun ProgressBar(fraction: Float) {
  Box(Modifier.fillMaxWidth().height(BAR).background(Tokens.line)) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(BAR).background(Tokens.amber))
  }
}

private val Scrim = Color(0x99000000)
private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val SHEET_WIDTH = 520.dp
private val BAR = 6.dp
private const val PERCENT = 100
