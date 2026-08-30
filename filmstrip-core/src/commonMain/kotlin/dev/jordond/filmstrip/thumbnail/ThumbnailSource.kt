package dev.jordond.filmstrip.thumbnail

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.PlatformImage
import kotlin.time.Duration

/**
 * Produces frames for a timeline strip.
 *
 * An extension point, so a host can serve thumbnails from its own cache or from a server. Implement
 * it from Kotlin or from Swift and register it with a [ThumbnailSourceFactory].
 *
 * Kotlin callers do not use it directly. Filmstrip wraps it into `suspend` and `Flow` forms, and
 * both cancel the handle.
 */
public fun interface ThumbnailSource {
  /**
   * Asks for one frame.
   *
   * Returns immediately and the frame arrives on [callback]. Cancelling the returned handle must
   * stop the decode, not merely discard its result.
   *
   * @param request Which frame, at what size.
   * @param callback Receives the frame, or the failure, exactly once.
   * @return a handle that cancels the request.
   */
  public fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable
}

/**
 * Receives the outcome of one [ThumbnailSource.requestThumbnail].
 */
public fun interface ThumbnailCallback {
  /**
   * Called exactly once per request, unless the request was cancelled first.
   */
  public fun onThumbnail(result: ThumbnailResult)
}

/**
 * What one thumbnail was asked for.
 *
 * @property composition The composition to render, so the strip shows effect-applied frames rather
 *   than raw source frames.
 * @property position The composition time to render.
 * @property heightPx Target height in pixels. The width follows from the composition's output
 *   aspect.
 * @property effectsRevision Bumped whenever the effect chain changes, and part of the cache key so
 *   stale pre-crop thumbnails do not survive an edit. It advances on a structural change and on the
 *   commit of a crop or rotation drag, never during the drag.
 */
@Poko
public class ThumbnailRequest(
  public val composition: EditComposition,
  public val position: Duration,
  public val heightPx: Int,
  public val effectsRevision: Long,
)

/**
 * The outcome of one thumbnail request.
 */
public sealed interface ThumbnailResult {
  /**
   * A frame was produced. The caller owns [image] and must close it.
   *
   * @property image The rendered frame.
   * @property presentationTime The composition time actually rendered, which may differ from
   *   the requested position.
   */
  @Poko
  public class Success(
    public val image: PlatformImage,
    public val presentationTime: Duration,
  ) : ThumbnailResult

  /**
   * No frame was produced.
   *
   * @property error Why it failed.
   */
  @Poko
  public class Failure(
    public val error: ExportError,
  ) : ThumbnailResult
}

/**
 * Builds a [ThumbnailSource] for a request, or declines.
 *
 * Registered through the component registry and asked in registration order, so a plain
 * metadata-only source and an effect-applied one can coexist.
 */
public fun interface ThumbnailSourceFactory {
  /**
   * Builds a source able to serve [request].
   *
   * @param request Which frame, at what size.
   * @param components The registry the owning `Filmstrip` was built with, holding the same
   *   resolvers and probers an export runs through.
   * @return a source, or null to defer to the next factory.
   */
  public fun create(
    request: ThumbnailRequest,
    components: ComponentRegistry,
  ): ThumbnailSource?
}
