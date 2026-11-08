package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.LoopWrap
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestResult
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * How the media3 engine's loop wrap behaves around seeks, on a real `CompositionPlayer`.
 *
 * Each test acts from inside a listener, which runs on the engine's own dispatcher, so what it does lands between two
 * steps of the engine rather than wherever a poll happens to wake up.
 */
class AndroidLoopWrapMediaTest {
  /**
   * A seek just past where the loop range wraps, issued while playing, lands where it was asked before anything wraps.
   *
   * The seek goes out as the playhead reaches the range's last slot, so the mirror can still read a position past the
   * wrap from before media3 took the seek. That reading must not wrap under the seek in flight.
   */
  @Test
  fun aSeekJustPastTheWrapWhilePlayingLandsWhereItWasAsked(): TestResult =
    contractTest { scope ->
      val subject = AndroidEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        val (_, wrap) = loadAndLoop(subject.engine, recorder)
        val target = wrap.at + FIXTURE_FRAME_STEP / 2
        val landing = outputSlotTime(outputSlotOf(target, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
        val lastSlot = outputSlotTime(wrap.lastSlot, FIXTURE_FRAME_RATE)

        var issuedAt: Int? = null
        val trigger =
          subject.engine.listen(
            positions = { position ->
              if (issuedAt == null && position >= lastSlot) {
                issuedAt = recorder.events.size
                subject.engine.seekTo(target, SeekAccuracy.Exact)
              }
            },
          )
        subject.engine.play()
        awaitContract("the seek past the wrap to land") {
          val mark = issuedAt ?: return@awaitContract false
          recorder.events.drop(mark).any { it is PlaybackEvent.SeekCompleted }
        }
        trigger.cancel()
        subject.engine.pause()

        val mark = issuedAt ?: fail("The playhead never reached the range's last slot.")
        val untilLanded = recorder.events.drop(mark).takeWhile { it !is PlaybackEvent.SeekCompleted }
        untilLanded.filterIsInstance<PlaybackEvent.RangeLooped>().shouldBeEmpty()
        recorder.events
          .drop(mark)
          .first { it is PlaybackEvent.SeekCompleted }
          .shouldBeInstanceOf<PlaybackEvent.SeekCompleted>()
          .position shouldBe landing
      }
    }

  /**
   * A loop range changed while its wrap is on the way lands on the new range's first slot, and reports one lap.
   *
   * The new range starts before the old one and still holds the old range's first slot, so the new range issues no
   * seek of its own and only the wrap can move playback there.
   */
  @Test
  fun aRangeChangedAfterAWrapFiresLandsOnTheNewRangesFirstSlot(): TestResult =
    contractTest { scope ->
      val subject = AndroidEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        val (range, wrap) = loadAndLoop(subject.engine, recorder)
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
        val moved = TimeRange.of(MOVED_START, MOVED_END)
        val movedWrap = loopWrapOf(moved, duration, FIXTURE_FRAME_RATE) ?: fail("The moved range has nothing to loop.")
        val movedFirstSlot = outputSlotTime(movedWrap.firstSlot, FIXTURE_FRAME_RATE)
        movedWrap.firstSlot shouldBeLessThan wrap.firstSlot
        movedWrap.lastSlot shouldBeGreaterThan wrap.firstSlot

        var looped = false
        val trigger =
          subject.engine.listen(
            events = { event ->
              if (!looped && event is PlaybackEvent.RangeLooped) {
                looped = true
                subject.engine.setLoopRange(moved)
              }
            },
          )
        subject.engine.play()
        awaitContract("the wrap to land") { sinceFirstLap(recorder).any { it is PlaybackEvent.SeekCompleted } }
        trigger.cancel()
        subject.engine.pause()

        val untilLanded = sinceFirstLap(recorder).takeWhile { it !is PlaybackEvent.SeekCompleted }
        val laps = untilLanded.filterIsInstance<PlaybackEvent.RangeLooped>()
        laps shouldHaveSize 1
        laps.single().range shouldBe range
        sinceFirstLap(recorder)
          .first { it is PlaybackEvent.SeekCompleted }
          .shouldBeInstanceOf<PlaybackEvent.SeekCompleted>()
          .position shouldBe movedFirstSlot
      }
    }

  /**
   * Loads the fixture, seeks a few slots short of the loop range's end and sets the range, which starts and ends in the
   * middle of the composition.
   */
  private suspend fun loadAndLoop(
    engine: Media3PlayerEngine,
    recorder: ContractRecorder,
  ): Pair<TimeRange, LoopWrap> {
    engine.awaitComposition(androidFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
    awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }

    val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
    val range = TimeRange.of(outputSlotTime(LOOP_FIRST_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP / 3, LOOP_END)
    val wrap = loopWrapOf(range, duration, FIXTURE_FRAME_RATE) ?: fail("The loop range has nothing to loop.")
    wrap.reachesEnd shouldBe false

    val seeks = recorder.seekCompletions.size
    engine.seekTo(outputSlotTime(wrap.lastSlot - APPROACH_SLOTS, FIXTURE_FRAME_RATE), SeekAccuracy.Exact)
    awaitContract("the seek into the loop range to land") { recorder.seekCompletions.size > seeks }
    engine.setLoopRange(range)
    return range to wrap
  }

  private fun sinceFirstLap(recorder: ContractRecorder): List<PlaybackEvent> {
    val lap = recorder.events.indexOfFirst { it is PlaybackEvent.RangeLooped }
    return if (lap < 0) emptyList() else recorder.events.drop(lap)
  }

  private fun Media3PlayerEngine.listen(
    positions: (Duration) -> Unit = {},
    events: (PlaybackEvent) -> Unit = {},
  ): Cancellable =
    addListener(
      object : EngineListener {
        override fun onStateChanged(state: PlayerState) = Unit

        override fun onEvent(event: PlaybackEvent) = events(event)

        override fun onPosition(position: Duration) = positions(position)

        override fun onPreviewInfo(info: PreviewInfo) = Unit
      },
    )

  private companion object {
    // Slots whose times fall between two whole milliseconds, all well short of the composition's end.
    const val LOOP_FIRST_SLOT = 10L
    const val LOOP_END_SLOT = 28L
    const val MOVED_FIRST_SLOT = 4L
    const val MOVED_END_SLOT = 22L

    // A third of a slot into the moved range's first slot, the way the loop range starts.
    val MOVED_START: Duration = outputSlotTime(MOVED_FIRST_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP / 3

    // Two fifths of a slot past each end slot, so each range ends inside a slot rather than on one.
    val LOOP_END: Duration = outputSlotTime(LOOP_END_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP * 2 / 5
    val MOVED_END: Duration = outputSlotTime(MOVED_END_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP * 2 / 5

    // How far before the range's last slot playback into it starts, so the first lap is short.
    const val APPROACH_SLOTS = 3
  }
}
