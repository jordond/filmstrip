package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.EngineUnderTest
import dev.jordond.filmstrip.player.contract.Interruption
import dev.jordond.filmstrip.player.contract.PlayerEngineContractTest
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shared engine contracts, run against real WebCodecs in a real browser.
 *
 * The browser's interruption list is genuinely shorter than any other platform's, and short for
 * platform reasons rather than because this suite left something out. A page never hears about
 * audio focus, because nothing takes it away, and never hears about an output route, because a
 * headphone unplug is invisible to it. What is left is the page going off screen and the browser
 * refusing to start an audio graph without a fresh gesture, and the second of those has no analogue
 * anywhere else. The two in `UNSTAGEABLE_INTERRUPTIONS` are out of reach on every platform.
 *
 * Both staged occasions go through the engine's own seam rather than through the browser, because
 * whether a page is granted autoplay depends on how the runner was launched and a karma page cannot
 * be sent to the background at all. The audio test below takes the real path instead, and a headless
 * browser with its stock policy does refuse there.
 */
class WebEngineContractTest : PlayerEngineContractTest() {
  override fun createEngine(scope: CoroutineScope): EngineUnderTest = WebEngineUnderTest(scope)

  override val fixture: EditComposition = webFixtureComposition()

  override val fixtureFrameRate: Int = FIXTURE_FRAME_RATE

  override val stageableInterruptions: Set<Interruption> =
    setOf(Interruption.AutoplayBlocked, Interruption.AppBackgrounded)

