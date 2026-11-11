package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
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
 * A backend inherits this, supplies [createEngine], [fixture] and [fixtureFrameRate], and gets the eleven behaviours
 * that drift silently between backends: the duration a snapshot reports, what the preview reports it is delivering, one
 * completion per issued seek, the slot a seek reports it landed on, the playhead moving once when a relaxed seek lands,
 * an equal composition doing no platform work, a rebuild leaving the transport and the playhead where they were, how an
 * interruption from outside filmstrip reaches a listener, a loop range the composition ends inside wrapping at that end
 * rather than ending there, a surface reading a paused player getting the first frame and then a parameter edit, and a
 * play or pause given before the first load.
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
  protected abstract val fixture: Composition

  /**
   * [fixture] with its clip handed over as bytes rather than named.
   *
   * Every engine plays a buffer, so no subclass gets to skip this.
   */
  protected abstract val bytesFixture: Composition

  /**
   * A composition this backend refuses to load.
   *
   * Defaults to a buffer holding no container at all, which no real backend can read.
   */
  protected open val unreadableFixture: Composition
    get() = Composition(tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(ByteArray(UNREADABLE_BYTES)))))))

  /**
   * Builds an engine primed to fail on one edit, in whichever way this backend can make its own item fail.
   *
   * @param scope The dispatcher the suite drives the engine from.
   */
  protected abstract fun createFailingItem(scope: CoroutineScope): FailingItem

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
  protected open val plainFixture: Composition get() = fixture

  /**
   * Whether this backend goes back through [PlaybackStatus.Preparing] when [plainFixture] takes its first effect.
   *
   * True only where the backend's own graph cannot take that edge, or the host's encoders change what the edit
   * resolves to, which the override names.
   */
  protected open suspend fun plainClipEditPrepares(): Boolean = false

  /**
   * Whether this backend's preview info carries the output frame an edit settled on.
   */
  protected open val reportsOutputFrame: Boolean get() = true

  /**
   * How far from its target a relaxed seek on this backend may land, on either side.
   *
   * Zero for a backend that lands every relaxed seek exactly. An override reads the backend's own tolerance, or the
   * fixture's sync interval where the landing is a sync sample.
   */
  protected open val relaxedSeekTolerance: Duration get() = Duration.ZERO

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
        val tolerance = relaxedSeekTolerance
        val earliest = outputSlotTime(outputSlotOf((at - tolerance).coerceAtLeast(Duration.ZERO), rate), rate)
        val latest = outputSlotTime(outputSlotOf(at + tolerance, rate), rate)
        val before = recorder.seekCompletions.size
        subject.engine.seekTo(at, SeekAccuracy.Nearest)
        awaitContract("the relaxed seek to $at to complete") { recorder.seekCompletions.size > before }

        // The frame shown can sit on either side of the target, so the slot reported can too.
        val reported = recorder.seekCompletions.last().position
        outputSlotTime(outputSlotOf(reported, rate), rate) shouldBe reported
        reported shouldBeGreaterThanOrEqualTo earliest
        reported shouldBeLessThanOrEqualTo latest

        subject.quiet()
        recorder.playhead shouldBe reported
      }
    }

  @Test
  fun aShuttleRunsToEachEdgeAndHolds() = shuttles.runsToEachEdgeAndHolds()

  @Test
  fun aPausedShuttleEndsPausedOnTheFrameShowing() = shuttles.aPausedShuttleEndsPausedOnTheFrameShowing()

  @Test
  fun aPlayingShuttleEndsPlayingFromTheFrameShowing() = shuttles.aPlayingShuttleEndsPlayingFromTheFrameShowing()

  @Test
  fun anInvalidShuttleRateThrows() = shuttles.anInvalidRateThrows()

  @Test
  fun aPausedRelaxedSeekHoldsTheRequestedSlotThenStaysWhereItLanded() =
    relaxedSeeks.holdsThenStaysWhereItLanded(ROUNDS_UP_AT)

  @Test
  fun aPlayingRelaxedSeekHoldsTheRequestedSlotThenPlaysOnFromWhereItLanded() =
    relaxedSeeks.holdsThenPlaysOnFromWhereItLanded(ROUNDS_UP_AT)

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
      prepares = ::plainClipEditPrepares,
    )

  @Test
  fun aParameterEditThatMovesTheFrameHoldsTheTransport() =
    rebuilds.frameMovingEditHoldsTheTransport(
      moved = fixture.withEffects(listOf(Crop(AspectRatio.Square))),
      reportsFrame = reportsOutputFrame,
    )

  @Test
  fun aPlayBeforeTheFirstLoadMovesThePlayheadOnceItLoads() = earlyTransport.playMovesThePlayheadOnceLoaded()

  @Test
  fun aPauseBeforeTheFirstLoadHoldsThePlayheadOnTheStart() = earlyTransport.pauseHoldsThePlayheadOnTheStart()

  @Test
  fun aRefusedFirstLoadStaysOnTheStatus() = earlyTransport.refusalStaysOnTheStatus()

  @Test
  fun aPlayBeforeARefusedFirstLoadMovesThePlayheadOnTheNextLoad() = earlyTransport.playOutlivesARefusedLoad(true)

  @Test
  fun aPlayAfterARefusedFirstLoadMovesThePlayheadOnTheNextLoad() = earlyTransport.playOutlivesARefusedLoad(false)

  @Test
  fun aPlayBeforeAFailedItemMovesThePlayheadOnTheNextLoad() =
    earlyTransport.playOutlivesAFailedItem(::createFailingItem)

  @Test
  fun theSameEditReloadedAfterAFailedItemPlays() = earlyTransport.theSameEditPlaysAfterAFailedItem(::createFailingItem)

  @Test
  fun aParameterEditAfterAFailedItemPlays() = earlyTransport.aParameterEditPlaysAfterAFailedItem(::createFailingItem)

  @Test
  fun aParameterEditAfterARefusedEditPlaysOnTheStandingGraph() =
    earlyTransport.aParameterEditLiftsARefusalOverAStandingGraph()

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
    loopEnds.wrapsAtTheCompositionEnd(thirds = -1) { start, _ -> TimeRange.from(start) }

  @Test
  fun aLoopRangeEndingOnTheDurationWrapsAtTheCompositionEnd() =
    loopEnds.wrapsAtTheCompositionEnd(thirds = 1) { start, duration -> TimeRange.of(start, duration) }

  @Test
  fun aLoopRangeEndingPastTheDurationWrapsAtTheCompositionEnd() =
    loopEnds.wrapsAtTheCompositionEnd(thirds = -1) { start, duration -> TimeRange.of(start, duration + duration / 2) }

  private suspend fun EngineUnderTest.quiet() {
    awaitStep("the engine to go quiet") { awaitQuiet() }
  }

  private val rebuilds: RebuildContract get() = RebuildContract(::createEngine, fixture, fixtureFrameRate)

  private val relaxedSeeks: RelaxedSeekContract get() = RelaxedSeekContract(::createEngine, fixture, fixtureFrameRate)

  private val shuttles: ShuttleContract get() = ShuttleContract(::createEngine, fixture, fixtureFrameRate)

  private val loopEnds: LoopEndContract get() = LoopEndContract(::createEngine, fixture, fixtureFrameRate)

  private val earlyTransport: EarlyTransportContract
    get() = EarlyTransportContract(::createEngine, fixture, unreadableFixture)

  private val fixtureRebuilt: Composition get() = fixture.cutInTwo(fixtureFrameRate)

  private val fixtureParameters: Composition get() = fixture.withEffects(listOf(Brightness(RETUNED_BRIGHTNESS)))

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

    // Where across the composition the seeks in the landing tests aim. Well inside it, and the relaxed one mid GOP for
    // every fixture's keyframe interval.
    const val ROUNDS_DOWN_AT = 0.3
    const val ROUNDS_UP_AT = 0.6
    const val RELAXED_AT = 0.45

    // Any brightness a fixture does not already carry, which changes a parameter and nothing on the timeline.
    const val RETUNED_BRIGHTNESS = 0.7f

    // Long enough to reach a backend's reader as a file, and all zeroes so it opens no container.
    const val UNREADABLE_BYTES = 256
  }
}
