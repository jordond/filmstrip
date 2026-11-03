package dev.jordond.filmstrip.player.internal

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.Volatile

/**
 * Owns the `CompositionPlayer` on the thread media3 is confined to, and hands what it raises to the engine's
 * collaborators.
 *
 * `CompositionPlayer` confines every call and every callback to one looper, so the player is built on [thread] and
 * every callback it raises is hopped onto [scope] before it reaches [status] or [seeks].
 *
 * @param context The application context media3 decodes and renders against.
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param thread The thread the player is built on and confined to. Quit on [release].
 * @param status Hears the player's state, first frames and failures.
 * @param seeks Hears where each seek landed.
 * @param loops Mirrors the playhead while the player moves pixels.
 * @param surfaces Holds the view a fresh player draws into.
 * @param onBuilt Called on the player's thread with each player as it is built.
 * @param onReleased Called on the player's thread with each player just before it is released.
 */
@ExperimentalApi
internal class Media3PlayerHost(
  private val context: Context,
  private val scope: CoroutineScope,
  private val thread: HandlerThread,
  private val status: Media3StatusTracker,
  private val seeks: Media3SeekCoordinator,
  private val loops: Media3LoopRangeController,
  private val surfaces: Media3SurfaceManager,
  private val onBuilt: (CompositionPlayer) -> Unit,
  private val onReleased: (CompositionPlayer) -> Unit,
) {
  private val handler = Handler(thread.looper)

  /**
   * The player standing now, or null before [start] and after [release].
   */
  @Volatile
  var player: CompositionPlayer? = null
    private set

  // Read from the engine's dispatcher and written on the player's, so a listener registered for a
  // player that has since been released stops hearing without waiting for a cache line.
  @Volatile
  private var epoch = 0

  private var takesMultipleInputs = false

  /**
   * Builds the first player on the player's thread and waits for [describe] to answer from there.
   *
   * Only construction needs this. Everything after it is posted and nothing waits.
   */
  fun <T> start(describe: (CompositionPlayer) -> T): T {
    var outcome: Result<T>? = null
    val done = CountDownLatch(1)
    handler.post {
      outcome =
        runCatching {
          val built = buildPlayer(multipleInputs = false)
          built.addListener(PlayerListener(epoch))
          player = built
          onBuilt(built)
          describe(built)
        }
      done.countDown()
    }
    done.await()
    return checkNotNull(outcome).getOrThrow()
  }

  /**
   * The player that can draw a composition with this many video inputs, building a fresh one when
   * the standing player cannot. Player thread only.
   *
   * A video graph that takes one input is the default and the cheaper one. Handed a composition
   * with more than one video sequence it logs a warning and draws the wrong picture, so the choice
   * is made from the lowering.
   */
  fun playerFor(multipleInputs: Boolean): CompositionPlayer {
    val standing = player
    if (standing != null && takesMultipleInputs == multipleInputs) return standing

    standing?.let {
      onReleased(it)
      it.release()
    }
    epoch++
    takesMultipleInputs = multipleInputs
    return buildPlayer(multipleInputs).also {
      it.addListener(PlayerListener(epoch))
      surfaces.attached?.let(it::setVideoSurfaceView)
      player = it
      onBuilt(it)
    }
  }

  /**
   * Releases the player and stops its thread. Player thread only.
   */
  fun release() {
    player?.let {
      onReleased(it)
      it.release()
    }
    player = null
    thread.quitSafely()
  }

  private fun buildPlayer(multipleInputs: Boolean): CompositionPlayer =
    CompositionPlayer
      .Builder(context)
      .setLooper(thread.looper)
      // Audio focus handled by media3, which is what makes a focus loss reach the listener.
      .setAudioAttributes(MONITOR_AUDIO, true)
      // What buys a redrawn frame under a paused finger. It costs power and memory while the
      // preview is up, which is the whole of what a preview is for.
      .experimentalSetEnableReplayableCache(true)
      .apply { if (multipleInputs) setVideoGraphFactory(MultipleInputVideoGraph.Factory()) }
      .build()

  /**
   * Everything media3 pushes, marshalled from the player's thread onto the engine's.
   *
   * A player released and rebuilt for a different video graph leaves its own callbacks queued
   * behind it, so each listener carries the generation it was registered for and a stale one is
   * dropped rather than moving the snapshot of a graph that is gone.
   */
  private inner class PlayerListener(
    private val generation: Int,
  ) : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      val position = player?.currentPosition ?: 0
      val duration = player?.duration ?: C.TIME_UNSET
      val running = player?.playWhenReady == true
      dispatch { status.onPlatformState(playbackState, position, duration, running) }
    }

    override fun onPlayWhenReadyChanged(
      playWhenReady: Boolean,
      reason: Int,
    ) {
      dispatch { status.onPlayWhenReadyChanged(playWhenReady, reason) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      if (generation != epoch) return
      if (isPlaying) loops.startMirror() else loops.stopMirror()
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
        return
      }
      val landed = newPosition.positionMs
      val serial = seeks.playerSerial
      dispatch {
        status.platformPositionMs = landed
        seeks.complete(serial)
      }
    }

    override fun onRenderedFirstFrame() {
      dispatch { status.onRenderedFirstFrame() }
    }

    override fun onPlayerError(error: PlaybackException) {
      val reported = error.toPlaybackError()
      dispatch { status.fail(reported) }
    }

    private fun dispatch(block: () -> Unit) {
      if (generation != epoch) return
      scope.launch { if (generation == epoch) block() }
    }
  }

  private companion object {
    val MONITOR_AUDIO: AudioAttributes =
      AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()
  }
}

/**
 * Whether the display a preview draws onto advertises an HDR format.
 *
 * The graph hands its surface BT.2020 PQ frames whatever is on the other end of it, so the panel is
 * what decides whether the grade arrives or is tone mapped on the way.
 */
internal fun displayShowsHdr(context: Context): Boolean {
  val display =
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    display.mode.supportedHdrTypes.isNotEmpty()
  } else {
    @Suppress("DEPRECATION")
    display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
  }
}
