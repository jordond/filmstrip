package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.ffmpeg.PreviewStream
import dev.jordond.filmstrip.ffmpeg.PreviewStreamResult
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotsOf
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.awaitStep
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.playback.internal.FfmpegPreviewPlan
import dev.jordond.filmstrip.playback.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.playback.internal.frameRate
import dev.jordond.filmstrip.playback.internal.loopWrapOf
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where the desktop engine wraps a loop range and stops a playback range.
 *
 * Both end on the last output slot the range carries, counted on the same grid the pump writes its frames to, rather
 * than on the first frame whose time falls past the range's end. A loop range the composition ends inside of wraps
 * once the composition's last slot has played.
 */
class JvmRangeEndTest {
  // A loop range plays the slots it carries and then wraps to the first of them, pass after pass. One range ends three
  // tenths of the way into a frame period, so the slot that period opens on sits before its end and is still not one of
  // its slots, and the other ends three fifths of the way in, where that slot is its last.
  @Test
  fun `a loop range plays exactly the slots it carries on every pass`() =
    contractTest { scope ->
      listOf(TimeRange.of(ROUNDS_DOWN, ROUNDS_DOWN_END), TimeRange.of(ROUNDS_UP, ROUNDS_UP_END)).forEach { range ->
        val subject = JvmEngineUnderTest(scope)
        val positions = mutableListOf<Duration>()

        withEngine(subject.engine) { recorder ->
          val loaded = subject.engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH))
          loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the first frame to land") { recorder.playhead != null }
          val registration = subject.engine.addListener(PositionRecorder(positions))

          subject.engine.setLoopRange(range)
          val before = recorder.seekCompletions.size
          subject.engine.seekTo(range.start, SeekAccuracy.Exact)
          awaitContract("the seek to $range's start to complete") { recorder.seekCompletions.size > before }
          subject.engine.play()
          awaitContract("$range to loop twice", timeout = PLAY_BUDGET) {
            recorder.events.filterIsInstance<PlaybackEvent.RangeLooped>().size >= 2
          }
          subject.engine.pause()
          registration.cancel()
        }

