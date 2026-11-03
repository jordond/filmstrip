package dev.jordond.filmstrip.player.internal

/**
 * Decides when an engine wraps a loop range and where a wrap in flight lands.
 *
 * The engine reports each seek it issues and the landing of its latest one, and each change to its loop range. No wrap
 * may happen while a seek is in flight or while the transport is stopped. A wrap that lands after the range changed,
 * while a range still stands, is redirected to that range's first slot. The slot and its time come from [loopWrapOf]
 * and `outputSlotTime`, so the guard only decides and never places. The engine calls [reset] each time it loads a
 * composition.
 */
internal class WrapGuard {
  private var seeking = false
  private var wrapping = false
  private var rangeMoved = false

  /**
   * Records a seek the engine just issued, which takes the place of any seek still in flight.
   *
   * @param wrap Whether the seek is a wrap onto the loop range's first slot.
   */
  fun seekIssued(wrap: Boolean = false) {
    seeking = true
    wrapping = wrap
    rangeMoved = false
  }

  /**
   * Records that the engine's latest seek landed, and says whether a wrap has to move on to the range standing now.
   *
   * A redirect is returned as the slot [current] starts on. It counts as a wrap seek in flight, so the engine seeks
   * there without calling [seekIssued] and does not report a second lap. A wrap under a cleared range, a wrap whose
   * range never changed and a seek that was not a wrap all land where they were sent, and give null.
   *
   * @param current Where the loop range standing now wraps, or null when no range stands.
   */
  fun seekLanded(current: LoopWrap?): Long? {
    val redirect = if (wrapping && rangeMoved) current?.firstSlot else null
    rangeMoved = false
    if (redirect == null) {
      seeking = false
      wrapping = false
    }
    return redirect
  }

  /**
   * Records that the engine's loop range changed or was cleared.
   *
   * Only a wrap in flight takes any notice, and only once it lands.
   */
  fun rangeChanged() {
    if (wrapping) rangeMoved = true
  }

  /**
   * Whether playback that has reached its loop range's wrap may wrap now.
   *
   * The engine compares its own reading against the wrap point, so this only weighs the seek in flight and the
   * transport.
   *
   * @param running Whether the transport runs, whoever started it.
   */
  fun mayWrap(running: Boolean): Boolean = running && !seeking

  /**
   * Forgets every seek in flight and every range change, as if the guard were new.
   *
   * The engine calls this when it loads a composition, since a seek on the old one may never report its landing.
   */
  fun reset() {
    seeking = false
    wrapping = false
    rangeMoved = false
  }
}
