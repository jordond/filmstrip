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
 * Hands seeks to media3 and answers the chase once the frame each one landed on has been drawn.
 *
 * Every seek carries a serial, so a landing media3 reports for a seek that has since been replaced is dropped. The
 * playhead reads the seek's target while one is in flight and the slot it was aimed at once it lands.
 *
 * media3 accepts a seek long before it draws the frame, so while frames reach a surface a seek is answered by the
 * first frame drawn after media3's playback thread has handled it. A seek to the millisecond media3 already rests on
 * is one it skips and draws nothing for. Where the last seek put the player there and nothing has played since, the
 * frame on the surface is the one that seek drew, so the seek is answered by the frame the seek before it is still
 * waiting on, or at once when there is none. A player that played its way onto that millisecond shows whichever frame
 * it stopped on, so there the seek is carried out. A paused player can rest on a millisecond other than the one it
 * reports, so every other paused seek outside a scrub goes through the millisecond beside its target, which media3
 * never skips. So does a seek in a scrub onto the millisecond media3 rests on, which scrubbing mode would draw nothing
 * for, and the seek that finds a player held out of that mode, playing or not. With nothing to draw onto, a seek is
 * answered when media3 accepts it.
 *
 * [represent] is the one seek nobody asked for. It goes back to the slot the preview already holds, through the
 * millisecond beside it so media3 carries it out, and lands without a word to the chase. One made as the surface
 * goes lands when media3 accepts it, and the frame it decoded is the first one the next surface is given.
 *
 * Everything here runs on the engine's dispatcher except what the player's thread owns, which is [playerSerial], the
 * pin, which [pause], [unpin], [stopped] and the hand-over of a seek move, and [frameDrawn], which media3's playback
 * thread calls.
 *
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param handler Posts onto the player's thread.
 * @param player The player standing now, read on the player's thread.
 * @param playback Posts onto the thread media3 plays back on, or null while no player stands.
 * @param resolved The composition loaded now, or null before one is.
 * @param guard Hears every seek issued and landed, and decides whether a landing is sent on to a moved loop range.
 * @param platformPositionMs The last position media3 reported, in its own milliseconds.
 * @param loopWrap Where the loop range standing now wraps, or null when there is none.
 * @param showsFrames Whether the frames media3 draws reach a surface now.
 * @param scrubbing Whether a scrub or a shuttle is under way. Read as each seek is issued, so a seek is handed over in
 * the mode it was asked for in and not the one the player's thread finds by the time it gets to it.
 * @param inScrubbingMode Whether media3 was put in its scrubbing mode, which a player that has played since its last
 * seek is held out of. Read on the player's thread.
 * @param enterScrubbingMode Puts media3 in its scrubbing mode where one is under way and it was held out of it. Called
 * on the player's thread.
 * @param onIssued Called as each seek is handed over, including a redirect.
 * @param onRedirected Reports the slot time a wrap was sent on to, which the playhead holds until it lands.
 * @param firstSlotOf The slot a sample shown on a slot is written on. media3 drops every decoded frame before a seek's
 * position, so a seek into a later slot of a held sample goes to that slot and the preview draws the frame it lands on
 * on the slot asked for.
 * @param pin Holds the later slot of a held sample the preview is pinned to.
 * @param resting The slot time of the frame on screen while the player is paused and one has been drawn since the last
 * seek, or null. A paused player reports that slot, whatever media3's own clock reads.
 * @param restingAt The slot a paused player's clock reads rests on, for a player that has drawn no frame to name one,
 * and the time itself while it plays.
 * @param playWanted Whether the player was told to play. Only then does the clock take the playhead on from the slot a
 * pause rested on.
 * @param onRepresented Called once a held slot has been drawn again.
 */
