package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionRequest
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

// Records how each applied edit changed and every failure it reports, and stands a graph once a plan is applied unless
// told to refuse it.
internal class PlanRecordingEngine(
  parent: CoroutineScope,
  planner: PreviewPlanner<PreviewPlan>,
) : PlannedPlayerEngine<PreviewPlan>(parent, PreviewQualityPolicy.Full, ExportSpec(), planner) {
  val changes: MutableList<CompositionDiff> = mutableListOf()
  val failures: MutableList<PlaybackError> = mutableListOf()
  var refusal: PlaybackError? = null
  var graphStanding = false

  override val id: String = "filmstrip.planned"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  override val isGraphStanding: Boolean get() = graphStanding

  fun loadedOf(): Composition? = loaded

  fun planOf(): PreviewPlan? = plan

  fun policyOf(): PreviewQualityPolicy = policy

  fun failItem(error: PlaybackError) {
    setStatus(PlaybackStatus.Error(error))
  }

  override suspend fun apply(
    change: CompositionDiff,
    next: PreviewPlan,
    request: SetCompositionRequest,
  ): PlaybackError? {
    changes += change
    refusal?.let { return it }
    loaded = request.composition
    plan = next
    graphStanding = true
    return null
  }

  override fun fail(error: PlaybackError) {
    failures += error
  }

  override fun readPosition(): Duration = Duration.ZERO

  override fun isSeekReady(): Boolean = false

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ): Unit = Unit

  override fun onPlay(): Unit = Unit

  override fun onPause(): Unit = Unit

  override fun onRelease(): Unit = Unit

  override fun stepFrames(frames: Int): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit
}
