package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.LoopWrap
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.TestResult
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.addBoundaryTimeObserverForTimes
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.valueWithCMTime
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSValue
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * Where the AVFoundation engine wraps a loop range, for ranges and transports the shared contract suite does not reach.
 *
 * A range whose end is off the grid wraps before the slot its end lands in, or it either plays part of a slot the range
 * does not carry or cuts its last slot short. Playback started on the native player wraps the way the engine's own
 * does, at the range's wrap and at the item's end. A range changed while a wrap is in flight moves the landing onto
 * the range standing when it lands. A pause made on the native player stops playback there and never becomes a wrap.
 * A seek a load supersedes leaves the playhead where the load put it. A frame step taken as a wrap fires steps from the
 * wrap's landing.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleLoopWrapMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a loop range ending just past a slot wraps before it`(): TestResult = wrapsOntoItsFirstSlot(LOOP_END_ROUNDS_DOWN)

  @Test
  fun `a loop range ending just short of a slot wraps before it`(): TestResult =
    wrapsOntoItsFirstSlot(LOOP_END_ROUNDS_UP)

  @Test
  fun `playback started on the native player wraps at the range end`(): TestResult =
    wrapsOntoItsFirstSlot(LOOP_END_ROUNDS_DOWN, outside = true)

  @Test
  fun `playback started on the native player wraps at an item end inside the range`(): TestResult =
    wrapsOntoItsFirstSlot(end = null, outside = true)

  @Test
  fun `a native pause just before an item end inside the range stays paused`(): TestResult = staysPaused()

  @Test
  fun `a native pause racing the item end notification stays paused`(): TestResult = staysPaused(raceEnd = true)

  @Test
  fun `a range changed while a wrap is in flight lands on the new range's first slot`(): TestResult =
    landsOnTheRangeStanding(TimeRange.of(outputSlotTime(MOVED_FIRST_SLOT, FIXTURE_FRAME_RATE), LOOP_END_ROUNDS_DOWN))

  @Test
  fun `a range cleared while a wrap is in flight keeps its landing`(): TestResult = landsOnTheRangeStanding(null)

  /**
   * Steps a frame from inside the first lap's report, while that wrap's seek is still in flight. The step goes from the
   * wrap's landing, which is where the playhead reads, rather than from the range's end the item still sits on.
   */
  @Test
  fun `a frame step taken as a wrap fires steps on from the wrap's landing`(): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        val duration = loadFixture(engine, recorder)
        val range =
          TimeRange.of(outputSlotTime(LOOP_FIRST_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP / 3, LOOP_END_ROUNDS_UP)
        val wrap = loopWrapOf(range, duration, FIXTURE_FRAME_RATE) ?: fail("The loop range has nothing to loop.")
        approach(engine, recorder, wrap)
        engine.setLoopRange(range)

        var stepped = false
        val stepper =
          engine.addListener(
            OnLap {
              if (!stepped) {
                stepped = true
                engine.stepFrames(1)
              }
            },
          )

        val eventsMark = recorder.events.size
        engine.play()
        awaitContract("the step after the wrap to land") {
          recorder.events
            .drop(eventsMark)
            .dropWhile { it !is PlaybackEvent.RangeLooped }
            .count { it is PlaybackEvent.SeekCompleted } >= 2
        }
        engine.pause()
        stepper.cancel()
        awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }

        recorder.events
          .drop(eventsMark)
          .dropWhile { it !is PlaybackEvent.RangeLooped }
          .filterIsInstance<PlaybackEvent.SeekCompleted>()[1]
          .position shouldBe outputSlotTime(wrap.firstSlot + 1, FIXTURE_FRAME_RATE)
      }
    }

  /**
   * Seeks from inside a parameter change's preview report, so the load's own seek to its start supersedes that seek on
   * the item it keeps. The superseded seek's completion arrives after the load's seek went out and leaves the playhead
   * on the load's start.
   */
  @Test
  fun `a seek superseded by a load's seek leaves the playhead on the load's start`(): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        engine
          .awaitComposition(appleFixtureComposition(listOf(Brightness(DIM))))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val superseded = outputSlotTime(SUPERSEDED_SLOT, FIXTURE_FRAME_RATE)
        val start = outputSlotTime(LOAD_START_SLOT, FIXTURE_FRAME_RATE)
        val loads = engine.platformLoads

        val seeker = SeekOnPreviewInfo(engine, superseded)
        val registration = engine.addListener(seeker)
        seeker.armed = true
        val seeks = recorder.seekCompletions.size
        val positionsMark = recorder.playheads.size
        engine
          .awaitComposition(appleFixtureComposition(listOf(Brightness(BRIGHT))), startAt = start)
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the superseded seek to complete") { recorder.seekCompletions.size > seeks }
        engine.drainCallbacks()
        registration.cancel()

        engine.platformLoads shouldBe loads
        val positions = recorder.playheads.drop(positionsMark)
        positions.shouldNotContain(superseded)
        positions.last() shouldBe start
      }
    }

  /**
   * Plays the native player through a range running to the composition's end, and pauses it on the native player as
   * playback reaches the range's last slot. Playback stays paused short of the end, with no lap reported.
   *
   * The pause is made from AVFoundation's own boundary callback, so only that one slot sits between it and the item's
   * end. With [raceEnd] set the callback first posts the item's end notification, which puts an end ahead of the pause
   * the way one landing on the end itself would.
   */
  private fun staysPaused(raceEnd: Boolean = false): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        val duration = loadFixture(engine, recorder)
        val start = outputSlotOf(duration, FIXTURE_FRAME_RATE) - END_LAP_SLOTS
        val range = TimeRange(outputSlotTime(start, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP / 3, null)
        val wrap = loopWrapOf(range, duration, FIXTURE_FRAME_RATE) ?: fail("The loop range has nothing to loop.")
        wrap.reachesEnd shouldBe true

        approach(engine, recorder, wrap)
        engine.setLoopRange(range)

        val player = engine.platformPlayer
        var paused = false
        val lastSlot = NSValue.valueWithCMTime(CMTimeMake(wrap.lastSlot, FIXTURE_FRAME_RATE))
        val token =
          player.addBoundaryTimeObserverForTimes(listOf(lastSlot), dispatch_queue_create("pause", null)) {
            if (raceEnd) {
              NSNotificationCenter.defaultCenter.postNotificationName(
                aName = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = player.currentItem,
              )
            }
            player.pause()
            paused = true
          }

        val eventsMark = recorder.events.size
        val positionsMark = recorder.playheads.size
        try {
          player.play()
          awaitContract("the native player to be paused before the item end") { paused }
          engine.drainCallbacks()
        } finally {
          player.removeTimeObserver(token)
        }

        player.timeControlStatus shouldBe AVPlayerTimeControlStatusPaused
        recorder.events
          .drop(eventsMark)
          .filterIsInstance<PlaybackEvent.RangeLooped>()
          .shouldBeEmpty()
        val positions = recorder.playheads.drop(positionsMark)
        dropIndices(positions).shouldBeEmpty()
        positions.last() shouldBeLessThan wrap.at
      }
    }

  /**
   * Loops a range ending at [end], or running to the composition's end when [end] is null, and checks every wrap lands
   * on the slot its start lands on, and that no reading reaches the wrap [loopWrapOf] places.
   *
   * The start sits a third of a slot past its slot, so a playhead reporting the raw start is caught at every drop. With
   * [outside] set the native player is played and paused directly, so the engine never sees a play of its own.
   */
  private fun wrapsOntoItsFirstSlot(
    end: Duration?,
    outside: Boolean = false,
  ): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        val duration = loadFixture(engine, recorder)
        val start = if (end == null) outputSlotOf(duration, FIXTURE_FRAME_RATE) - END_LAP_SLOTS else LOOP_FIRST_SLOT
        val range = TimeRange(outputSlotTime(start, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP / 3, end)
        val wrap = loopWrapOf(range, duration, FIXTURE_FRAME_RATE) ?: fail("The loop range has nothing to loop.")
        val firstSlot = outputSlotTime(wrap.firstSlot, FIXTURE_FRAME_RATE)
        wrap.reachesEnd shouldBe (end == null)
        if (end != null) wrap.lastSlot shouldBe LOOP_END_SLOT - 1

        approach(engine, recorder, wrap)
        engine.setLoopRange(range)

        val eventsMark = recorder.events.size
        val positionsMark = recorder.playheads.size
        if (outside) engine.platformPlayer.play() else engine.play()
        awaitContract("playback to wrap twice and carry on") {
          val looped = recorder.events.drop(eventsMark).count { it is PlaybackEvent.RangeLooped }
          val positions = recorder.playheads.drop(positionsMark)
          val drops = dropIndices(positions)
          looped >= 2 && drops.size >= 2 && positions.last() > positions[drops.last()]
        }
        if (outside) {
          engine.platformPlayer.pause()
        } else {
          engine.pause()
          awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }
        }
        engine.drainCallbacks()

        val since = recorder.events.drop(eventsMark)
        since.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        val looped = since.filterIsInstance<PlaybackEvent.RangeLooped>()
        looped.forEach { it.range shouldBe range }

        val positions = recorder.playheads.drop(positionsMark)
        val drops = dropIndices(positions)
        drops.map { index -> positions[index] }.forEach { it shouldBe firstSlot }
        drops.size shouldBe looped.size
        positions.drop(drops.first()).forEach { it shouldBeGreaterThanOrEqualTo firstSlot }
        positions.forEach { it shouldBeLessThan wrap.at }
      }
    }

  /**
   * Loops a range from [LOOP_FIRST_SLOT], and moves the range to [next] from inside the first lap's report, while that
   * wrap's seek is still in flight.
   *
   * The seek lands on [next]'s first slot, or on the old range's first slot when [next] is null, and the lap is
   * reported once.
   */
  private fun landsOnTheRangeStanding(next: TimeRange?): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        val duration = loadFixture(engine, recorder)
        val range =
          TimeRange.of(outputSlotTime(LOOP_FIRST_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP / 3, LOOP_END_ROUNDS_UP)
        val wrap = loopWrapOf(range, duration, FIXTURE_FRAME_RATE) ?: fail("The loop range has nothing to loop.")
        val landingWrap = next?.let { loopWrapOf(it, duration, FIXTURE_FRAME_RATE) } ?: wrap
        val landing = outputSlotTime(landingWrap.firstSlot, FIXTURE_FRAME_RATE)
        if (next != null) landingWrap.firstSlot shouldBeLessThan wrap.firstSlot

        approach(engine, recorder, wrap)
        engine.setLoopRange(range)

        // Called from inside the lap's report, which the engine makes right after it issues the wrap's seek.
        var moved = false
        val mover =
          engine.addListener(
            OnLap {
              if (!moved) {
                moved = true
                engine.setLoopRange(next)
              }
            },
          )

        val eventsMark = recorder.events.size
        engine.play()
        awaitContract("the wrap's seek to land") {
          recorder.events.drop(eventsMark).any { it is PlaybackEvent.SeekCompleted }
        }
        engine.pause()
        mover.cancel()
        awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }

        val since = recorder.events.drop(eventsMark)
        val landed = since.indexOfFirst { it is PlaybackEvent.SeekCompleted }
        since[landed].shouldBeInstanceOf<PlaybackEvent.SeekCompleted>().position shouldBe landing
        val laps = since.take(landed).filterIsInstance<PlaybackEvent.RangeLooped>()
        laps shouldHaveSize 1
        laps.single().range shouldBe range
      }
    }

  private suspend fun loadFixture(
    engine: AvPlayerEngine,
    recorder: ContractRecorder,
  ): Duration {
    engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
    awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
    return recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
  }

  /**
   * Seeks a few slots before [wrap]'s last slot, so the first lap is short.
   */
  private suspend fun approach(
    engine: AvPlayerEngine,
    recorder: ContractRecorder,
    wrap: LoopWrap,
  ) {
    val approach = outputSlotTime(wrap.lastSlot - APPROACH_SLOTS, FIXTURE_FRAME_RATE)
    approach shouldBeGreaterThan outputSlotTime(wrap.firstSlot, FIXTURE_FRAME_RATE)
    val seeks = recorder.seekCompletions.size
    engine.seekTo(approach, SeekAccuracy.Exact)
    awaitContract("the seek into the loop range to land") { recorder.seekCompletions.size > seeks }
  }

  /**
   * Runs [block] on every lap the engine reports, on the engine's own dispatcher.
   */
  private class OnLap(
    private val block: () -> Unit,
  ) : EngineListener {
    override fun onStateChanged(state: PlayerState) = Unit

    override fun onEvent(event: PlaybackEvent) {
      if (event is PlaybackEvent.RangeLooped) block()
    }

    override fun onPosition(position: Duration) = Unit

    override fun onPreviewInfo(info: PreviewInfo) = Unit
  }

  /**
   * Seeks [engine] exactly to [target] from inside the next preview report once [armed], and only that once.
   */
  private class SeekOnPreviewInfo(
    private val engine: AvPlayerEngine,
    private val target: Duration,
  ) : EngineListener {
    var armed: Boolean = false

    override fun onStateChanged(state: PlayerState) = Unit

    override fun onEvent(event: PlaybackEvent) = Unit

    override fun onPosition(position: Duration) = Unit

    override fun onPreviewInfo(info: PreviewInfo) {
      if (!armed) return
      armed = false
      engine.seekTo(target, SeekAccuracy.Exact)
    }
  }

  private companion object {
    // Two slots whose times fall between two whole milliseconds, the second well short of the composition's end.
    const val LOOP_FIRST_SLOT = 10L
    const val LOOP_END_SLOT = 28L

    // Where a moved range starts, before LOOP_FIRST_SLOT so its landing cannot be mistaken for the old one.
    const val MOVED_FIRST_SLOT = 4L

    // How many slots a range running to the composition's end covers, so its laps stay short.
    const val END_LAP_SLOTS = 10L

    // Two fifths of a slot either side of LOOP_END_SLOT, so one end rounds down onto it and one rounds up.
    val LOOP_END_ROUNDS_DOWN: Duration = outputSlotTime(LOOP_END_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP * 2 / 5
    val LOOP_END_ROUNDS_UP: Duration = outputSlotTime(LOOP_END_SLOT, FIXTURE_FRAME_RATE) - FIXTURE_FRAME_STEP * 2 / 5

    // How far before the range's last slot playback starts, so the first lap is short.
    const val APPROACH_SLOTS = 3

    // Where a seek the load supersedes was going, and where the load starts, well apart inside the composition.
    const val SUPERSEDED_SLOT = 30L
    const val LOAD_START_SLOT = 12L

    // Two brightness amounts, so moving from one to the other is a parameter change the item's chain takes in place.
    const val DIM = 0.4f
    const val BRIGHT = 1.4f

    fun dropIndices(readings: List<Duration>): List<Int> =
      (1 until readings.size).filter { index -> readings[index] < readings[index - 1] }
  }
}
