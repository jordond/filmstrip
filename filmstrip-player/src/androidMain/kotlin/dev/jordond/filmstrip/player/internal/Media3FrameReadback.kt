package dev.jordond.filmstrip.player.internal

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import androidx.media3.common.Effect
import androidx.media3.inspector.frame.FrameExtractor
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media3.internal.preview.Media3Preview
import dev.jordond.filmstrip.media3.internal.preview.Media3Readback
import dev.jordond.filmstrip.media3.internal.preview.readbackSourceFactory
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.ReadbackCallback
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Reads one rendered preview frame back without touching the live player.
 *
 * `FrameExtractor` takes one media item and one effect list rather than a composition, so the clip
 * covering the requested time is found through the spans the lowering already laid out and its own
 * chain is run ahead of the composition's. Both lists are lowered from the plan the player is drawing,
 * so a parameter swapped into the live graph is in this frame too, and the readback is the preview's
 * own output. The graph here is separate from the player's, and overlays build themselves per graph.
 *
 * A photo goes through [Media3StillFrames] instead, because the extractor builds its player with a
 * single video renderer and an image item has nothing there to decode it. A gap is laid as a
 * transparent still, so a position inside one takes that path too and reads the fill the export
 * paints there.
 *
 * The extractor builds its item's source with [readbackSourceFactory], so a clip trimmed before its source ends is read
 * to its last frame the way the player shows it.
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
 * @param revision What the loaded edit's frames are decided by, which tells one request's chain
 *   from the last one's.
 * @param transfer The transfer of the grade the loaded edit keeps, or null when it is SDR.
 */
