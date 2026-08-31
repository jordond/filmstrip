package dev.jordond.filmstrip.playback.contract

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The contracts every player backend must honour that need nothing but an engine and a
 * composition.
 *
 * A backend inherits this, supplies [createEngine] and [fixture], and gets the five behaviours that
 * drift silently between backends: the duration a snapshot reports, what the preview reports it is
 * delivering, one completion per issued seek, an equal composition doing no platform work, and how
 * an interruption from outside filmstrip reaches a listener.
 *
 * Pixel work is in [PlayerPixelContractTest] instead, so a backend with no export to compare
 * against is not made to stub out half of one class.
 *
 * The test names here are camel case rather than the backticked sentences the rest of the module
 * uses. This class is compiled into an Android instrumented APK, and dex before version 040, which
 * needs API 30, rejects a space in a method name.
 */
abstract class PlayerEngineContractTest {
  /**
   * Builds a fresh engine holding no composition.
   *
   * Called once per subject. The suite disposes the engine afterwards.
   *
   * @param scope The dispatcher the suite drives the engine from. Confine platform callbacks to it.
   */
  protected abstract fun createEngine(scope: CoroutineScope): EngineUnderTest

  /**
   * A composition this backend can load, long enough to seek around inside.
   */
  protected abstract val fixture: EditComposition

  /**
   * Which interruptions this backend can raise through [EngineUnderTest.stage].
   *
   * Empty is the honest answer for a suite that does not run on a device, since audio focus and
   * route changes need a real audio session. Nothing in [UNSTAGEABLE_INTERRUPTIONS] may appear.
   */
  protected open val stageableInterruptions: Set<Interruption> get() = emptySet()

  @Test
  fun theReportedDurationMatchesTheResolvedCompositionAndThePlatform() =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        val result = subject.engine.awaitComposition(fixture)
        val loaded = result.shouldBeInstanceOf<SetCompositionResult.Success>()

        awaitContract("the snapshot to carry a duration") { recorder.lastState.duration != null }
        recorder.lastState.duration shouldBe loaded.duration

        // Both are cross checks against the one answer above, never a source for it.
        fixture.duration?.let { declared -> loaded.duration shouldBe declared }

        // A backend that claims a platform clock has to produce a reading. Waiting for it is what
        // stops an absent one from passing this line by being skipped.
        if (subject.reportsPlatformDuration) {
          awaitContract("the platform to report a duration of its own") { subject.platformDuration != null }
        }
        subject.platformDuration?.let { platform -> loaded.duration shouldBe platform }
      }
    }

  @Test
  fun aLoadedCompositionReportsWhatThePreviewIsDelivering() =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to report what it is delivering") { recorder.previewInfo.isNotEmpty() }

        val delivered = recorder.previewInfo.last()
        delivered.outputSize.width shouldBeGreaterThan 0
        delivered.outputSize.height shouldBeGreaterThan 0
        delivered.renderScale shouldBeGreaterThan 0f

        // A host that builds its surface after the load still has to learn what to size it to, so
        // the replay to a late listener is part of the contract rather than a convenience.
        val late = ContractRecorder()
        val registration = subject.engine.addListener(late)
        try {
          late.previewInfo shouldBe listOf(delivered)
        } finally {
          registration.cancel()
        }
      }
    }

  @Test
  fun everyIssuedSeekYieldsExactlyOneCompletion() =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to seek within.")

        // A burst of one, then bursts big enough that most requests are superseded before the
        // platform reaches them. A superseded request still owes the caller a completion.
        for (burst in BURST_SIZES) {
          val before = recorder.seekCompletions.size
          spreadOver(duration, burst).forEach { subject.engine.seekTo(it, SeekAccuracy.Exact) }

          awaitContract("$burst seeks to complete") { recorder.seekCompletions.size - before >= burst }
          awaitContract("the seeking axis to settle after $burst seeks") { !recorder.lastState.isSeeking }
          settle()

          recorder.seekCompletions.size - before shouldBe burst
        }
      }
    }

  @Test
  fun anEqualCompositionDoesNoPlatformWork() =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first load to finish") { recorder.lastState.hasComposition }
        subject.platformLoads shouldBe 1

        // A distinct instance carrying the same edit, so passing depends on the diff rather than on
        // the backend recognising the object it was already holding.
        val equal = fixture.withEffects(fixture.effects)
        (equal === fixture) shouldBe false
        diff(fixture, equal) shouldBe CompositionDiff.Equal

        val mark = recorder.mark()
        subject.engine.awaitComposition(equal).shouldBeInstanceOf<SetCompositionResult.Success>()
        settle()

        subject.platformLoads shouldBe 1
        recorder.statesSince(mark).forEach { it.status shouldBe PlaybackStatus.Ready }
      }
    }

  @Test
  fun eachStagedInterruptionReportsOneExternalChangeAndLeavesPlaybackUnwanted() =
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
          settle()

          recorder.externalChanges.map { it.playWhenReady } shouldBe listOf(false)
          recorder.lastState.playWhenReady shouldBe false
        }
      }
    }

  // Named rather than absent. A gap that appears nowhere reads as coverage.
  @Test
  fun anUnreachableInterruptionIsNeverClaimedAsStageable() {
    stageableInterruptions.intersect(UNSTAGEABLE_INTERRUPTIONS) shouldBe emptySet<Interruption>()
  }

  private companion object {
    val BURST_SIZES = listOf(1, 2, 5, 9)

    /**
     * [count] positions spread evenly across `(0, duration)`, touching neither end.
     *
     * Zero and the duration are the two a backend special-cases, and a suite that visited only
     * those would agree with an engine that handles nothing in between.
     */
    fun spreadOver(
      duration: Duration,
      count: Int,
    ): List<Duration> = (1..count).map { step -> duration * (step / (count + 1.0)) }
  }
}
