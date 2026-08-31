package dev.jordond.filmstrip.playback.contract

import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlayerEngine
import kotlin.time.Duration

/**
 * One backend's engine, wrapped so a contract suite can see what the platform underneath it did.
 *
 * A backend supplies this from [PlayerEngineContractTest.createEngine]. Everything here beyond
 * [engine] is a fact the public API does not report, so a suite that needs one asks for it rather
 * than inferring it from a state snapshot.
 */
interface EngineUnderTest {
  /**
   * The engine under test, freshly built with no composition loaded.
   */
  val engine: PlayerEngine

  /**
   * How many times this backend has built or rebuilt its platform playback graph.
   *
   * Counts the work a decoder reinitialisation costs. Loading into a fresh engine reads 1, and a
   * call that reused the graph already standing leaves the count where it was.
   */
  val platformLoads: Int

  /**
   * Whether the platform under this backend keeps a duration of its own.
   *
   * A backend that answers true owes the suite a [platformDuration], and the suite waits for one
   * rather than reading once, since a platform that loads its item asynchronously has no answer at
   * the instant a load resolves. A backend whose platform reports no duration at all leaves this
   * false, and the cross check is skipped rather than passing on an absence.
   */
  val reportsPlatformDuration: Boolean get() = false

  /**
   * The duration the platform reports for itself, or null where the platform has no answer yet.
   *
   * A cross check against the resolved composition, never a source for it. It has to come off a
   * path the backend did not lower itself, or the check compares filmstrip's answer with its own.
   */
  val platformDuration: Duration? get() = null

  /**
   * Raises one interruption the way the system would.
   *
   * Called only for an occasion named in [PlayerEngineContractTest.stageableInterruptions].
   */
  fun stage(interruption: Interruption): Unit =
    throw NotImplementedError("This backend stages no interruption, so $interruption cannot be raised.")
}

/**
 * An occasion outside filmstrip that changes whether playback is wanted.
 *
 * Each one a backend can raise must produce exactly one
 * [PlaybackEvent.ExternalPlayWhenReadyChanged] and leave `playWhenReady` false.
 */
enum class Interruption {
  /**
   * Another app took the audio session, such as a notification sound or a second player starting.
   */
  AudioFocusLost,

  /**
   * The output route changed under playback, such as headphones being unplugged.
   */
  OutputRouteChanged,

  /**
   * The app left the foreground and the system stopped its playback.
   */
  AppBackgrounded,

  /**
   * The browser refused to keep playing without a fresh user gesture.
   */
  AutoplayBlocked,

  /**
   * A telephony call took the audio session.
   */
  IncomingCall,

  /**
   * The system's media services restarted underneath the player.
   */
  MediaServicesReset,
}

/**
 * The occasions no backend can raise from a test, on any of the four platforms.
 *
 * A call arriving needs real telephony, and a media services restart needs the OS daemon to die.
 * Neither has an entry point a test can reach, so both stay uncovered and are named here rather
 * than quietly left out of [Interruption]. A suite that claims to stage one is describing something
 * it faked.
 */
val UNSTAGEABLE_INTERRUPTIONS: Set<Interruption> =
  setOf(Interruption.IncomingCall, Interruption.MediaServicesReset)
