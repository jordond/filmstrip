package dev.jordond.filmstrip.playback.internal

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.media3.internal.Media3Preview
import dev.jordond.filmstrip.media3.internal.Media3Readback
import dev.jordond.filmstrip.media3.internal.toMedia3Preview
import dev.jordond.filmstrip.media3.media3ExportEngine
import dev.jordond.filmstrip.thumbnail.ThumbnailBatchCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * Renders strip frames through `FrameExtractor`, over the graph an export of the same edit runs.
 *
 * The extractor takes one media item and one effect list rather than a composition, so the clip
 * covering the requested time is found through the spans the lowering already laid out and its own
 * chain is run ahead of the composition's. That is the same arithmetic the preview's readback uses
 * and the same objects, so a strip frame is the frame the file carries.
 *
 * A photo goes through [Media3StillFrames] instead. The extractor builds its player with a single
 * video renderer, so an image item has nothing to decode it, and the same chain is run over the
 * decoded picture rather than over a seek.
 *
 * A run of requests is served on one extractor per clip it draws from. Frame extraction shares one
 * player across the process and releases it once nothing holds an extractor open, so a handle kept
 * for the length of a run turns each frame after the first into a seek on a prepared player rather
 * than a decoder, a graph and a source read from cold.
 *
 * The codec selector is left at its `PREFER_SOFTWARE` default. media3 sets it there because
 * flushing a hardware decoder crashes when video effects are attached, which is every frame here.
 *
 * The seek parameters follow the request. Exact is the extractor's own default and is what a
 * precise request wants, and anything else snaps to the nearest sync sample, which decodes faster
 * and draws a frame from somewhere else on the timeline. Positions that disagree about it are
 * served on extractors of their own, since the parameters are fixed when one is built.
 *
 * Each frame is copied before it is handed on. The bitmap an extraction answers with belongs to the
 * player media3 shares across the process, which answers a later extraction with a frame it already
 * made, the same object included. A [PlatformImage] owns what it recycles, so it is given a copy
 * rather than the shared object, and a tile closed by one run cannot empty a bitmap another run is
 * about to be handed. The copy costs one frame at strip height.
 *
 * @param scope Where every callback is delivered.
 * @param context The application context the extractor decodes on.
 * @param planner Lowers an edit the way an export of it would be lowered.
 */
