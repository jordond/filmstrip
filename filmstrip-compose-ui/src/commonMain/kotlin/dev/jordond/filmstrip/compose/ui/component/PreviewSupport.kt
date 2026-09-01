package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How much source time every preview here draws.
 */
internal val PreviewDuration: Duration = 30.seconds

/**
 * What every preview lays out against, scaled so the whole of [PreviewDuration] fits the surface.
 */
internal val PreviewScale: TimelineScale = TimelineScale(PreviewDuration, pixelsPerSecond = 10f)

/**
 * A one clip composition over a path no preview ever opens, so every frame stays a placeholder.
 */
internal fun previewComposition(): EditComposition =
  EditComposition(
    tracks = listOf(Track(listOf(Clip(MediaSource.of("preview.mp4"), TimeRange.of(Duration.ZERO, PreviewDuration))))),
  )

/**
 * A dark ground the neutral palette reads against, as wide as [PreviewScale] is long.
 */
@Composable
internal fun PreviewSurface(
  height: Dp = FilmstripTimelineDefaults.StripHeight,
  content: @Composable () -> Unit,
) {
  Box(
    Modifier
      .width(PREVIEW_WIDTH)
      .height(height)
      .background(PREVIEW_BACKGROUND),
  ) {
    content()
  }
}

private val PREVIEW_WIDTH = 300.dp
private val PREVIEW_BACKGROUND = Color(0xFF15171B)
