package dev.jordond.filmstrip.sample.stories

/**
 * The buttons both shells show, floating over the picture on a phone and down the rail on a desktop.
 *
 * @property label What a screen reader calls the button, the same in both shells.
 * @property title The word the rail writes beside it.
 * @property shortcut The key that does the same, or null when there is none.
 */
internal enum class StoryToolButton(
  val label: String,
  val title: String,
  val shortcut: StoryShortcut? = null,
) {
  ChangeClip("Change clip", "Clip"),
  Undo("Undo", "Undo", StoryShortcut.Undo),
  Redo("Redo", "Redo", StoryShortcut.Redo),
  Text("Aa", "Text", StoryShortcut.Text),
  Stickers("Stickers", "Stickers", StoryShortcut.Stickers),
  Draw("Draw", "Draw", StoryShortcut.Draw),
  Share("Share", "Share"),
}

/**
 * Whether [button] can be pressed now. Undo and Redo wait for something to step to.
 */
internal fun StoryEditor.allows(button: StoryToolButton): Boolean =
  when (button) {
    StoryToolButton.Undo -> story.canUndo
    StoryToolButton.Redo -> story.canRedo
    else -> true
  }

/**
 * Does what [button] is for.
 */
internal fun StoryEditor.press(button: StoryToolButton) {
  when (button) {
    StoryToolButton.ChangeClip -> open(null)
    StoryToolButton.Undo -> story.undo()
    StoryToolButton.Redo -> story.redo()
    StoryToolButton.Text -> openText()
    StoryToolButton.Stickers -> tool = StoryTool.Stickers
    StoryToolButton.Draw -> openDraw()
    StoryToolButton.Share -> tool = StoryTool.Export
  }
}