@OptIn(InternalFilmstripApi::class)
internal class Media3ThumbnailSource(
  private val scope: CoroutineScope,
  private val context: Context,
  private val planner: Media3ThumbnailPlanner,
) : ThumbnailSource {
  private val stills = Media3StillFrames(context)

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable = requestThumbnails(listOf(request)) { _, result -> callback.onThumbnail(result) }

  override fun requestThumbnails(
    requests: List<ThumbnailRequest>,
    callback: ThumbnailBatchCallback,
  ): Cancellable {
    if (requests.isEmpty()) return Cancellable { }

    val run = ThumbnailRun(callback)
    val job = scope.launch { serve(requests, run) }

    return Cancellable {
      job.cancel()
      run.cancel()
    }
  }

  /**
   * Serves [requests] in order, lowering once per stretch of them that lowers the same way.
   *
   * The reader thread is the one every `FrameExtractor` call is made from, which is what that class
   * asks of a caller. It comes down once the run settles, however it settled.
   */
  private suspend fun serve(
    requests: List<ThumbnailRequest>,
    run: ThumbnailRun,
  ) {
    val thread = HandlerThread(THREAD_NAME).also { it.start() }
    if (!run.attach(thread)) return
    val reader = Handler(thread.looper).asCoroutineDispatcher(THREAD_NAME)

    try {
      var from = 0
      while (from < requests.size && !run.isCancelled) {
        currentCoroutineContext().ensureActive()
        val head = requests[from]
        var to = from + 1

        while (to < requests.size && requests[to].lowersWith(head)) {
          currentCoroutineContext().ensureActive()
          to++
        }

        serveLowering(reader, requests.subList(from, to), from, run)
        from = to
      }
    } finally {
      run.release()
    }
  }

  /**
   * Serves [group], every entry of which lowers to the same graph.
   *
   * The lowering runs off the caller's dispatcher because it probes the sources, which reads files.
   */
  private suspend fun serveLowering(
    reader: CoroutineDispatcher,
    group: List<ThumbnailRequest>,
    offset: Int,
    run: ThumbnailRun,
  ) {
    val head = group.first()
    val preview =
      when (val plan = withContext(Dispatchers.Default) { planner.lower(head) }) {
        is Media3ThumbnailPlan.Refused -> return run.failAll(group, offset, plan.error)
        is Media3ThumbnailPlan.Ready -> plan.preview
      }

    val readbacks =
      preview.readbacksAt(group.map { it.position }, head.effectsRevision)
        ?: return run.failAll(group, offset, ExportError.InvalidComposition(NO_CLIP))

    // Positions drawn from one clip share a readback, so a stretch of them is one extractor.
    var from = 0
    while (from < group.size && !run.isCancelled) {
      currentCoroutineContext().ensureActive()
      val readback = readbacks[from]
      val still = readback.span.still
      val precise = group[from].precise
      var to = from + 1
      // A photo carries no sync samples for a relaxed seek to snap to, so an accuracy that cannot
      // change its answer does not split a run over one.
      while (to < group.size && readbacks[to] === readback && (still || group[to].precise == precise)) {
        currentCoroutineContext().ensureActive()
        to++
      }

      val stretch = group.subList(from, to)
      if (still) {
        serveStill(readback, stretch, offset + from, run)
      } else {
        serveClip(reader, readback, precise, stretch, offset + from, run)
      }
      from = to
    }
  }

  /**
   * Draws every frame [group] asks for out of the photo [readback] covers them with.
   *
   * A photo contributes the same pixels at every position in its span, which is what lets one
   * render answer a whole stretch of requests and what makes the frame covering a position sit
   * exactly at that position however precise the request asked to be.
   *
   * Each tile is handed a copy, for the reason every other frame here is copied: a [PlatformImage]
   * recycles what it owns, and one tile closed by its caller must not empty another's.
   */
  private suspend fun serveStill(
    readback: Media3Readback,
    group: List<ThumbnailRequest>,
    offset: Int,
    run: ThumbnailRun,
  ) {
    val drawn =
      try {
        stills.render(readback)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (
        @Suppress("TooGenericExceptionCaught") broken: Exception,
      ) {
        return run.failAll(
          group,
          offset,
          ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, broken.message ?: broken.toString()),
        )
      }

    try {
      group.forEachIndexed { at, request ->
        if (run.isCancelled) return
        val owned =
          drawn.copy(drawn.config ?: Bitmap.Config.ARGB_8888, false)
            ?: return run.failAll(
              group.subList(at, group.size),
              offset + at,
              ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, NO_COPY),
            )
        run.deliver(
          offset + at,
          ThumbnailResult.Success(image = PlatformImage(owned), presentationTime = request.position),
        )
      }
    } finally {
      drawn.recycle()
    }
  }

  /**
   * Decodes every frame [group] asks for out of the one clip [readback] draws them from.
   */
  private suspend fun serveClip(
    reader: CoroutineDispatcher,
    readback: Media3Readback,
    precise: Boolean,
    group: List<ThumbnailRequest>,
    offset: Int,
    run: ThumbnailRun,
  ) {
    // The extractor exists the moment build() returns, and the hop back to the caller drops the
    // handle when the run is cancelled. Frame extraction shares a player across the process and
    // releases it once nothing holds an extractor, so a dropped handle pins that player for the
    // life of the process. It is parked across that hop and closed here instead.
    var built: FrameExtractor? = null
    val extractor =
      try {
        withContext(reader) {
          FrameExtractor
            .Builder(context, readback.span.item)
            .setEffects(readback.effects)
            .setSeekParameters(if (precise) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
            .build()
            .also { built = it }
        }
      } catch (cancelled: CancellationException) {
        built?.let { run.close(it, reader) }
        throw cancelled
      }
    if (!run.accepts(extractor, reader)) return

    try {
      group.forEachIndexed { at, request ->
        if (run.isCancelled) return
        run.deliver(offset + at, extractor.decode(reader, readback, request.position))
      }
    } finally {
      run.close(extractor, reader)
    }
  }

  /**
   * Decodes the frame at [position] on [reader] and hands it back on the caller's dispatcher.
   *
   * The frame is parked while it crosses back. A cancellation that arrived while the decode was
   * running is reported once it finishes, so a frame decoded on the way to a caller that has since
   * gone is closed here.
   */
  private suspend fun FrameExtractor.decode(
    reader: CoroutineDispatcher,
    readback: Media3Readback,
    position: Duration,
  ): ThumbnailResult {
    var decoded: ThumbnailResult? = null
    try {
      return withContext(reader) { frameAt(readback, position).also { decoded = it } }
    } catch (cancelled: CancellationException) {
      (decoded as? ThumbnailResult.Success)?.image?.close()
      throw cancelled
    }
  }

  /**
   * The one frame [readback] draws at [position], as an outcome the run can hand on.
   */
  private suspend fun FrameExtractor.frameAt(
    readback: Media3Readback,
    position: Duration,
  ): ThumbnailResult =
    try {
      val frame = getFrame(readback.span.positionIn(position).inWholeMilliseconds).awaitFrame()
      val owned =
        frame.bitmap.copy(frame.bitmap.config ?: Bitmap.Config.ARGB_8888, false)
          ?: return ThumbnailResult.Failure(ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, NO_COPY))
      ThumbnailResult.Success(
        image = PlatformImage(owned),
        presentationTime = readback.span.compositionTimeOf(frame.presentationTimeMs),
      )
    } catch (cancelled: CancellationException) {
      // Two different cancellations reach here. ensureActive rethrows the run's own, which is not a
      // failed frame and must not be reported as one. It falls through for a future media3 cancelled
      // under a run that is still going, which is a frame this request did not get and the rest of
      // the batch still wants.
      currentCoroutineContext().ensureActive()
      ThumbnailResult.Failure(
        ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, cancelled.message ?: CANCELLED),
      )
    } catch (
      @Suppress("TooGenericExceptionCaught") broken: Exception,
    ) {
      ThumbnailResult.Failure(
        ExportError.Underlying(
          ExportError.Underlying.NO_PLATFORM_CODE,
          broken.message ?: broken.toString(),
        ),
      )
    }

  /**
   * One outstanding run, so a cancellation and a completion settle each entry exactly once between
   * them, and the reader thread comes down either way.
   *
   * A frame that arrives for a run already cancelled is closed here rather than handed on, since
   * the caller that would have owned it has gone.
   */
  private class ThumbnailRun(
    private val callback: ThumbnailBatchCallback,
  ) {
    @Volatile
    var isCancelled: Boolean = false
      private set

    @Volatile
    private var thread: HandlerThread? = null

    /**
     * Takes ownership of [running], or refuses because the run was already cancelled.
     *
     * @return whether the reader thread should go on to be used.
     */
    fun attach(running: HandlerThread): Boolean {
      thread = running
      if (!isCancelled) return true
      running.quitSafely()
      return false
    }

    /**
     * Whether [open] should go on to be used, closing it here when the run was cancelled while it
     * was being built.
     */
    suspend fun accepts(
      open: FrameExtractor,
      reader: CoroutineDispatcher,
    ): Boolean {
      if (!isCancelled) return true
      close(open, reader)
      return false
    }

    /**
     * Hands [open] back, which is what releases the player shared across the process once no run
     * is left holding one.
     *
     * A run cancelled mid-decode unwinds through here, so this outlasts the cancellation rather
     * than being skipped by it.
     */
    suspend fun close(
      open: FrameExtractor,
      reader: CoroutineDispatcher,
    ) {
      withContext(NonCancellable + reader) { open.close() }
    }

    fun deliver(
      index: Int,
      result: ThumbnailResult,
    ) {
      if (isCancelled) {
        (result as? ThumbnailResult.Success)?.image?.close()
        return
      }
      callback.onThumbnail(index, result)
    }

    fun failAll(
      group: List<ThumbnailRequest>,
      offset: Int,
      error: ExportError,
    ) {
      group.indices.forEach { deliver(offset + it, ThumbnailResult.Failure(error)) }
    }

    /**
     * Stops the run, leaving the reader thread up.
     *
     * The run is already unwinding towards [release] and closes on the way through, and every step
     * of that unwind is posted to the reader. Taking the thread down here would strand it.
     */
    fun cancel() {
      if (isCancelled) return
      isCancelled = true
    }

    fun release() {
      thread?.quitSafely()
      thread = null
    }
  }

  private companion object {
    const val THREAD_NAME = "filmstrip-thumbnail"
    const val NO_CLIP = "The composition draws nothing at that time, so there is no frame to render."
    const val NO_COPY = "The decoded frame could not be copied, so there is no frame to hand on."
    const val CANCELLED = "media3 cancelled the extraction before it produced a frame."

    // The future has already finished by the time this runs, so the callback costs the thread that
    // completed it one lambda and nothing else.
    val DIRECT = Executor { command -> command.run() }

    /**
     * Suspends until this extraction finishes.
     *
     * The future is not cancelled when the caller goes away. Frame extraction runs one task at a
     * time on a player shared across the process, and dropping the frame it is already decoding
     * costs less than leaving that player mid-task for the next reader to find.
     *
     * A frame resumed into a caller that has since gone is let go of rather than recycled, since
     * the bitmap is the shared player's, and it hands the same one out again.
     */
    suspend fun ListenableFuture<FrameExtractor.Frame>.awaitFrame(): FrameExtractor.Frame =
      suspendCancellableCoroutine { continuation ->
        addListener(
          {
            try {
              continuation.resume(get()) { _, _, _ -> }
            } catch (
              @Suppress("TooGenericExceptionCaught")
              broken: Exception,
            ) {
              continuation.resumeWithException(broken)
            }
          },
          DIRECT,
        )
      }

    /**
     * Whether [other] lowers to the graph this one does, which is what one lowering can serve.
     */
    fun ThumbnailRequest.lowersWith(other: ThumbnailRequest): Boolean =
      heightPx == other.heightPx &&
        effectsRevision == other.effectsRevision &&
        composition == other.composition
  }
}

