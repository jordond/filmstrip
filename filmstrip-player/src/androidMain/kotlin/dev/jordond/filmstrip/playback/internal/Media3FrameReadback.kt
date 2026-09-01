package dev.jordond.filmstrip.playback.internal

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.inspector.frame.FrameExtractor
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media3.internal.Media3Preview
import dev.jordond.filmstrip.media3.internal.Media3Readback
import dev.jordond.filmstrip.media3.internal.Media3Span
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.ReadbackCallback
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Reads one rendered preview frame back without touching the live player.
 *
 * `FrameExtractor` takes one media item and one effect list rather than a composition, so the clip
 * covering the requested time is found through the spans the lowering already laid out and its own
 * chain is run ahead of the composition's. Both lists are the objects the player is drawing with, so
 * a parameter swapped into the live graph is in this frame too, and the readback is the preview's
 * own output rather than a second rendering of it.
 *
 * A photo goes through [Media3StillFrames] instead, because the extractor builds its player with a
 * single video renderer and an image item has nothing there to decode it.
 *
 * Because the extractor is a separate player, no seek is observable, the playhead does not move, and
 * playback if running is unaffected, which is what [PreviewFrameReadback] requires.
 *
 * The codec selector is left at its `PREFER_SOFTWARE` default. media3 sets it there because
 * flushing a hardware decoder crashes when video effects are attached, which is every frame here.
 *
 * @param scope The engine's dispatcher, where every callback is delivered.
 * @param context The application context the extractor decodes on.
 * @param preview The graph the player is showing, or null before one is loaded.
 * @param renderScale The preview-only downscale in force.
 * @param colorSpace What the rendered pixels are in.
 * @param revision What the loaded edit's frames are decided by, which tells one request's chain
 *   from the last one's.
 */
