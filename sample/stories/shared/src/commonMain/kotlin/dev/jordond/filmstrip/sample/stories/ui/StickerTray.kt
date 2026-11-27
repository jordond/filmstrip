package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryStickerKind
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.stories.stickersMatching
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * The sticker tray, a sheet rising from the bottom with a filter field over a grid of every sticker.
 *
 * A press on a sticker drops it at the centre of the picture, selected, and closes the tray. A press outside the
 * sheet closes it with nothing added.
 */
@Composable
internal fun StickerTray(
  editor: StoryEditor,
  modifier: Modifier = Modifier,
) {
  var query by remember { mutableStateOf("") }
  Box(
    modifier.fillMaxSize().pointerInput(editor) { detectTapGestures { editor.tool = StoryTool.None } },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Column(
      modifier =
        Modifier
          .widthIn(max = SHEET_WIDTH)
          .fillMaxWidth()
          .fillMaxHeight(SHEET_SHARE)
          .imePadding()
          .clip(SheetShape)
          .background(Tokens.panelRaised)
          .pointerInput(Unit) { detectTapGestures { } }
          .padding(12.dp)
          .testTag(StoryTags.TRAY),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      FilterField(query) { query = it }
      LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(stickersMatching(query), key = { it.name }) { kind ->
          StickerCell(kind) {
            editor.story.add(StoryContent.Sticker(kind))
            editor.tool = StoryTool.None
          }
        }
      }
    }
  }
}

/**
 * What a screen reader calls the tray's cell for [kind].
 */
internal fun stickerLabel(kind: StoryStickerKind): String = "Sticker ${kind.label}"

@Composable
private fun FilterField(
  query: String,
  onChange: (String) -> Unit,
) {
  Box(Modifier.fillMaxWidth().background(Tokens.ground, FieldShape).padding(horizontal = 14.dp, vertical = 10.dp)) {
    if (query.isEmpty()) Text("Search stickers", style = MaterialTheme.typography.bodyMedium, color = Tokens.muted)
    BasicTextField(
      value = query,
      onValueChange = onChange,
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tokens.text),
      cursorBrush = SolidColor(Tokens.amber),
      modifier = Modifier.fillMaxWidth().testTag(StoryTags.TRAY_FILTER),
    )
  }
}

@Composable
private fun StickerCell(
  kind: StoryStickerKind,
  onPick: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .clip(FieldShape)
        .clickable(role = Role.Button, onClick = onPick)
        .semantics { contentDescription = stickerLabel(kind) }
        .padding(6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(Modifier.height(CELL_ART), contentAlignment = Alignment.Center) {
      Box(
        Modifier.graphicsLayer {
          scaleX = CELL_SCALE
          scaleY = CELL_SCALE
        },
      ) { StickerContent(StoryContent.Sticker(kind)) }
    }
    Text(kind.label, style = MaterialTheme.typography.bodySmall, color = Tokens.muted)
  }
}

private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val FieldShape = RoundedCornerShape(10.dp)
private val SHEET_WIDTH = 520.dp
private val CELL = 88.dp
private val CELL_ART = 64.dp
private const val SHEET_SHARE = 0.6f
private const val CELL_SCALE = 0.75f
