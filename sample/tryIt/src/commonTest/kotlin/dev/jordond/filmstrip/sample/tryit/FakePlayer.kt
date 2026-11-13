package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

/**
 * A player whose state and playhead a test sets, on top of the one a filmstrip with no backend hands out.
 */
@OptIn(InternalFilmstripApi::class)
internal class FakePlayer(
  delegate: VideoPlayer,
) : VideoPlayer by delegate {
  val playerState = MutableStateFlow(PlayerState.Initial)
  val playhead = MutableStateFlow(Duration.ZERO)
  val loopRanges = mutableListOf<TimeRange?>()
  val ticks = mutableListOf<Duration>()
  var plays = 0
  val edits = mutableListOf<LoadedEdit>()

  /**
   * Holds each load open when set, the way a rebuild keeps a player busy, until it completes.
   */
  var loadGate: CompletableDeferred<Unit>? = null

  override val state: StateFlow<PlayerState> = playerState

  override fun positionFlow(tick: Duration): Flow<Duration> =
    flow {
      ticks += tick
      playhead.collect { emit(it) }
    }

  override fun setLoopRange(range: TimeRange?) {
    loopRanges += range
  }

  override suspend fun setComposition(
    composition: Composition,
    startAt: Duration?,
    playWhenReady: Boolean,
  ): SetCompositionResult {
    edits += LoadedEdit(composition, startAt)
    loadGate?.await()
    return SetCompositionResult.Success(Duration.ZERO)
  }

  override fun play() {
    plays++
    setPlayWhenReady(true)
  }

  /**
   * What a browser does when it stops playback on its own.
   */
  fun platformPause() {
    setPlayWhenReady(false)
  }

  private fun setPlayWhenReady(wanted: Boolean) {
    val now = playerState.value
    playerState.value = PlayerState(now.status, wanted, now.isStalled, now.isSeeking, now.duration)
  }

  fun show(
    status: PlaybackStatus,
    duration: Duration? = null,
  ) {
    playerState.value = PlayerState(status, true, false, false, duration)
  }
}

internal data class LoadedEdit(
  val composition: Composition,
  val startAt: Duration?,
)

/**
 * A [FakePlayer] over [source], delegating to the player a filmstrip with no backend hands out.
 */
internal fun fakePlayer(source: MediaSource): FakePlayer =
  FakePlayer(Filmstrip { autoRegister(false) }.preview(compositionOf { clip(source) }, PlayerConfig()))
