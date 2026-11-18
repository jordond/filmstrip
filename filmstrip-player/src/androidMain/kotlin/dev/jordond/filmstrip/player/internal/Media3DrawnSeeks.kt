package dev.jordond.filmstrip.player.internal

import kotlin.concurrent.Volatile

/**
 * Which seek the frames media3 draws come after, counted by the serial each seek is handed over with.
 *
 * Everything here is written on media3's playback thread, where a frame drawn after a seek was handled is a frame from
 * after it. The first such frame answers the seek. A seek media3 skips draws nothing, so it is answered as it is
 * handled, unless a seek before it is still waiting on its frame.
 */
internal class Media3DrawnSeeks {
  /**
   * The serial of the last seek the playback thread has handled.
   */
  @Volatile
  var handled: Int = 0
    private set

  /**
   * The serial of the last seek a frame was drawn for, or that was answered without one.
   */
  @Volatile
  var drawn: Int = 0
    private set

  // Whether every seek before the one being waited on had its frame drawn.
  private var settledBefore = true

  /**
   * Opens the wait for the frame of the seek numbered [issued], so a frame drawn from here on answers it.
   */
  fun await(issued: Int) {
    if (handled == issued) return
    settledBefore = handled == drawn
    handled = issued
  }

  /**
   * Hears that the playback thread handled the seek numbered [issued].
   *
   * @param skipped Whether media3 skips it and so draws nothing for it.
   * @return Whether the seek is answered now, as a skipped one with nothing before it still waiting is.
   */
  fun onHandled(
    issued: Int,
    skipped: Boolean,
  ): Boolean {
    await(issued)
    if (!skipped || !settledBefore || drawn == issued) return false
    drawn = issued
    return true
  }

  /**
   * Hears a frame drawn once the seek numbered [handled] was handled.
   *
   * @return Whether it is the first frame drawn after that seek, which answers it.
   */
  fun frameAfter(handled: Int): Boolean {
    if (handled == drawn) return false
    drawn = handled
    return true
  }
}
