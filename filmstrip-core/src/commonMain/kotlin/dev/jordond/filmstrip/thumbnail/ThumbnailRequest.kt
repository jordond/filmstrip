package dev.jordond.filmstrip.thumbnail

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.PlatformImage
import kotlin.time.Duration

/**
 * Receives the outcome of each request in one [ThumbnailSource.requestThumbnails].
 */
public fun interface ThumbnailBatchCallback {
  /**
   * Called once per request, at its index in the list that was asked for.
   */
  public fun onThumbnail(
    index: Int,
    result: ThumbnailResult,
  )
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
 *   aspect. Null renders at the composition's own output height, and so does a height at or above
 *   it, since a frame is never scaled up.
 * @property effectsRevision An opaque token over everything that would change a rendered frame,
 *   and part of the cache key so stale pre-crop thumbnails do not survive an edit. Compare it for
 *   equality only. It changes on a structural edit and on the commit of a crop or rotation drag,
 *   and holds during the drag, but it does not increase and carries no ordering.
 * @property precise Whether the frame has to be the one covering [position]. False lets a source
 *   answer from the nearest sync sample instead, taking the faster read where it has one, which is
 *   what a strip wants. [ThumbnailResult.Success.presentationTime] says which frame came back
 *   either way.
 * @property exportSpec The export this frame stands in for, which renders it at the HDR grade that export resolves to.
 * @throws IllegalArgumentException when [heightPx] is zero or less.
 */
@Poko
public class ThumbnailRequest(
  public val composition: Composition,
  public val position: Duration,
  public val heightPx: Int?,
  public val effectsRevision: Long,
  public val precise: Boolean,
  public val exportSpec: ExportSpec = ExportSpec(),
) {
  init {
    require(heightPx == null || heightPx > 0) { "A thumbnail height has to be positive or null, got $heightPx." }
  }
}

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
    public val error: FilmstripError,
  ) : ThumbnailResult
}
