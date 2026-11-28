package dev.jordond.filmstrip.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerEngineId
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An engine with no platform under it, whose playhead a test puts where it wants.
 *
 * It reports [length] as the duration, or none when [length] is null, and its position to every listener as soon as
 * one is added and on every [moveTo].
 */
internal class ClockEngine(
  private val length: Duration? = CLOCK_LENGTH,
  private var position: Duration = Duration.ZERO,
) : PlayerEngine {
  private var listeners: List<EngineListener> = emptyList()

  private val ready = PlayerState(PlaybackStatus.Ready, false, false, false, length)

  override val id: PlayerEngineId = PlayerEngineId("filmstrip.compose-ui-clock-test")

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  fun moveTo(position: Duration) {
    this.position = position
    listeners.forEach { it.onPosition(position) }
  }

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    callback.onResult(SetCompositionResult.Success(length ?: Duration.ZERO))
    return Cancellable { }
  }

  override fun addListener(listener: EngineListener): Cancellable {
    listeners = listeners + listener
    listener.onStateChanged(ready)
    listener.onEvent(PlaybackEvent.FirstFrameRendered)
    listener.onPosition(position)
    return Cancellable { listeners = listeners - listener }
  }

  override fun play(): Unit = Unit

  override fun pause(): Unit = Unit

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ): Unit = Unit

  override fun stepFrames(frames: Int): Unit = Unit

  override fun beginScrub(): Unit = Unit

  override fun endScrub(): Unit = Unit

  override fun beginShuttle(rate: Float): Unit = Unit

  override fun endShuttle(): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  override fun setExportSpec(spec: ExportSpec): Unit = Unit

  override fun dispose() {
    listeners = emptyList()
  }
}

internal val CLOCK_LENGTH: Duration = 10.seconds

/**
 * The span a stage over a [ClockEngine] of the default length draws its elements in.
 */
internal val CLOCK_SPAN: TimeRange = TimeRange.of(Duration.ZERO, CLOCK_LENGTH)

/**
 * A player whose only backend is [engine].
 */
@Composable
internal fun rememberClockPlayer(engine: ClockEngine): VideoPlayer =
  rememberFilmstripPlayer(
    previews = remember(engine) { Filmstrip { autoRegister(false).addPlayerEngineFactory { _, _ -> engine } } },
    composition =
      remember {
        Composition(listOf(Track(listOf(Clip(MediaSource.of("clock.mp4"), TimeRange.of(Duration.ZERO, CLOCK_LENGTH))))))
      },
  )
