package dev.jordond.filmstrip.sample.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.overlay.OverlayCaptureHost
import dev.jordond.filmstrip.compose.overlay.rememberOverlayCapturer
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.filekit.releasePicked
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.stories.ui.MediaPicker
import dev.jordond.filmstrip.sample.stories.ui.PointerEditor
import dev.jordond.filmstrip.sample.stories.ui.StoryPreview
import dev.jordond.filmstrip.sample.stories.ui.TouchEditor
import dev.jordond.filmstrip.sample.ui.SampleTheme
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
    val capturer = rememberOverlayCapturer()
    OverlayCaptureHost(capturer)
    DisposableEffect(editor, capturer) {
      editor.capturer = capturer
      onDispose { editor.capturer = null }
    }

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
  val player = rememberFilmstripPlayer(filmstrip, composition)
  val info by player.previewInfo.collectAsState()
  val output = info.outputSize.takeIf { it.width > 0 && it.height > 0 } ?: FALLBACK_OUTPUT

  val state by player.state.collectAsState()
  val scope = rememberCoroutineScope()
  val export = remember(filmstrip, scope) { StoryExport(filmstrip, scope) }
  val preview = remember(player, composition, export) { StoryPreview(player, filmstrip, composition, export) }

  LaunchedEffect(player) {
    player.setLoopRange(TimeRange.from(Duration.ZERO))
    player.play()
  }
  LaunchedEffect(player) { player.positionFlow(PLAYHEAD_TICK).collect { editor.playhead = it } }
  LaunchedEffect(state) {
    editor.duration = state.duration ?: Duration.ZERO
    editor.playing = state.isPlaying
  }

  when (remember { inputMode() }) {
    InputMode.Touch -> TouchEditor(editor, preview, output)
    InputMode.Pointer -> PointerEditor(editor, preview, output)
  }
}

private val FALLBACK_OUTPUT = Size(1080, STORY_MAX_HEIGHT)
private val PLAYHEAD_TICK = 33.milliseconds
