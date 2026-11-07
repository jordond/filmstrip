package dev.jordond.filmstrip.player

import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.EngineUnderTest
import dev.jordond.filmstrip.player.contract.FailingItem
import dev.jordond.filmstrip.player.contract.Interruption
import dev.jordond.filmstrip.player.contract.PlayerEngineContractTest
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.loopWrapOf
import dev.jordond.filmstrip.player.internal.toMediaMs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestResult
import java.io.File
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shared engine contracts, run against a real `CompositionPlayer` on a device.
 *
 * [Interruption.OutputRouteChanged] is staged by handing the engine's own receiver the intent the
 * system sends. Everything filmstrip owns runs from there: the transport stops, the snapshot flips
 * and exactly one event is emitted. The delivery step ahead of it is Android's, and a test cannot
 * run it, since `ACTION_AUDIO_BECOMING_NOISY` is a protected broadcast that no process but the
 * system may send, adb shell included.
 *
 * What is not covered here:
 * - [Interruption.AudioFocusLost]. media3 owns the focus request and reports the loss as a
 *   `playWhenReady` reason, which is pinned in `Media3InterruptionsTest`. Raising a real loss needs
 *   another app to take the session: a request from this process is granted without media3's own
 *   ever being told it lost, so there is nothing here to assert on.
 * - [Interruption.AppBackgrounded]. media3 does not watch the host's lifecycle and filmstrip
 *   registers no observer either, so nothing on Android raises it today.
 * - [Interruption.IncomingCall] and [Interruption.MediaServicesReset], which need real telephony and
 *   a dead media daemon and so are in `UNSTAGEABLE_INTERRUPTIONS` for every platform.
 * - A real route change, which needs hardware to be unplugged.
 */
class AndroidEngineContractTest : PlayerEngineContractTest() {
  override fun createEngine(scope: CoroutineScope): EngineUnderTest = androidEngine(scope)

