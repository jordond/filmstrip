package dev.jordond.filmstrip.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Where an [OverlayCapturer] draws the elements it bakes.
 *
 * Mount it once, anywhere in the app's composition that is on screen. It lays itself out one pixel square and shows
 * nothing in that pixel. An element being captured is composed inside it and drawn into an offscreen layer only. A
 * host that is off screen is never drawn, and a capture that waits on it fails after 10 seconds.
 *
 * @param capturer The capturer that draws here.
 * @param modifier The modifier for the host's own one pixel node.
 */
@Composable
public fun OverlayCaptureHost(
  capturer: OverlayCapturer,
  modifier: Modifier = Modifier,
) {
  DisposableEffect(capturer) {
    capturer.hosts += 1
    onDispose { capturer.hosts -= 1 }
  }
  // The host's own draw reads this, so a capture can ask for a draw of the subtree it inserted.
  val redraw = remember { mutableIntStateOf(0) }
  Layout(
    content = { capturer.request?.let { pending -> key(pending) { Capture(pending, redraw) } } },
    modifier = modifier.drawBehind { redraw.intValue },
  ) { list, _ ->
    val placeables = list.map { it.measure(Constraints()) }
    layout(HOST_PX, HOST_PX) { placeables.forEach { it.place(0, 0) } }
  }
}

@OptIn(InternalFilmstripApi::class)
@Composable
private fun Capture(
  pending: CaptureRequest,
  redraw: MutableIntState,
) {
  val content = rememberGraphicsLayer()
  val graphics = LocalGraphicsContext.current
  val measured = remember(pending) { MeasuredSize() }
  val recorded = remember(pending) { MutableStateFlow(-1) }
  val constraints = pending.canvas.constraintsFor(pending.output)

  DisposableEffect(pending) {
    onDispose { pending.result.completeExceptionally(IllegalStateException(HOST_LEFT)) }
  }
  CompositionLocalProvider(LocalDensity provides OverlayLayoutDensity) {
    Layout(
      content = {
        Box(
          Modifier.drawWithContent {
            val asked = redraw.intValue
            content.record { this@drawWithContent.drawContent() }
            recorded.value = asked
          },
        ) { pending.content() }
      },
    ) { list, _ ->
      val placeable = list.single().measure(constraints)
      measured.size = IntSize(placeable.width, placeable.height)
      layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
  }
  LaunchedEffect(pending) {
    try {
      drawn { repeat(SETTLE_FRAMES) { withFrameNanos {} } }
      pending.ready()
      drawn {
        repeat(SETTLE_FRAMES) { withFrameNanos {} }
        // A frame does not promise a draw, so ask for one and wait until the content is recorded after ready.
        val asked = ++redraw.intValue
        recorded.first { it >= asked }
      }
      val bounds = pending.transform.boundsOn(measured.size, pending.canvas, pending.output)
      if (bounds == null) {
        pending.result.complete(null)
        return@LaunchedEffect
      }
      val raster = bounds.raster
      val baked = graphics.createGraphicsLayer()
      try {
        baked.record(OverlayLayoutDensity, LayoutDirection.Ltr, IntSize(raster.width, raster.height)) {
          drawElement(content, measured.size, pending, raster.left.toFloat(), raster.top.toFloat())
        }
        pending.result.complete(Captured(baked.toImageBitmap(), bounds))
      } finally {
        graphics.releaseGraphicsLayer(baked)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      // A failure belongs to the caller of the capture, never to the composition the host sits in.
      pending.result.completeExceptionally(failure)
    }
  }
}

// Frames stop for a host that is off screen, so the waits on the host's own frames and draws are bounded.
private suspend fun drawn(wait: suspend () -> Unit) {
  withTimeoutOrNull(DRAW_TIMEOUT) { wait() } ?: throw IllegalStateException(NOT_DRAWN)
}

// Draws the recorded content with its centre on the transform's point inside the raster, scaled to the output frame
// and turned about its own centre.
@OptIn(InternalFilmstripApi::class)
private fun DrawScope.drawElement(
  content: GraphicsLayer,
  size: IntSize,
  pending: CaptureRequest,
  rasterLeft: Float,
  rasterTop: Float,
) {
  val transform = pending.transform
  val output = pending.output
  val perLayoutPx = transform.drawnScale(pending.canvas, output.width.toFloat())
  withTransform({
    translate(transform.center.x * output.width - rasterLeft, transform.center.y * output.height - rasterTop)
    rotate(transform.rotationDegrees, Offset.Zero)
    scale(perLayoutPx, perLayoutPx, Offset.Zero)
    translate(-size.width / 2f, -size.height / 2f)
  }) {
    drawLayer(content)
  }
}

private class MeasuredSize {
  var size: IntSize = IntSize.Zero
}

private const val SETTLE_FRAMES = 2
private const val HOST_PX = 1
private val DRAW_TIMEOUT = 10.seconds
