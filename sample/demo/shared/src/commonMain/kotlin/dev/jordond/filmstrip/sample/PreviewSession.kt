package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The live preview of the edit and the transport that drives it.
 *
 * It opens a player over whatever [composition] returns, keeps the playhead, and runs a clock of its own while no
 * player is open so the timeline still moves.
 *
 * @param composition Builds the edit the player renders, or null while there is nothing to play.
 * @param spec The export settings the preview is opened with.
 * @param duration How long the edit runs.
 * @param frameRate The frame rate a frame step falls back on, or null when nothing says.
 */
@Stable
public class PreviewSession internal constructor(
  private val filmstrip: Filmstrip,
  private val scope: CoroutineScope,
  private val recorder: DiagnosticsRecorder,
  private val composition: () -> Composition?,
  private val spec: () -> ExportSpec,
  private val duration: () -> Duration,
  private val frameRate: () -> Int?,
) {
  var positionSeconds: Float by mutableStateOf(0f)
    internal set
  var playing: Boolean by mutableStateOf(false)
    private set

  /**
   * The preview player over the current edit, or null while no preview screen is showing.
   */
  var player: VideoPlayer? by mutableStateOf(null)
    private set

  /**
   * Where the preview player is in its lifecycle.
   */
  var playerStatus: PlaybackStatus by mutableStateOf(PlaybackStatus.Idle)
    private set

  /**
   * Why the preview cannot play, or null while it can.
   */
  var previewError: PlaybackError? by mutableStateOf(null)
    private set

  /**
   * The frame the composition outputs, as the preview reports it, or null until it has.
   */
  var previewOutput: Size? by mutableStateOf(null)
    private set

  private var loopingState: Boolean by mutableStateOf(true)

  var looping: Boolean
    get() = loopingState
    set(value) {
      loopingState = value
      applyLoopRange()
    }

  private var previewCapState: Int? by mutableStateOf(null)

  /**
   * The most the preview renders tall, or null for the composition's own height. Changing it reopens the player,
   * since a player keeps the policy it was opened with.
   */
  var previewCap: Int?
    get() = previewCapState
    set(value) {
      if (value == previewCapState) return
      previewCapState = value
      if (player == null) return
      val at = positionSeconds
      start()
      seekTo(at)
    }

  private var clockJob: Job? = null
  private var previewJobs: List<Job> = emptyList()
  private var reloadJob: Job? = null

  private val durationSeconds: Float
    get() = duration().inWholeMilliseconds / 1000f

  /**
   * What a preview is opened with: the export's spec, and the height cap when one is set.
   */
  fun playerConfig(): PlayerConfig =
    PlayerConfig(
      qualityPolicy = previewCap?.let(PreviewQualityPolicy::CapHeight) ?: PreviewQualityPolicy.Full,
      exportSpec = spec(),
    )

  /**
   * Opens a player over the current edit, replacing whatever was open.
   *
   * The preview screen calls this on the way in and [stop] on the way out, so the player
   * lives exactly as long as the surface it draws into.
   */
  fun start() {
    val composition = composition() ?: return
    stop()

    val opened = filmstrip.preview(composition, playerConfig())
    player = opened
    recorder.record("player.opened", "duration" to duration().toString())
    observe(opened)
    applyLoopRange()
  }

  /**
   * Closes the player and drops everything derived from it.
   */
  fun stop() {
    reloadJob?.cancel()
    reloadJob = null
    previewJobs.forEach { it.cancel() }
    previewJobs = emptyList()

    player?.let { open ->
      open.close()
      recorder.record("player.closed")
    }
    player = null
    playerStatus = PlaybackStatus.Idle
    previewError = null
    previewOutput = null
    playing = false
    stopClock()
  }

  fun togglePlay() {
    if (playing) pause() else play()
  }

  fun play() {
    val open = player
    if (open == null) {
      startLocalClock()
      return
    }

    recorder.record("player.play", "playWhenReady" to "true")
    open.play()
  }

  fun pause() {
    val open = player
    if (open == null) {
      stopClock()
      return
    }

    recorder.record("player.pause", "playWhenReady" to "false")
    open.pause()
  }

  fun seekTo(seconds: Float) {
    val target = seconds.coerceIn(0f, durationSeconds)
    positionSeconds = target
    player?.seekTo(target.asSeconds())
  }

  fun beginScrub() {
    player?.beginScrub()
  }

  fun endScrub() {
    player?.endScrub()
  }

  /**
   * Whether the player open now can shuttle, which is what the shuttle keys are shown for.
   */
  val canShuttle: Boolean
    get() = player?.features?.supports(PlayerFeature.Shuttle) == true

  fun beginShuttle(rate: Float) {
    val open = player ?: return
    recorder.record("player.beginShuttle", "rate" to rate.toString())
    open.beginShuttle(rate)
  }

  fun endShuttle() {
    val open = player ?: return
    recorder.record("player.endShuttle")
    open.endShuttle()
  }

  fun stepFrames(frames: Int) {
    val open = player
    if (open != null && open.features.supports(PlayerFeature.FrameStepping)) {
      open.stepFrames(frames)
      return
    }

    val fps = frameRate() ?: DEFAULT_FRAME_RATE
    seekTo(positionSeconds + frames.toFloat() / fps)
  }

  private fun observe(open: VideoPlayer) {
    // Events first, because they are edge-triggered with no replay and the player starts loading
    // the composition it was built with the moment it exists.
    previewJobs =
      listOf(
        scope.launch { open.events.collect(::onPlaybackEvent) },
        scope.launch { open.state.collect(::onPlayerState) },
        scope.launch { snapshotFlow { spec() }.collect(open::setExportSpec) },
        scope.launch {
          open.previewInfo.collect { info ->
            previewOutput = info.outputSize.takeIf { it.width > 0 && it.height > 0 }
          }
        },
        scope.launch {
          open.positionFlow(POSITION_TICK).collect { position ->
            // The player's own duration once it has loaded, so the clamp agrees with what it is
            // actually playing rather than with a bound this recomputes from the edit on its own.
            val bound = (open.state.value.duration ?: duration()).inWholeMilliseconds / 1000f
            positionSeconds = (position.inWholeMilliseconds / 1000f).coerceIn(0f, bound)
          }
        },
      )
  }

  private fun onPlayerState(snapshot: PlayerState) {
    playing = snapshot.playWhenReady
    previewError = (snapshot.status as? PlaybackStatus.Error)?.error

    if (snapshot.status == playerStatus) return
    playerStatus = snapshot.status
    recorder.record(
      label = "player.status",
      "status" to snapshot.status.label(),
      "playWhenReady" to snapshot.playWhenReady.toString(),
    )
  }

  private fun onPlaybackEvent(event: PlaybackEvent) {
    val wanted = player?.state?.value?.playWhenReady
    recorder.record(event.label(), event.detail() + ("playWhenReady" to wanted.toString()))
  }

  /**
   * Hands the edit to the open player, debounced so one drag issues one rebuild.
   */
  internal fun reload() {
    val open = player ?: return
    reloadJob?.cancel()
    reloadJob =
      scope.launch {
        delay(RELOAD_DEBOUNCE_MILLIS)
        val composition = composition() ?: return@launch
        val result = open.setComposition(composition)
        if (result is SetCompositionResult.Failure) {
          recorder.record("player.loadFailed", "error" to result.error.message)
        }
        applyLoopRange()
      }
  }

  private fun applyLoopRange() {
    val open = player ?: return
    val end = duration()
    open.setLoopRange(if (loopingState && end > Duration.ZERO) TimeRange(Duration.ZERO, end) else null)
  }

  /**
   * Advances a local playhead while no player is open, so the timeline still moves.
   */
  private fun startLocalClock() {
    if (playing || durationSeconds <= 0f) return
    playing = true
    clockJob =
      scope.launch {
        while (isActive) {
          delay(TICK_MILLIS)
          val next = positionSeconds + TICK_SECONDS
          val end = durationSeconds
          positionSeconds =
            when {
              next < end -> {
                next
              }
              loopingState -> {
                0f
              }
              else -> {
                playing = false
                end
              }
            }
          if (!playing) break
        }
      }
  }

  private fun stopClock() {
    clockJob?.cancel()
    clockJob = null
    playing = false
  }

  private companion object {
    const val TICK_MILLIS = 33L
    const val TICK_SECONDS = 0.033f
    const val RELOAD_DEBOUNCE_MILLIS = 150L
    const val DEFAULT_FRAME_RATE = 30

    val POSITION_TICK = 33.milliseconds
  }
}

