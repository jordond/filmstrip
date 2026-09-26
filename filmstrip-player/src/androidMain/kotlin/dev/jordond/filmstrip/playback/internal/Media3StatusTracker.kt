package dev.jordond.filmstrip.playback.internal

import androidx.media3.common.C
import androidx.media3.common.Player
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * Turns what media3 reports about its own state into the status, first frame and ending a listener sees.
 *
 * Holds the last state, position and duration media3 reported, and the edges that are reported once each: the first
 * frame of a composition on a surface, and the end of it. Everything here runs on the engine's dispatcher. The
 * platform fields are also read from other threads, which is why they are volatile.
 *
 * @param shutter Held closed over a rebuild and lifted on the first frame, or on a failure.
 * @param hasPreview Whether a lowered graph is loaded.
 * @param wrapsAtEnd Whether an end media3 reported fell inside the loop range, given whether it was running.
 * @param position Where the playhead reads now.
 * @param duration How long the loaded composition runs.
 * @param stopPlayer Stops media3's own transport.
 * @param setStatus Publishes a status.
 * @param setStalled Publishes whether playback waits on data.
 * @param setPlayWhenReady Publishes whether playback is asked to run.
 * @param reportExternalPlayWhenReady Publishes a transport change something outside filmstrip made.
 * @param emitEvent Sends an event to every listener.
 */
internal class Media3StatusTracker(
  private val shutter: PreviewShutter,
  private val hasPreview: () -> Boolean,
  private val wrapsAtEnd: (running: Boolean) -> Boolean,
  private val position: () -> Duration,
  private val duration: () -> Duration,
  private val stopPlayer: () -> Unit,
  private val setStatus: (PlaybackStatus) -> Unit,
  private val setStalled: (Boolean) -> Unit,
  private val setPlayWhenReady: (Boolean) -> Unit,
  private val reportExternalPlayWhenReady: (Boolean) -> Unit,
  private val emitEvent: (PlaybackEvent) -> Unit,
) {
  @Volatile
  var platformState: Int = Player.STATE_IDLE
    private set

  @Volatile
  var platformPositionMs: Long = 0

  @Volatile
  var platformDurationMs: Long = C.TIME_UNSET
    private set

  // Read on the player's thread and written on the engine's. media3 keeps a size read off the
  // holder only once it has a graph to hand it to, and this is the engine's view of whether it has.
  @Volatile
  var graphUp = false
    private set

  private var presentable = false
  private var surfaceRendered = false
  private var ended = false
  private var endReported = false
  private var firstFrameEmitted = false
  private var failure: PlaybackError? = null

  fun onPlatformState(
    state: Int,
    positionMs: Long,
    durationMs: Long,
    running: Boolean,
  ) {
    platformState = state
    platformPositionMs = positionMs
    platformDurationMs = durationMs

    if (state == Player.STATE_ENDED && wrapsAtEnd(running)) return

    when (state) {
      Player.STATE_READY -> {
        presentable = true
        graphUp = true
        ended = false
      }
      Player.STATE_ENDED -> {
        ended = true
      }
      else -> {
        Unit
      }
    }

    setStalled(state == Player.STATE_BUFFERING)
    publishStatus()
    reportFirstFrame()

    if (state == Player.STATE_ENDED && !endReported) {
      endReported = true
      setPlayWhenReady(false)
      emitEvent(PlaybackEvent.Ended(position(), duration()))
    }
  }

  /**
   * Publishes a transport change media3 made, unless it only echoes one filmstrip asked for.
   */
  fun onPlayWhenReadyChanged(
    playWhenReady: Boolean,
    reason: Int,
  ) {
    when {
      reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> Unit
      reachedEndOfMedia(reason) -> setPlayWhenReady(playWhenReady)
      isInterruption(reason) -> reportExternalPlayWhenReady(playWhenReady)
      else -> setPlayWhenReady(playWhenReady)
    }
  }

  /**
   * Arms the first frame once media3 says the attached surface has drawn one.
   */
  fun onRenderedFirstFrame() {
    surfaceRendered = true
    reportFirstFrame()
  }

  /**
   * Forgets the surface's first frame once the player lets go of it, so the next surface earns its own.
   */
  fun onSurfaceDetached() {
    surfaceRendered = false
    firstFrameEmitted = false
    shutter.reveal()
  }

  /**
   * Forgets the ending, so an end media3 reports after this is reported again.
   */
  fun clearEnd() {
    ended = false
    endReported = false
  }

  /**
   * Forgets a failure the graph has been given new parameters since.
   */
  fun clearFailure() {
    failure = null
  }

  /**
   * Forgets everything the last graph reported, as a fresh one is built.
   */
  fun reset() {
    graphUp = false
    failure = null
    presentable = false
    ended = false
    endReported = false
    firstFrameEmitted = false
  }

  fun fail(error: PlaybackError) {
    failure = error
    graphUp = false
    shutter.reveal()
    publishStatus()
    emitEvent(PlaybackEvent.Failed(error))
  }

  /**
   * Reacts to an occasion outside filmstrip that took playback away.
   *
   * The transport is stopped first and the change reported second, so a listener that reads the
   * player back on the event sees it already paused.
   */
  fun onInterrupted() {
    stopPlayer()
    reportExternalPlayWhenReady(false)
  }

  private fun publishStatus() {
    val error = failure
    setStatus(
      when {
        error != null -> PlaybackStatus.Error(error)
        ended -> PlaybackStatus.Ended
        presentable -> PlaybackStatus.Ready
        hasPreview() -> PlaybackStatus.Preparing
        else -> PlaybackStatus.Idle
      },
    )
  }

  /**
   * Emits [PlaybackEvent.FirstFrameRendered] on the edge where the loaded composition reaches an
   * attached surface.
   *
   * media3 raises `onRenderedFirstFrame` once per surface rather than once per composition, so a
   * graph rebuilt under a surface that is already drawing has only its own readiness to go on.
   * That is the edge here, and the surface's own signal is what arms it.
   *
   * Once per composition and once per surface: a host holds a shutter closed on this, and a surface
   * let go of and taken up again, which is what backgrounding does to a preview, has to earn its
   * own signal or the shutter never lifts on the way back.
   */
  private fun reportFirstFrame() {
    if (firstFrameEmitted || !surfaceRendered || !presentable) return
    firstFrameEmitted = true
    shutter.reveal()
    emitEvent(PlaybackEvent.FirstFrameRendered)
  }
}
