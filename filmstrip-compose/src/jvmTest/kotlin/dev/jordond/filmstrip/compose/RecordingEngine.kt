package dev.jordond.filmstrip.compose

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewFrameReadback
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
 */
internal class RecordingEngine : PlayerEngine {
  private var listeners: List<EngineListener> = emptyList()

  val loads: MutableList<EditComposition> = mutableListOf()
  val seeks: MutableList<Duration> = mutableListOf()

  var isScrubbing: Boolean = false
    private set

  override val id: String = "filmstrip.compose-test"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    loads += request.composition
    listeners.forEach { it.onStateChanged(READY) }
    listeners.forEach { it.onEvent(PlaybackEvent.FirstFrameRendered) }
    callback.onResult(SetCompositionResult.Success(CLIP_LENGTH))
    return Cancellable { }
  }

  override fun addListener(listener: EngineListener): Cancellable {
    listeners = listeners + listener
    listener.onStateChanged(READY)
    return Cancellable { listeners = listeners - listener }
  }

  /**
   * Pushes one playhead reading, the way a ticker would.
   */
  fun reportPosition(position: Duration) {
    listeners.forEach { it.onPosition(position) }
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

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  override fun dispose() {
    listeners = emptyList()
  }

  private companion object {
    val READY = PlayerState(PlaybackStatus.Ready, false, false, false, CLIP_LENGTH)
  }
}

internal val CLIP_LENGTH: Duration = 4.seconds

/**
 * A one clip composition over [path], which nothing here ever opens.
 */
internal fun testComposition(path: String): EditComposition =
  EditComposition(
    tracks = listOf(Track(listOf(Clip(MediaSource.of(path), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
  )

/**
 * A `Filmstrip` whose only backend is [engine].
 */
internal fun filmstripWith(engine: RecordingEngine): Filmstrip = Filmstrip { addPlayerEngineFactory { _, _ -> engine } }
