package dev.jordond.filmstrip.player.internal

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything media3 pushes, marshalled from the player's thread onto the engine's.
 *
 * A player released and rebuilt for a different video graph leaves its own callbacks queued behind it, so each listener
 * carries the generation it was registered for and a stale one is dropped rather than moving the snapshot of a graph
 * that is gone.
 *
 * What a graph says about itself, which is its state, its position and its failure, also goes through [loads], since a
 * rebuild replaces the graph under the same player. A transport change, a first frame and the answer to a seek outlive
 * the graph that raised them, so they still arrive.
 *
 * @param generation The generation of the player this listens to.
 * @param standingGeneration The generation of the player standing now.
 * @param player The player standing now, read on the player's thread.
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param loads Drops what a graph reports once a rebuild has replaced it.
 * @param status Hears the player's state, first frames and failures.
 * @param seeks Hears where each seek landed.
 * @param loops Mirrors the playhead while the player moves pixels.
 */
@ExperimentalApi
internal class Media3PlayerListener(
  private val generation: Int,
  private val standingGeneration: () -> Int,
  private val player: () -> Player?,
  private val scope: CoroutineScope,
  private val loads: Media3GraphLoads,
  private val status: Media3StatusTracker,
  private val seeks: Media3SeekCoordinator,
  private val loops: Media3LoopRangeController,
) : Player.Listener {
  override fun onPlaybackStateChanged(playbackState: Int) {
    val standing = player()
    val position = standing?.currentPosition ?: 0
    val duration = standing?.duration ?: C.TIME_UNSET
    val running = standing?.playWhenReady == true
    report { status.onPlatformState(playbackState, position, duration, running) }
  }

  override fun onPlayWhenReadyChanged(
    playWhenReady: Boolean,
    reason: Int,
  ) {
    dispatch { status.onPlayWhenReadyChanged(playWhenReady, reason) }
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    if (generation != standingGeneration()) return
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
    report { status.platformPositionMs = landed }
    dispatch { seeks.complete(serial) }
  }

  override fun onRenderedFirstFrame() {
    dispatch { status.onRenderedFirstFrame() }
  }

  override fun onPlayerError(error: PlaybackException) {
    val reported = error.toPlaybackError()
    report { status.onPlayerError(reported) }
  }

  private fun dispatch(block: () -> Unit) {
    if (generation != standingGeneration()) return
    scope.launch { if (generation == standingGeneration()) block() }
  }

  private fun report(block: () -> Unit) {
    if (generation != standingGeneration()) return
    loads.report(block)
  }
}