@OptIn(InternalFilmstripApi::class)
internal class Media3FrameReadback(
  private val scope: CoroutineScope,
  private val context: Context,
  private val preview: () -> Media3Preview?,
  private val renderScale: () -> Float,
  private val colorSpace: () -> ColorSpace,
  private val revision: () -> Long,
) : PreviewFrameReadback {
  private val stills = Media3StillFrames(context)

  private val thread = HandlerThread(THREAD_NAME).apply { start() }
  private val handler = Handler(thread.looper)

  override fun requestFrame(
    position: Duration,
    callback: ReadbackCallback,
  ): Cancellable {
    val graph = preview() ?: return refuse(callback, NO_COMPOSITION)
    val lowered = graph.readbackAt(position, revision()) ?: return refuse(callback, NO_CLIP)
    val span = lowered.span
    val scale = renderScale()
    val space = colorSpace()
    val request = Request(callback)

    if (span.still) return readStill(lowered, position, scale, space, request)

    // The extractor builds an ExoPlayer, which binds to the looper of whatever thread creates it,
    // so it is built on one of this class's own rather than on the player's.
    handler.post {
      if (request.cancelled) return@post
      val extractor =
        FrameExtractor
          .Builder(context, span.item)
          .setEffects(lowered.effects)
          .build()
      request.extractor = extractor

      val future = extractor.getFrame(span.positionIn(position).inWholeMilliseconds)
      future.addListener(
        {
          val outcome =
            try {
              future.get().toReadback(span, scale, space)
            } catch (
              @Suppress("TooGenericExceptionCaught") broken: Exception,
            ) {
              ReadbackResult.Failure(
                PlaybackError.Underlying(
                  PlaybackError.Underlying.NO_PLATFORM_CODE,
                  broken.message ?: broken.toString(),
                ),
              )
            }
          // Closed before the caller is resumed, never alongside it. FrameExtractor draws on
          // machinery media3 shares between instances, so an extractor still open when the next
          // one is built can answer with the frame it already made. A caller that reads a frame,
          // changes a parameter and reads again, which is what a scrubber does, gets the picture
          // it asked for the first time twice.
          handler.post {
            request.closeExtractor()
            scope.launch { request.settle(outcome) }
          }
        },
        DIRECT,
      )
    }

    return Cancellable {
      request.cancelled = true
      handler.post { request.closeExtractor() }
    }
  }

  /**
   * Reads the frame [lowered]'s photo draws at [position].
   *
   * A photo holds one picture for its whole span, so the frame covering a position sits exactly
   * there and the time is reported as asked rather than as decoded. The chain over that picture
   * still gets the composition time, since an effect that travels over the clip's span draws a
   * different part of it at each position.
   */
  private fun readStill(
    lowered: Media3Readback,
    position: Duration,
    scale: Float,
    space: ColorSpace,
    request: Request,
  ): Cancellable {
    val job =
      scope.launch {
        val outcome =
          try {
            val drawn = stills.render(lowered, position)
            ReadbackResult
              .Success(
                ReadbackFrame(
                  pixels = drawn.toRgba(),
                  size = Size(drawn.width, drawn.height),
                  presentationTime = position,
                  colorSpace = space,
                  renderScale = scale,
                ),
              ).also { drawn.recycle() }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (
            @Suppress("TooGenericExceptionCaught") broken: Exception,
          ) {
            ReadbackResult.Failure(
              PlaybackError.Underlying(
                PlaybackError.Underlying.NO_PLATFORM_CODE,
                broken.message ?: broken.toString(),
              ),
            )
          }
        request.settle(outcome)
      }

    return Cancellable {
      request.cancelled = true
      job.cancel()
    }
  }

  /**
   * Releases the extractor thread. Idempotent.
   */
  fun dispose() {
    thread.quitSafely()
  }

  /**
   * One outstanding request, so a cancellation and a completion settle it exactly once between
   * them.
   */
  private class Request(
    private val callback: ReadbackCallback,
  ) {
    @Volatile
    var cancelled: Boolean = false

    @Volatile
    var extractor: FrameExtractor? = null

    private var settled = false

    /**
     * Releases the extractor, once.
     *
     * It hands back a share of machinery media3 reference counts between instances, so releasing
     * twice would take a share this request never held. Only ever called on the readback thread.
     */
    fun closeExtractor() {
      val open = extractor ?: return
      extractor = null
      open.close()
    }

    fun settle(result: ReadbackResult) {
      if (settled || cancelled) return
      settled = true
      callback.onReadback(result)
    }
  }

  private fun refuse(
    callback: ReadbackCallback,
    message: String,
  ): Cancellable {
    callback.onReadback(ReadbackResult.Failure(PlaybackError.SourceUnreadable(message)))
    return Cancellable { }
  }

  private companion object {
    const val THREAD_NAME = "filmstrip-readback"
    const val NO_COMPOSITION = "No composition is loaded, so there is no frame to read back."
    const val NO_CLIP = "The composition draws nothing at that time, so there is no frame to read back."

    // The future has already finished by the time this runs, so the callback costs the thread that
    // completed it one lambda and nothing else.
    val DIRECT = Executor { command -> command.run() }
  }
}

/**
 * This extracted frame as the packed, opaque RGBA a [ReadbackFrame] carries.
 *
 * The extractor reports a time inside the clip it decoded, so it is put back on the composition's
 * clock before a caller sees it.
 *
 * The bitmap is read rather than taken. It belongs to the player media3 shares across the process,
 * which answers a later extraction with a frame it already made, the same object included, so
 * recycling it here would leave that later reader copying pixels out of a bitmap holding none.
 * Nothing outlives this call to need a copy of its own, which is what [Media3ThumbnailSource] takes
 * for the frames it does hand on.
 */
private fun FrameExtractor.Frame.toReadback(
  span: Media3Span,
  scale: Float,
  space: ColorSpace,
): ReadbackResult =
  ReadbackResult.Success(
    ReadbackFrame(
      pixels = bitmap.toRgba(),
      size = Size(bitmap.width, bitmap.height),
      presentationTime = span.compositionTimeOf(presentationTimeMs),
      colorSpace = space,
      renderScale = scale,
    ),
  )

/**
 * This bitmap as tightly packed RGBA_8888, row major.
 *
 * Read through `getPixels` rather than straight out of the backing buffer, because a bitmap is free
 * to pad its rows to its own alignment and the packed form must carry none of that. Every pixel is
 * opaque, the frame having been flattened onto the composition's fill before it got here, so the
 * alpha channel is written full and premultiplied and straight alpha are the same bytes.
 */
private fun Bitmap.toRgba(): ByteArray {
  val colors = IntArray(width * height)
  getPixels(colors, 0, width, 0, 0, width, height)

  val pixels = ByteArray(colors.size * CHANNELS)
  for (index in colors.indices) {
    val color = colors[index]
    val base = index * CHANNELS
    pixels[base] = (color shr RED_SHIFT).toByte()
    pixels[base + 1] = (color shr GREEN_SHIFT).toByte()
    pixels[base + 2] = color.toByte()
    pixels[base + 3] = OPAQUE
  }
  return pixels
}

private const val CHANNELS = 4
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val OPAQUE = 0xFF.toByte()
