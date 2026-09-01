package dev.jordond.filmstrip.compose.ui

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PlatformImage
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
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import java.awt.image.BufferedImage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An engine with no platform under it, counting the scrub protocol's calls.
 *
 * The gesture tests here need a real `ScrubState` , which only a real player builds, so this stands in for the platform
 * rather than for the protocol.
 */
internal class ScrubbingEngine : PlayerEngine {
  private var listeners: List<EngineListener> = emptyList()

  val seeks: MutableList<Duration> = mutableListOf()

  var scrubStarts: Int = 0
    private set

  var scrubEnds: Int = 0
    private set

  override val id: String = "filmstrip.compose-ui-test"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
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
    scrubStarts++
  }

  override fun endScrub() {
    scrubEnds++
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

internal val CLIP_LENGTH: Duration = 30.seconds

/**
 * A one clip composition over a path nothing here ever opens.
 */
internal fun testComposition(): EditComposition =
  EditComposition(
    tracks = listOf(Track(listOf(Clip(MediaSource.of("scrub.mp4"), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
  )

/**
 * A `Filmstrip` whose only backend is [engine].
 */
internal fun filmstripWith(engine: ScrubbingEngine): Filmstrip = Filmstrip { addPlayerEngineFactory { _, _ -> engine } }

/**
 * A thumbnail source that records what it was asked for and answers at once.
 *
 * It answers because the dispatcher serialises requests: a source that never replies leaves every position after the
 * first one queued, and the strip's window would look like one tile.
 */
internal class RecordingThumbnailSource : ThumbnailSource {
  val requests: MutableList<ThumbnailRequest> = mutableListOf()
  val requested: MutableList<Duration> = mutableListOf()

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    requests += request
    requested += request.position
    val image = PlatformImage(BufferedImage(FRAME_SIZE, FRAME_SIZE, BufferedImage.TYPE_INT_ARGB))
    callback.onThumbnail(ThumbnailResult.Success(image, request.position))
    return Cancellable { }
  }

  private companion object {
    const val FRAME_SIZE = 8
  }
}

/**
 * A `Filmstrip` whose only backend is [source].
 */
internal fun filmstripWith(source: RecordingThumbnailSource): Filmstrip =
  Filmstrip { addThumbnailSourceFactory { _, _ -> source } }
