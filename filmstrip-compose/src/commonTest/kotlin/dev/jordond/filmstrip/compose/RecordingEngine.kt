package dev.jordond.filmstrip.compose

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An engine with no platform under it, recording what the compose layer asks of it.
 *
 * Every load reports a first frame, including one that changed nothing. A real engine would not,
 * but an event only the loading composable could have missed is exactly what these tests are for,
 * and a fake that emits selectively would hide a lost subscription behind a later load.
 *
 * @param readback What answers a frame request, which by default never answers at all.
 * @param nativePlayer What the engine hands a platform surface, which by default is nothing.
 * @param initialState What the engine reports before anything moves it, which by default is a loaded composition.
 */
internal class RecordingEngine(
  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } },
  override val nativePlayer: Any? = null,
  initialState: PlayerState = READY,
) : PlayerEngine {
  private var listeners: List<EngineListener> = emptyList()
  private var state = initialState

  val loads: MutableList<Composition> = mutableListOf()
  val seeks: MutableList<Duration> = mutableListOf()
  val policies: MutableList<PreviewQualityPolicy> = mutableListOf()
  val exportSpecs: MutableList<ExportSpec> = mutableListOf()

  var isScrubbing: Boolean = false
    private set

  override val id: String = "filmstrip.compose-test"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    loads += request.composition
    listeners.forEach { it.onStateChanged(state) }
    listeners.forEach { it.onEvent(PlaybackEvent.FirstFrameRendered) }
    callback.onResult(SetCompositionResult.Success(CLIP_LENGTH))
    return Cancellable { }
  }

  override fun addListener(listener: EngineListener): Cancellable {
    listeners = listeners + listener
    listener.onStateChanged(state)
    return Cancellable { listeners = listeners - listener }
  }

  /**
   * Pushes one playhead reading, the way a ticker would.
   */
  fun reportPosition(position: Duration) {
    listeners.forEach { it.onPosition(position) }
  }

  /**
   * Moves the engine to [next] and reports it, the way a load finishing or starting does.
   */
  fun reportState(next: PlayerState) {
    state = next
    listeners.forEach { it.onStateChanged(next) }
  }

  /**
   * Reports what the preview is delivering, the way an engine does once its graph is built.
   */
  fun reportPreviewInfo(info: PreviewInfo) {
    listeners.forEach { it.onPreviewInfo(info) }
  }

  override fun play(): Unit = Unit

  override fun pause(): Unit = Unit

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ) {
    seeks += position
  }

  override fun stepFrames(frames: Int): Unit = Unit

  override fun beginScrub() {
    isScrubbing = true
  }

  override fun endScrub() {
    isScrubbing = false
  }

  override fun beginShuttle(rate: Float): Unit = Unit

  override fun endShuttle(): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy) {
    policies += policy
  }

  override fun setExportSpec(spec: ExportSpec) {
    exportSpecs += spec
  }

  override fun dispose() {
    listeners = emptyList()
  }

  companion object {
    val READY: PlayerState = PlayerState(PlaybackStatus.Ready, false, false, false, CLIP_LENGTH)
    val PREPARING: PlayerState = PlayerState(PlaybackStatus.Preparing, false, false, false, null)
  }
}

internal val CLIP_LENGTH: Duration = 4.seconds

/**
 * A one clip composition over [path], which nothing here ever opens.
 */
internal fun testComposition(path: String): Composition =
  Composition(
    tracks = listOf(Track(listOf(Clip(MediaSource.of(path), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
  )

/**
 * A `Filmstrip` whose only backend is [engine].
 */
internal fun filmstripWith(engine: RecordingEngine): Filmstrip = Filmstrip { addPlayerEngineFactory { _, _ -> engine } }

/**
 * A preview reporting [outputSize] and nothing else of note.
 */
internal fun previewInfo(outputSize: Size): PreviewInfo =
  PreviewInfo(
    outputSize = outputSize,
    renderScale = 1f,
    parity = EffectParity.Exact,
    parityNotes = emptyList(),
    fidelity = emptyList(),
  )
