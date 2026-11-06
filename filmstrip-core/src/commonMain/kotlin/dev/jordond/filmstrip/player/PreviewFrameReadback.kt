package dev.jordond.filmstrip.player

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Reads back a fully rendered preview frame: post-effects, pre-encode.
 *
 * Every player backend provides one. It is pull-based and allocates its target per request, so it
 * costs nothing until a frame is asked for.
 *
 * Not a [dev.jordond.filmstrip.thumbnail.ThumbnailSource]. That one serves many cheap approximate
 * frames, keyframe-snapped and downscaled. This one produces a single exact frame out of the live
 * preview pipeline.
 */
public fun interface PreviewFrameReadback {
  /**
   * Renders the frame at [position] through the current effect chain and delivers it.
   *
   * Must not disturb transport state: no seek is observable to the caller, the playhead does not
   * move, and playback if running is unaffected.
   *
   * @param callback Receives the frame, or the failure, exactly once.
   * @return a handle that cancels the request.
   */
  public fun requestFrame(
    position: Duration,
    callback: ReadbackCallback,
  ): Cancellable
}

/**
 * Renders the frame at [position] through the current effect chain, suspending until it is ready.
 *
 * The suspend form of [PreviewFrameReadback.requestFrame], with the same promise about transport
 * state. Cancelling the caller cancels the request.
 *
 * @param position The composition time to render.
 * @return The frame, or why it could not be rendered.
 */
public suspend fun PreviewFrameReadback.frameAt(position: Duration): ReadbackResult =
  suspendCancellableCoroutine { continuation ->
    val request = requestFrame(position) { result -> continuation.resume(result) }
    continuation.invokeOnCancellation { request.cancel() }
  }

/**
 * The playhead positions a surface drawing through [VideoPlayer.readback] owes a frame for.
 *
 * Follows [VideoPlayer.positionFlow] while a composition is presentable and emits nothing before that, since there is
 * no graph to read yet. Every move into a presentable status and every move of [VideoPlayer.frameRevision] emits the
 * playhead again, because a load or a parameter edit has a new frame to show at a position the playhead alone would not
 * report as changed.
 *
 * @param tick The grid to snap the playhead to.
 * @return A cold flow of the positions to read a frame at.
 */
@InternalFilmstripApi
public fun VideoPlayer.readbackPositions(tick: Duration): Flow<Duration> =
  combine(
    positionFlow(tick),
    state.map { it.hasComposition }.distinctUntilChanged(),
    frameRevision(),
  ) { position, presentable, _ ->
    position.takeIf { presentable }
  }.filterNotNull()

/**
 * Receives the outcome of one [PreviewFrameReadback.requestFrame].
 */
public fun interface ReadbackCallback {
  /**
   * Called exactly once per request, unless the request was cancelled first.
   */
  public fun onReadback(result: ReadbackResult)
}

/**
 * The outcome of one readback.
 */
public sealed interface ReadbackResult {
  /**
   * A frame was rendered.
   *
   * @property frame The rendered frame.
   */
  @Poko
  public class Success(
    public val frame: ReadbackFrame,
  ) : ReadbackResult

  /**
   * No frame was rendered.
   *
   * @property error Why it failed.
   */
  @Poko
  public class Failure(
    public val error: PlaybackError,
  ) : ReadbackResult
}

/**
 * One frame read back out of the preview pipeline.
 *
 * @property pixels Tightly packed RGBA_8888, row-major, no row padding.
 *   `size.width * size.height * 4` bytes. Every pixel is opaque, because the frame is flattened
 *   onto the composition's fill before it is read back, so premultiplied and straight alpha are the
 *   same bytes here.
 * @property size The frame's dimensions, in pixels.
 * @property presentationTime The composition time actually rendered, which may differ from
 *   the requested position.
 * @property colorSpace The color space the pixels are in.
 * @property renderScale The preview-only downscale in force, `1f` when there is none.
 */
public class ReadbackFrame
  @InternalFilmstripApi
  constructor(
    public val pixels: ByteArray,
    public val size: Size,
    public val presentationTime: Duration,
    public val colorSpace: ColorSpace,
    public val renderScale: Float,
  ) {
    override fun toString(): String = "ReadbackFrame(size=$size, at=$presentationTime, scale=$renderScale)"
  }
