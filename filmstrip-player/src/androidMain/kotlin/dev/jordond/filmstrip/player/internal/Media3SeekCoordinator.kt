package dev.jordond.filmstrip.player.internal

import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hands seeks to media3 and answers the chase once media3 says where each one landed.
 *
 * Every seek carries a serial, so a landing media3 reports for a seek that has since been replaced is dropped. The
 * playhead reads the seek's target while one is in flight and the slot it was aimed at once it lands.
 *
 * Everything here runs on the engine's dispatcher except [playerSerial], which the player's thread owns.
 *
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param handler Posts onto the player's thread.
 * @param player The player standing now, read on the player's thread.
 * @param resolved The composition loaded now, or null before one is.
 * @param guard Hears every seek issued and landed, and decides whether a landing is sent on to a moved loop range.
 * @param platformPositionMs The last position media3 reported, in its own milliseconds.
 * @param loopWrap Where the loop range standing now wraps, or null when there is none.
 * @param onIssued Called as each seek is handed over, including a redirect.
 * @param onRedirected Reports the slot time a wrap was sent on to, which the playhead holds until it lands.
 */
@ExperimentalApi
internal class Media3SeekCoordinator(
  private val scope: CoroutineScope,
  private val handler: Handler,
  private val player: () -> Player?,
  private val resolved: () -> ResolvedComposition?,
  private val guard: WrapGuard,
  private val platformPositionMs: () -> Long,
  private val loopWrap: () -> LoopWrap?,
  private val onIssued: () -> Unit,
  private val onRedirected: (Duration) -> Unit,
) {
  private var completion: ((Duration) -> Unit)? = null

  /**
   * Where the seek in flight is going, or null when none is.
   */
  var target: Duration? = null
    private set

  // The slot time the last seek handed to media3 was aimed at, which the playhead reports while media3 reads the
  // floored millisecond it was given.
  var landing: Duration? = null

  // Counts the seeks handed to media3. A landing media3 reports is only taken for the latest of them.
  private var serial = 0

  /**
   * The serial of the seek media3 was handed last. Read and written on the player's thread only.
   */
  var playerSerial: Int = 0
    private set

  /**
   * The accuracy the last seek really ran at.
   *
   * Not always the accuracy that was asked for. See [clampedAccuracy].
   */
  var lastAccuracy: SeekAccuracy = SeekAccuracy.Exact
    private set

  // A seek in flight reads where it is going, since a reading media3 reports before it lands is from before the seek.
  fun position(): Duration = target ?: playheadOf(platformPositionMs(), landing)

  /**
   * Seeks to the slot [position] lands on and calls [onComplete] once media3 has landed there.
   *
   * @param wrap Whether the chase marked this seek as a loop wrap.
   */
  fun seek(
    position: Duration,
    accuracy: SeekAccuracy,
    wrap: Boolean,
    onComplete: (landed: Duration) -> Unit,
  ) {
    val loaded = resolved()
    if (loaded == null) {
      onComplete(position)
      return
    }

    lastAccuracy = clampedAccuracy(accuracy)
    guard.seekIssued(wrap = wrap)
    completion = onComplete
    issue(loaded.seekLandingOf(position))
  }

  /**
   * Hands media3 a seek to [target] for the completion already waiting in [completion].
   *
   * A redirect goes through here too, so the one completion reports wherever the last seek it was handed landed.
   */
  private fun issue(target: Duration) {
    val issued = ++serial
    this.target = target
    landing = target
    onIssued()

    handler.post {
      val standing = player()
      if (standing == null) {
        scope.launch { complete(issued) }
      } else {
        playerSerial = issued
        standing.seekTo(target.toMediaMs())
      }
    }
  }

  /**
   * Answers media3 reporting that the seek numbered [serial] landed.
   *
   * A landing for a seek that has since been replaced, or one filmstrip did not ask for, is dropped. A wrap that lands
   * after the loop range moved is sent on to the range standing now, and its completion waits for that seek instead.
   */
  fun complete(serial: Int) {
    if (serial != this.serial) return
    val waiting = completion ?: return
    val redirect = guard.seekLanded(loopWrap())
    val rate = resolved()?.frameRate
    if (redirect != null && rate != null) {
      // The chase is not told. Sending this through seekTo would resolve the one chase request twice.
      val sentOn = outputSlotTime(redirect, rate)
      issue(sentOn)
      onRedirected(sentOn)
      return
    }

    val landed = target ?: position()
    completion = null
    target = null
    waiting(landed)
  }

  /**
   * Drops the seek in flight without answering it.
   */
  fun cancel() {
    completion = null
    target = null
  }
}

/**
 * The playhead a media3 reading of [positionMs] reports, given the slot time [landing] the last seek was aimed at.
 *
 * A seek reaches media3 floored to a whole millisecond by [toMediaMs], so a player resting on the slot it landed on
 * reads up to a millisecond before that slot's time. A reading of exactly that floored millisecond reports [landing]
 * itself, and every other reading is reported as media3 gave it.
 */
internal fun playheadOf(
  positionMs: Long,
  landing: Duration?,
): Duration = landing?.takeIf { it.toMediaMs() == positionMs } ?: positionMs.milliseconds
