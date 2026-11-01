package dev.jordond.filmstrip.playback.internal

import android.os.Handler
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Mirrors media3's playhead while pixels move and keeps playback inside the loop and playback ranges.
 *
 * Nothing on `Player` pushes a position, so this reads one on a timer from the player's thread and hops each reading
 * onto the engine's dispatcher. A reading past where the loop range wraps takes playback back to its start, and one
 * past where the playback range stops pauses there.
 *
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param handler Posts onto the player's thread.
 * @param player The player standing now, read on the player's thread.
 * @param resolved The composition loaded now, or null before one is.
 * @param guard Decides whether a reading or an end is due to wrap.
 * @param playhead Where the playhead reads now.
 * @param recordPosition Records a position in media3's own milliseconds as the last one it reported.
 * @param seekTo Seeks exactly to a position through the chase.
 * @param seekToWrap Seeks to a wrap's landing through the chase, marked as a wrap.
 * @param pause Stops the transport.
 * @param onLooped Reports a lap of the range, and the slot playback wrapped to.
 */
@ExperimentalApi
internal class Media3LoopRangeController(
  private val scope: CoroutineScope,
  private val handler: Handler,
  private val player: () -> CompositionPlayer?,
  private val resolved: () -> ResolvedComposition?,
  private val guard: WrapGuard,
  private val playhead: () -> Duration,
  private val recordPosition: (Long) -> Unit,
  private val seekTo: (Duration) -> Unit,
  private val seekToWrap: (Duration) -> Unit,
  private val pause: () -> Unit,
  private val onLooped: (range: TimeRange, landing: Duration) -> Unit,
) {
  private var loopRange: TimeRange? = null
  private var playbackRange: TimeRange? = null

  private var mirroring = false
  private val mirrorTick = Runnable { mirrorPlayhead() }

  fun setLoopRange(range: TimeRange?) {
    if (range != loopRange) {
      loopRange = range
      guard.rangeChanged()
    }
    range?.start?.takeIf { playhead() < it }?.let(seekTo)
  }

  fun setPlaybackRange(range: TimeRange?) {
    playbackRange = range
    range?.start?.takeIf { playhead() < it }?.let(seekTo)
  }

  /**
   * Starts reading the playhead on the player's thread, unless it already is.
   */
  fun startMirror() {
    if (!mirroring) {
      mirroring = true
      handler.post(mirrorTick)
    }
  }

  /**
   * Stops reading the playhead after the reading already queued.
   */
  fun stopMirror() {
    mirroring = false
  }

  /**
   * Where the loop range standing now wraps, or null when there is no range or nothing loaded.
   */
  fun wrap(): LoopWrap? = loopRange?.let(::loopWrapIn)

  /**
   * Reads the playhead off the player and keeps the loop and playback ranges honest.
   *
   * [BasePlayerEngine]'s ticker publishes what this last read.
   */
  private fun mirrorPlayhead() {
    val standing = player() ?: return
    val reading = standing.currentPosition
    val running = standing.playWhenReady
    // Recorded beside the loop check rather than here, so the playhead never reads a position past the wrap before the
    // wrap has been issued.
    scope.launch {
      recordPosition(reading)
      onPlayhead(reading.milliseconds, running)
    }
    if (mirroring) handler.postDelayed(mirrorTick, MIRROR_INTERVAL_MS)
  }

  private fun onPlayhead(
    position: Duration,
    running: Boolean,
  ) {
    val loop = loopRange
    val wrap = loop?.let(::loopWrapIn)
    if (loop != null && wrap != null && guard.wrapDue(position, running, wrap)) {
      wrapLoop(loop, wrap)
      return
    }

    val stopAt = playbackRange?.endExclusive ?: return
    if (position >= stopAt) {
      recordPosition(stopAt.inWholeMilliseconds)
      pause()
      seekTo(stopAt)
    }
  }

  /**
   * Answers media3 reaching the end of the composition inside the loop range, which is a wrap rather than an ending.
   *
   * The end is the only signal that always arrives, since the mirror stops reading once playback stops and may never
   * read a position at or past where the range stops. It wraps whenever [running] says the transport runs, whoever
   * started it. An end reported while a seek is on its way is from before that seek reached the player, and an end
   * with the transport stopped is a paused playhead at the end. Neither wraps, and neither is reported as an ending.
   *
   * @param running Whether media3 was asked to play when it reported the end.
   * @return whether the end fell inside the loop range, and so is not reported as an ending.
   */
  fun wrapsAtEnd(running: Boolean): Boolean {
    val loop = loopRange ?: return false
    val wrap = loopWrapIn(loop) ?: return false
    val duration = resolved()?.duration ?: return false
    if (guard.wrapDue(duration, running, wrap)) wrapLoop(loop, wrap)
    return true
  }

  private fun loopWrapIn(range: TimeRange): LoopWrap? {
    val loaded = resolved() ?: return null
    return loopWrapOf(range, loaded.duration, loaded.frameRate)
  }

  /**
   * Takes playback back to the slot [range] starts on, as [wrap] places it, and reports the lap.
   *
   * The playhead reports that slot from here on, rather than whatever media3 reads until the seek reaches it. The seek
   * goes through the chase marked as a wrap, so the guard hears it as one even if the chase holds it back.
   */
  private fun wrapLoop(
    range: TimeRange,
    wrap: LoopWrap,
  ) {
    val rate = resolved()?.frameRate ?: return
    val landing = outputSlotTime(wrap.firstSlot, rate)
    seekToWrap(landing)
    onLooped(range, landing)
  }

  companion object {
    /**
     * How long after one playhead reading the next is posted, roughly display rate, which is the ceiling.
     *
     * `positionFlow(tick)` snaps it down to whatever a collector asked for.
     */
    const val MIRROR_INTERVAL_MS = 16L
  }
}