        val passes = passesOf(slotsPresented(positions, checkNotNull(range.endExclusive)))
        passes.take(2) shouldBe List(2) { outputSlotsOf(range, FIXTURE_RATE).toList() }
      }
    }

  // A playback range whose end is the composition's own plays on through the last slot the composition carries and
  // stops there. One composition ends three tenths of the way into a frame period, so the slot that period opens on is
  // past its last, and the other ends three fifths of the way in, where that slot is its last.
  @Test
  fun `a playback range ending at the composition's end plays through its last slot`() =
    contractTest { scope ->
      listOf(ROUNDS_DOWN_END, ROUNDS_UP_END).forEach { length ->
        val subject = JvmEngineUnderTest(scope)
        val positions = mutableListOf<Duration>()

        withEngine(subject.engine) { recorder ->
          val loaded = subject.engine.awaitComposition(jvmVideoComposition(length))
          loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the first frame to land") { recorder.playhead != null }
          val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
          val carried = outputSlotsOf(TimeRange.of(Duration.ZERO, duration), FIXTURE_RATE)
          val expected = (outputSlotOf(ROUNDS_DOWN, FIXTURE_RATE)..carried.last).toList()
          val registration = subject.engine.addListener(PositionRecorder(positions))

          val before = recorder.seekCompletions.size
          subject.engine.setPlaybackRange(TimeRange.of(ROUNDS_DOWN, duration))
          awaitContract("the seek to $ROUNDS_DOWN to complete") { recorder.seekCompletions.size > before }
          subject.engine.play()
          awaitContract("playback to reach the end of a $length composition", timeout = PLAY_BUDGET) {
            recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
          }
          awaitStep("every pump to finish") { subject.engine.awaitIdle() }
          registration.cancel()

          slotsPresented(positions, duration).distinct() shouldBe expected
          recorder.events
            .filterIsInstance<PlaybackEvent.Ended>()
            .single()
            .finalPosition shouldBe duration
          recorder.lastState.status shouldBe PlaybackStatus.Ended
        }
      }
    }

  // A loop range with no end stops where the composition does. Playback runs on through the last slot the composition
  // carries and wraps from there to the range's first, pass after pass, and never ends.
  @Test
  fun `a loop range with no end wraps after the composition's last slot`() =
    contractTest { scope ->
      listOf(ROUNDS_DOWN_END, ROUNDS_UP_END).forEach { length ->
        assertWrapsAtTheEnd(scope, length) { TimeRange.from(ROUNDS_DOWN) }
      }
    }

  // A loop range ending at or past the composition's end is held to it, so the pump runs out on the composition's last
  // slot with no frame past the range's. Playback wraps there instead of ending.
  @Test
  fun `a loop range ending at or past the composition's end wraps after its last slot`() =
    contractTest { scope ->
      listOf(ROUNDS_DOWN_END, ROUNDS_UP_END).forEach { length ->
        assertWrapsAtTheEnd(scope, length) { duration -> TimeRange.of(ROUNDS_DOWN, duration) }
        assertWrapsAtTheEnd(scope, length) { duration -> TimeRange.of(ROUNDS_DOWN, duration + PAST_THE_END) }
      }
    }

  // A pump running out only wraps a loop range the composition ends inside once it has shown the composition's last
  // slot. Here every pump stops in the middle of the range, the way a process failing on the same frame each time
  // would, so playback ends as any failed pump does rather than wrapping onto the same failure pass after pass.
  @Test
  fun `a loop range the composition ends inside ends when its pump stops short of the last slot`() =
    contractTest { scope ->
      val range = TimeRange.from(ROUNDS_DOWN)
      val planner = StoppingPlanner(stopsAfter = outputSlotOf(STOPS_AFTER, FIXTURE_RATE))
      val engine = FfmpegPlayerEngine(parent = scope, planner = planner, config = PlayerConfig())
      val positions = mutableListOf<Duration>()

      withEngine(engine) { recorder ->
        engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH)).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val wrap = loopWrapOf(range, duration, FIXTURE_RATE) ?: fail("$range has nothing to loop")
        wrap.reachesEnd shouldBe true
        assertTrue(
          planner.stopsAfter > wrap.firstSlot && planner.stopsAfter < wrap.lastSlot,
          "the pump stops after slot ${planner.stopsAfter}, not inside ${wrap.firstSlot}..${wrap.lastSlot}",
        )
        val registration = engine.addListener(PositionRecorder(positions))

        engine.setLoopRange(range)
        val before = recorder.seekCompletions.size
        engine.seekTo(range.start, SeekAccuracy.Exact)
        awaitContract("the seek to ${range.start} to complete") { recorder.seekCompletions.size > before }
        val opens = planner.opens
        engine.play()
        awaitContract("playback to end where its pump stopped", timeout = PLAY_BUDGET) {
          recorder.events.firstOrNull { it is PlaybackEvent.RangeLooped }?.let { fail("playback reported $it") }
          recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
        }
        awaitStep("every pump to finish") { engine.awaitIdle() }
        registration.cancel()

        recorder.events.filterIsInstance<PlaybackEvent.RangeLooped>().shouldBeEmpty()
        planner.opens shouldBe opens
        slotsPresented(positions, duration).distinct() shouldBe (wrap.firstSlot..planner.stopsAfter).toList()
        recorder.events
          .filterIsInstance<PlaybackEvent.Ended>()
          .single()
          .finalPosition shouldBe duration
        recorder.lastState.status shouldBe PlaybackStatus.Ended
      }
    }

  /**
   * Plays a [length] composition from the start of the loop range [rangeOf] builds out of its duration, and checks two
   * passes each play from the range's first slot through the composition's last and then wrap, with no end reported.
   */
  private suspend fun assertWrapsAtTheEnd(
    scope: CoroutineScope,
    length: Duration,
    rangeOf: (duration: Duration) -> TimeRange,
  ) {
    val subject = JvmEngineUnderTest(scope)
    val positions = mutableListOf<Duration>()

    withEngine(subject.engine) { recorder ->
      val loaded = subject.engine.awaitComposition(jvmVideoComposition(length))
      loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the first frame to land") { recorder.playhead != null }
      val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
      val range = rangeOf(duration)
      val wrap = loopWrapOf(range, duration, FIXTURE_RATE) ?: fail("$range has nothing to loop")
      wrap.reachesEnd shouldBe true
      val registration = subject.engine.addListener(PositionRecorder(positions))

      subject.engine.setLoopRange(range)
      val before = recorder.seekCompletions.size
      subject.engine.seekTo(range.start, SeekAccuracy.Exact)
      awaitContract("the seek to ${range.start} to complete") { recorder.seekCompletions.size > before }
      subject.engine.play()
      awaitContract("$range to wrap twice on a $length composition", timeout = PLAY_BUDGET) {
        recorder.events.firstOrNull { it is PlaybackEvent.Ended }?.let { fail("playback reported $it, not a wrap") }
        recorder.events.filterIsInstance<PlaybackEvent.RangeLooped>().size >= 2
      }
      subject.engine.pause()
      registration.cancel()

      recorder.events.filterIsInstance<PlaybackEvent.RangeLooped>().forEach { it.range shouldBe range }
      val passes = passesOf(slotsPresented(positions, duration))
      passes.take(2) shouldBe List(2) { (wrap.firstSlot..wrap.lastSlot).toList() }
      recorder.lastState.status shouldNotBe PlaybackStatus.Ended
    }
  }

  private companion object {
    // The fixture runs 1.5s and the pump reads forward at about a millisecond a frame, so this is
    // real time plus a cold spawn or two rather than a decode budget.
    val PLAY_BUDGET = 30.seconds

    // How far past the composition's end a loop range that overruns it ends.
    val PAST_THE_END = 500.milliseconds

    // A third of the way into a frame period and three quarters of the way into one.
    val ROUNDS_DOWN = 510.milliseconds
    val ROUNDS_UP = 525.milliseconds

    // A composition or a range that ends three tenths of the way into a frame period, so the slot that period opens on
    // is not one it carries.
    val ROUNDS_DOWN_END = 1_010.milliseconds

    // A composition or a range that ends three fifths of the way into a frame period, so its last slot is the one that
    // period rounds up to.
    val ROUNDS_UP_END = 1_020.milliseconds

    // Where a pump that stops early shows its last frame, well inside a loop range from ROUNDS_DOWN and clear of the
    // composition's last slot.
    val STOPS_AFTER = 900.milliseconds
  }
}

