package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The contracts every player backend must honour that need nothing but an engine and a
 * composition.
 *
 * A backend inherits this, supplies [createEngine], [fixture] and [fixtureFrameRate], and gets the nine behaviours
 * that drift silently between backends: the duration a snapshot reports, what the preview reports it is delivering, one
 * completion per issued seek, the slot a seek reports it landed on, an equal composition doing no platform work, a
 * rebuild leaving the transport and the playhead where they were, how an interruption from outside filmstrip reaches a
 * listener, a loop range the composition ends inside wrapping at that end rather than ending there, and a surface
 * reading a paused player getting the first frame and then a parameter edit.
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
   * [fixture] with its clip handed over as bytes rather than named.
   *
   * Every engine plays a buffer, so no subclass gets to skip this.
   */
  protected abstract val bytesFixture: EditComposition

  /**
   * Which interruptions this backend can raise through [EngineUnderTest.stage].
   *
   * Empty is the honest answer for a suite that does not run on a device, since audio focus and
   * route changes need a real audio session. Nothing in [UNSTAGEABLE_INTERRUPTIONS] may appear.
   */
  protected open val stageableInterruptions: Set<Interruption> get() = emptySet()

  /**
   * Whether this backend claims [dev.jordond.filmstrip.player.PlayerFeature.LiveParameterRedraw].
   *
   * Read against what the engine reports, so a backend losing the claim on a machine goes red instead of skipping.
   */
  protected abstract val claimsLiveParameterRedraw: Boolean

  /**
   * The output frame rate [fixture] plays at, which is the grid every seek into it lands on.
   */
  protected abstract val fixtureFrameRate: Int

  /**
   * One clip with no effect on it that keeps its audio, which is the plan that leaves the copy path on its first
   * effect.
   */
  protected open val plainFixture: EditComposition get() = fixture

  /**
   * Whether this backend goes back through [PlaybackStatus.Preparing] when [plainFixture] takes its first effect.
   *
   * True only for a backend whose own graph cannot take that edge, which its override names.
   */
  protected open val plainClipEditPrepares: Boolean get() = false

  /**
   * Whether this backend's preview info carries the output frame an edit settled on.
   */
  protected open val reportsOutputFrame: Boolean get() = true

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
  fun aBufferLoadsAndSeeksLikeTheClipItHolds() =
    contractTest { scope ->
      var named: Duration? = null
      val namedSubject = createEngine(scope)
      withEngine(namedSubject.engine) {
        named =
          namedSubject.engine
            .awaitComposition(fixture)
            .shouldBeInstanceOf<SetCompositionResult.Success>()
            .duration
      }

      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(bytesFixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        loaded.duration shouldBe named

        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to seek within.")
        val at = duration / 2
        val landing = outputSlotTime(outputSlotOf(at, fixtureFrameRate), fixtureFrameRate)

        subject.engine.seekTo(at, SeekAccuracy.Exact)
        awaitContract("the seek to $at to complete") { recorder.seekCompletions.isNotEmpty() }

        recorder.seekCompletions.last().position shouldBe landing
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
  fun aSurfaceReadingAPausedPlayerGetsTheFirstFrameThenAParameterEdit() =
    contractTest { scope ->
      createEngine(
        scope,
      ).surfaceReadStaysCurrentWhilePaused(scope, fixture, fixtureFrameRate, claimsLiveParameterRedraw)
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
          subject.quiet()

          recorder.seekCompletions.size - before shouldBe burst
        }
      }
    }

  @Test
  fun anExactSeekBetweenTwoFramesReportsTheSlotItLandedOn() =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to seek within.")
        val rate = fixtureFrameRate

        // One a third of a slot past its slot and one a third short of it, so a landing that rounds either way wrong
        // lands a slot off.
        val positions =
          listOf(
            offGrid(duration, ROUNDS_DOWN_AT, rate, thirds = 1),
            offGrid(duration, ROUNDS_UP_AT, rate, thirds = -1),
          )
        for (at in positions) {
          val landing = outputSlotTime(outputSlotOf(at, rate), rate)
          val before = recorder.seekCompletions.size
          subject.engine.seekTo(at, SeekAccuracy.Exact)
          awaitContract("the seek to $at to complete") { recorder.seekCompletions.size > before }

          recorder.seekCompletions.last().position shouldBe landing
          recorder.playhead shouldBe landing

          subject.quiet()
          recorder.playhead shouldBe landing
        }
      }
    }

  @Test
  fun aRelaxedSeekReportsASlotThePlayheadAgreesWith() =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to seek within.")
        val rate = fixtureFrameRate

        val at = offGrid(duration, RELAXED_AT, rate, thirds = 1)
        val exact = outputSlotTime(outputSlotOf(at, rate), rate)
        val before = recorder.seekCompletions.size
        subject.engine.seekTo(at, SeekAccuracy.Nearest)
        awaitContract("the relaxed seek to $at to complete") { recorder.seekCompletions.size > before }

        val reported = recorder.seekCompletions.last().position
        outputSlotTime(outputSlotOf(reported, rate), rate) shouldBe reported
        reported shouldBeLessThanOrEqualTo exact

        subject.quiet()
        recorder.playhead shouldBe reported
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
        subject.quiet()

        subject.platformLoads shouldBe 1
        recorder.statesSince(mark).forEach { it.status shouldBe PlaybackStatus.Ready }
      }
    }

  @Test
  fun aRebuildWhilePlayingKeepsPlayingFromWhereThePlayheadWas() = rebuilds.keepsPlaying()

  @Test
  fun aRebuildWhilePausedStaysPausedAndHoldsThePlayhead() = rebuilds.staysPaused()

  @Test
  fun aPausedRebuildShorterThanThePlayheadLandsWhereTheHeldPositionSays() =
    rebuilds.pausedLandsOnLastFrame(loop = false)

  @Test
  fun aPausedRebuildShorterThanThePlayheadLandsOnItsLastFrameUnderALoopRange() =
    rebuilds.pausedLandsOnLastFrame(loop = true)

  @Test
  fun aPlayingRebuildShorterThanThePlayheadWrapsIntoTheLoopRange() = rebuilds.playingWrapsIntoLoopRange()

  @Test
  fun aPausedRebuildPastTheLoopRangeStaysWhereItLanded() = rebuilds.pausedStaysPastLoopRange(fixtureRebuilt)

  @Test
  fun aPausedParameterEditPastTheLoopRangeStaysWhereItLanded() = rebuilds.pausedStaysPastLoopRange(fixtureParameters)

  @Test
  fun aPlainClipTakingItsFirstEffectHoldsTheTransport() =
    rebuilds.plainClipEditHoldsTheTransport(
      plain = plainFixture,
      edited = plainFixture.withEffects(listOf(Brightness(RETUNED_BRIGHTNESS))),
      prepares = plainClipEditPrepares,
    )

  @Test
  fun aParameterEditThatMovesTheFrameHoldsTheTransport() =
    rebuilds.frameMovingEditHoldsTheTransport(
      moved = fixture.withEffects(listOf(Crop(AspectRatio.Square))),
      reportsFrame = reportsOutputFrame,
    )

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
          subject.quiet()

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

  @Test
  fun anOpenLoopRangeWrapsAtTheCompositionEnd() =
    wrapsAtTheCompositionEnd(thirds = -1) { start, _ -> TimeRange.from(start) }

  @Test
  fun aLoopRangeEndingOnTheDurationWrapsAtTheCompositionEnd() =
    wrapsAtTheCompositionEnd(thirds = 1) { start, duration -> TimeRange.of(start, duration) }

  @Test
  fun aLoopRangeEndingPastTheDurationWrapsAtTheCompositionEnd() =
    wrapsAtTheCompositionEnd(thirds = -1) { start, duration -> TimeRange.of(start, duration + duration / 2) }

  /**
   * Plays into the end of a composition that ends inside the loop range [shape] builds, and checks it wraps there
   * twice.
   *
   * [shape] gets the range's start and the composition's duration. The start sits well inside the composition,
   * [thirds] thirds of a slot off the slot it rounds to, so a wrap that lands on the raw start rather than on that slot
   * is caught. Playback starts close to the end, so the first pass is short. The second wrap is what shows playback
   * ran the whole range again.
   */
  private fun wrapsAtTheCompositionEnd(
    thirds: Int,
    shape: (start: Duration, duration: Duration) -> TimeRange,
  ): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
        val rate = fixtureFrameRate

        val range = shape(offGrid(duration, LOOP_FROM, rate, thirds), duration)
        val wrap = loopWrapOf(range, duration, rate) ?: fail("The loop range at ${range.start} has nothing to loop.")
        val firstSlot = outputSlotTime(wrap.firstSlot, rate)
        subject.engine.setLoopRange(range)

        val approach = outputSlotTime(outputSlotOf(duration * APPROACH_AT, rate), rate)
        val seeks = recorder.seekCompletions.size
        subject.engine.seekTo(approach, SeekAccuracy.Exact)
        awaitContract("the seek to $approach to complete") { recorder.seekCompletions.size > seeks }

        val eventsMark = recorder.events.size
        val positionsMark = recorder.playheads.size
        subject.engine.play()
        awaitLoopWraps(recorder, eventsMark, positionsMark, count = 2)

        // A wrap landing between the events read and the playheads read below, or the landing playhead
        // arriving a moment before the RangeLooped event that explains it, is what makes this flaky.
        // Confirming playback is still running before pausing it, then waiting for that pause to settle,
        // means both reads see the same still record instead of racing a wrap in progress.
        recorder.lastState.playWhenReady shouldBe true
        recorder.lastState.status shouldNotBe PlaybackStatus.Ended
        subject.engine.pause()
        awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }
        subject.quiet()

        val since = recorder.events.drop(eventsMark)
        since.filterIsInstance<PlaybackEvent.Ended>() shouldBe emptyList()
        val looped = since.filterIsInstance<PlaybackEvent.RangeLooped>()
        looped.forEach { it.range shouldBe range }

        val positions = recorder.playheads.drop(positionsMark)
        val drops = dropIndices(positions)
        drops.map { index -> positions[index] }.forEach { it shouldBe firstSlot }
        drops.size shouldBe looped.size
        positions.drop(drops.first()).forEach { it shouldBeGreaterThanOrEqualTo firstSlot }
      }
    }

  /**
   * Waits for playback to have wrapped [count] times, with at least [count] drops recorded in the playhead since
   * [positionsMark].
   *
   * Fails as soon as the end is reported, rather than waiting out the budget for a wrap that is not coming.
   */
  private suspend fun awaitLoopWraps(
    recorder: ContractRecorder,
    eventsMark: Int,
    positionsMark: Int,
    count: Int,
  ) {
    awaitContract("playback to wrap $count times") {
      val since = recorder.events.drop(eventsMark)
      since.firstOrNull { it is PlaybackEvent.Ended }?.let { fail("Playback reported $it instead of wrapping.") }
      val looped = since.count { it is PlaybackEvent.RangeLooped }
      looped >= count && dropIndices(recorder.playheads.drop(positionsMark)).size >= count
    }
  }

  private suspend fun EngineUnderTest.quiet() {
    awaitStep("the engine to go quiet") { awaitQuiet() }
  }

  private val rebuilds: RebuildContract get() = RebuildContract(::createEngine, fixture, fixtureFrameRate)

  private val fixtureRebuilt: EditComposition get() = fixture.cutInTwo(fixtureFrameRate)

  private val fixtureParameters: EditComposition get() = fixture.withEffects(listOf(Brightness(RETUNED_BRIGHTNESS)))

  private companion object {
    val BURST_SIZES = listOf(1, 2, 5, 9)

    /**
     * The index of every reading in [readings] that is lower than the one straight before it.
     *
     * That is what a wrap looks like in an ordered position record: a drop, not a plateau or a climb.
     */
    fun dropIndices(readings: List<Duration>): List<Int> =
      (1 until readings.size).filter { index -> readings[index] < readings[index - 1] }

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

    // Where across the composition the seeks in the landing tests aim. Well inside it, and the relaxed one mid GOP for
    // every fixture's keyframe interval.
    const val ROUNDS_DOWN_AT = 0.3
    const val ROUNDS_UP_AT = 0.6
    const val RELAXED_AT = 0.45

    // Any brightness a fixture does not already carry, which changes a parameter and nothing on the timeline.
    const val RETUNED_BRIGHTNESS = 0.7f

    // Where across the composition the loop range starts, and where playback into its end begins.
    const val LOOP_FROM = 0.7
    const val APPROACH_AT = 0.9
  }
}