/**
 * How a status reads in the session log.
 */
private fun PlaybackStatus.label(): String =
  when (this) {
    is PlaybackStatus.Error -> "Error(${error::class.simpleName ?: "unknown"})"
    else -> this::class.simpleName ?: "unknown"
  }

/**
 * The log label for an event, namespaced the way the rest of the session log is.
 */
private fun PlaybackEvent.label(): String =
  when (this) {
    is PlaybackEvent.Ended -> "player.ended"
    is PlaybackEvent.SeekCompleted -> "player.seeked"
    PlaybackEvent.FirstFrameRendered -> "player.firstFrame"
    is PlaybackEvent.RangeLooped -> "player.looped"
    is PlaybackEvent.ShuttleReachedEdge -> "player.shuttleEdge"
    is PlaybackEvent.Failed -> "player.failed"
    is PlaybackEvent.ExternalPlayWhenReadyChanged -> "player.external"
    is PlaybackEvent.EffectsDegraded -> "player.effectsDegraded"
    is PlaybackEvent.QualityDegraded -> "player.qualityDegraded"
  }

/**
 * The fields of an event worth carrying into a bug report.
 */
private fun PlaybackEvent.detail(): Map<String, String> =
  when (this) {
    is PlaybackEvent.Ended -> {
      mapOf("at" to finalPosition.toString(), "duration" to duration.toString())
    }
    is PlaybackEvent.SeekCompleted -> {
      mapOf("at" to position.toString())
    }
    PlaybackEvent.FirstFrameRendered -> {
      emptyMap()
    }
    is PlaybackEvent.RangeLooped -> {
      mapOf("range" to range.toString())
    }
    is PlaybackEvent.ShuttleReachedEdge -> {
      mapOf("edge" to edge.name, "at" to position.toString())
    }
    is PlaybackEvent.Failed -> {
      mapOf("error" to (error::class.simpleName ?: "unknown"), "message" to error.message)
    }
    is PlaybackEvent.ExternalPlayWhenReadyChanged -> {
      mapOf("wanted" to playWhenReady.toString())
    }
    is PlaybackEvent.EffectsDegraded -> {
      adjustments
        .groupBy({ it.kind.name }, { it.message })
        .mapValues { (_, messages) -> messages.joinToString(" | ") }
    }
    is PlaybackEvent.QualityDegraded -> {
      mapOf("message" to message)
    }
  }
