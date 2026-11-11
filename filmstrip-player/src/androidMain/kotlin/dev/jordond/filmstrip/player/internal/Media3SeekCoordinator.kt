package dev.jordond.filmstrip.player.internal

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hands seeks to media3 and answers the chase once the frame each one landed on has been drawn.
 *
 * Every seek carries a serial, so a landing media3 reports for a seek that has since been replaced is dropped. The
 * playhead reads the seek's target while one is in flight and the slot it was aimed at once it lands.
 *
 * media3 accepts a seek long before it draws the frame, so while frames reach a surface a seek is answered by the
 * first frame drawn after media3's playback thread has handled it. A seek to the millisecond media3 already rests on
 * is one it skips and draws nothing for, so that one is answered by the frame the seek before it is still waiting on,
 * or at once when there is none. A paused player can rest on a millisecond other than the one it reports, so every
 * other paused seek outside a scrub goes through the millisecond beside its target, which media3 never skips. With
 * nothing to draw onto, a seek is answered when media3 accepts it.
 *
 * [represent] is the one seek nobody asked for. It goes back to the slot the preview already holds, through the
 * millisecond beside it so media3 carries it out, and lands without a word to the chase. One made as the surface
 * goes lands when media3 accepts it, and the frame it decoded is the first one the next surface is given.
 *
 * Everything here runs on the engine's dispatcher except [playerSerial], which the player's thread owns, and
 * [frameDrawn], which media3's playback thread calls.
 *
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param handler Posts onto the player's thread.
 * @param player The player standing now, read on the player's thread.
 * @param playbackLooper The looper media3 plays back on, or null while no player stands.
 * @param resolved The composition loaded now, or null before one is.
 * @param guard Hears every seek issued and landed, and decides whether a landing is sent on to a moved loop range.
 * @param platformPositionMs The last position media3 reported, in its own milliseconds.
 * @param loopWrap Where the loop range standing now wraps, or null when there is none.
 * @param showsFrames Whether the frames media3 draws reach a surface now.
 * @param scrubbing Whether media3 is in its scrubbing mode. Read on the player's thread.
 * @param onIssued Called as each seek is handed over, including a redirect.
 * @param onRedirected Reports the slot time a wrap was sent on to, which the playhead holds until it lands.
 * @param onRepresented Called once a held slot has been drawn again.
 */
