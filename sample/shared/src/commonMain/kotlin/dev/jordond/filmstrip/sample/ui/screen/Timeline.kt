package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.asClock
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * The strip under the viewport: the whole source laid out end to end, with the trim window over it
 * and the playhead in it.
 *
 * The tiles are stand-ins. `Filmstrip.frames` is what fills them once a decode path is wired up,
 * which is why the strip is addressed in source time rather than in trimmed time.
 */
@Composable
public fun Timeline(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val durationSeconds = state.sourceDuration?.let { it.inWholeMilliseconds / 1000f } ?: 0f
  val edit = state.edit
  val trimStart = if (edit.trimEnabled) edit.trimStartSeconds else 0f
  val trimEnd = if (edit.trimEnabled) edit.trimEndSeconds else durationSeconds
  val absolute = (trimStart + state.positionSeconds).coerceIn(0f, durationSeconds.coerceAtLeast(0f))

  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Ruler(durationSeconds)

    BoxWithConstraints(
      Modifier
        .fillMaxWidth()
        .height(STRIP_HEIGHT)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
      val density = LocalDensity.current
      val widthPx = with(density) { maxWidth.toPx() }
      val widthDp = maxWidth

      fun fractionOf(seconds: Float): Float =
        if (durationSeconds <= 0f) 0f else (seconds / durationSeconds).coerceIn(0f, 1f)

      fun seek(x: Float) {
        if (durationSeconds <= 0f) return
        val seconds = (x / widthPx).coerceIn(0f, 1f) * durationSeconds
        state.seekTo(seconds - trimStart)
      }

      FrameTiles(widthDp)

      Box(
        Modifier
          .fillMaxSize()
          .pointerInput(durationSeconds, trimStart) {
            detectTapGestures { offset -> seek(offset.x) }
          }.pointerInput(durationSeconds, trimStart) {
            detectHorizontalDragGestures { change, _ -> seek(change.position.x) }
          },
      )

      if (edit.trimEnabled && durationSeconds > 0f) {
        Box(
          Modifier
            .fillMaxHeight()
            .width(widthDp * fractionOf(trimStart))
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f)),
        )
        Box(
          Modifier
            .fillMaxHeight()
            .offset(x = widthDp * fractionOf(trimEnd))
            .width((widthDp * (1f - fractionOf(trimEnd))).coerceAtLeast(0.dp))
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f)),
        )
        TrimHandle(
          offsetX = widthDp * fractionOf(trimStart),
          leading = true,
          onDrag = { delta ->
            val seconds = edit.trimStartSeconds + delta / widthPx * durationSeconds
            edit.trimStartSeconds = seconds.coerceIn(0f, edit.trimEndSeconds - MIN_TRIM_SECONDS)
            state.onEditChanged()
          },
        )
        TrimHandle(
          offsetX = widthDp * fractionOf(trimEnd) - HANDLE_WIDTH,
          leading = false,
          onDrag = { delta ->
            val seconds = edit.trimEndSeconds + delta / widthPx * durationSeconds
            edit.trimEndSeconds = seconds.coerceIn(edit.trimStartSeconds + MIN_TRIM_SECONDS, durationSeconds)
            state.onEditChanged()
          },
        )
      }

      Playhead(offsetX = widthDp * fractionOf(absolute))

    }
  }
}

@Composable
private fun Ruler(durationSeconds: Float) {
  val marks = 5
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    repeat(marks) { index ->
      val seconds = durationSeconds * index / (marks - 1)
      Text(
        text = seconds.toDouble().seconds.asClock(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}

@Composable
private fun FrameTiles(width: Dp) {
  val tileWidth = 46.dp
  val count = (width / tileWidth).roundToInt().coerceIn(1, 40)

  Row(Modifier.fillMaxSize()) {
    repeat(count) { index ->
      val hue = (index * 37f) % 360f
      Box(
        Modifier
          .weight(1f)
          .fillMaxHeight()
          .background(
            Brush.verticalGradient(
              listOf(
                Color.hsv(hue, 0.35f, 0.42f),
                Color.hsv((hue + 24f) % 360f, 0.45f, 0.22f),
              ),
            ),
          ),
      )
      if (index != count - 1) {
        Spacer(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)))
      }
    }
  }

  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
    Sprockets()
    Sprockets()
  }
}

@Composable
private fun Sprockets() {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(9.dp)
      .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f))
      .padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(18) {
      Box(
        Modifier.size(width = 7.dp, height = 4.dp)
          .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(1.dp)),
      )
    }
  }
}

@Composable
private fun TrimHandle(
  offsetX: Dp,
  leading: Boolean,
  onDrag: (Float) -> Unit,
) {
  Box(
    modifier = Modifier
      .offset(x = offsetX)
      .fillMaxHeight()
      .width(HANDLE_WIDTH)
      .background(
        MaterialTheme.colorScheme.primary,
        if (leading) {
          RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
        } else {
          RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
        },
      ).pointerInput(leading) {
        detectHorizontalDragGestures { _, delta -> onDrag(delta) }
      },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier.size(width = 2.dp, height = 18.dp)
        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(1.dp)),
    )
  }
}

@Composable
private fun Playhead(offsetX: Dp) {
  Box(Modifier.offset(x = offsetX - 1.dp).fillMaxHeight().width(2.dp).background(MaterialTheme.colorScheme.onSurface))
  Box(
    Modifier
      .offset(x = offsetX - 5.dp)
      .size(10.dp)
      .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(2.dp)),
  )
}

private val STRIP_HEIGHT = 72.dp
private val HANDLE_WIDTH = 14.dp
private const val MIN_TRIM_SECONDS = 0.2f
