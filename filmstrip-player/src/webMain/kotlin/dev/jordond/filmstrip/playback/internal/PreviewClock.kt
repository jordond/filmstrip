package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.webcodecs.internal.BrowserAudioPreview
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where the playhead is while the transport runs.
 *
 * Two sources behind one seam, because a browser has two and they fail differently. A composition
 * with audio reads the audio hardware's own clock, which is the steadiest a page has and which does
 * not move at all while the browser refuses to start the graph. A silent one has no such clock and
 * counts wall time instead, which keeps running in a background tab where the frame callbacks that
 * feed the decoder do not.
 */
internal interface PreviewClock {
  /**
   * The playhead now.
   */
  val position: Duration

  /**
   * Whether the clock is really advancing, which the audio branch can answer no to even after
   * [start].
   */
  val isRunning: Boolean

  /**
   * Starts counting from [from].
   */
  fun start(from: Duration)

  /**
   * Stops counting, holding the playhead where it reached.
   */
  fun stop()

  /**
   * Moves the playhead without starting or stopping it.
   */
  fun moveTo(position: Duration)
}

/**
 * The silent branch: wall time since the transport started.
 */
internal class MonotonicClock : PreviewClock {
  private var anchorMillis = 0.0
  private var anchorPosition = Duration.ZERO
  private var running = false

  override val isRunning: Boolean get() = running

  override val position: Duration
    get() = if (running) anchorPosition + (performance.now() - anchorMillis).milliseconds else anchorPosition

  override fun start(from: Duration) {
    anchorPosition = from
    anchorMillis = performance.now()
    running = true
  }

  override fun stop() {
    anchorPosition = position
    running = false
  }

  override fun moveTo(position: Duration) {
    anchorPosition = position
    anchorMillis = performance.now()
  }
}

/**
 * The audio branch: the playhead the live graph is sounding.
 *
 * The graph is anchored by whoever starts it, so this only decides whether to read the graph's
 * clock or the position it was last left at.
 */
internal class AudioClock(
  private val audio: BrowserAudioPreview,
) : PreviewClock {
  private var held = Duration.ZERO
  private var running = false

  override val isRunning: Boolean get() = running && audio.isRunning

  override val position: Duration get() = if (running) audio.position else held

  override fun start(from: Duration) {
    held = from
    running = true
  }

  override fun stop() {
    held = position
    running = false
  }

  override fun moveTo(position: Duration) {
    held = position
  }
}
