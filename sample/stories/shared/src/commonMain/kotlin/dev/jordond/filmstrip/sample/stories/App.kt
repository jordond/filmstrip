package dev.jordond.filmstrip.sample.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.filekit.releasePicked
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.sample.stories.ui.MediaPicker
import dev.jordond.filmstrip.sample.stories.ui.PointerEditor
import dev.jordond.filmstrip.sample.stories.ui.StoryPreview
import dev.jordond.filmstrip.sample.stories.ui.TouchEditor
import dev.jordond.filmstrip.sample.ui.SampleTheme
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlin.time.Duration

/**
 * The story editor, one clip filling a 9:16 frame with text, stickers and a drawing laid over it.
 *
 * Every shell mounts this and nothing else.
 */
@Composable
public fun App() {
  SampleTheme {
    val filmstrip = remember { Filmstrip() }
    val editor = remember { StoryEditor() }

    Box(Modifier.fillMaxSize().background(Tokens.ground)) {
      when (val source = editor.source) {
        null -> MediaPicker { picked, fromPicker -> editor.open(picked, fromPicker) }
        else -> StoryScreen(filmstrip, editor, source)
      }
    }
  }
}

@Composable
private fun StoryScreen(
  filmstrip: Filmstrip,
  editor: StoryEditor,
  source: MediaSource,
) {
  // Declared ahead of the player, so it runs after the player has closed and nothing reads the clip any more.
  DisposableEffect(source) {
    val picked = editor.sourcePicked
    onDispose { if (picked) releasePicked(source) }
  }
  val look = editor.story.look
  val composition = remember(source, look) { storyComposition(source, look) }
  val player = rememberStoryPlayer(filmstrip, composition)
  val clock = rememberStoryClock(player)
  val scope = rememberCoroutineScope()
  val export = remember(filmstrip, scope) { StoryExport(filmstrip, scope) }
  val preview =
    remember(player, clock, composition, export) { StoryPreview(player, clock, filmstrip, composition, export) }

  when (remember { inputMode() }) {
    InputMode.Touch -> TouchEditor(editor, preview)
    InputMode.Pointer -> PointerEditor(editor, preview)
  }
}

/**
 * The player for a story, which loops the whole clip and plays from the moment it is made.
 */
@Composable
private fun rememberStoryPlayer(
  filmstrip: Filmstrip,
  composition: Composition,
): VideoPlayer {
  val player = rememberFilmstripPlayer(filmstrip, composition)
  return remember(player) {
    player.setLoopRange(TimeRange.from(Duration.ZERO))
    player.play()
    player
  }
}
