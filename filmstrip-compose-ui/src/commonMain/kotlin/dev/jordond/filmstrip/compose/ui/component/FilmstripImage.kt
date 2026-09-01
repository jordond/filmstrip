package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.rememberFilmstripFrame
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.edit.EditComposition
import kotlin.time.Duration

/**
 * One frame of [composition], drawn at the size this is laid out to.
 *
 * A poster frame, a chapter picker's tile or a clip card. The frame is rendered at the height the image is given, so
 * a card that is 96dp tall never decodes more than 96dp worth of pixels, and nothing is asked for until the image has a
 * height. An image with no height, such as one left to wrap content it does not have, shows [placeholder] and nothing
 * else.
 *
 * ```
 * FilmstripImage(
 *   filmstrip = filmstrip,
 *   composition = composition,
 *   at = 2.seconds,
 *   modifier = Modifier.size(width = 160.dp, height = 90.dp),
 *   placeholder = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) },
 * )
 * ```
 *
 * @param filmstrip The instance the frame is rendered by.
 * @param composition The edit to render from.
 * @param at Where in the composition to render.
 * @param modifier Modifier for the image, and what gives it its size.
 * @param contentScale How the frame is fitted into the image.
 * @param contentDescription What the frame shows, for accessibility.
 * @param placeholder What fills the image while the frame has not arrived.
 */
@Composable
public fun FilmstripImage(
  filmstrip: Filmstrip,
  composition: EditComposition,
  at: Duration,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  contentDescription: String? = null,
  placeholder: @Composable () -> Unit = { },
) {
  var heightPx by remember { mutableIntStateOf(0) }
  val bitmap = if (heightPx > 0) rememberFilmstripFrame(filmstrip, composition, at, heightPx) else null

  Box(modifier.onSizeChanged { heightPx = it.height }) {
    if (bitmap == null) {
      placeholder()
    } else {
      Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
      )
    }
  }
}

@Preview
@Composable
private fun FilmstripImagePreview() {
  PreviewSurface(height = PREVIEW_HEIGHT) {
    FilmstripImage(
      filmstrip = Filmstrip(),
      composition = previewComposition(),
      at = Duration.ZERO,
      modifier = Modifier.size(width = PREVIEW_HEIGHT * PREVIEW_ASPECT, height = PREVIEW_HEIGHT),
      placeholder = { Box(Modifier.fillMaxSize().background(FilmstripTimelineDefaults.Palette.tile)) },
    )
  }
}

private val PREVIEW_HEIGHT = 90.dp
private const val PREVIEW_ASPECT = 16f / 9f
