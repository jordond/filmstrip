package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [VideoPlayer] with no backend under it, for testing code that drives a player.
 *
 * Everything but the platform is the player filmstrip ships: the state snapshots, the seek and scrub handling, the
 * shuttle and the listeners. In place of a decoder there is a clock on [scope]'s scheduler. The playhead moves at
 * normal speed while the player is playing, ready and not stalled, a seek lands on the frame grid, and reaching the
 * duration stops the player and reports [PlaybackEvent.Ended]. Nothing here reads the wall clock, so a test moves
 * time with `advanceTimeBy` and lets queued work run with `runCurrent`.
 *
 * The player is ready as soon as it is made. Every call the code under test makes is kept in [calls].
 *
 * It does not loop a loop range, decode, or draw, and [VideoPlayer.readback] never answers. A playback range bounds a
 * shuttle and nothing else, so normal playback runs to the duration. Both ranges are kept in [calls].
 */
public class FakeVideoPlayer private constructor(
  private val engine: FakePlayerEngine,
  private val player: VideoPlayer,
  composition: Composition,
) : VideoPlayer by player {
  /**
   * Makes a player over [composition] that is [duration] long.
   *
   * @param scope The test's scope, whose scheduler is the player's clock.
   * @param composition The edit the player starts with, which [calls] and [compositions] record first.
   * @param duration How long the edit plays for.
   * @param frameRate The frame grid seeks, steps and a shuttle land on.
   * @param features What the player claims to support.
   */
  public constructor(
    scope: TestScope,
    composition: Composition = Composition(emptyList()),
    duration: Duration = DEFAULT_DURATION,
    frameRate: Int = DEFAULT_FRAME_RATE,
    features: Set<PlayerFeature> = PlayerFeature.entries.toSet(),
  ) : this(FakePlayerEngine(scope, features, frameRate, duration), composition)

  private constructor(
    engine: FakePlayerEngine,
    composition: Composition,
  ) : this(engine, Filmstrip { addPlayerEngineFactory { _, _ -> engine } }.preview(composition), composition)

  private val recorded = mutableListOf<PlayerCall>(PlayerCall.SetComposition(composition, null, false))

  /**
   * Every call made on the player so far, oldest first, starting with the composition it was made with.
   */
  public val calls: List<PlayerCall> get() = recorded.toList()

  /**
   * Every composition the player was handed, oldest first.
   */
  public val compositions: List<Composition> get() = engine.compositions.toList()

  /**
   * Whether [close] has been called.
   */
  public var isClosed: Boolean = false
    private set

  /**
   * Whether a seek waits for [landSeek] rather than landing by itself, which holds `isSeeking` true in between.
   */
  public var holdSeeks: Boolean
    get() = engine.holdSeeks
    set(value) {
      engine.holdSeeks = value
    }

  /**
   * Answers each `setComposition`, or null to load every composition at the duration the player has.
   *
   * A [SetCompositionResult.Success] takes its duration and leaves the player ready, and a
   * [SetCompositionResult.Failure] leaves it in [PlaybackStatus.Error].
   */
  public var onSetComposition: ((SetCompositionRequest) -> SetCompositionResult)?
    get() = engine.onSetComposition
    set(value) {
      engine.onSetComposition = value
    }

  /**
   * Moves the player to [status], the way a backend reports preparing, ready or released.
   */
  public fun setStatus(status: PlaybackStatus): Unit = engine.show(status)

  /**
   * Stalls the player or lets it run again. A stalled player keeps its playhead where it is.
   */
  public fun setStalled(stalled: Boolean): Unit = engine.stall(stalled)

  /**
   * Changes how long the edit plays for.
   */
  public fun setDuration(duration: Duration): Unit = engine.lengthen(duration)

  /**
   * Fails the player with [error], which leaves it in [PlaybackStatus.Error].
   */
  public fun fail(error: PlaybackError): Unit = engine.fail(error)

  /**
   * Sends [event] to everything listening, as a backend would.
   */
  public fun emit(event: PlaybackEvent): Unit = engine.emit(event)

  /**
   * Lands the seek [holdSeeks] is holding. Does nothing when none is waiting.
   */
  public fun landSeek(): Unit = engine.landSeek()

  override fun play() {
    recorded += PlayerCall.Play
    player.play()
  }

  override fun pause() {
    recorded += PlayerCall.Pause
    player.pause()
  }

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ) {
    recorded += PlayerCall.SeekTo(position, accuracy)
    player.seekTo(position, accuracy)
  }

  override fun stepFrames(frames: Int) {
    recorded += PlayerCall.StepFrames(frames)
    player.stepFrames(frames)
  }

  override fun beginScrub() {
    recorded += PlayerCall.BeginScrub
    player.beginScrub()
  }

  override fun endScrub() {
    recorded += PlayerCall.EndScrub
    player.endScrub()
  }

  override fun beginShuttle(rate: Float) {
    recorded += PlayerCall.BeginShuttle(rate)
    player.beginShuttle(rate)
  }

  override fun endShuttle() {
    recorded += PlayerCall.EndShuttle
    player.endShuttle()
  }

  override fun setVolume(volume: Float) {
    recorded += PlayerCall.SetVolume(volume)
    player.setVolume(volume)
  }

  override fun setLoopRange(range: TimeRange?) {
    recorded += PlayerCall.SetLoopRange(range)
    player.setLoopRange(range)
  }

  override fun setPlaybackRange(range: TimeRange?) {
    recorded += PlayerCall.SetPlaybackRange(range)
    player.setPlaybackRange(range)
  }

  override fun setQualityPolicy(policy: PreviewQualityPolicy) {
    recorded += PlayerCall.SetQualityPolicy(policy)
    player.setQualityPolicy(policy)
  }

  override fun setExportSpec(spec: ExportSpec) {
    recorded += PlayerCall.SetExportSpec(spec)
    player.setExportSpec(spec)
  }

  override suspend fun setComposition(
    composition: Composition,
    startAt: Duration?,
    playWhenReady: Boolean,
  ): SetCompositionResult {
    recorded += PlayerCall.SetComposition(composition, startAt, playWhenReady)
    return player.setComposition(composition, startAt, playWhenReady)
  }

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    recorded += PlayerCall.SetComposition(request.composition, request.startAt, request.playWhenReady)
    return player.setComposition(request, callback)
  }

  override fun close() {
    recorded += PlayerCall.Close
    isClosed = true
    player.close()
  }

  private companion object {
    val DEFAULT_DURATION: Duration = 10.seconds
    const val DEFAULT_FRAME_RATE = 30
  }
}