internal class Media3FrameReadback(
  private val scope: CoroutineScope,
  private val context: Context,
  private val preview: () -> Media3Preview?,
  private val renderScale: () -> Float,
  private val revision: () -> Long,
  private val transfer: () -> HdrTransfer?,
) : PreviewFrameReadback,
  HdrPreviewReadback {
  private val stills = Media3StillFrames(context)

  private val thread = HandlerThread(THREAD_NAME).apply { start() }
  private val handler = Handler(thread.looper)

  override fun requestFrame(
    position: Duration,
    callback: ReadbackCallback,
  ): Cancellable {
    val graph = preview() ?: return refuse(callback, NO_COMPOSITION)
    val at = graph.slotTimeOf(position)
    val lowered = graph.readbackAt(at, revision()) ?: return refuse(callback, NO_CLIP)
    val span = lowered.span
    val scale = renderScale()
    val request = Request(callback)

    if (span.still) return readStill(lowered, at, scale, request)

    // The extractor builds an ExoPlayer, which binds to the looper of whatever thread creates it,
    // so it is built on one of this class's own rather than on the player's.
    handler.post {
      if (request.cancelled) return@post
      val extractor = extractorOf(lowered, lowered.effects, extractsHdr = false)
      request.extractor = extractor

      val future = extractor.getFrame(lowered.extractorPosition(at))
      future.addListener(
        {
          val outcome =
            try {
              future.get().toReadback(lowered, scale)
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
   * Renders the frame at [position] on the loaded edit's kept grade and reads it out of media3's working space.
   *
   * A clip goes through a `FrameExtractor` asked for HDR frames and a photo through [Media3StillFrames.renderHdr], each
   * with an [HdrTap] at the end of the chain. Refused on an SDR grade, and below API 34, where media3 hands back no HDR
   * frame and lays no picture on an HDR output.
   */
  override suspend fun hdrFrameAt(position: Duration): HdrPreviewResult {
    val grade = transfer() ?: return hdrRefusal(NO_HDR_GRADE)
    hdrReadbackLimit(Build.VERSION.SDK_INT)?.let { return hdrRefusal(it) }
    val graph = preview() ?: return hdrRefusal(NO_COMPOSITION)
    val at = graph.slotTimeOf(position)
    val lowered = graph.readbackAt(at, revision()) ?: return hdrRefusal(NO_CLIP)
    val tap = HdrTap()
    return try {
      val frame =
        if (lowered.span.still) {
          stills.renderHdr(lowered, at, grade, tap)
        } else {
          val shownMs = extractHdr(lowered, at, tap)
          withContext(Dispatchers.Default) { tap.frameAt(lowered.compositionTimeOf(shownMs), grade) }
        }
      HdrPreviewResult.Success(frame)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (
      @Suppress("TooGenericExceptionCaught") broken: Exception,
    ) {
      HdrPreviewResult.Failure(
        PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, broken.message ?: broken.toString()),
      )
    }
  }

  /**
   * Extracts the frame of [lowered]'s clip at [position] as HDR through [tap], returning the time media3 reports it at.
   *
   * The extractor is closed before this resumes, for the reason [requestFrame] gives.
   */
  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private suspend fun extractHdr(
    lowered: Media3Readback,
    position: Duration,
    tap: HdrTap,
  ): Long =
    suspendCancellableCoroutine { continuation ->
      handler.post {
        if (!continuation.isActive) return@post
        val extractor = extractorOf(lowered, lowered.effects + tap, extractsHdr = true)
        val future = extractor.getFrame(lowered.extractorPosition(position))
        future.addListener(
          {
            val outcome = runCatching { future.get().presentationTimeMs }
            handler.post {
              extractor.close()
              continuation.resumeWith(outcome)
            }
          },
          DIRECT,
        )
      }
    }

  private fun extractorOf(
    lowered: Media3Readback,
    effects: List<Effect>,
    extractsHdr: Boolean,
  ): FrameExtractor =
    FrameExtractor
      .Builder(context, lowered.span.item)
      .setMediaSourceFactory(readbackSourceFactory(context))
      .setEffects(effects)
      .setExtractHdrFrames(extractsHdr)
      .build()

  // The extractor answers with the first frame at or after the time it is asked for, so a position inside a slot is
  // read at the slot's own time, or it would land on the next source frame and the next slot.
  private fun Media3Preview.slotTimeOf(position: Duration): Duration =
    outputSlotTime(outputSlotOf(position, resolved.output.frameRate), resolved.output.frameRate)

  private fun Media3Readback.extractorPosition(position: Duration): Long = span.positionIn(position).inWholeMilliseconds

  private fun hdrRefusal(message: String): HdrPreviewResult =
    HdrPreviewResult.Failure(PlaybackError.SourceUnreadable(message))

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
                  colorSpace = READBACK_COLOR_SPACE,
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
   * Releases the extractor thread and the picture the last photo was drawn from. Idempotent.
   */
  fun dispose() {
    thread.quitSafely()
    stills.forget()
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
    const val NO_HDR_GRADE = "The composition is on an SDR grade, so there are no HDR codes to read."

    // The future has already finished by the time this runs, so the callback costs the thread that
    // completed it one lambda and nothing else.
    val DIRECT = Executor { command -> command.run() }
  }
}

/**
 * This extracted frame as the packed, opaque RGBA a [ReadbackFrame] carries.
 *
 * The time is the one [lowered]'s chain reports, which is already the composition's.
 *
 * The bitmap is read rather than taken. It belongs to the player media3 shares across the process,
 * which answers a later extraction with a frame it already made, the same object included, so
 * recycling it here would leave that later reader copying pixels out of a bitmap holding none.
 * Nothing outlives this call to need a copy of its own, which is what
 * [dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailSource] takes for the frames it does hand on.
 */
private fun FrameExtractor.Frame.toReadback(
  lowered: Media3Readback,
  scale: Float,
): ReadbackResult =
  ReadbackResult.Success(
    ReadbackFrame(
      pixels = bitmap.toRgba(),
      size = Size(bitmap.width, bitmap.height),
      presentationTime = lowered.compositionTimeOf(presentationTimeMs),
      colorSpace = READBACK_COLOR_SPACE,
      renderScale = scale,
    ),
  )

// media3 tone-maps an HDR frame on the way out of both a FrameExtractor and the still processor,
// since neither is asked for HDR frames, so a readback is BT.709 whatever the plan encodes.
private val READBACK_COLOR_SPACE = ColorSpace.Bt709

/**
 * This bitmap as tightly packed RGBA_8888, row major.
 *
 * Read through `getPixels`, because a bitmap's backing buffer is free to pad its rows to its own
 * alignment and the packed form must carry none of that. Every pixel is
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

/**
 * Why media3 cannot read a preview frame back as HDR on [sdkInt], or null when it can.
 *
 * `FrameExtractor` hands back HDR frames from API 34, and a picture reaches an HDR output only with a gainmap, which
 * starts there too.
 */
internal fun hdrReadbackLimit(sdkInt: Int): String? =
  if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    "media3 reads a preview frame back as HDR from API 34, and this device runs API $sdkInt."
  } else {
    null
  }
