package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.CONTRACT_COMPONENTS
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.jvmFixtureComposition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The fixture composition lowered for the preview the way the engine lowers it, once per test JVM.
 *
 * Resolving probes the fixture with the real toolchain, so the grid and the duration a test reads off it are the ones a
 * pump opened on it counts against.
 */
internal fun fixturePlan(): PreviewPlan = FIXTURE_PLAN

private val FIXTURE_PLAN: PreviewPlan by lazy {
  val planner = FfmpegPreviewPlanner(CONTRACT_COMPONENTS)
  runBlocking {
    when (val result = planner.plan(jvmFixtureComposition(), PreviewQualityPolicy.Full, ExportSpec())) {
      is PlanResult.Ready -> result.plan
      is PlanResult.Refused -> fail("the preview refused the fixture: ${result.error.message}")
    }
  }
}

/**
 * Opens whatever [open] hands back for each position a caller asks for, in place of spawning ffmpeg.
 *
 * Records every position it opened at, so a suite can tell whether a caller spawned at all.
 */
internal class StreamPlanner(
  private val open: (at: Duration) -> PreviewStreamResult,
) : FfmpegPreviewPlanner(CONTRACT_COMPONENTS) {
  val opened = mutableListOf<Duration>()

  override suspend fun open(
    plan: PreviewPlan,
    composition: EditComposition,
    at: Duration,
  ): PreviewStreamResult = open(at).also { opened += at }
}

/**
 * A stream opened at [startPosition] that hands over [frames] frames and then ends with [status].
 *
 * Each frame's single byte is its index from the stream's head, so a test can tell which frame a caller kept. Once
 * closed it reads back no status, the way a process the caller killed does.
 *
 * @param readGate Holds the read after the last frame until it completes.
 * @param failure What the stream classifies a failed exit as, or null for one carrying the status and [stderr].
 */
internal class IndexedStream(
  override val startPosition: Duration,
  private val frames: Int,
  private val status: Int = 0,
  private val stderr: String = "",
  private val readGate: CompletableDeferred<Unit>? = null,
  private val failure: FilmstripError? = null,
) : PreviewStream {
  private var sent = 0

  var closed: Boolean = false
    private set

  override val size: Size = Size(1, 1)

  override suspend fun next(): ByteArray? {
    if (sent == frames) readGate?.await()
    if (closed || sent == frames) return null
    return byteArrayOf((sent++).toByte())
  }

  override suspend fun exitStatus(): Int? = if (closed) null else status

  override fun errors(): String = stderr

  override fun exitError(status: Int): FilmstripError = failure ?: FilmstripError.Underlying(status, stderr)

  override fun close() {
    closed = true
  }
}
