package dev.jordond.filmstrip.thumbnail

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripComponents

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

  /**
   * Asks for a run of frames, which a strip fetches together.
   *
   * Returns immediately and each frame arrives on [callback], in the order [requests] lists them,
   * one call per entry. Frames are produced one at a time, so a source that holds a decoder open
   * across the run serves the whole strip on one setup rather than on one per frame.
   *
   * Cancelling the returned handle stops the run where it is, and the entries not yet delivered
   * never are. The default walks [requests] through [requestThumbnail], which is what a source
   * with nothing to reuse between frames wants.
   *
   * @param requests Which frames, at what sizes, in the order they should arrive.
   * @param callback Receives each frame, or its failure, exactly once.
   * @return a handle that cancels the rest of the run.
   */
  public fun requestThumbnails(
    requests: List<ThumbnailRequest>,
    callback: ThumbnailBatchCallback,
  ): Cancellable = SerialThumbnailRun(this::requestThumbnail, requests, callback)
}

/**
 * Builds a [ThumbnailSource] for a request, or declines.
 *
 * Registered through the component registry and asked newest registration first, so one registered
 * later overrides one registered earlier and a plain metadata-only source and an effect-applied one
 * can coexist.
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
    components: FilmstripComponents,
  ): ThumbnailSource?
}
