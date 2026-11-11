package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * The test's hold on one export a [FakeFilmstrip] was asked to run.
 *
 * The flow the subject collects emits nothing by itself. Each call here sends one status down it, in the order the
 * calls are made, and [succeed] or [fail] ends it. A status sent before the flow is collected waits for the collector.
 *
 * Exports take turns the way real ones do. While an earlier export of the same [FakeFilmstrip] is being collected and
 * has not finished, the statuses of this one are held back, so its [ExportStatus.Started] arrives only once the
 * earlier one ends or is cancelled.
 *
 * Collecting the flow again runs the export again, driven by the same handle.
 *
 * @property plan The plan being run. For an export that was handed a composition, the plan its verdict carried.
 * @property sink Where the subject asked for the output to be written.
 */
public class FakeExport internal constructor(
  public val plan: ExportPlan,
  public val sink: MediaSink,
  private val turn: Mutex,
  private val opening: List<ExportStatus>,
) {
  private var statuses = Channel<ExportStatus>(Channel.UNLIMITED)
  private var started = false

  /**
   * Whether the flow is being collected and has its turn, which is when statuses reach the collector.
   */
  public var isRunning: Boolean = false
    private set

  /**
   * Whether the last run of the export delivered its [ExportStatus.Finished].
   */
  public var isFinished: Boolean = false
    private set

  /**
   * Whether a collector went away before the export finished, which is how a real export is cancelled.
   */
  public var wasCancelled: Boolean = false
    private set

  internal val flow: Flow<ExportStatus> =
    flow {
      var finished = false
      try {
        opening.forEach { emit(it) }
        turn.withLock {
          isRunning = true
          isFinished = false
          try {
            for (status in statuses) {
              finished = status is ExportStatus.Finished
              isFinished = finished
              emit(status)
              if (finished) break
            }
          } finally {
            // Only a run that had its turn clears what was sent, so statuses sent ahead of a collector survive one
            // that left while it was still waiting.
            isRunning = false
            statuses = Channel(Channel.UNLIMITED)
            started = false
          }
        }
      } catch (cancellation: CancellationException) {
        if (!finished) wasCancelled = true
        throw cancellation
      }
    }

  /**
   * Sends [ExportStatus.Started], as an export does when its turn comes. Does nothing the second time.
   */
  public fun start() {
    if (started) return
    started = true
    statuses.trySend(ExportStatus.Started)
  }

  /**
   * Sends [ExportStatus.Adjusted] with what the device changed.
   *
   * An export of a composition whose verdict is degraded already sends its adjustments first, without this.
   *
   * @param adjustments What the device changed.
   */
  public fun adjusted(adjustments: List<Adjustment>) {
    statuses.trySend(ExportStatus.Adjusted(adjustments))
  }

  /**
   * Sends [ExportStatus.Progress], starting the export first when [start] has not been called.
   *
   * @param fraction How much of the export is done, from 0 to 1.
   * @param position How far into the output the export is, or null when unknown.
   * @param estimatedRemaining How long the export has left, or null when unknown.
   */
  public fun progress(
    fraction: Float,
    position: Duration? = null,
    estimatedRemaining: Duration? = null,
  ) {
    start()
    statuses.trySend(ExportStatus.Progress(fraction, position, estimatedRemaining))
  }

  /**
   * Ends the export with [ExportStatus.Success], starting it first when [start] has not been called.
   *
   * @param info What the written file is, by default a file as long as the plan.
   * @param adjustments What the device changed along the way.
   * @param output Where the output ended up, by default the sink that was asked for.
   */
  public fun succeed(
    info: MediaInfo = testMediaInfo(duration = plan.duration),
    adjustments: List<Adjustment> = emptyList(),
    output: MediaSink = sink,
  ) {
    start()
    statuses.trySend(ExportStatus.Success(output, info, adjustments))
  }

  /**
   * Ends the export with [ExportStatus.Failure].
   *
   * The export is not started first, since a real one can fail before its turn comes. Call [start] before this for a
   * failure part way through.
   *
   * @param error Why the export failed.
   */
  public fun fail(error: FilmstripError) {
    statuses.trySend(ExportStatus.Failure(error))
  }
}
