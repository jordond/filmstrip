package dev.jordond.filmstrip.player.internal

import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks media3 to draw the frame on the surface again, and reports the frame that was drawn for it.
 *
 * The replayable cache draws the frame at the presentation time it already has, so an effect on the preview clock is
 * the only thing that moves. media3's limit: a redraw flushes the one before it, so asking again before a frame has
 * come out leaves a device slower than the interval showing none.
 *
 * @param scope The dispatcher a report is made on.
 * @param handler Runs [redraw] on the player's thread.
 * @param playback The handler of the thread media3 plays back on, or null where there is none, which is where a redraw
 * is handled as soon as it is asked for.
 * @param redraw Asks the player for the redraw.
 * @param paused Whether the player is drawing no frames of its own, read on the player's thread.
 * @param showsFrames Whether a surface is drawn onto, so a redraw has a frame to come out as.
 * @param report Reports that the frame at the playhead changed with the playhead standing still.
 */
internal class Media3Redraws(
  private val scope: CoroutineScope,
  private val handler: Handler,
  private val playback: () -> Handler?,
  private val redraw: () -> Unit,
  private val paused: () -> Boolean,
  private val showsFrames: () -> Boolean,
  private val report: () -> Unit,
) {
  // Whether a redraw has yet to hand its frame to the surface. Set on the engine's dispatcher and cleared on media3's
  // playback thread.
  private val pending = AtomicBoolean(false)

  // The serial of the change that owes the report, or none. It is owed to the first frame handed to the surface once
  // the playback thread has handled that change's redraw, since a frame already on its way was drawn before the change.
  private val owed = AtomicInteger(NONE)

  // Counts the changes asked for. Engine dispatcher only.
  private var asked = NONE

  // The serial of the last change whose redraw the playback thread has handled. Written on the playback thread, where
  // a frame handed over after that is a frame from after the redraw.
  @Volatile
  private var handled = NONE

  /**
   * Redraws the frame on the surface unless a redraw is already on its way to it.
   */
  fun requestHeld() {
    if (!showsFrames() || !pending.compareAndSet(false, true)) return
    handler.post { redraw() }
  }

  /**
   * Redraws the frame on the surface for a change to what it is drawn with, and reports it once the frame is drawn.
   *
   * Asked even where a redraw is on its way, since that one may have been asked for before the change. With no surface
   * there is no frame to wait for, so it is reported at once.
   */
  fun requestChanged() {
    if (!showsFrames()) {
      report()
      return
    }
    val serial = ++asked
    owed.set(serial)
    pending.set(true)
    handler.post {
      val thread = playback()
      val mark = Runnable { handled = serial }
      // A paused player draws nothing but the frame the redraw asks for, and can draw it before anything queued behind
      // the redraw runs, so the wait opens ahead of the redraw. A playing one is still drawing frames from before the
      // change, so there the wait opens once the playback thread has handled the redraw.
      val ahead = paused()
      if (ahead) thread.run(mark)
      redraw()
      if (!ahead) thread.run(mark)
    }
  }

  private fun Handler?.run(block: Runnable) {
    if (this == null) block.run() else post(block)
  }

  /**
   * Hears that media3 handed a frame to the surface. Called on media3's playback thread.
   */
  fun frameDrawn() {
    pending.set(false)
    val waiting = owed.get()
    if (waiting != NONE && handled >= waiting && owed.compareAndSet(waiting, NONE)) scope.launch { report() }
  }

  /**
   * Forgets a redraw on its way, as a seek is issued whose own frame replaces it.
   */
  fun seekIssued() {
    pending.set(false)
  }

  /**
   * Answers a change still owed a frame, as the surface goes and none will come.
   */
  fun surfaceDetached() {
    pending.set(false)
    if (owed.getAndSet(NONE) != NONE) report()
  }
}

private const val NONE = 0
