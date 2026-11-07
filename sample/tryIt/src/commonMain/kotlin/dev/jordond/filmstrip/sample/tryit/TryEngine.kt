package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.sample.SamplePreset
import dev.jordond.filmstrip.sample.exportRefusalOf
import dev.jordond.filmstrip.sample.loadPreset
import dev.jordond.filmstrip.sample.releaseExport
import dev.jordond.filmstrip.sample.saveExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where an export stands.
 */
internal sealed interface ExportState {
  data object Idle : ExportState

  data class Running(
    val fraction: Float,
  ) : ExportState

  data class Done(
    val output: MediaSink,
  ) : ExportState
}

/**
 * Everything the app knows about the edit and the clip it runs over.
 *
 * @property source The clip once it has downloaded, or null until then.
 * @property attempt Counts the loads asked for, and a new one gets the screen a new player.
 * @property showing Whether a player has the clip up and has not failed since.
 * @property steps The steps of the edit the page has on.
 * @property position Where the player's playhead is on the edit.
 * @property export Where an export stands.
 * @property error The text of the last refusal or failed load, or null.
 * @property exportRefusal Why this device can't export at all, or null when it can or hasn't answered yet.
 * @property awaitingTap Whether the browser stopped playback and only a gesture inside the app can start it again.
 */
internal data class TryState(
  val source: MediaSource? = null,
  val attempt: Int = 0,
  val showing: Boolean = false,
  val steps: Steps = Steps(),
  val position: Duration = Duration.ZERO,
  val export: ExportState = ExportState.Idle,
  val error: String? = null,
  val exportRefusal: String? = null,
  val awaitingTap: Boolean = false,
)

/**
 * Holds the edit, applies each [Control] to it, loads the clip and runs the export. Main thread only.
 *
 * @param scope Where loads, exports and player watching run.
 * @param exporter Writes one edit out.
 * @param loadClip Fetches the clip.
 * @param save Hands a finished export to the reader.
 * @param release Lets go of a finished export.
 */