  override fun createFailingItem(scope: CoroutineScope): FailingItem {
    val source = File(contractContext().cacheDir, "vanishing.mp4")
    val clip = androidFixtureClip().shouldBeInstanceOf<MediaSource.Path>()
    File(clip.path).copyTo(source, overwrite = true)
    val failing =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of(source.path), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
      )
    return FailingItem(
      subject = AndroidEngineUnderTest(scope, VanishingSourcePlanner(source)),
      composition = failing,
      restoreSource = { File(clip.path).copyTo(source, overwrite = true) },
    )
  }

  override val fixture: EditComposition = androidFixtureComposition()

  override val bytesFixture: EditComposition = androidFixtureBytesComposition()

  override val claimsLiveParameterRedraw: Boolean = true

  override val fixtureFrameRate: Int = FIXTURE_FRAME_RATE

  // media3's own limit: a graph keeps the effect slots it was built with and a plain clip has none, so the first effect
  // needs a new one.
  override val plainClipEditPrepares: Boolean = true

  override val stageableInterruptions: Set<Interruption> =
    setOf(Interruption.OutputRouteChanged)

  @Test
  fun itClaimsWhatCompositionPlayerReallyOffersAndNothingItDoesNot() =
    contractTest { scope ->
      val subject = androidEngine(scope)
      withEngine(subject.engine) {
        val features = subject.engine.features.all()

        features shouldContain PlayerFeature.FrameReadback
        features shouldContain PlayerFeature.FrameStepping
        features shouldContain PlayerFeature.LiveParameterRedraw
        features shouldContain PlayerFeature.AudioMonitoring
        // COMMAND_SET_SPEED_AND_PITCH is absent from CompositionPlayer's available set.
        features shouldNotContain PlayerFeature.PlaybackSpeed

        // Read off the display rather than typed here, since the same engine is right to claim it
        // on a panel that shows HDR and wrong to claim it on one that does not.
        features.contains(PlayerFeature.HdrPreview) shouldBe displayAdvertisesHdr()

        // What a surface attaches itself to, which on this backend has to be the player itself.
        subject.engine.nativePlayer.shouldBeInstanceOf<CompositionPlayer>()
      }
    }

  @Test
  fun disposingTwiceIsHarmlessAndDisposingMidLoadSupersedesTheLoad() =
    contractTest { scope ->
      val settled = createEngine(scope)
      settled.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
      settled.engine.dispose()
      settled.engine.dispose()

      val loading = createEngine(scope)
      val outcome =
        suspendCancellableCoroutine { continuation ->
          loading.engine.setComposition(SetCompositionRequest(fixture)) { result ->
            if (continuation.isActive) continuation.resume(result)
          }
          loading.engine.dispose()
        }

      outcome.shouldBeInstanceOf<SetCompositionResult.Superseded>()
    }

  /**
   * A relaxed seek lands where an exact one would, which is the clamp made visible.
   *
   * The engine reports the accuracy it really ran at, and the seek reports the output slot the requested time rounds to
   * rather than a sync sample before it. media3 is handed that slot's time in whole milliseconds, so where the player
   * says it went is the slot read through the engine's own conversion.
   */
  @Test
  fun aRelaxedSeekRunsExactSinceMedia3OffersNoTolerance() =
    contractTest { scope ->
      val subject = androidEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }

        subject.engine.seekTo(OFF_GRID, SeekAccuracy.Nearest)
        awaitContract("the relaxed seek to land") { recorder.seekCompletions.isNotEmpty() }

        val landing = outputSlotTime(outputSlotOf(OFF_GRID, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
        subject.engine.lastSeekAccuracy shouldBe SeekAccuracy.Exact
        recorder.seekCompletions.last().position shouldBe landing
        subject.engine.platformPosition shouldBe landing.toMediaMs().milliseconds
      }
    }

  /**
   * A frame step taken from between two frames lands on the output frame grid.
   *
   * The landing is read off the shared grid, one slot on from the one the playhead is nearest. media3 seeks and reports
   * its playhead in whole milliseconds, so where the player says it went is that slot's time floored to one.
   */
  @Test
  fun aFrameStepFromBetweenTwoFramesLandsOnTheOutputGrid() =
    contractTest { scope ->
      val subject = androidEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val before = recorder.seekCompletions.size

        subject.engine.seekTo(OFF_GRID, SeekAccuracy.Exact)
        awaitContract("the seek between two frames to land") { recorder.seekCompletions.size == before + 1 }
        subject.engine.stepFrames(1)
        awaitContract("the frame step to land") { recorder.seekCompletions.size == before + 2 }

        val landing = outputSlotTime(outputSlotOf(OFF_GRID, FIXTURE_FRAME_RATE) + 1, FIXTURE_FRAME_RATE)
        recorder.seekCompletions.last().position shouldBe landing
        subject.engine.platformPosition shouldBe landing.inWholeMilliseconds.milliseconds
      }
    }

  /**
   * A loop range whose end rounds down onto a slot wraps onto the slot its start lands on without showing that slot.
   *
   * The end sits inside the slot, so the wrap is due before the range's own end. A wrap taken at the raw end shows the
   * slot the range stops short of.
   */
  @Test
  fun aLoopRangeEndingJustPastASlotWrapsBeforeIt(): TestResult = wrapsOntoItsFirstSlot(LOOP_END_ROUNDS_DOWN)

  /**
   * A loop range whose end rounds up onto a slot wraps onto the slot its start lands on without showing that slot.
   *
   * The end sits just short of the slot, so the wrap is due after the range's own end.
   */
  @Test
  fun aLoopRangeEndingJustShortOfASlotWrapsBeforeIt(): TestResult = wrapsOntoItsFirstSlot(LOOP_END_ROUNDS_UP)

  /**
   * Loops a range ending at [end], well before the composition does, and checks every wrap lands on the slot its start
   * lands on and that no reading reaches a slot past the range's last one.
   *
   * The start sits a third of a slot past that slot, and media3 is handed a seek in whole milliseconds, so a playhead
   * reporting the raw start or media3's floored reading instead of the slot is caught at every drop.
   */
  private fun wrapsOntoItsFirstSlot(end: Duration): TestResult =
    contractTest { scope ->
      val subject = androidEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }

        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
        val range = TimeRange.of(outputSlotTime(LOOP_FIRST_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP / 3, end)
        val wrap = loopWrapOf(range, duration, FIXTURE_FRAME_RATE) ?: fail("The loop range has nothing to loop.")
        val firstSlot = outputSlotTime(wrap.firstSlot, FIXTURE_FRAME_RATE)
        wrap.reachesEnd shouldBe false
        wrap.lastSlot shouldBe LOOP_END_SLOT - 1

        // Into the range before it is set, so setting it issues no seek of its own.
        val approach = outputSlotTime(wrap.lastSlot - APPROACH_SLOTS, FIXTURE_FRAME_RATE)
        val seeks = recorder.seekCompletions.size
        subject.engine.seekTo(approach, SeekAccuracy.Exact)
        awaitContract("the seek into the loop range to land") { recorder.seekCompletions.size > seeks }
        subject.engine.setLoopRange(range)

        val eventsMark = recorder.events.size
        val positionsMark = recorder.playheads.size
        subject.engine.play()
        awaitContract("playback to wrap twice") {
          val looped = recorder.events.drop(eventsMark).count { it is PlaybackEvent.RangeLooped }
          looped >= 2 && dropIndices(recorder.playheads.drop(positionsMark)).size >= 2
        }
        subject.engine.pause()
        awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }
        awaitStep("the engine to go quiet after pausing") { subject.awaitQuiet() }

        val since = recorder.events.drop(eventsMark)
        since.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        val looped = since.filterIsInstance<PlaybackEvent.RangeLooped>()
        looped.forEach { it.range shouldBe range }

        val positions = recorder.playheads.drop(positionsMark)
        val drops = dropIndices(positions)
        drops.map { index -> positions[index] }.forEach { it shouldBe firstSlot }
        drops.size shouldBe looped.size
        positions.drop(drops.first()).forEach { it shouldBeGreaterThanOrEqualTo firstSlot }
        // The wrap time is where the slot after the range's last one starts, so a reading at or past it is a slot the
        // range does not play.
        positions.forEach { it shouldBeLessThan wrap.at }
      }
    }

  /**
   * A composition running out is an ending, never an external pause.
   *
   * media3 reports the same `onPlayWhenReadyChanged(false)` for both, told apart only by a reason,
   * so this is the one that would have every clip that plays through read as an interruption.
   */
  @Test
  fun playingThroughToTheEndReportsAnEndingAndNoExternalChange(): TestResult =
    contractTest { scope ->
      val subject = androidEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }

        subject.engine.seekTo(CLIP_LENGTH - NEAR_THE_END, SeekAccuracy.Exact)
        awaitContract("the seek near the end to land") { recorder.seekCompletions.isNotEmpty() }
        subject.engine.play()

        awaitContract("the composition to reach its end") {
          recorder.events.any { it is PlaybackEvent.Ended }
        }
        awaitStep("the engine to go quiet after the end") { subject.awaitQuiet() }

        recorder.externalChanges.shouldBeEmpty()
        recorder.lastState.playWhenReady shouldBe false
      }
    }

  /**
   * A source nothing can read fails the load rather than leaving the caller waiting.
   */
  @Test
  fun aSourceThatCannotBeReadFailsTheLoadAndReportsAnError() = failingLoad { androidFixtureBrokenComposition() }

  /**
   * The same, for a planner that threw rather than refusing.
   *
   * Planning reaches a prober, a resolver and a device query, and under load any of them can throw.
   * An engine that only handled a refusal left the caller suspended forever and published nothing.
   */
  @Test
  fun aPlannerThatThrewFailsTheLoadAndReportsAnError() = failingLoad(::throwingEngine) { fixture }

  private fun failingLoad(
    build: (CoroutineScope) -> AndroidEngineUnderTest = ::androidEngine,
    composition: () -> EditComposition,
  ) = contractTest { scope ->
    val subject = build(scope)
    withEngine(subject.engine) { recorder ->
      val outcome = subject.engine.awaitComposition(composition())

      outcome.shouldBeInstanceOf<SetCompositionResult.Failure>()
      awaitContract("the status to leave Idle") { recorder.lastState.status is PlaybackStatus.Error }
      recorder.events.filterIsInstance<PlaybackEvent.Failed>().shouldNotBeEmpty()
      recorder.lastState.hasComposition shouldBe false
    }
  }

  private fun throwingEngine(scope: CoroutineScope): AndroidEngineUnderTest =
    AndroidEngineUnderTest(scope, ThrowingPlanner())

  private fun androidEngine(scope: CoroutineScope): AndroidEngineUnderTest = AndroidEngineUnderTest(scope)

  /**
   * What the platform says the display can show, read the way a host would read it.
   *
   * A display mode only lists its HDR types from API 34, so an older device is read through the display's own HDR
   * capabilities.
   */
  private fun displayAdvertisesHdr(): Boolean {
    val manager = contractContext().getSystemService(DisplayManager::class.java) ?: return false
    val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      display.mode.supportedHdrTypes.isNotEmpty()
    } else {
      @Suppress("DEPRECATION")
      display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }
  }

  private companion object {
    // Between two frames of the fixture's 30fps grid, so an exact landing and a sync-sample landing
    // are different times rather than the same one.
    val OFF_GRID: Duration = 717.milliseconds

    val NEAR_THE_END: Duration = 300.milliseconds

    // Two slots whose times fall between two whole milliseconds, the second well short of the composition's end.
    const val LOOP_FIRST_SLOT = 10L
    const val LOOP_END_SLOT = 28L

    // Two fifths of a slot either side of LOOP_END_SLOT, so one end rounds down onto it and one rounds up. Far enough
    // off it that a wrap taken at the raw end rather than on the grid shows up in the playhead.
    val LOOP_END_ROUNDS_DOWN: Duration = outputSlotTime(LOOP_END_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP * 2 / 5
    val LOOP_END_ROUNDS_UP: Duration = outputSlotTime(LOOP_END_SLOT, FIXTURE_FRAME_RATE) - FIXTURE_FRAME_STEP * 2 / 5

    // How far before the range's last slot playback into it starts, so the first lap is short.
    const val APPROACH_SLOTS = 3

    /**
     * The index of every reading in [readings] that is lower than the one straight before it, which is what a wrap
     * looks like in an ordered playhead record.
     */
    fun dropIndices(readings: List<Duration>): List<Int> =
      (1 until readings.size).filter { index -> readings[index] < readings[index - 1] }
  }
}

/**
 * Plans an edit as usual and then takes [source] away, so media3 itself fails on the graph it is handed.
 *
 * Only after the first plan, so a source put back afterwards stays.
 */
private class VanishingSourcePlanner(
  private val source: File,
) : Media3PreviewPlanner(CONTRACT_COMPONENTS) {
  private var vanished = false

  override suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
    exportSpec: ExportSpec,
  ): PlanResult<Media3PreviewPlan, PlaybackError> =
    super.plan(composition, policy, exportSpec).also {
      if (!vanished) source.delete()
      vanished = true
    }
}