@ExperimentalApi
internal class Media3SeekCoordinator(
  private val scope: CoroutineScope,
  private val handler: Handler,
  private val player: () -> Player?,
  private val playbackLooper: () -> Looper?,
  private val resolved: () -> ResolvedComposition?,
  private val guard: WrapGuard,
  private val platformPositionMs: () -> Long,
  private val loopWrap: () -> LoopWrap?,
  private val showsFrames: () -> Boolean,
  private val scrubbing: () -> Boolean,
  private val onIssued: () -> Unit,
  private val onRedirected: (Duration) -> Unit,
  private val onRepresented: () -> Unit,
) {
  private var completion: ((Duration) -> Unit)? = null

  // Whether the seek in flight is a held slot being seeked back to, which a second request for the same has nothing
  // to add to.
  private var representing = false

  // Whether the seek in flight was made as the surface went, so no frame will come to answer it.
  private var landsUnseen = false

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

  // The serial of the last seek the playback thread has handled, and the last one a frame was reported for. Written on
  // the playback thread, where a frame drawn after a seek was handled is a frame from after it.
  @Volatile
  private var handledSerial = 0

  @Volatile
  private var drawnSerial = 0

  // Whether every seek before the one being waited on had its frame drawn. Playback thread only.
  private var settledBefore = true

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
    representing = false
    landsUnseen = false
    completion = onComplete
    issue(loaded.seekLandingOf(position))
  }

  /**
   * Seeks back to the slot the preview holds, so it is the frame media3 draws next.
   *
   * A seek in flight is handed over again and answers the chase as it would have. With none in flight the slot the
   * player rests on is seeked to, and nothing but [onRepresented] hears it land. The player is read once the playback
   * thread has said all it had to, since a player paused a moment ago still reports an estimate.
   *
   * @param surfaceLost Whether this is for a surface that just went, rather than one the player was handed.
   */
  fun represent(surfaceLost: Boolean) {
    handler.post {
      val playback = playbackLooper()?.let(::Handler)
      playback.run {
        handler.post {
          val restingMs = player()?.currentPosition ?: return@post
          scope.launch { representFrom(restingMs, surfaceLost) }
        }
      }
    }
  }

  private fun representFrom(
    restingMs: Long,
    surfaceLost: Boolean,
  ) {
    val loaded = resolved() ?: return
    if (representing) return
    val inFlight = target
    if (inFlight == null) {
      guard.seekIssued(wrap = false)
      representing = true
      landsUnseen = surfaceLost
      completion = { onRepresented() }
    }
    issue(inFlight ?: loaded.seekLandingOf(playheadOf(restingMs, landing)), fresh = true)
  }

  /**
   * Hands media3 a seek to [target] for the completion already waiting in [completion].
   *
   * A redirect goes through here too, so the one completion reports wherever the last seek it was handed landed.
   *
   * @param fresh Whether media3 has to carry the seek out even where it already rests on [target].
   */
  private fun issue(
    target: Duration,
    fresh: Boolean = false,
  ) {
    val issued = ++serial
    this.target = target
    landing = target
    onIssued()

    handler.post {
      // Round the playback thread and back before the player is read. A player paused a moment ago still reports the
      // position it estimated while playing, and media3 decides whether to skip a seek from the one it really holds,
      // which the player reads once everything the playback thread had to say has arrived.
      val playback = playbackLooper()?.let(::Handler)
      playback.run { handler.post { handOver(issued, target, playback, fresh) } }
    }
  }

  private fun handOver(
    issued: Int,
    target: Duration,
    playback: Handler?,
    fresh: Boolean,
  ) {
    val standing = player()
    if (standing == null) {
      scope.launch { settle(issued) }
      return
    }

    playerSerial = issued
    val positionMs = target.toMediaMs()
    val skipped = !fresh && standing.skipsSeekTo(positionMs)
    // A paused player draws nothing but the frame a seek lands on, and can draw it before anything queued behind the
    // seek runs, so the wait opens ahead of the seek. A playing one is still drawing frames from before it, so there
    // the wait opens once the seek was handled.
    if (!standing.isPlaying) playback.run { awaitFrame(issued) }
    // media3's limit: it skips a seek to the millisecond it rests on. See [seekNudgeMs].
    if (seekGoesThroughNudge(fresh, skipped, standing.isPlaying, scrubbing())) {
      standing.seekTo(seekNudgeMs(positionMs))
    }
    standing.seekTo(positionMs)
    playback.run { onSeekHandled(issued, skipped) }
  }

  private fun Handler?.run(block: () -> Unit) {
    if (this == null) block() else post(block)
  }

  private fun awaitFrame(issued: Int) {
    if (handledSerial == issued) return
    settledBefore = handledSerial == drawnSerial
    handledSerial = issued
  }

  private fun onSeekHandled(
    issued: Int,
    skipped: Boolean,
  ) {
    awaitFrame(issued)
    if (skipped && settledBefore && drawnSerial != issued) {
      drawnSerial = issued
      scope.launch { settle(issued) }
    }
  }

  /**
   * Hears that media3 is about to draw a frame. Called on media3's playback thread.
   */
  fun frameDrawn() {
    val handled = handledSerial
    if (handled == drawnSerial) return
    drawnSerial = handled
    scope.launch { settle(handled) }
  }

  /**
   * Answers media3 accepting the seek numbered [serial], which is as much as it says when no frame reaches a surface.
   */
  fun accepted(serial: Int) {
    if (!showsFrames() || landsUnseen) settle(serial)
  }

  /**
   * Answers the seek in flight when the frames it was waiting on stopped coming, as they do when the surface goes, the
   * player fails or the graph is replaced.
   */
  fun framesStopped() {
    settle(serial)
  }

  /**
   * Answers the seek numbered [serial] having landed.
   *
   * A landing for a seek that has since been replaced, or one filmstrip did not ask for, is dropped. A wrap that lands
   * after the loop range moved is sent on to the range standing now, and its completion waits for that seek instead.
   */
  private fun settle(serial: Int) {
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
    representing = false
    landsUnseen = false
    waiting(landed)
  }

  /**
   * Drops the seek in flight without answering it.
   */
  fun cancel() {
    completion = null
    target = null
    representing = false
    landsUnseen = false
  }
}

/**
 * Whether media3 will skip a seek of this player to [positionMs] and draw nothing for it.
 *
 * media3 does nothing for a seek to the millisecond a prepared player already rests on. A playing one keeps drawing
 * either way, so only a paused player is asked.
 */
private fun Player.skipsSeekTo(positionMs: Long): Boolean {
  val prepared = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
  return prepared && !isPlaying && currentPosition == positionMs
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