@ExperimentalApi
internal class Media3SeekCoordinator(
  private val scope: CoroutineScope,
  private val handler: Handler,
  private val player: () -> Player?,
  private val playback: () -> Handler?,
  private val resolved: () -> ResolvedComposition?,
  private val guard: WrapGuard,
  private val platformPositionMs: () -> Long,
  private val loopWrap: () -> LoopWrap?,
  private val showsFrames: () -> Boolean,
  private val scrubbing: () -> Boolean,
  private val inScrubbingMode: () -> Boolean = { true },
  private val enterScrubbingMode: () -> Unit = {},
  private val onIssued: () -> Unit,
  private val onRedirected: (Duration) -> Unit,
  private val firstSlotOf: (Duration) -> Duration = { it },
  private val pin: Media3HeldPin = Media3HeldPin(player, { resolved()?.frameRate }, firstSlotOf),
  private val resting: () -> Duration? = { null },
  private val restingAt: (Duration) -> Duration = { it },
  private val playWanted: () -> Boolean = { true },
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

  // The slot media3 was handed to read that landing from, which is the slot the sample shown there is written on.
  private var mediaLanding: Duration? = null

  // Whether the player rests where the last seek handed over put it, having played nothing since. Read and written on
  // the player's thread only.
  private var restsOnSeek = false

  // Whether a play was asked for that waits on the replay seek to start the player. Read and written on the player's
  // thread only.
  private var playPending = false

  /**
   * Whether the player has played since the last seek it was handed, or was handed none. It may be playing still. Read
   * on the player's thread.
   */
  val playedSinceSeek: Boolean get() = !restsOnSeek

  /**
   * Rests the playhead on [slot], which media3 reads from [media], as a new graph starts there.
   */
  fun rest(
    slot: Duration,
    media: Duration,
  ) {
    landing = slot
    mediaLanding = media
    floor.clear()
  }

  // Counts the seeks handed to media3. A landing media3 reports is only taken for the latest of them.
  private var serial = 0

  /**
   * The serial of the seek media3 was handed last. Read and written on the player's thread only.
   */
  var playerSerial: Int = 0
    private set

  private val nudge = Media3SeekNudge()

  private val drawnSeeks = Media3DrawnSeeks()

  /**
   * The accuracy the last seek really ran at.
   *
   * Not always the accuracy that was asked for. See [clampedAccuracy].
   */
  var lastAccuracy: SeekAccuracy = SeekAccuracy.Exact
    private set

  private val floor = Media3PlayheadFloor(resting, playWanted)

  // A seek in flight reads where it is going, since a reading media3 reports before it lands is from before the seek.
  fun position(): Duration = target ?: positionAt(platformPositionMs())

  /**
   * The slot the last replay was for, or null where a seek or a new graph has moved the playhead since.
   */
  fun replayed(): Duration? = floor.replayed()

  private fun positionAt(clockMs: Long): Duration =
    floor.read(clockMs, restingAt(playheadOf(clockMs, landing, mediaLanding)))

  /**
   * How many seeks were asked for, counted on the engine's dispatcher.
   *
   * A play reads it as it is asked for, so a replay can tell a seek asked after the play from one asked before it.
   */
  var seeksAsked: Long = 0
    private set

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

    seeksAsked++
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
   * playhead reports is seeked to, which for a paused player is the frame it drew last and not where media3's clock
   * stopped, and nothing but [onRepresented] hears it land. The player is read once the playback thread has said all
   * it had to, since a player paused a moment ago still reports an estimate.
   *
   * @param surfaceLost Whether this is for a surface that just went, rather than one the player was handed. A player
   * told to play by the time the seek back would be issued is left to play, since it has no paused frame to keep and
   * the seek would take it back to the slot it rested on.
   * @param ended Whether the player ran to its end. media3 still means to play there, and would play the last slot out
   * again from the seek back, so it is stopped first and the seek only draws that slot. The last slot is drawn whatever
   * frame the play drew last, since a player too slow to keep up drops the frames late at the end and stops short of
   * it, while the playhead of an ended player reads the end.
   */
  fun represent(
    surfaceLost: Boolean,
    ended: Boolean = false,
  ) {
    handler.post {
      if (ended) player()?.playWhenReady = false
      val playback = playback()
      playback.run {
        handler.post {
          val standing = player() ?: return@post
          if (surfaceLost && !ended && (standing.playWhenReady || playPending)) return@post
          val restingMs = standing.currentPosition
          scope.launch { representFrom(restingMs, surfaceLost, ended) }
        }
      }
    }
  }

  /**
   * Starts the player, replaying the held sample it rests on where [at] is in a later slot of one and it was paused.
   *
   * Called on the player's thread, in the order it was asked for among the plays and pauses. A seek issued just before
   * reaches the player two hops later, so it can be handed over after this, and pins nothing since the player is
   * playing by then. Only a paused player has drawn nothing but the slot it rests on, so a play asked for while one is
   * already under way draws the sample as it goes and is not sent back to the sample's first slot, which would be a
   * seek that flushes what is playing.
   *
   * A replay starts the player in the same task that hands its seek over, right behind the seek, so the player draws
   * nothing from where it rested before the seek takes it back. Until then the play is pending, and a pause clears it.
   *
   * @param at Where the playhead read as the play was asked for. A player that played its way there reads a time inside
   * a slot, so it is the slot [at] lands on that is looked up. At or past the end there is nothing left to play, so
   * nothing is replayed, though the last slot is the nearest one to land on.
   * @param asked [seeksAsked] as the play was asked for, so a seek asked after it takes over from the replay.
   */
  fun resume(
    at: Duration,
    asked: Long,
  ) {
    floor.played()
    val standing = player()
    val wasPlaying = standing?.playWhenReady == true || playPending
    unpin()
    val loaded = resolved()
    val slot = loaded?.seekLandingOf(at) ?: at
    val replays = !wasPlaying && (loaded == null || at < loaded.duration) && firstSlotOf(slot) != slot
    if (!replays) {
      if (!playPending) standing?.playWhenReady = true
      return
    }
    playPending = true
    scope.launch { replay(slot, asked) }
  }

  /**
   * Stops the player and pins the preview to the slot it rests on. Called on the player's thread.
   *
   * The pin starts at [at], the slot read as the pause was asked for, and [stopped] moves it to the frame on the
   * surface.
   */
  fun pause(at: Duration) {
    playPending = false
    pin.pin(at)
    player()?.playWhenReady = false
    stopped()
  }

  /**
   * Hears that the player was stopped, by a pause or by media3 itself, as it is when the audio focus goes. Called on
   * the player's thread.
   *
   * A frame the player had released before it stopped is drawn after that, so once the playback thread has stopped the
   * pin is moved to the frame on the surface, which is the slot the playhead reports. The playback thread says which
   * seek that frame was drawn after, so a frame from before a seek handed over since is left to that seek.
   */
  fun stopped() {
    playback().run {
      val drawnAfter = drawnSeeks.drawn
      handler.post { pin.settle(drawnAfter, playerSerial) }
    }
  }

  /**
   * Hears that the player was started, by the engine or by media3's own controls. Called on the player's thread.
   *
   * A scrub under way keeps the pin its seeks set, since scrubbing mode stops the player on the frame each one lands
   * on, and lets go of it as the scrub ends. A play asked for in a scrub can start the player after its seek was
   * handed over, so letting go here would leave a later slot of a hold undrawn.
   */
  fun started() {
    floor.played()
    if (!scrubbing()) unpin()
  }

  /**
   * Lets go of the slot a paused player was pinned to, as it is about to play. Called on the player's thread. A seek
   * handed over after it finds the player playing and pins nothing.
   */
  fun unpin() {
    pin.release()
    restsOnSeek = false
  }

  /**
   * Seeks back to the first slot of the sample the player rests on, so a player about to play draws every later slot of
   * it, and starts the player behind that seek. Nothing but the playhead hears it land. A seek in flight is left to
   * carry on, since it is handed over after the pin was let go of, and the player is started at once. A seek back to a
   * held slot in flight, a replay among them, is one the player has to draw before it plays, so there the play waits
   * until that seek was handed over, and a replay takes it on itself. A seek asked after the play takes the player
   * somewhere else, so there is nothing to replay and the player is started behind that seek.
   *
   * @param asked [seeksAsked] as the play was asked for.
   */
  fun replay(
    slot: Duration,
    asked: Long,
  ) {
    if (asked != seeksAsked) {
      handler.post(::startPendingPlay)
      return
    }
    if (representing) {
      roundTrip { startPendingPlay() }
      return
    }
    if (target != null) {
      handler.post(::startPendingPlay)
      return
    }
    // The pause rested on this slot, whatever frame was last drawn, so the playhead holds it until the clock reaches
    // it.
    floor.raiseTo(slot)
    guard.seekIssued(wrap = false)
    representing = true
    completion = {}
    issue(slot, fresh = true, keepsCarry = true, starts = true)
  }

  /**
   * Hands over the play still pending, for a new player to take on. Called on the player's thread.
   *
   * @return Whether a play was pending.
   */
  fun takePendingPlay(): Boolean = playPending.also { playPending = false }

  // A seek back to a held slot handed over since the play was asked for pinned the paused player, so the pin goes
  // first. A scrub keeps the pin its seeks set until it ends.
  private fun startPendingPlay() {
    if (!takePendingPlay()) return
    if (!scrubbing()) unpin()
    player()?.playWhenReady = true
  }

  private fun representFrom(
    restingMs: Long,
    surfaceLost: Boolean,
    ended: Boolean,
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
    val resting = if (ended) loaded.duration else positionAt(restingMs)
    issue(inFlight ?: loaded.seekLandingOf(resting), fresh = true, keepsCarry = !ended)
  }

  /**
   * Hands media3 a seek to [target] for the completion already waiting in [completion].
   *
   * A redirect goes through here too, so the one completion reports wherever the last seek it was handed landed.
   *
   * @param fresh Whether media3 has to carry the seek out even where it already rests on [target].
   * @param keepsCarry Whether the slot a pause rested on is still the floor of the playhead after this seek, as it is
   * for a replay and a seek back to a held slot, which only draw what the player rests on again and move nothing.
   * @param starts Whether the play pending waits on this seek, as it does for a replay.
   */
  private fun issue(
    target: Duration,
    fresh: Boolean = false,
    keepsCarry: Boolean = false,
    starts: Boolean = false,
  ) {
    val issued = ++serial
    this.target = target
    if (!keepsCarry) floor.clear()
    landing = target
    val media = firstSlotOf(target)
    mediaLanding = media
    val scrubbed = scrubbing()
    onIssued()

    // Round the playback thread and back before the player is read. A player paused a moment ago still reports the
    // position it estimated while playing, and media3 decides whether to skip a seek from the one it really holds,
    // which the player reads once everything the playback thread had to say has arrived.
    roundTrip { playback -> handOver(issued, target, media, playback, fresh, scrubbed, starts) }
  }

  // Runs [block] on the player's thread once the playback thread has run what it had queued, behind every seek issued
  // before it.
  private fun roundTrip(block: (playback: Handler?) -> Unit) {
    handler.post {
      val playback = playback()
      playback.run { handler.post { block(playback) } }
    }
  }

  private fun handOver(
    issued: Int,
    target: Duration,
    media: Duration,
    playback: Handler?,
    fresh: Boolean,
    scrubbed: Boolean,
    starts: Boolean,
  ) {
    val standing = player()
    if (standing == null) {
      if (starts) playPending = false
      scope.launch { settle(issued) }
      return
    }

    playerSerial = issued
    val positionMs = media.toMediaMs()
    // The player is started right behind the seek, which media3 handles while it is still paused.
    val plays = starts && takePendingPlay()
    val pinned = heldSlotOf(target, media, standing.playWhenReady || plays, scrubbed && scrubbing())
    // media3's limit: see [Media3ScrubMode]. The seek that finds the player held out of the mode is an ordinary one,
    // and the one that resets what the player had read, so media3 has to carry it out wherever the player is.
    val inMode = inScrubbingMode()
    val fresh = fresh || (scrubbed && !inMode)
    val scrubbed = scrubbed && inMode
    // A seek to the millisecond the player rests on is still carried out when it moves which slot the sample is drawn
    // on.
    val moved = pin.moveTo(pinned)
    val skipped = !fresh && !moved && restsOnSeek && standing.skipsSeekTo(positionMs)
    // A paused player draws nothing but the frame a seek lands on, and can draw it before anything queued behind the
    // seek runs, so the wait opens ahead of the seek. A playing one is still drawing frames from before it, so there
    // the wait opens once the seek was handled.
    if (!standing.isPlaying) playback.run { drawnSeeks.await(issued) }
    // media3's limit: it skips a seek to the millisecond it rests on. See [seekNudgeMs].
    val nudged =
      seekGoesThroughNudge(
        fresh,
        skipped,
        standing.isPlaying,
        scrubbed,
        standing.currentPosition == positionMs,
      )
    nudge.through(nudged, positionMs)?.let(standing::seekTo)
    standing.seekTo(positionMs)
    if (plays) standing.playWhenReady = true
    restsOnSeek = !standing.playWhenReady
    enterScrubbingMode()
    playback.run { if (drawnSeeks.onHandled(issued, skipped)) scope.launch { settle(issued) } }
  }

  /**
   * Whether a seek media3 reports landing on [positionMs] is one to answer. Called on the player's thread.
   *
   * The landing on the millisecond a seek was nudged through is not, since the seek to its target comes right behind
   * it and is the one the player rests on.
   */
  fun landsOn(positionMs: Long): Boolean = nudge.landsOn(positionMs)

  private fun Handler?.run(block: () -> Unit) {
    if (this == null) block() else post(block)
  }

  /**
   * Hears that media3 is about to draw a frame. Called on media3's playback thread.
   */
  fun frameDrawn() {
    val handled = drawnSeeks.handled
    handler.post { pin.settle(handled, playerSerial) }
    if (drawnSeeks.frameAfter(handled)) scope.launch { settle(handled) }
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
    floor.clear()
    representing = false
    landsUnseen = false
  }
}
