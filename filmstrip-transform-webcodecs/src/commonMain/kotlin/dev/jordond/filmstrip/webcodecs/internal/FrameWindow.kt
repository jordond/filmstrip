@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs

/**
 * The decoded frames waiting ahead of the playhead, for one clip at a time.
 *
 * Every sample in here is the window's: it closes one when the playhead leaves it behind, when the
 * window is cleared, and when the preview is released. Nothing else closes one, so a decoded frame
 * has exactly one owner from the moment the decoder yields it. A frame the decoder hands back after
 * a clear is closed on arrival rather than kept, since there is no longer anywhere to keep it.
 *
 * The bound is a count and not a span of time, so a clip running at three frames a second cannot
 * hold half a minute of decoded video.
 */
internal class FrameWindow(
  private val capacity: Int,
) {
  private val frames = ArrayDeque<VideoSample>()
  private var stream: FrameStream? = null
  private var drained = false
  private var generation = 0

  var clipIndex: Int = NO_CLIP
    private set

  /**
   * How many frames this window has taken from a decoder, and so how many it owes [closed] once
   * every one is released.
   */
  var opened: Int = 0
    private set

  var closed: Int = 0
    private set

  val size: Int get() = frames.size

  /**
   * Whether this window is the one reading [index], and has anything left to say about it.
   */
  fun holds(index: Int): Boolean = clipIndex == index && (frames.isNotEmpty() || !drained)

  /**
   * Whether everything held starts after [sourceUs], which is a playhead that moved backwards.
   */
  fun startsAfter(sourceUs: Double): Boolean = frames.firstOrNull()?.let { it.microsecondTimestamp > sourceUs } == true

  fun seat(
    index: Int,
    stream: FrameStream,
  ) {
    clear()
    clipIndex = index
    this.stream = stream
    drained = false
  }

  /**
   * Decodes forward until a frame past [sourceUs] is in hand, dropping what the playhead has left
   * behind to make room for it.
   */
  suspend fun advanceTo(sourceUs: Double) {
    dropBehind(sourceUs)
    while (!drained && (frames.isEmpty() || frames.last().microsecondTimestamp <= sourceUs)) {
      if (frames.size >= capacity) break
      if (!pull()) break
    }
  }

  /**
   * Decodes forward until the window is full or the clip runs out.
   */
  suspend fun fill() {
    while (!drained && frames.size < capacity) {
      if (!pull()) break
    }
  }

  /**
   * The frame the export draws at [sourceUs], still owned by this window, or null when nothing here
   * answers for that time.
   *
   * The choice between the two frames straddling the slot is the export pipeline's own: whichever
   * timestamp sits closer.
   */
  fun nearest(sourceUs: Double): VideoSample? {
    val first = frames.firstOrNull() ?: return null
    if (first.microsecondTimestamp > sourceUs) return null

    var index = 1
    var current = first
    while (index < frames.size && frames[index].microsecondTimestamp <= sourceUs) {
      current = frames[index]
      index++
    }
    val ahead = frames.getOrNull(index) ?: return current
    val nearer = abs(ahead.microsecondTimestamp - sourceUs) < abs(current.microsecondTimestamp - sourceUs)
    return if (nearer) ahead else current
  }

  fun clear() {
    generation++
    stream?.close()
    stream = null
    drained = false
    clipIndex = NO_CLIP
    while (frames.isNotEmpty()) closeFront()
  }

  private suspend fun pull(): Boolean {
    val open = stream ?: return false
    val at = generation
    val sample = open.next()
    if (at != generation) {
      sample?.also {
        opened++
        it.close()
        closed++
      }
      return false
    }
    if (sample == null) {
      drained = true
      return false
    }
    opened++
    frames.addLast(sample)
    return true
  }

  private fun dropBehind(sourceUs: Double) {
    while (frames.size > 1 && frames[1].microsecondTimestamp <= sourceUs) closeFront()
  }

  private fun closeFront() {
    frames.removeFirst().close()
    closed++
  }
}

/**
 * The clip index a [FrameWindow] reports while it reads no clip at all.
 */
internal const val NO_CLIP: Int = -1
