package dev.jordond.filmstrip.export

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import kotlin.time.Duration

/**
 * What an export is doing, as it does it.
 *
 * There is no cancelled arm. Cancel by cancelling the collecting scope, which cancels the export
 * and propagates a `CancellationException`.
 */
public sealed interface ExportStatus {
  /**
   * The terminal statuses, as their own type.
   *
   * Implemented only by [Success] and [Failure], so a caller that only cares about the outcome
   * branches on two.
   */
  public sealed interface Finished : ExportStatus

  /**
   * The export has actually begun.
   *
   * Not the same as the call returning. Exports run one at a time across the process, so this may
   * arrive well after `export` was called. It is how a caller tells queued from running.
   */
  public data object Started : ExportStatus

  /**
   * Emitted at most once, before any [Progress], when the plan differs from the request.
   *
   * Only the [dev.jordond.filmstrip.Filmstrip.export] that plans emits this. Running an
   * [ExportPlan] emits none, since the verdict that plan came from already carried the list, and
   * [Success.adjustments] carries it again at the end whichever overload was called.
   *
   * @property adjustments What filmstrip changed to make the export possible, in the order applied.
   */
  @Poko
  public class Adjusted(
    public val adjustments: List<Adjustment>,
  ) : ExportStatus

  /**
   * How far along the export is.
   *
   * @property fraction Completion in `0f..1f`. Monotonic: it never goes backwards.
   * @property position Media time processed so far, or null when the backend cannot report it.
   * @property estimatedRemaining Wall-clock estimate of the time left, or null before there is
   *   enough data for one.
   */
  @Poko
  public class Progress(
    public val fraction: Float,
    public val position: Duration?,
    public val estimatedRemaining: Duration?,
  ) : ExportStatus

  /**
   * The export finished and wrote [output].
   *
   * @property output Where the file is. A [MediaSink.Temporary] request is resolved to a real path here.
   * @property info What the written file turned out to be.
   * @property adjustments What filmstrip changed, non-empty when the plan was degraded or the run changed something the
   * plan could not see, as [ExportSpec.strict] describes.
   */
  @Poko
  public class Success(
    public val output: MediaSink,
    public val info: MediaInfo,
    public val adjustments: List<Adjustment>,
  ) : ExportStatus,
    Finished

  /**
   * The export failed.
   *
   * A run that stopped part way has one reason. An edit refused before any work started has every
   * reason [dev.jordond.filmstrip.Filmstrip.plan] would have given for it, in the same order.
   *
   * @property reasons Why it failed, never empty.
   * @throws IllegalArgumentException when [reasons] is empty.
   */
  @Poko
  public class Failure(
    public val reasons: List<FilmstripError>,
  ) : ExportStatus,
    Finished {
    /**
     * A failure with one reason, which is how a run that stopped part way reports.
     */
    public constructor(error: FilmstripError) : this(listOf(error))

    init {
      require(reasons.isNotEmpty()) { "A failure needs at least one reason" }
    }

    /**
     * The first of [reasons], the one that stopped the export.
     */
    public val error: FilmstripError get() = reasons.first()
  }
}