  /**
   * The audio branch of the clock, and what happens when the browser will not start it.
   *
   * Which of the two outcomes a run takes is the browser's call rather than the test's: a page that
   * has been granted autoplay plays and the hardware clock advances the playhead, and one that has
   * not is refused and says so exactly once. A headless browser on its stock policy takes the
   * second, so this is where the refusal is exercised for real rather than through a seam. Both are
   * the contract, and asserting only one would make this suite depend on how the runner was
   * launched.
   */
  @Test
  fun `a composition with audio runs on the audio clock, or reports the refusal`() =
    contractTest { scope ->
      val subject = WebEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.features
          .supports(PlayerFeature.AudioMonitoring) shouldBe true

        subject.engine
          .awaitComposition(webFixtureComposition(audio = AudioSpec.Keep))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        // Written before the graph has been allowed to start, which a suspended context accepts.
        subject.engine.setVolume(HALF_VOLUME)
        subject.engine.play()

        awaitContract("the audio clock to advance, or the browser to refuse it") {
          (recorder.playhead ?: Duration.ZERO) > Duration.ZERO || recorder.externalChanges.isNotEmpty()
        }

        when {
          recorder.externalChanges.isEmpty() -> {
            recorder.lastState.playWhenReady shouldBe true
          }
          else -> {
            recorder.externalChanges.map { it.playWhenReady } shouldBe listOf(false)
            recorder.lastState.playWhenReady shouldBe false
          }
        }
      }
    }

  /**
   * A relaxed seek lands on the slot the sync sample at or before the target sits on.
   *
   * That sample is never more than one keyframe interval behind the slot an exact seek would land on. The completion,
   * the playhead, the clock and the frame drawn at the reported time all have to agree on it.
   */
  @Test
  fun `a relaxed seek reports the sync sample's slot`() =
    contractTest { scope ->
      val subject = WebEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }

        for (at in eitherSideOf(MID_GOP_POSITION)) {
          val exact = slotTimeOf(at)
          val before = recorder.seekCompletions.size
          subject.engine.seekTo(at, SeekAccuracy.Nearest)
          awaitContract("the relaxed seek to $at to complete") { recorder.seekCompletions.size > before }

          val reported = recorder.seekCompletions.last().position
          slotTimeOf(reported) shouldBe reported
          reported shouldBeLessThanOrEqualTo exact
          reported shouldBeGreaterThanOrEqualTo exact - FIXTURE_SYNC_INTERVAL
          recorder.playhead shouldBe reported
          subject.engine.clockPosition shouldBe reported
          subject.engine.readback
            .awaitFrame(reported)
            .presentationTime shouldBe reported
        }
      }
    }

  /**
   * A frame step counts output slots from the one the playhead sits on.
   *
   * After an exact seek between two frames the step lands on the slot after the one that seek rounded to, rather than
   * one frame length past the time that was asked for.
   */
  @Test
  fun `a frame step from between two frames lands on the grid`() =
    contractTest { scope ->
      val subject = WebEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }

        for (at in eitherSideOf(MID_GOP_POSITION)) {
          val seeked = recorder.seekCompletions.size
          subject.engine.seekTo(at, SeekAccuracy.Exact)
          awaitContract("the seek to $at to complete") { recorder.seekCompletions.size > seeked }

          val landing = outputSlotTime(outputSlotOf(at, FIXTURE_FRAME_RATE) + 1, FIXTURE_FRAME_RATE)
          val stepped = recorder.seekCompletions.size
          subject.engine.stepFrames(1)
          awaitContract("the frame step from $at to complete") { recorder.seekCompletions.size > stepped }

          recorder.seekCompletions.last().position shouldBe landing
          recorder.playhead shouldBe landing
        }
      }
    }

  /**
   * A loop range the composition runs on past wraps at its own last slot, onto the slot its start lands on.
   *
   * Both ends sit a third of a slot off the grid, so a wrap that lands on the raw start rather than on its slot is
   * caught. Playback starts inside the range and has to wrap twice without ever reaching the composition's end.
   */
  @Test
  fun `a loop range ending before the composition wraps onto its first slot`() =
    contractTest { scope ->
      val subject = WebEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")

        val range = TimeRange.of(LOOP_START, LOOP_END)
        val wrap = loopWrapOf(range, duration, FIXTURE_FRAME_RATE) ?: fail("The loop range $range has nothing to loop.")
        wrap.reachesEnd shouldBe false
        val firstSlot = outputSlotTime(wrap.firstSlot, FIXTURE_FRAME_RATE)
        subject.engine.setLoopRange(range)

        val seeks = recorder.seekCompletions.size
        subject.engine.seekTo(LOOP_APPROACH, SeekAccuracy.Exact)
        awaitContract("the seek to $LOOP_APPROACH to complete") { recorder.seekCompletions.size > seeks }

        val eventsMark = recorder.events.size
        val positionsMark = recorder.playheads.size
        subject.engine.play()
        awaitContract("playback to wrap twice") {
          val looped = recorder.events.drop(eventsMark).count { it is PlaybackEvent.RangeLooped }
          looped >= 2 && dropIndices(recorder.playheads.drop(positionsMark)).size >= 2
        }
        // Paused before reading anything back, so a wrap whose seek is still landing cannot split the two counts.
        subject.engine.pause()
        awaitStep("the engine to go quiet") { subject.engine.awaitQuiet() }

        val since = recorder.events.drop(eventsMark)
        since.filterIsInstance<PlaybackEvent.Ended>() shouldBe emptyList()
        val looped = since.filterIsInstance<PlaybackEvent.RangeLooped>()
        looped.forEach { it.range shouldBe range }

        val positions = recorder.playheads.drop(positionsMark)
        val drops = dropIndices(positions)
        drops.map { index -> positions[index] }.forEach { it shouldBe firstSlot }
        drops.size shouldBe looped.size
        positions.drop(drops.first()).forEach { it shouldBeGreaterThanOrEqualTo firstSlot }
        positions.forEach { it shouldBeLessThan duration }
      }
    }

  private companion object {
    const val HALF_VOLUME = 0.5f

    // A loop range with both ends a third of a slot past a slot and well clear of the fixture's last one, and where
    // playback into it starts.
    val LOOP_START: Duration = 700.milliseconds + FIXTURE_FRAME_STEP / 3
    val LOOP_END: Duration = 1000.milliseconds + FIXTURE_FRAME_STEP / 3
    val LOOP_APPROACH: Duration = 900.milliseconds

    /**
     * The index of every reading in [readings] that is lower than the one straight before it.
     *
     * That is what a wrap looks like in an ordered position record.
     */
    fun dropIndices(readings: List<Duration>): List<Int> =
      (1 until readings.size).filter { index -> readings[index] < readings[index - 1] }

    // A third of a slot past the slot nearest anchor and a third short of it, so one rounds down to it and one up.
    fun eitherSideOf(anchor: Duration): List<Duration> {
      val slot = outputSlotOf(anchor, FIXTURE_FRAME_RATE)
      val last = outputSlotOf(CLIP_LENGTH, FIXTURE_FRAME_RATE) - 1
      check(slot in 1 until last - 1) { "Slot $slot and the one after it are not strictly inside the fixture." }
      val time = outputSlotTime(slot, FIXTURE_FRAME_RATE)
      return listOf(time + FIXTURE_FRAME_STEP / 3, time - FIXTURE_FRAME_STEP / 3)
    }

    fun slotTimeOf(at: Duration): Duration = outputSlotTime(outputSlotOf(at, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
  }
}