/**
 * Splits [slots] into the passes a loop played, one run of consecutive slots each.
 *
 * A slot that repeats is the ticker reading the playhead again between frames, so it stays in its pass. Any other slot
 * that is not one past the one before it starts a new pass.
 */
private fun passesOf(slots: List<Long>): List<List<Long>> {
  val passes = mutableListOf<MutableList<Long>>()
  slots.forEach { slot ->
    val pass = passes.lastOrNull()
    when {
      pass != null && pass.last() == slot -> Unit
      pass != null && pass.last() + 1 == slot -> pass += slot
      else -> passes += mutableListOf(slot)
    }
  }
  return passes
}

/**
 * Opens the pumps a real preview would, each of which stops after the frame on [stopsAfter] as though its process
 * failed on the next one.
 *
 * Counts every pump it opens, so a suite can tell whether the engine spawned another.
 */
private class StoppingPlanner(
  val stopsAfter: Long,
) : FfmpegPreviewPlanner(CONTRACT_COMPONENTS) {
  private val opened = AtomicInteger()

  val opens: Int get() = opened.get()

  override suspend fun open(
    plan: FfmpegPreviewPlan,
    composition: EditComposition,
    at: Duration,
  ): PreviewStreamResult {
    opened.incrementAndGet()
    return when (val result = super.open(plan, composition, at)) {
      is PreviewStreamResult.Opened -> {
        PreviewStreamResult.Opened(StoppingStream(result.stream, plan.frameRate, stopsAfter))
      }
      is PreviewStreamResult.Refused -> {
        result
      }
    }
  }
}

/**
 * The frames of [stream] up to and including the one on [lastSlot] of the grid at [frameRate], and then nothing.
 *
 * Slots are counted from the stream's start position, one per frame, the way the engine counts them.
 */
private class StoppingStream(
  private val stream: PreviewStream,
  frameRate: Int,
  private val lastSlot: Long,
) : PreviewStream by stream {
  private var slot = outputSlotOf(stream.startPosition, frameRate)

  override suspend fun next(): ByteArray? = if (slot > lastSlot) null else stream.next()?.also { slot++ }
}
