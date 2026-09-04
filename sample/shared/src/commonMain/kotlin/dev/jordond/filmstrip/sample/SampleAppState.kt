package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the editor is currently editing, which decides what the tool panel shows.
 */
public enum class EditorTool {
  Trim,
  Crop,
  Transform,
  Scale,
  Adjust,
  Text,
  Watermark,
  Audio,
  Background,
}

/**
 * Drives one pick-to-export session against a [Filmstrip], exposing each step as compose state.
 *
 * The edit itself lives in [edit]. Everything here is the session around it: what was picked, what
 * the device said about it, and what came back from a run.
 */
@Stable
public class SampleAppState(
  internal val filmstrip: Filmstrip,
  public val recorder: DiagnosticsRecorder = DiagnosticsRecorder(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
  public val edit: EditState = EditState()

  /**
   * What each registered backend calls itself, for the first line of a bug report.
   */
  public val backends: List<BackendInfo> get() = filmstrip.components.backends

  /**
   * Where the app is, newest last. Handed straight to `NavDisplay`.
   *
   * It lives on the state rather than in composition because the state outlives the activity, so a
   * rotation mid-export lands back on the screen the export was started from.
   */
  public val backStack: NavBackStack<SampleRoute> = NavBackStack(SampleRoute.Editor)

  var source: MediaSource? by mutableStateOf(null)
    private set
  var sourceLabel: String by mutableStateOf("")
    private set
  var probing: Boolean by mutableStateOf(false)
    private set
  var probe: ProbeResult? by mutableStateOf(null)
    private set
  var pickFailure: String? by mutableStateOf(null)
    private set
  var loadingPreset: SamplePreset? by mutableStateOf(null)
    private set
  var sourcePreset: SamplePreset? by mutableStateOf(null)
    private set

  private var activeToolState: EditorTool by mutableStateOf(EditorTool.Trim)

  /**
   * Which tool panel is open, which decides what the tool panel shows.
   *
   * Reloads the live preview on the way in or out of [croppingRect], since that flips whether the
   * player renders the rectangle crop.
   */
  var activeTool: EditorTool
    get() = activeToolState
    set(value) {
      val wasCroppingRect = croppingRect
      activeToolState = value
      if (croppingRect != wasCroppingRect) reloadPreview()
    }

  var positionSeconds: Float by mutableStateOf(0f)
    private set
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
   * True where the player reported no preview backend rather than a failure while playing. Every
   * platform ships one, so a build reaching this is one that never registered it.
   */
  val previewUnavailable: Boolean
    get() = previewError is PlaybackError.BackendMissing

  /**
   * True while the crop tool is open on a rectangle crop, which is when the crop overlay is drawn
   * over the stage and the live preview renders the frame before that crop rather than after it.
   */
  val croppingRect: Boolean
    get() = activeTool == EditorTool.Crop && edit.cropMode == CropMode.Rect

  private var loopingState: Boolean by mutableStateOf(true)

  var looping: Boolean
    get() = loopingState
    set(value) {
      loopingState = value
      applyLoopRange()
    }

  var targetHeight: Int? by mutableStateOf(1080)
  var videoCodec: VideoCodec by mutableStateOf(VideoCodec.Auto)
  var audioCodec: AudioCodec by mutableStateOf(AudioCodec.Auto)
  var bitrateMbps: Int? by mutableStateOf(4)
  var frameRate: Int? by mutableStateOf(null)
  var hdr: HdrMode by mutableStateOf(HdrMode.Auto)
  var strict: Boolean by mutableStateOf(false)

  var planning: Boolean by mutableStateOf(false)
    private set
  var verdict: Verdict? by mutableStateOf(null)
    private set

  var exporting: Boolean by mutableStateOf(false)
    private set
  var exportProgress: ExportStatus.Progress? by mutableStateOf(null)
    private set
  var exportAdjustments: List<Adjustment> by mutableStateOf(emptyList())
    private set
  var exported: ExportStatus.Success? by mutableStateOf(null)
    private set
  var exportedInfo: ProbeResult? by mutableStateOf(null)
    private set
  var exportFailure: ExportError? by mutableStateOf(null)
    private set

  var capabilities: CapabilitiesResult? by mutableStateOf(null)
    private set
  var loadingCapabilities: Boolean by mutableStateOf(false)
    private set

  private var exportJob: Job? = null
  private var exportGeneration = 0
  private var clockJob: Job? = null
  private var previewJobs: List<Job> = emptyList()
  private var reloadJob: Job? = null

  val info: MediaInfo?
    get() = (probe as? ProbeResult.Success)?.info

  val sourceDuration: Duration?
    get() = info?.duration

  val sourceAspect: Float
    get() = info?.video?.displaySize?.aspect?.takeIf { it > 0f } ?: DEFAULT_ASPECT

  val editedDuration: Duration
    get() = edit.editedDuration(sourceDuration) ?: Duration.ZERO

  /**
   * Where the playhead sits inside the trimmed edit, in seconds.
   */
  val editedDurationSeconds: Float
    get() = editedDuration.inWholeMilliseconds / 1000f

  fun onPicked(source: MediaSource?, label: String, preset: SamplePreset? = null) {
    if (source == null) return

    this.source = source
    sourceLabel = label
    sourcePreset = preset
    pickFailure = null
    resetRun()
    stopPreview()
    positionSeconds = 0f
    // The label is the user's own file name, so the log gets the same redaction the report does.
    recorder.record("source.picked", "source" to (preset?.fileName ?: source.redactedName()))

    probing = true
    probe = null
    scope.launch {
      try {
        val result = filmstrip.probe(source)
        probe = result
        when (result) {
          is ProbeResult.Success -> recorder.record("source.probed", "info" to result.info.toString())
          is ProbeResult.Failure -> recorder.record("source.unreadable", "error" to result.error.message)
        }
        edit.reset((result as? ProbeResult.Success)?.info?.duration)
        activeTool = EditorTool.Trim
      } finally {
        probing = false
      }
    }
  }

  /**
   * Downloads [preset] and opens the editor on it, reporting through [pickFailure] if the download
   * fails.
   */
  fun pickPreset(preset: SamplePreset) {
    if (loadingPreset != null) return

    loadingPreset = preset
    pickFailure = null
    scope.launch {
      try {
        recorder.record("preset.download", "clip" to preset.fileName)
        onPicked(loadPreset(preset), preset.name, preset)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (failure: Throwable) {
        pickFailure = failure.message ?: "The download gave no reason."
        recorder.record("preset.failed", "clip" to preset.fileName, "reason" to pickFailure.orEmpty())
      } finally {
        loadingPreset = null
      }
    }
  }

  /**
   * Records that the platform picker could not finish, which is not the same as the user declining.
   */
  fun onPickFailed(message: String?) {
    pickFailure = message ?: "No reason given."
    recorder.record("source.pickFailed", "reason" to pickFailure.orEmpty())
  }

  /**
   * Drops the plan and the export an edit just invalidated.
   */
  fun onEditChanged() {
    verdict = null
    exported = null
    exportedInfo = null
    exportFailure = null
    exportProgress = null
    exportAdjustments = emptyList()
    positionSeconds = positionSeconds.coerceIn(0f, editedDurationSeconds)
    reloadPreview()
  }

  /**
   * Pushes a route, unless it is already the one on top.
   */
  fun navigateTo(route: SampleRoute) {
    if (backStack.lastOrNull() != route) backStack.add(route)
  }

  /**
   * Pops one route. The editor is the root, so back from it is left to the platform.
   */
  fun navigateBack() {
    if (backStack.size <= 1) return
    backStack.removeLastOrNull()
  }

  fun openExport() {
    navigateTo(SampleRoute.Export)
  }

  fun openCapabilities() {
    navigateTo(SampleRoute.Capabilities)
    refreshCapabilities()
  }

  fun openDiagnostics() {
    navigateTo(SampleRoute.Diagnostics)
  }

  /**
   * Drops the clip and everything derived from it, leaving the root on its picker.
   */
  fun closeProject() {
    stopPreview()
    exportJob?.cancel()
    source = null
    sourceLabel = ""
    sourcePreset = null
    probe = null
    pickFailure = null
    resetRun()
    backStack.clear()
    backStack.add(SampleRoute.Editor)
  }

  fun composition(): EditComposition? {
    val source = source ?: return null
    return edit.composition(source, sourceDuration)
  }

  /**
   * The composition the live player renders.
   *
   * Omits the rectangle crop while [croppingRect] is true, since the overlay drawn over the player
   * authors that rectangle against the frame before the crop rather than after it.
   */
  private fun previewComposition(): EditComposition? {
    val source = source ?: return null
    return edit.composition(source, sourceDuration, cropped = !croppingRect)
  }

  /**
   * The current edit over the whole source, with no trim applied.
   *
   * Built for the timeline strip, which lays the source out end to end and draws the trim window
   * over it rather than following it.
   */
  fun filmstripComposition(): EditComposition? {
    val source = source ?: return null
    return edit.composition(source, sourceDuration, trimmed = false)
  }

  fun spec(): ExportSpec = ExportSpec(
    targetHeight = targetHeight,
    bitrate = bitrateMbps?.let(Bitrate::mbps),
    videoCodec = videoCodec,
    audioCodec = audioCodec,
    frameRate = frameRate,
    hdr = hdr,
    strict = strict,
  )

  /**
   * Opens a player over the current edit, replacing whatever was open.
   *
   * The preview screen calls this on the way in and [stopPreview] on the way out, so the player
   * lives exactly as long as the surface it draws into.
   */
  fun startPreview() {
    val composition = previewComposition() ?: return
    stopPreview()

    val opened = filmstrip.preview(composition, PlayerConfig())
    player = opened
    recorder.record("player.opened", "duration" to editedDuration.toString())
    observe(opened)
    applyLoopRange()
  }

  /**
   * Closes the player and drops everything derived from it.
   */
  fun stopPreview() {
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
    playing = false
    stopClock()
  }

  fun togglePlay() {
    if (playing) pause() else play()
  }

  fun play() {
    val open = livePlayer()
    if (open == null) {
      startLocalClock()
      return
    }

    recorder.record("player.play", "playWhenReady" to "true")
    open.play()
  }

  fun pause() {
    val open = livePlayer()
    if (open == null) {
      stopClock()
      return
    }

    recorder.record("player.pause", "playWhenReady" to "false")
    open.pause()
  }

  fun seekTo(seconds: Float) {
    val target = seconds.coerceIn(0f, editedDurationSeconds)
    positionSeconds = target
    livePlayer()?.seekTo(target.toDouble().seconds)
  }

  fun beginScrub() {
    livePlayer()?.beginScrub()
  }

  fun endScrub() {
    livePlayer()?.endScrub()
  }

  fun stepFrames(frames: Int) {
    val open = livePlayer()
    if (open != null && open.features.supports(PlayerFeature.FrameStepping)) {
      open.stepFrames(frames)
      return
    }

    val fps = frameRate ?: info?.video?.frameRate?.toInt() ?: DEFAULT_FRAME_RATE
    seekTo(positionSeconds + frames.toFloat() / fps)
  }

  fun plan() {
    if (planning) return
    val composition = composition() ?: return
    verdict = null
    planning = true
    scope.launch {
      try {
        val result = filmstrip.plan(composition, spec())
        verdict = result
        recorder.record("plan.done", "verdict" to (result::class.simpleName ?: "unknown"))
      } finally {
        planning = false
      }
    }
  }

  fun export(plan: ExportPlan) {
    exportJob?.cancel()
    exported = null
    exportedInfo = null
    exportFailure = null
    exportProgress = null
    exportAdjustments = emptyList()
    pause()

    // The flag is flipped here rather than inside the coroutine, and cleared only by the run that
    // still owns it. cancel() above does not wait, so a superseded run's finally lands after the
    // replacement has started and would otherwise report the new export as finished.
    val generation = ++exportGeneration
    exporting = true
    exportJob = scope.launch {
      try {
        filmstrip.export(plan, MediaSink.temporary()).collect { status ->
          when (status) {
            is ExportStatus.Started -> recorder.record("export.started", "path" to plan.path.name)
            is ExportStatus.Adjusted -> {
              exportAdjustments = status.adjustments
              recorder.record(
                label = "export.adjusted",
                detail = status.adjustments.associate { it.kind.name to "${'$'}{it.requested} -> ${'$'}{it.resolved}" },
              )
            }
            is ExportStatus.Progress -> exportProgress = status
            is ExportStatus.Success -> {
              exported = status
              exportedInfo = filmstrip.probe(status.output.asSource())
              recorder.record("export.succeeded", "output" to status.info.toString())
              backStack.remove(SampleRoute.Export)
              navigateTo(SampleRoute.Result)
            }
            is ExportStatus.Failure -> {
              exportFailure = status.error
              recorder.record(
                label = "export.failed",
                detail = mapOf(
                  "error" to (status.error::class.simpleName ?: "unknown"),
                  "message" to status.error.message,
                ),
              )
            }
          }
        }
      } finally {
        if (generation == exportGeneration) exporting = false
      }
    }
  }

  fun cancelExport() {
    exportJob?.cancel()
  }

  fun refreshCapabilities() {
    if (loadingCapabilities) return
    loadingCapabilities = true
    scope.launch {
      try {
        val result = filmstrip.capabilities()
        capabilities = result
        recorder.record("capabilities.read", "result" to (result::class.simpleName ?: "unknown"))
      } finally {
        loadingCapabilities = false
      }
    }
  }

  /**
   * How faithfully each effect in the current edit will preview, straight from the engine.
   */
  fun parity(): List<Pair<String, String>> =
    composition()?.effects.orEmpty().map { spec ->
      spec.id to (filmstrip.parityOf(spec.id)?.name ?: "unknown")
    }

  private fun livePlayer(): VideoPlayer? = player?.takeIf { !previewUnavailable }

  private fun observe(open: VideoPlayer) {
    // Events first, because they are edge-triggered with no replay and the player starts loading
    // the composition it was built with the moment it exists.
    previewJobs = listOf(
      scope.launch { open.events.collect(::onPlaybackEvent) },
      scope.launch { open.state.collect(::onPlayerState) },
      scope.launch {
        open.positionFlow(POSITION_TICK).collect { position ->
          // The player's own duration once it has loaded, so the clamp agrees with what it is
          // actually playing rather than with a bound this recomputes from the edit on its own.
          val bound = (open.state.value.duration ?: editedDuration).inWholeMilliseconds / 1000f
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
  private fun reloadPreview() {
    val open = player ?: return
    reloadJob?.cancel()
    reloadJob = scope.launch {
      delay(RELOAD_DEBOUNCE_MILLIS)
      val composition = previewComposition() ?: return@launch
      val result = open.setComposition(composition)
      if (result is SetCompositionResult.Failure) {
        recorder.record("player.loadFailed", "error" to result.error.message)
      }
      applyLoopRange()
    }
  }

  private fun applyLoopRange() {
    val open = livePlayer() ?: return
    val duration = editedDuration
    open.setLoopRange(if (loopingState && duration > Duration.ZERO) TimeRange(Duration.ZERO, duration) else null)
  }

  /**
   * Advances a local playhead while no player is open, so the timeline still moves.
   */
  private fun startLocalClock() {
    if (playing || editedDurationSeconds <= 0f) return
    playing = true
    clockJob = scope.launch {
      while (isActive) {
        delay(TICK_MILLIS)
        val next = positionSeconds + TICK_SECONDS
        val end = editedDurationSeconds
        positionSeconds = when {
          next < end -> next
          loopingState -> 0f
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

  private fun resetRun() {
    verdict = null
    exported = null
    exportedInfo = null
    exportFailure = null
    exportProgress = null
    exportAdjustments = emptyList()
  }

  private fun MediaSink.asSource(): MediaSource =
    when (this) {
      is MediaSink.Path -> MediaSource.of(path)
      is MediaSink.Uri -> MediaSource.ofUri(uri)
      MediaSink.Temporary -> error("A resolved temporary sink reports a path or uri.")
    }

  private companion object {
    const val TICK_MILLIS = 33L
    const val TICK_SECONDS = 0.033f
    const val RELOAD_DEBOUNCE_MILLIS = 150L
    const val DEFAULT_ASPECT = 16f / 9f
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
    is PlaybackEvent.Ended -> mapOf("at" to finalPosition.toString(), "duration" to duration.toString())
    is PlaybackEvent.SeekCompleted -> mapOf("at" to position.toString())
    PlaybackEvent.FirstFrameRendered -> emptyMap()
    is PlaybackEvent.RangeLooped -> mapOf("range" to range.toString())
    is PlaybackEvent.Failed -> mapOf("error" to (error::class.simpleName ?: "unknown"), "message" to error.message)
    is PlaybackEvent.ExternalPlayWhenReadyChanged -> mapOf("wanted" to playWhenReady.toString())
    is PlaybackEvent.EffectsDegraded -> adjustments.associate { it.kind.name to it.message }
    is PlaybackEvent.QualityDegraded -> mapOf("message" to message)
  }
