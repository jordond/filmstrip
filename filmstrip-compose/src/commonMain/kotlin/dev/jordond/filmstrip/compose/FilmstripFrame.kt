package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.media.FrameRenderer
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.PlatformImage
import kotlin.time.Duration

/**
 * Remembers one frame of [composition], rendered at [at] with its effects applied, and closes it
 * when this leaves composition.
 *
 * For a poster frame, a chapter picker or a clip card, which want the frame covering one exact
 * instant rather than the run of nearest sync samples a strip reads. Each fetch goes through
 * [FrameRenderer.frame], so nothing here is shared with [rememberFilmstripFrames].
 *
 * The frame is fetched again whenever [at], [heightPx] or anything that would change a rendered
 * frame changes. An edit that changes nothing a frame is rendered from, such as the audio, keeps
 * the frame already held. The last frame produced stays in place while the next one renders, and a
 * render that fails clears it.
 *
 * @param renderer What renders the frame, usually the `Filmstrip` itself.
 * @param composition The edit to render from.
 * @param at Where in the composition to render.
 * @param heightPx The height to render at, in pixels. Zero renders at the composition's own output
 *   height.
 * @return The frame, or null while none has arrived.
 */
@OptIn(InternalFilmstripApi::class)
@Composable
public fun rememberFilmstripFrame(
  renderer: FrameRenderer,
  composition: EditComposition,
  at: Duration,
  heightPx: Int,
): ImageBitmap? {
  val holder = remember(renderer) { FrameHolder() }
  val revision = remember(composition) { composition.effectsRevision() }

  DisposableEffect(holder) {
    onDispose { holder.close() }
  }

  LaunchedEffect(holder, revision, at, heightPx) {
    val result = renderer.frame(composition, at, heightPx)
    holder.replace((result as? FrameResult.Success)?.image)
  }

  return holder.bitmap
}

/**
 * One decoded frame and the drawable form of it, swapped out as a whole.
 *
 * The image is closed only once its replacement is in place, since on some platforms the bitmap
 * draws from the image's own pixels.
 */
private class FrameHolder {
  private var image: PlatformImage? = null

  var bitmap: ImageBitmap? by mutableStateOf(null)
    private set

  fun replace(next: PlatformImage?) {
    val previous = image
    image = next
    bitmap = next?.toImageBitmap()
    previous?.close()
  }

  fun close() {
    replace(null)
  }
}
