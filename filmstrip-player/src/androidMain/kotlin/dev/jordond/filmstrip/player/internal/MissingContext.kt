package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * The player built when nothing installed a [FilmstripContext].
 *
 * Every load fails with [FilmstripContext.MISSING_CONTEXT] and puts playback in the error state, so a preview names
 * the missing install rather than a missing backend. The transport has nothing to drive and ignores every call.
 *
 * @param parent The scope this engine's own is a child of.
 */
internal class MissingContextPlayerEngine(
  parent: CoroutineScope,
) : BasePlayerEngine(parent) {
  private val error =
    PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, FilmstripContext.MISSING_CONTEXT)

  override val id: String = "filmstrip.media3"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback =
    PreviewFrameReadback { _, callback ->
      callback.onReadback(ReadbackResult.Failure(error))
      Cancellable { }
    }

  override val nativePlayer: Any? = null

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    setStatus(PlaybackStatus.Error(error))
    emitEvent(PlaybackEvent.Failed(error))
    callback.onResult(SetCompositionResult.Failure(error))
    return Cancellable { }
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

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  override fun setExportSpec(spec: ExportSpec): Unit = Unit
}

/**
 * The thumbnail source built when nothing installed a [FilmstripContext], which fails every request with
 * [FilmstripContext.MISSING_CONTEXT].
 */
internal object MissingContextThumbnailSource : ThumbnailSource {
  private val failure =
    ThumbnailResult.Failure(
      FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, FilmstripContext.MISSING_CONTEXT),
    )

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    callback.onThumbnail(failure)
    return Cancellable { }
  }
}