internal class TryEngine(
  private val scope: CoroutineScope,
  private val exporter: (EditComposition, ExportSpec) -> Flow<ExportStatus>,
  private val loadClip: suspend (SamplePreset) -> MediaSource = ::loadPreset,
  private val save: (MediaSink) -> Unit = ::saveExport,
  private val release: (MediaSink) -> Unit = ::releaseExport,
) {
  private val mutableState = MutableStateFlow(TryState())
  private var loadJob: Job? = null
  private var exportJob: Job? = null
  private var watchJob: Job? = null
  private var editJob: Job? = null
  private var watched: VideoPlayer? = null

  // While an edit with a landing of its own is loading, the player's playhead still reads the edit before it.
  private var holdingPosition = false
  private var pageHidden = false

  // A download reads its object url after the press that started it, so a saved export is let go of only when the next
  // one starts.
  private var saved: MediaSink? = null

  private val presetLoads = PresetLoads(scope, loadClip)

  /**
   * The state a screen draws from.
   */
  val state: StateFlow<TryState> = mutableState.asStateFlow()

  /**
   * Applies one change from the page.
   */
  fun apply(control: Control) {
    when (control) {
      is Control.SetSteps -> setSteps(control.steps)
      Control.Export -> export()
      Control.Save -> saveResult()
      Control.Cancel -> cancelExport()
      Control.Retry -> retry()
    }
  }

  /**
   * Fetches the clip, which a download already running or a clip already here answers without a second fetch.
   */
  fun load() {
    loadJob?.cancel()
    forgetPlayer()
    update { copy(attempt = attempt + 1, showing = false, error = null) }
    val load = presetLoads.request(beachClip)
    loadJob =
      scope.launch {
        load.await().fold(
          onSuccess = { source -> update { copy(source = source) } },
          onFailure = { failure -> update { copy(error = failure.message ?: "The clip could not be loaded.") } },
        )
      }
  }

  /**
   * Takes what the device said it can encode. With no video encoder EXPORT does nothing and the page says why, while
   * preview and the steps carry on.
   */
  fun onCapabilities(answer: CapabilitiesResult) {
    update { copy(exportRefusal = exportRefusalOf(answer)) }
  }

  /**
   * Starts [player] on a loop and follows it for the status the page shows. Every later change of the steps reaches
   * [player] from here.
   */
  fun watch(player: VideoPlayer) {
    forgetPlayer()
    watched = player
    watchJob =
      scope.launch {
        launch {
          player.positionFlow(POSITION_TICK).collect { position ->
            if (!holdingPosition) update { copy(position = position) }
          }
        }
        var looped: Duration? = null
        var refused = false
        var wanted = false
        player.state.collect { playerState ->
          if (wanted && !playerState.playWhenReady) onPlaybackTaken()
          wanted = playerState.playWhenReady
          val duration = playerState.duration
          if (duration != null && duration != looped) {
            looped = duration
            player.setLoopRange(TimeRange.of(Duration.ZERO, duration))
          }
          val status = playerState.status
          if (status is PlaybackStatus.Error) {
            refused = true
            update { copy(showing = false, error = status.error.message) }
          } else {
            if (refused) {
              refused = false
              update { copy(error = null) }
            }
            if (duration != null) update { copy(showing = true) }
          }
        }
      }
    player.play()
  }

  /**
   * Takes whether the page is hidden. The player stops itself on a hidden page and stays stopped, so it is started
   * again on the way back, which a browser that still refuses answers by stopping it again.
   */
  fun onPageHidden(hidden: Boolean) {
    pageHidden = hidden
    if (hidden || mutableState.value.awaitingTap) return
    watched?.takeUnless { it.state.value.playWhenReady }?.play()
  }

  /**
   * Takes a gesture inside the app, which is the only thing that can start playback the browser refused.
   */
  fun onGesture() {
    if (!mutableState.value.awaitingTap) return
    update { copy(awaitingTap = false) }
    watched?.play()
  }

  // Nothing in the sample pauses the player, so this is the browser. A hidden page answers for itself on its way back.
  private fun onPlaybackTaken() {
    if (!pageHidden) update { copy(awaitingTap = true) }
  }

  private fun retry() {
    if (!mutableState.value.showing) load()
  }

  private fun forgetPlayer() {
    watchJob?.cancel()
    editJob?.cancel()
    watched = null
    holdingPosition = false
    update { copy(awaitingTap = false) }
  }

  private fun setSteps(steps: Steps) {
    val current = mutableState.value
    if (steps == current.steps) return
    exportJob?.cancel()
    exportJob = null
    (current.export as? ExportState.Done)?.let { release(it.output) }
    // A new trim moves every edit position, and an edit still landing has not reached the player's reading yet.
    val landing =
      if (steps.trim != current.steps.trim || holdingPosition) {
        editPosition(sourcePosition(current.position, current.steps), steps)
      } else {
        null
      }
    update { copy(steps = steps, position = landing ?: position, export = ExportState.Idle, error = null) }
    sendEdit(steps, landing)
  }

  private fun sendEdit(
    steps: Steps,
    landing: Duration?,
  ) {
    val player = watched ?: return
    val source = mutableState.value.source ?: return
    editJob?.cancel()
    holdingPosition = landing != null
    editJob =
      scope.launch {
        player.setComposition(beachEdit(source, steps), startAt = landing)
        holdingPosition = false
      }
  }

  private fun export() {
    val current = mutableState.value
    val source = current.source ?: return
    if (current.exportRefusal != null || current.export is ExportState.Running) return
    saved?.let(release)
    saved = null
    (current.export as? ExportState.Done)?.let { release(it.output) }
    update { copy(export = ExportState.Running(0f), error = null) }
    exportJob =
      scope.launch {
        try {
          exporter(beachEdit(source, current.steps), ExportSpec())
            .onCompletion { cause -> if (cause == null) settleUnfinished() }
            .collect(::onExportStatus)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Throwable) {
          update { copy(export = ExportState.Idle, error = failure.message ?: "The export failed.") }
        }
      }
  }

  private fun settleUnfinished() {
    if (mutableState.value.export is ExportState.Running) update { copy(export = ExportState.Idle) }
  }

  private fun onExportStatus(status: ExportStatus) {
    when (status) {
      is ExportStatus.Progress -> update { copy(export = ExportState.Running(status.fraction)) }
      is ExportStatus.Success -> update { copy(export = ExportState.Done(status.output)) }
      is ExportStatus.Failure -> update { copy(export = ExportState.Idle, error = status.error.message) }
      else -> Unit
    }
  }

  private fun saveResult() {
    val done = mutableState.value.export as? ExportState.Done ?: return
    save(done.output)
    saved = done.output
    update { copy(export = ExportState.Idle) }
  }

  private fun cancelExport() {
    if (mutableState.value.export !is ExportState.Running) return
    exportJob?.cancel()
    exportJob = null
    update { copy(export = ExportState.Idle) }
  }

  private inline fun update(change: TryState.() -> TryState) {
    mutableState.value = mutableState.value.change()
  }
}

/**
 * What the page draws for [state].
 */
internal fun statusOf(state: TryState): TryStatus =
  TryStatus(
    position = sourcePosition(state.position, state.steps),
    duration = beachClip.duration.takeIf { state.showing },
    trimRange = TRIM_RANGE.takeIf { state.steps.trim },
    export = state.export,
    exportRefusal = state.exportRefusal,
    error = state.error,
    awaitingTap = state.awaitingTap,
  )

/**
 * What the page hears as [states] change, only when that differs from what it last heard.
 */
internal fun statusesOf(states: Flow<TryState>): Flow<TryStatus> = states.map(::statusOf).distinctUntilChanged()

private val POSITION_TICK: Duration = 100.milliseconds
