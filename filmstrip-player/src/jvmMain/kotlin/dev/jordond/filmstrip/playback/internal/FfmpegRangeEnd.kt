package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.internal.outputSlotsOf
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Decides where a pump lands, where it stops and when it wraps, and holds the seek waiting on it.
 *
 * It holds the loop range and the playback range, and puts every seek, landing, range change and wrap to one
 * [WrapGuard], so a wrap never races a seek in flight. The seek a pump was opened for is answered here once the pump
 * shows its first frame or ends without one.
 *
 * @param presented The frame the transport last showed.
 * @param position The playhead.
 * @param moveTo Moves the playhead without reporting it.
 * @param wanted Whether playback is wanted.
 * @param startPump Opens a pump at a position, with the seek completion it answers once it lands.
 * @param seekToWrap Seeks to the first slot of a wrap, through the engine's chase, as a wrap.
 * @param looped Reports a lap of a loop range.
 * @param finish Reports the end of the stream, given whether the pump showed a frame.
 */
internal class FfmpegRangeEnd(
  private val presented: () -> PreviewFrame?,
  private val position: () -> Duration,
  private val moveTo: (Duration) -> Unit,
  private val wanted: StateFlow<Boolean>,
  private val startPump: (from: Duration, onLanded: ((Duration) -> Unit)?) -> Unit,
  private val seekToWrap: (Duration) -> Unit,
  private val looped: (TimeRange) -> Unit,
  private val finish: (landed: Boolean) -> Unit,
) {
  private val guard = WrapGuard()

  private var loopRange: TimeRange? = null
  private var landing: ((Duration) -> Unit)? = null

  // The latest seek's completion, until the guard has heard where it landed.
  private var latestSeek: ((Duration) -> Unit)? = null

  /**
   * The range playback is held inside, or null for the whole composition.
   *
   * A frame past its last slot ends playback rather than being shown.
   */
  var playbackRange: TimeRange? = null

  /**
   * Loops playback over [range], or stops looping when it is null.
   *
   * Setting the range already standing changes nothing and is not put to the guard.
   */
  fun setLoopRange(range: TimeRange?) {
    if (range == loopRange) return
    loopRange = range
    guard.rangeChanged()
  }

  /**
   * Forgets every seek and wrap in flight.
   *
   * Called when a newly lowered plan puts a fresh pump on the timeline.
   */
  fun reset() {
    guard.reset()
  }

  /**
   * Records a seek the engine is about to issue, with [onComplete] as its completion.
   *
   * [wrap] marks a seek issued by a loop wrap. Only the latest seek's landing is put to the guard.
   */
  fun seekIssued(
    wrap: Boolean,
    onComplete: (Duration) -> Unit,
  ) {
    guard.seekIssued(wrap = wrap)
    latestSeek = onComplete
  }

  /**
   * Answers the seek the running pump was opened for and waits on [onLanded] for the next one.
   *
   * A caller counting completions against requests is never left waiting on a frame that is no longer coming.
   */
  fun hold(onLanded: ((Duration) -> Unit)?) {
    settleLanding()
    landing = onLanded
  }

  /**
   * Drops the seek waiting on the pump without answering it.
   *
   * Only a released engine does this, since nothing is left to answer.
   */
  fun dropLanding() {
    landing = null
  }

  /**
   * Answers the seek waiting on the pump at the playhead.
   *
   * When that seek is the latest one, the guard hears that it landed, and hears it only this once.
   */
  fun settleLanding() {
    val callback = landing ?: return
    landing = null
    if (callback === latestSeek) {
      latestSeek = null
      guard.seekLanded(null)
    }
    callback(position())
  }

  /**
   * Wraps a loop range, or stops a playback range, once [at] lands past the last slot the range carries on the grid of
   * [current].
   *
   * A frame past a loop range is never shown. When a seek is in flight the wrap waits for it, and this pump stops on the
   * range's last slot until the seek replaces it.
   *
   * @return true when [at] is outside what may play, so the caller stops presenting.
   */
  fun reachedEnd(
    at: Duration,
    current: FfmpegPreviewPlan,
  ): Boolean {
    val rate = current.frameRate
    val slot = outputSlotOf(at, rate)
    loopRange?.let { range ->
      loopWrapIn(range, current)?.takeIf { slot > it.lastSlot }?.let {
        wrapLoop(range, it, current)
        return true
      }
    }
    playbackRange?.takeIf { it.endsBefore(slot, rate) }?.let {
      finish(true)
      return true
    }
    return false
  }

  /**
   * Handles a pump whose process ran out of frames and exited cleanly.
   *
   * No frame comes past the last slot of a loop range the composition ends inside, so it wraps where the pump runs out,
   * once that slot is on screen. A pump that ran out anywhere short of it failed, and ends rather than respawning onto
   * the same failure.
   *
   * @param landed Whether the pump showed a frame.
   * @param current The plan the pump was opened on.
   */
  fun ranOut(
    landed: Boolean,
    current: FfmpegPreviewPlan,
  ) {
    val shown = presented()?.takeIf { landed }?.let { outputSlotOf(it.position, current.frameRate) }
    // A wrap held back by a seek in flight leaves this pump to the seek rather than ending playback under it.
    val range = loopRange
    val wrap = range?.let { loopWrapIn(it, current) }?.takeIf { it.reachesEnd && it.lastSlot == shown }
    if (range != null && wrap != null && (wrapLoop(range, wrap, current) || wanted.value)) return
    finish(landed)
  }

  /**
   * The slot a wrap landing on this pump moves on to, when the loop range changed while it was in flight, or null when
   * it lands here.
   *
   * Only the landing of the latest seek is put to the guard, so a pump opened for anything else lands where it opened.
   * A seek that lands here is not put to the guard again when it settles.
   */
  fun redirectOf(current: FfmpegPreviewPlan): Long? {
    val callback = landing ?: return null
    if (callback !== latestSeek) return null
    val redirect = guard.seekLanded(loopRange?.let { loopWrapIn(it, current) })
    if (redirect == null) latestSeek = null
    return redirect
  }

  /**
   * Opens a pump on [slot] of the grid of [current] for the wrap still waiting, without reporting another lap.
   *
   * The wrap's seek stays unanswered until the new pump lands, so the playhead a listener sees next is the new range's
   * first slot and never the old one's.
   */
  fun redirect(
    slot: Long,
    current: FfmpegPreviewPlan,
  ) {
    val callback = landing
    landing = null
    moveTo(outputSlotTime(slot, current.frameRate))
    startPump(position(), callback)
  }

  // Where range wraps on the grid of current, or null when it has nothing to loop.
  private fun loopWrapIn(
    range: TimeRange,
    current: FfmpegPreviewPlan,
  ): LoopWrap? = loopWrapOf(range, current.resolved.duration, current.frameRate)

  /**
   * Seeks to the first slot of [wrap] on the grid of [current] and reports a lap of [range], when the guard says a wrap
   * is due.
   *
   * Playback has already reached where [wrap] wraps when this is called, so the guard only weighs the seek in flight and
   * whether the transport runs. The seek is marked as a wrap for as long as the chase holds it.
   *
   * @return true when playback wrapped.
   */
  private fun wrapLoop(
    range: TimeRange,
    wrap: LoopWrap,
    current: FfmpegPreviewPlan,
  ): Boolean {
    if (!guard.wrapDue(wrap.at, wanted.value, wrap)) return false
    seekToWrap(outputSlotTime(wrap.firstSlot, current.frameRate))
    looped(range)
    return true
  }

  // A range with no end has no last slot, so no slot is past it.
  private fun TimeRange.endsBefore(
    slot: Long,
    rate: Int,
  ): Boolean = lastSlotOf(this, rate)?.let { slot > it } ?: false
}

/**
 * The last of the slots a playback [range] carries at [rate], or null for a range with no end.
 *
 * A range that covers no slot of its own has no last one, so this is the range's own first slot instead, the only slot
 * the pump ever presents for a range that narrow.
 */
internal fun lastSlotOf(
  range: TimeRange,
  rate: Int,
): Long? {
  if (range.endExclusive == null) return null
  val slots = outputSlotsOf(range, rate)
  return if (slots.isEmpty()) outputSlotOf(range.start, rate) else slots.last
}
