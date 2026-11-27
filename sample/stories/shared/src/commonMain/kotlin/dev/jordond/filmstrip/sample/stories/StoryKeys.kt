package dev.jordond.filmstrip.sample.stories

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import kotlin.math.roundToInt

/**
 * The editor's own key bindings. The keys that move, size and turn the selected element are the library's and are
 * listed by [ELEMENT_BINDINGS].
 *
 * @property keys The keys as the legend writes them.
 * @property does What the binding does, as the legend writes it.
 */
internal enum class StoryShortcut(
  val keys: String,
  val does: String,
) {
  Text("T", "Add text"),
  Stickers("S", "Stickers"),
  Draw("D", "Draw"),
  Delete("Delete", "Remove the selection"),
  Escape("Esc", "Close the tool or clear the selection"),
  Undo("Ctrl Z", "Undo"),
  Redo("Ctrl Shift Z", "Redo"),
  PreviousLook(",", "Previous look"),
  NextLook(".", "Next look"),
  Play("Space", "Play or pause"),
}

/**
 * One line of the legend for an input the library handles.
 *
 * @property keys The keys or the mouse action, as the legend writes it.
 * @property does What it does to the selected element.
 */
internal class ElementBinding(
  val keys: String,
  val does: String,
)

/**
 * What the library's mouse and keyboard handling does to the selected element, with the step sizes it uses.
 */
internal val ELEMENT_BINDINGS: List<ElementBinding> =
  listOf(
    ElementBinding(
      "Arrows",
      "Move ${percent(ComposedOverlayDefaults.NUDGE)}, Shift ${percent(ComposedOverlayDefaults.COARSE_NUDGE)}",
    ),
    ElementBinding("+ -", "Size by ${percent(ComposedOverlayDefaults.WHEEL_STEP - 1f)}"),
    ElementBinding(
      "[ ]",
      "Turn ${degrees(
        ComposedOverlayDefaults.KEY_TURN_STEP,
      )}, Shift ${degrees(ComposedOverlayDefaults.COARSE_KEY_TURN_STEP)}",
    ),
    ElementBinding("Wheel", "Size by ${percent(ComposedOverlayDefaults.WHEEL_STEP - 1f)} a notch"),
    ElementBinding("Shift wheel", "Turn ${degrees(ComposedOverlayDefaults.TURN_STEP)} a notch"),
    ElementBinding("Corner handle", "Drag to size"),
    ElementBinding("Top handle", "Drag to turn"),
  )

/**
 * The binding [event] presses, or null when it is not one of the editor's. Only a key going down counts, and Cmd
 * stands in for Ctrl.
 */
internal fun shortcutOf(event: KeyEvent): StoryShortcut? {
  if (event.type != KeyEventType.KeyDown) return null
  val command = event.isCtrlPressed || event.isMetaPressed
  return when (event.key) {
    Key.Z -> {
      if (!command) {
        null
      } else if (event.isShiftPressed) {
        StoryShortcut.Redo
      } else {
        StoryShortcut.Undo
      }
    }
    Key.T -> {
      StoryShortcut.Text.takeUnless { command }
    }
    Key.S -> {
      StoryShortcut.Stickers.takeUnless { command }
    }
    Key.D -> {
      StoryShortcut.Draw.takeUnless { command }
    }
    Key.Delete, Key.Backspace -> {
      StoryShortcut.Delete
    }
    Key.Escape -> {
      StoryShortcut.Escape
    }
    Key.Comma -> {
      StoryShortcut.PreviousLook
    }
    Key.Period -> {
      StoryShortcut.NextLook
    }
    Key.Spacebar -> {
      StoryShortcut.Play
    }
    else -> {
      null
    }
  }
}

/**
 * Does what [shortcut] is bound to. With a tool open only Escape acts, so typing a caption never fires a binding.
 *
 * @param togglePlay Plays or pauses the preview.
 * @return Whether the binding did anything, which is whether the key press is spent.
 */
internal fun StoryEditor.perform(
  shortcut: StoryShortcut,
  togglePlay: () -> Unit,
): Boolean {
  if (shortcut == StoryShortcut.Escape) {
    when (tool) {
      StoryTool.None -> story.select(null)
      StoryTool.Text -> closeText()
      StoryTool.Draw -> closeDraw()
      else -> tool = StoryTool.None
    }
    return true
  }
  if (tool != StoryTool.None) return false
  when (shortcut) {
    StoryShortcut.Text -> openText()
    StoryShortcut.Stickers -> tool = StoryTool.Stickers
    StoryShortcut.Draw -> openDraw()
    StoryShortcut.Delete -> story.selectedId?.let(story::remove) ?: return false
    StoryShortcut.Undo -> story.undo()
    StoryShortcut.Redo -> story.redo()
    StoryShortcut.PreviousLook -> story.look(story.look.previous())
    StoryShortcut.NextLook -> story.look(story.look.next())
    StoryShortcut.Play -> togglePlay()
    StoryShortcut.Escape -> Unit
  }
  return true
}

/**
 * Reads the editor's key bindings off whatever holds the keyboard under this modifier.
 *
 * @param togglePlay Plays or pauses the preview.
 */
internal fun Modifier.storyKeys(
  editor: StoryEditor,
  togglePlay: () -> Unit,
): Modifier = onKeyEvent { event -> shortcutOf(event)?.let { editor.perform(it, togglePlay) } ?: false }

private fun percent(share: Float): String {
  val tenths = (share * PERCENT * TENTHS).roundToInt()
  return if (tenths % TENTHS == 0) "${tenths / TENTHS}%" else "${tenths / TENTHS}.${tenths % TENTHS}%"
}

private fun degrees(turn: Float): String = "${turn.roundToInt()}°"

private const val PERCENT = 100
private const val TENTHS = 10
