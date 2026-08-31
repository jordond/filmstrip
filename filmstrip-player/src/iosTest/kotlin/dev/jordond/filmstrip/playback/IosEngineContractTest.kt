package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.playback.contract.EngineUnderTest
import dev.jordond.filmstrip.playback.contract.Interruption
import dev.jordond.filmstrip.playback.contract.PlayerEngineContractTest
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.playback.contract.settleForAbsence
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerRateDidChangeNotification
import platform.AVFoundation.AVPlayerRateDidChangeReasonAppBackgrounded
import platform.AVFoundation.AVPlayerRateDidChangeReasonKey
import platform.AVFoundation.AVPlayerRateDidChangeReasonSetRateCalled
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.numberWithUnsignedLong
import kotlin.test.Test

/**
 * The shared engine contracts, run against real AVFoundation on the iOS simulator.
 *
 * All three occasions the iOS implementation watches are staged from the notification names and
 * `userInfo` keys the system itself posts, so what runs is the parsing in `AvInterruptions.ios.kt`:
 * an interruption whose type is `AVAudioSessionInterruptionTypeBegan`, a route change whose reason
 * is `AVAudioSessionRouteChangeReasonOldDeviceUnavailable`, and a rate change whose reason is
 * `AVPlayerRateDidChangeReasonAppBackgrounded`. The negative cases post the same three notifications
 * carrying the other codes, which a parser that fired on the name alone would fail.
 *
 * The rate change is posted with the engine's own player as the object, since that is what the
 * system posts it with and what the engine registered for.
 *
 * What is not covered here:
 * - [Interruption.IncomingCall] and [Interruption.MediaServicesReset], which need real telephony and
 *   a dead media daemon and so are in `UNSTAGEABLE_INTERRUPTIONS` for every platform.
 * - A real route change, which needs hardware to be unplugged. The route change posted here is
 *   synthetic, and nothing in the simulator changes an actual audio route.
 * - Real backgrounding, which needs an app lifecycle a test process does not have. The rate change
 *   posted here is synthetic.
 * - The concurrent decode session budget, which iOS enforces across every player in the process. It
 *   has no [Interruption] case, and the engine does nothing about it today.
 */
class IosEngineContractTest : PlayerEngineContractTest() {
  init {
    pumpMainRunLoopDuringContracts()
  }

  override fun createEngine(scope: CoroutineScope): EngineUnderTest = appleSubject(scope)

  override val fixture: EditComposition = appleFixtureComposition()

  override val stageableInterruptions: Set<Interruption> =
    setOf(Interruption.AudioFocusLost, Interruption.OutputRouteChanged, Interruption.AppBackgrounded)

  @Test
  fun `an interruption that ended leaves playback wanted`(): TestResult =
    playingThrough {
      postAudioSession(
        name = AVAudioSessionInterruptionNotification,
        key = AVAudioSessionInterruptionTypeKey,
        code = AVAudioSessionInterruptionTypeEnded,
      )
    }

  @Test
  fun `a route change that added a device leaves playback wanted`(): TestResult =
    playingThrough {
      postAudioSession(
        name = AVAudioSessionRouteChangeNotification,
        key = AVAudioSessionRouteChangeReasonKey,
        code = AVAudioSessionRouteChangeReasonNewDeviceAvailable,
      )
    }

  @Test
  fun `a rate change that somebody asked for leaves playback wanted`(): TestResult =
    playingThrough { engine ->
      postRateChange(engine.platformPlayer, AVPlayerRateDidChangeReasonSetRateCalled)
    }

  @Test
  fun `a repeated occasion reports one external change`(): TestResult =
    contractTest { scope ->
      for (occasion in stageableInterruptions) {
        val subject = createEngine(scope)
        withEngine(subject.engine) { recorder ->
          subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("$occasion's engine to be ready") { recorder.lastState.hasComposition }

          subject.engine.play()
          awaitContract("playback to be wanted before $occasion") { recorder.lastState.playWhenReady }

          subject.stage(occasion)
          awaitContract("$occasion to reach the listener") { recorder.externalChanges.isNotEmpty() }

          // Playback is already unwanted, so the second posting has no edge to report.
          subject.stage(occasion)
          settleForAbsence()

          recorder.externalChanges.map { it.playWhenReady } shouldBe listOf(false)
          recorder.lastState.playWhenReady shouldBe false
        }
      }
    }

  /**
   * Plays the fixture, runs [post] against the engine's own player, and asserts that nothing about
   * playback moved.
   */
  private fun playingThrough(post: (AvPlayerEngine) -> Unit): TestResult =
    contractTest { scope ->
      val subject = appleSubject(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }

        subject.engine.play()
        awaitContract("playback to be wanted") { recorder.lastState.playWhenReady }

        post(subject.engine)
        settle()

        // Read while the clip is still running, since a posting that took playback away would have
        // shown by now and the fixture is shorter than the wait below.
        recorder.lastState.playWhenReady shouldBe true

        // Nothing arrives late either. The clip runs out during this wait, and reaching the end
        // stops playback without reporting an external change.
        settleForAbsence()
        recorder.externalChanges.shouldBeEmpty()
      }
    }
}

/**
 * The harness, typed to the Apple engine so a test can reach the player a rate change is scoped to.
 */
private fun appleSubject(scope: CoroutineScope): AppleEngineUnderTest =
  AppleEngineUnderTest(scope) { engine, occasion -> postInterruption(engine, occasion) }

private fun postInterruption(
  engine: AvPlayerEngine,
  interruption: Interruption,
) {
  when (interruption) {
    Interruption.AudioFocusLost -> {
      postAudioSession(
        name = AVAudioSessionInterruptionNotification,
        key = AVAudioSessionInterruptionTypeKey,
        code = AVAudioSessionInterruptionTypeBegan,
      )
    }
    Interruption.OutputRouteChanged -> {
      postAudioSession(
        name = AVAudioSessionRouteChangeNotification,
        key = AVAudioSessionRouteChangeReasonKey,
        code = AVAudioSessionRouteChangeReasonOldDeviceUnavailable,
      )
    }
    Interruption.AppBackgrounded -> {
      postRateChange(engine.platformPlayer, AVPlayerRateDidChangeReasonAppBackgrounded)
    }
    else -> {
      throw NotImplementedError("iOS posts no notification for $interruption.")
    }
  }
}

/**
 * Posts one audio session notification carrying [code] under [key], the way iOS posts it.
 *
 * The session singleton is the object because that is what the system sends it from. The engine
 * observes with no object filter, so any poster reaches it, and passing the singleton keeps the
 * posting the same shape as the real one rather than proving anything extra.
 */
private fun postAudioSession(
  name: String?,
  key: String?,
  code: ULong,
) {
  NSNotificationCenter.defaultCenter.postNotificationName(
    aName = name,
    `object` = AVAudioSession.sharedInstance(),
    userInfo = mapOf<Any?, Any?>(key to NSNumber.numberWithUnsignedLong(code)),
  )
}

/**
 * Posts one rate change carrying [reason], the way [player] posts it when its rate moves.
 *
 * The player is the object rather than null. The engine registered for that one player, and an
 * observer scoped to an object reads nothing posted without it.
 */
private fun postRateChange(
  player: AVPlayer,
  reason: String?,
) {
  NSNotificationCenter.defaultCenter.postNotificationName(
    aName = AVPlayerRateDidChangeNotification,
    `object` = player,
    userInfo = mapOf<Any?, Any?>(AVPlayerRateDidChangeReasonKey to reason),
  )
}
