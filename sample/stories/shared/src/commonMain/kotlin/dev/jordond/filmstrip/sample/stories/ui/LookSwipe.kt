package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.stories.StoryLook
import dev.jordond.filmstrip.sample.stories.StoryState
import dev.jordond.filmstrip.sample.stories.next
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Steps the story's look on a sideways swipe: to the next one on a swipe towards the start edge, to the previous one
 * the other way. It sits on the bare picture, under the elements, so a swipe that starts on an element moves the
 * element instead.
 */
internal fun Modifier.lookSwipe(story: StoryState): Modifier =
  pointerInput(story) {
    var travelled = 0f
    detectHorizontalDragGestures(
      onDragStart = { travelled = 0f },
      onDragEnd = {
        if (abs(travelled) >= SWIPE.toPx()) story.look(if (travelled < 0f) story.look.next() else story.look.previous())
      },
    ) { change, dragged ->
      change.consume()
      travelled += dragged
    }
  }

/**
 * The name of [look], shown in the middle of the picture for a moment after it changes.
 */
@Composable
internal fun BoxScope.LookName(look: StoryLook) {
  var shown by remember { mutableStateOf<StoryLook?>(null) }
  val opened = remember { look }
  var changed by remember { mutableStateOf(false) }
  LaunchedEffect(look) {
    if (!changed && look == opened) return@LaunchedEffect
    changed = true
    shown = look
    delay(SHOWN)
    shown = null
  }
  shown?.let {
    Text(
      text = it.label,
      style = MaterialTheme.typography.labelLarge,
      color = Color.White,
      modifier =
        Modifier
          .align(Alignment.Center)
          .background(Tokens.backdrop, CircleShape)
          .padding(horizontal = 18.dp, vertical = 8.dp)
          .testTag(StoryTags.LOOK_NAME),
    )
  }
}

private val SWIPE = 48.dp
private val SHOWN = 900.milliseconds