/**
 * What lowering one thumbnail request settled on.
 */
internal sealed interface Media3ThumbnailPlan {
  /**
   * The edit lowered.
   */
  class Ready(
    val preview: Media3Preview,
  ) : Media3ThumbnailPlan

  /**
   * The edit cannot be rendered here.
   */
  class Refused(
    val error: ExportError,
  ) : Media3ThumbnailPlan
}

/**
 * Lowers a thumbnail request through the same media3 engine an export of it runs on.
 *
 * The natural output frame is settled first and the request's height applied against it, so a
 * thumbnail smaller than the export renders at the height that was asked for. That lowering is
 * handed the natural frame as the one text lays out against, which keeps a caption breaking on the
 * same words in a strip frame as in the exported file.
 *
 * A height at or above the natural one lowers once and renders at the export's own frame, since
 * there is nothing to gain from rendering a strip larger than the file it came from.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
@OptIn(InternalFilmstripApi::class)
internal class Media3ThumbnailPlanner(
  components: ComponentRegistry,
) {
  private val engine = media3ExportEngine(chainedProber(components), components.effectResolvers)

  suspend fun lower(request: ThumbnailRequest): Media3ThumbnailPlan {
    val natural =
      when (val result = engine.resolve(request.composition, ExportSpec())) {
        is ResolveResult.Refused -> return Media3ThumbnailPlan.Refused(result.error)
        is ResolveResult.Resolved -> result.composition
      }

    val naturalSize = natural.output.size
    val cap = request.heightPx.takeIf { it in 1..<naturalSize.height }
    val resolved =
      when (cap) {
        null -> {
          natural
        }
        else -> {
          when (
            val result = engine.resolve(request.composition, ExportSpec(targetHeight = cap), naturalSize)
          ) {
            is ResolveResult.Refused -> return Media3ThumbnailPlan.Refused(result.error)
            is ResolveResult.Resolved -> result.composition
          }
        }
      }

    return try {
      Media3ThumbnailPlan.Ready(resolved.toMedia3Preview())
    } catch (
      @Suppress("TooGenericExceptionCaught") failure: Exception,
    ) {
      Media3ThumbnailPlan.Refused(ExportError.InvalidComposition(failure.message ?: failure.toString()))
    }
  }
}
