package dev.jordond.filmstrip.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.ReadbackCallback
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import kotlin.time.Duration

/**
 * A frame of [size] whose pixels are [colors] in row-major order, packed the way the readback contract says.
 */
internal fun readbackFrame(
  size: Size,
  colors: List<Color>,
): ReadbackFrame =
  ReadbackFrame(
    pixels = rgba(colors),
    size = size,
    presentationTime = Duration.ZERO,
    colorSpace = ColorSpace.Bt709,
    renderScale = 1f,
  )

/**
 * A frame of [size] filled with [color].
 */
internal fun solidFrame(
  size: Size,
  color: Color,
): ReadbackFrame = readbackFrame(size, List(size.width * size.height) { color })

private fun rgba(colors: List<Color>): ByteArray =
  ByteArray(colors.size * 4).also { bytes ->
    colors.forEachIndexed { index, color ->
      val offset = index * 4
      bytes[offset] = (color.red * 255).toInt().toByte()
      bytes[offset + 1] = (color.green * 255).toInt().toByte()
      bytes[offset + 2] = (color.blue * 255).toInt().toByte()
      bytes[offset + 3] = (color.alpha * 255).toInt().toByte()
    }
  }

/**
 * Draws [content] into a 100x100 box over black.
 */
@Composable
internal fun SurfaceBox(content: @Composable () -> Unit) {
  Box(Modifier.size(100.dp).background(Color.Black)) { content() }
}

/**
 * A readback answering each request with what [answer] returns for its position, or holding it open on null.
 */
internal class ScriptedReadback(
  private val answer: (Duration) -> ReadbackResult?,
) : PreviewFrameReadback {
  val requested: MutableList<Duration> = mutableListOf()
  val cancelled: MutableList<Duration> = mutableListOf()

  override fun requestFrame(
    position: Duration,
    callback: ReadbackCallback,
  ): Cancellable {
    requested += position
    val result = answer(position)
    if (result != null) {
      callback.onReadback(result)
      return Cancellable { }
    }
    return Cancellable { cancelled += position }
  }
}
